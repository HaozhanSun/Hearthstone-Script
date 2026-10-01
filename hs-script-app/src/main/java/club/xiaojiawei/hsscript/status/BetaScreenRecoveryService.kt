package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.config.StarterConfig
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.ocr.OcrProviderKind
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.starter.GameStartupHandoffPolicy
import club.xiaojiawei.hsscript.starter.GameStartupRecoveryPolicy
import club.xiaojiawei.hsscript.strategy.AbstractModeStrategy
import club.xiaojiawei.hsscript.strategy.AbstractPhaseStrategy
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.File

/**
 * Optional Beta recovery orchestration. The upstream lifecycle loop remains
 * the owner of its normal heartbeat, stale-state fallback, and Power.log flow.
 * This service only schedules Beta-added no-progress/foreground recovery while
 * the explicit switch is enabled.
 */
internal object BetaScreenRecoveryService {
    private const val STARTUP_RECOVERY_GRACE_MS = 3_000L
    private const val STARTUP_HANDSHAKE_TIMEOUT_MS = 60_000L
    private val noProgressWatchdog = NoProgressWatchdog()
    private val startupHandoffActivity = StartupHandoffActivityTracker()
    private val recoveryCascadeGuard = RecoveryCascadeGuard()
    private val foregroundFailureCount = AtomicInteger(0)
    private val foregroundRecoveryPending = AtomicBoolean(false)
    @Volatile private var noProgressBoundPid: Long? = null
    @Volatile private var noProgressBoundPowerLogPath: String? = null
    @Volatile private var lifecycleStarted = false
    @Volatile private var monitor: ScheduledFuture<*>? = null
    @Volatile private var startupRecoveryGraceUntil = 0L
    private val startupRecoveryScheduled = AtomicBoolean(false)

    fun scheduleStartupHandoffWatchdog(
        startupHandshakeConfirmed: () -> Boolean,
        nextFailureAttempt: () -> Int,
        clearFailureAttempts: () -> Unit,
        lastGameLaunchAt: () -> Long,
        retryStarterChain: () -> Unit,
    ) {
        val recoveryToken = ScreenRecoveryRuntime.tokenOrNull() ?: return
        if (!startupRecoveryScheduled.compareAndSet(false, true)) return
        val taskRef = AtomicReference<Future<*>?>()
        val task = EXTRA_THREAD_POOL.submit {
            val startedAt = System.currentTimeMillis()
            try {
                while (ScreenRecoveryRuntime.isCurrent(recoveryToken) &&
                    System.currentTimeMillis() - startedAt < STARTUP_HANDSHAKE_TIMEOUT_MS
                ) {
                    if (startupHandshakeConfirmed()) {
                        clearFailureAttempts()
                        return@submit
                    }
                    if (!GameUtil.isAliveOfGame()) {
                        val attempt = nextFailureAttempt()
                        val launchAt = lastGameLaunchAt()
                        val now = System.currentTimeMillis()
                        val decision = GameStartupRecoveryPolicy.decide(
                            gameAlive = false,
                            startupConfirmed = false,
                            now = now,
                            lastLaunchAt = launchAt,
                        )
                        log.warn {
                            "GAME_STARTUP_PROCESS_EXITED attempt=$attempt decision=$decision " +
                                "platformAlive=${GameUtil.isAliveOfPlatform()} " +
                                "powerLog=${PowerLogListener.logFile?.path() ?: "none"} " +
                                "powerLogLength=${PowerLogListener.logFile?.length() ?: 0L}"
                        }
                        val retryDelay = GameStartupRecoveryPolicy.retryDelayMs(now, launchAt, attempt)
                        log.info {
                            "GAME_STARTUP_RETRY_DELAY delayMs=$retryDelay attempt=$attempt " +
                                "strategy=exponential-capped"
                        }
                        Thread.sleep(retryDelay)
                        if (ScreenRecoveryRuntime.isCurrent(recoveryToken) && !startupHandshakeConfirmed()) {
                            log.warn {
                                "GAME_STARTUP_RETRY action=STARTER_CHAIN reason=process-exited-before-handshake " +
                                    "platformPreserved=true attempt=$attempt retryDelayMs=$retryDelay"
                            }
                            retryStarterChain()
                        }
                        return@submit
                    }
                    Thread.sleep(1_000L)
                }
                if (ScreenRecoveryRuntime.isCurrent(recoveryToken) &&
                    GameStartupHandoffPolicy.onHandshakeTimeout(startupHandshakeConfirmed()) ==
                    GameStartupHandoffPolicy.HandshakeTimeoutDecision.AUTOMATIC_PAUSE
                ) {
                    log.error {
                        "GAME_STARTUP_STOPPED action=AUTOMATIC_PAUSE reason=handshake-timeout " +
                            "timeoutMs=$STARTUP_HANDSHAKE_TIMEOUT_MS " +
                            "gameAlive=${GameUtil.isAliveOfGame()} " +
                            "powerLog=${PowerLogListener.logFile?.path() ?: "none"} " +
                            "powerLogLength=${PowerLogListener.logFile?.length() ?: 0L}"
                    }
                    stopRecoveryCascade("startup-handshake-timeout")
                    PauseStatus.setAutomaticPause(true)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                if (ScreenRecoveryRuntime.isCurrent(recoveryToken)) {
                    log.info { "GAME_STARTUP_HANDOFF_WATCHDOG_INTERRUPTED" }
                }
            } finally {
                startupRecoveryScheduled.set(false)
                taskRef.get()?.let(ScreenRecoveryRuntime::forget)
            }
        }
        taskRef.set(task)
        ScreenRecoveryRuntime.track(recoveryToken, task)
    }

    fun initialize() {
        ScreenRecoveryRuntime.initialize()
    }

    fun start() {
        initialize()
        lifecycleStarted = true
        startOptionalMonitor()
    }

    fun stop() {
        lifecycleStarted = false
        monitor?.cancel(true)
        monitor?.let(ScreenRecoveryRuntime::forget)
        monitor = null
    }

    internal fun onFeatureChanged(enabled: Boolean) {
        if (!enabled) {
            monitor?.cancel(true)
            monitor?.let(ScreenRecoveryRuntime::forget)
            monitor = null
            noProgressWatchdog.reset()
            noProgressBoundPid = null
            noProgressBoundPowerLogPath = null
            foregroundFailureCount.set(0)
            foregroundRecoveryPending.set(false)
        } else if (lifecycleStarted) {
            startOptionalMonitor()
        }
    }

    private fun startOptionalMonitor() {
        if (!lifecycleStarted || monitor?.isDone == false) return
        val token = ScreenRecoveryRuntime.tokenOrNull() ?: return
        val task = EXTRA_THREAD_POOL.scheduleWithFixedDelay({
            if (ScreenRecoveryRuntime.isCurrent(token)) detectNoProgress(token)
        }, 10, 10, TimeUnit.SECONDS)
        if (ScreenRecoveryRuntime.track(token, task)) monitor = task
    }

    fun markStartupRequested(reason: String, now: Long) {
        if (!ScreenRecoveryRuntime.isEnabled()) return
        startupRecoveryGraceUntil = now + STARTUP_RECOVERY_GRACE_MS
        LifecycleTrace.mark("startup-recovery-grace reason=$reason until=$startupRecoveryGraceUntil")
    }

    fun markStartupHandoffAttempt(reason: String, now: Long) {
        if (!ScreenRecoveryRuntime.isEnabled()) return
        startupHandoffActivity.recordAttempt(now)
        log.debug { "STARTUP_HANDOFF_ACTIVITY_SIGNAL reason=$reason acceptance=not-confirmed" }
    }

    fun startupRecoveryGraceRemainingMs(now: Long): Long =
        if (ScreenRecoveryRuntime.isEnabled()) (startupRecoveryGraceUntil - now).coerceAtLeast(0L) else 0L

    fun stopRecoveryCascade(rootCause: String) {
        if (!ScreenRecoveryRuntime.isEnabled()) return
        if (recoveryCascadeGuard.trip(rootCause)) {
            log.error {
                "RECOVERY_CASCADE_STOP rootCause=$rootCause " +
                    "followOn=screen-recovery,unknown-state-screenshot,platform-close-skipped " +
                    "action=SUPPRESS_UNTIL_RESUME"
            }
        }
    }

    fun recoveryCascadeSuppressed(): Boolean =
        ScreenRecoveryRuntime.isEnabled() && recoveryCascadeGuard.suppressWhilePaused(PauseStatus.isPause)

    internal fun shouldObserveNoProgress(
        working: Boolean,
        automaticPause: Boolean,
        replaying: Boolean,
        paused: Boolean,
        recoveryPending: Boolean,
    ): Boolean = (working || automaticPause) && !replaying &&
        (!paused || automaticPause || recoveryPending)

    fun recordForegroundFailure(target: String, actual: String?) {
        if (!ScreenRecoveryRuntime.isEnabled()) return
        val count = foregroundFailureCount.incrementAndGet()
        log.warn {
            "NO_PROGRESS_FOREGROUND_FAILURE count=$count target=$target " +
                "actual=${actual ?: "unknown"} dispatch=false"
        }
        if (count >= NoProgressWatchdog.DEFAULT_FOREGROUND_FAILURE_THRESHOLD &&
            foregroundRecoveryPending.compareAndSet(false, true)
        ) {
            log.error {
                "NO_PROGRESS_FOREGROUND_RECOVERY_PENDING count=$count " +
                    "reason=foreground-mismatch-persistent action=PAUSE_AND_RECOVER"
            }
            AbstractModeStrategy.cancelAllTask()
            PauseStatus.isPause = true
        }
    }

    fun recordForegroundRecovered() {
        if (!ScreenRecoveryRuntime.isEnabled()) return
        if (foregroundFailureCount.getAndSet(0) > 0) {
            log.info { "NO_PROGRESS_FOREGROUND_RECOVERED dispatch=false" }
        }
    }

    fun requestActionRecovery(reason: String) {
        if (!ScreenRecoveryRuntime.isEnabled()) return
        log.error { "NO_PROGRESS_ACTION_RECOVERY_REQUESTED reason=$reason dispatch=false" }
        if (foregroundRecoveryPending.compareAndSet(false, true)) {
            AbstractModeStrategy.cancelAllTask()
            PauseStatus.isPause = true
        }
    }

    private fun detectNoProgress(recoveryToken: Long) {
        if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return
        if (recoveryCascadeGuard.suppressWhilePaused(PauseStatus.isPause)) {
            noProgressWatchdog.reset()
            noProgressBoundPid = null
            noProgressBoundPowerLogPath = null
            return
        }
        val recoveryPending = foregroundRecoveryPending.get()
        if (!shouldObserveNoProgress(
                working = WorkTimeListener.working,
                automaticPause = PauseStatus.isAutomaticPause,
                replaying = PowerLogListener.replayingExistingLog,
                paused = PauseStatus.isPause,
                recoveryPending = recoveryPending,
            )
        ) {
            noProgressWatchdog.reset()
            noProgressBoundPid = null
            noProgressBoundPowerLogPath = null
            return
        }

        val now = System.currentTimeMillis()
        val liveGamePid = GameUtil.findGameProcessIdForDiagnostics()
        val latestPowerLog = GameUtil.getLatestLogDir()?.resolve(club.xiaojiawei.hsscript.consts.GAME_WAR_LOG_NAME)
        val processStartedAt = liveGamePid?.let { pid ->
            runCatching {
                ProcessHandle.of(pid).orElse(null)?.info()?.startInstant()?.orElse(null)?.toEpochMilli()
            }.getOrNull()
        }
        val latestPowerLogUsable = latestPowerLog?.let { candidate ->
            candidate.isFile && candidate.canRead() && PowerLogSessionBindingPolicy.isCurrentSession(
                powerLogPath = candidate.absolutePath,
                gameLogsRoot = candidate.parentFile?.parent,
                length = candidate.length(),
                lastModifiedMs = candidate.lastModified(),
                processStartedAtMs = processStartedAt,
            )
        } == true
        if (latestPowerLogUsable && PowerLogListener.logFile?.path() != latestPowerLog?.absolutePath) {
            val attached = runCatching { PowerLogListener.bindCurrentSessionIfAvailable() }.getOrDefault(false)
            log.info {
                "POWER_LOG_CURRENT_SESSION_BIND result=${if (attached) "BOUND" else "PENDING"} " +
                    "pid=${liveGamePid ?: "none"} path=${latestPowerLog?.absolutePath} " +
                    "length=${latestPowerLog?.length() ?: 0L}"
            }
        }
        val powerLog = PowerLogListener.logFile
        val powerLogPath = powerLog?.path()
        val powerLogPosition = powerLog?.getPosition() ?: Long.MIN_VALUE
        val powerLogLength = powerLog?.length() ?: 0L
        val powerLogAge = powerLogPath?.let { path ->
            runCatching { (now - java.io.File(path).lastModified()).coerceAtLeast(0L) }
                .getOrNull()
        } ?: Long.MAX_VALUE
        val currentPid = liveGamePid
        if (noProgressBoundPid == null && currentPid != null) noProgressBoundPid = currentPid
        if (noProgressBoundPowerLogPath == null && powerLogPath != null) {
            noProgressBoundPowerLogPath = powerLogPath
        }
        val needsVisualConfirmation = Mode.nextMode == ModeEnum.STARTUP || powerLog == null
        val visualObservation = if (needsVisualConfirmation && currentPid != null) {
            runCatching { ScreenStateRecovery.observeFreshScreenForWatchdog() }.getOrNull()
        } else null
        val (screen, screenConfirmed) = when {
            WarEx.war.currentPhase == WarPhaseEnum.GAME_OVER || GameUtil.isTerminalGameState() ->
                NoProgressWatchdog.ScreenExpectation.RESULT to true
            WarEx.war.currentPhase == WarPhaseEnum.REPLACE_CARD ->
                NoProgressWatchdog.ScreenExpectation.MULLIGAN to true
            WarEx.inWar && !WarEx.war.isMyTurn -> NoProgressWatchdog.ScreenExpectation.OPPONENT_TURN to true
            WarEx.inWar && AbstractPhaseStrategy.dealing -> NoProgressWatchdog.ScreenExpectation.ANIMATION to true
            WarEx.inWar -> NoProgressWatchdog.ScreenExpectation.ACTIVE_GAMEPLAY to true
            visualObservation?.screen == "LOADING" -> NoProgressWatchdog.ScreenExpectation.STARTUP to true
            visualObservation?.screen in setOf(
                "HOME", "HOME_TASK_OVERLAY", "TOURNAMENT", "DECK_SELECTION", "MATCHMAKING", "COLLECTION", "GAME_MODE",
            ) -> NoProgressWatchdog.ScreenExpectation.MENU_OR_MATCHING to true
            visualObservation != null -> NoProgressWatchdog.ScreenExpectation.EXTERNAL_MODAL to true
            needsVisualConfirmation -> NoProgressWatchdog.ScreenExpectation.UNKNOWN to false
            else -> NoProgressWatchdog.ScreenExpectation.MENU_OR_MATCHING to true
        }
        val expectedMode = Mode.nextMode?.name ?: Mode.currMode?.name ?: "NONE"
        val startupActivityContext = StartupHandoffActivityTracker.Context(
            working = WorkTimeListener.working,
            paused = PauseStatus.isPause,
            automaticPause = PauseStatus.isAutomaticPause,
            recoveryPending = recoveryPending,
            replaying = PowerLogListener.replayingExistingLog,
            inWar = WarEx.inWar,
            terminalState = screen == NoProgressWatchdog.ScreenExpectation.RESULT,
            screen = screen,
            mode = Mode.currMode?.name ?: "NONE",
            expectedMode = expectedMode,
            gameProcessAlive = currentPid != null,
            powerLogPath = powerLogPath,
        )
        if (startupHandoffActivity.shouldDeferNoProgress(startupActivityContext, now)) {
            // Discard any old startup baseline while normal handoff retries are
            // being initiated. Once dispatch activity expires, the existing
            // bounded watchdog starts a fresh observation window.
            noProgressWatchdog.reset()
            noProgressBoundPid = null
            noProgressBoundPowerLogPath = null
            log.debug {
                "NO_PROGRESS_DEFERRED reason=active-startup-handoff " +
                    "mode=${startupActivityContext.mode} expectedMode=${startupActivityContext.expectedMode} " +
                    "gamePid=none powerLog=none acceptance=not-confirmed"
            }
            return
        }
        val decision = noProgressWatchdog.observe(
            NoProgressWatchdog.Snapshot(
                nowMs = now,
                mode = Mode.currMode?.name ?: "NONE",
                expectedMode = expectedMode,
                screen = screen,
                processAlive = currentPid != null,
                currentPid = currentPid,
                boundPid = noProgressBoundPid,
                windowPresent = ScriptStatus.gameHWND != null,
                foregroundMatches = foregroundFailureCount.get() == 0,
                foregroundFailureCount = foregroundFailureCount.get(),
                powerLogPath = powerLogPath,
                boundPowerLogPath = noProgressBoundPowerLogPath,
                powerLogPosition = powerLogPosition,
                powerLogLength = powerLogLength,
                powerLogAgeMs = powerLogAge,
                powerLogUsable = powerLog?.path()?.let { path ->
                    latestPowerLogUsable && File(path).absoluteFile.normalize() ==
                        latestPowerLog?.absoluteFile?.normalize()
                } == true,
                authoritativeLiveMatch = WarEx.inWar ||
                    WarEx.war.currentPhase == WarPhaseEnum.REPLACE_CARD,
                screenConfirmed = screenConfirmed,
                paddlexInitializing = runCatching {
                    OcrRuntime.currentProvider() == OcrProviderKind.PADDLEX &&
                        !PowerLogListener.replayingExistingLog && powerLogLength == 0L
                }.getOrDefault(false),
            ),
        )
        if (decision.action != NoProgressWatchdog.RecoveryAction.WAIT) {
            if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return
            log.warn {
                "NO_PROGRESS_OBSERVED elapsedMs=${decision.elapsedNoProgressMs} " +
                    "expectedMode=${Mode.nextMode?.name ?: Mode.currMode?.name ?: "NONE"} " +
                    "mode=${Mode.currMode?.name ?: "NONE"} pid=${currentPid ?: "none"} " +
                    "boundPid=${noProgressBoundPid ?: "none"} window=${ScriptStatus.gameHWND ?: "none"} " +
                    "powerLog=${powerLogPath ?: "none"} position=$powerLogPosition length=$powerLogLength " +
                    "ageMs=$powerLogAge screen=$screen action=${decision.action} reason=${decision.reason}"
            }
        }
        if (ScreenRecoveryRuntime.isCurrent(recoveryToken)) {
            applyNoProgressDecision(decision, currentPid, powerLogPath, recoveryToken)
        }
    }

    private fun applyNoProgressDecision(
        decision: NoProgressWatchdog.Decision,
        currentPid: Long?,
        powerLogPath: String?,
        recoveryToken: Long,
    ) {
        if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return
        when (decision.action) {
            NoProgressWatchdog.RecoveryAction.WAIT,
            NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED,
            NoProgressWatchdog.RecoveryAction.NOOP_RESULT,
            -> {
                if (decision.reason.contains("progress") || decision.reason.contains("after-recovery")) {
                    noProgressBoundPid = currentPid ?: noProgressBoundPid
                    noProgressBoundPowerLogPath = powerLogPath ?: noProgressBoundPowerLogPath
                    if (foregroundRecoveryPending.get() && decision.reason.contains("after-recovery")) {
                        foregroundRecoveryPending.set(false)
                        foregroundFailureCount.set(0)
                        if (PauseStatus.isPause) PauseStatus.isPause = false
                        log.info { "NO_PROGRESS_RECOVERY_CONFIRMED reason=${decision.reason} dispatch=false" }
                    }
                }
            }
            NoProgressWatchdog.RecoveryAction.DISMISS_EXTERNAL_MODAL -> {
                log.warn {
                    "NO_PROGRESS_RECOVERY attempt=${decision.recoveryAttempt} " +
                        "decision=DISMISS_EXTERNAL_MODAL reason=${decision.reason} dispatch=false"
                }
            }
            NoProgressWatchdog.RecoveryAction.REBIND -> {
                if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return
                log.warn {
                    "NO_PROGRESS_RECOVERY attempt=${decision.recoveryAttempt} " +
                        "decision=REBIND reason=${decision.reason} dispatch=false"
                }
                val rebound = runCatching { GameUtil.findGameHWND() }.getOrNull()
                if (rebound != null) {
                    ScriptStatus.gameHWND = rebound
                    log.info {
                        "NO_PROGRESS_REBOUND pid=${currentPid ?: "none"} hwnd=$rebound " +
                            "powerLog=${powerLogPath ?: "none"} dispatch=false"
                    }
                }
            }
            NoProgressWatchdog.RecoveryAction.RESTART -> {
                if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return
                log.error {
                    "NO_PROGRESS_RECOVERY attempt=${decision.recoveryAttempt} " +
                        "decision=RESTART reason=${decision.reason} dispatch=STARTER_CHAIN"
                }
                NoProgressRecoveryDispatch.restartToStartup(
                    recoverModeToStartup = {
                        Mode.recover(ModeEnum.STARTUP, "no-progress-${decision.reason}", enterStrategy = false)
                    },
                    startConfiguredStarterChain = {
                        StarterConfig.starter.start()
                        log.info { "NO_PROGRESS_STARTER_CHAIN_DISPATCHED reason=${decision.reason}" }
                    },
                )
            }
            NoProgressWatchdog.RecoveryAction.RECOVERY_RETRY_BACKOFF -> {
                log.warn {
                    "NO_PROGRESS_RECOVERY_RETRY_BACKOFF attempt=${decision.recoveryAttempt} " +
                        "reason=${decision.reason} watchdogRearmed=true scriptPaused=${PauseStatus.isPause} " +
                        "workerCancelled=false nextCycle=bounded-recovery"
                }
            }
        }
    }
}

