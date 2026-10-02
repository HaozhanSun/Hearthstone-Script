package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.config.StarterConfig
import club.xiaojiawei.hsscript.core.Core
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
import club.xiaojiawei.hsscriptbase.const.BuildChannel
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.io.File

/**
 * Beta recovery orchestration. The upstream lifecycle loop remains the owner
 * of normal screen classification; Beta adds paced process recovery, exact
 * native crash-dialog detection, and the opt-in no-progress/foreground path.
 */
internal object BetaScreenRecoveryService {
    private const val STARTUP_RECOVERY_GRACE_MS = 3_000L
    private const val STARTUP_HANDSHAKE_TIMEOUT_MS = 60_000L
    private val noProgressWatchdog = NoProgressWatchdog()
    private val startupHandoffActivity = StartupHandoffActivityTracker()
    private val startupFailurePolicy = BetaStartupFailureRecoveryPolicy()
    private val recoveryCascadeGuard = RecoveryCascadeGuard()
    private val foregroundFailureCount = AtomicInteger(0)
    private val foregroundRecoveryPending = AtomicBoolean(false)
    @Volatile private var noProgressBoundPid: Long? = null
    @Volatile private var noProgressBoundPowerLogPath: String? = null
    @Volatile private var lifecycleStarted = false
    @Volatile private var monitor: ScheduledFuture<*>? = null
    @Volatile private var startupFailureMonitor: ScheduledFuture<*>? = null
    @Volatile private var startupFailureSinceMs = 0L
    @Volatile private var lastObservedGamePid: Long? = null
    private val startupFailureDispatchInFlight = AtomicBoolean(false)
    private val startupFailureGeneration = AtomicLong(0L)
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
            try {
                while (ScreenRecoveryRuntime.isCurrent(recoveryToken)) {
                    val observationStartedAt = System.currentTimeMillis()
                    while (ScreenRecoveryRuntime.isCurrent(recoveryToken) &&
                        System.currentTimeMillis() - observationStartedAt < STARTUP_HANDSHAKE_TIMEOUT_MS
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
                    if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return@submit
                    if (startupHandshakeConfirmed()) {
                        clearFailureAttempts()
                        return@submit
                    }
                    val timeoutDecision = GameStartupHandoffPolicy.onHandshakeTimeout(startupConfirmed = false)
                    val recheckDelay = GameStartupHandoffPolicy.handshakeTimeoutRecheckDelayMs()
                    log.warn {
                        "GAME_STARTUP_HANDOFF_UNCONFIRMED action=$timeoutDecision " +
                            "reason=handshake-timeout timeoutMs=$STARTUP_HANDSHAKE_TIMEOUT_MS " +
                            "retryDelayMs=$recheckDelay gameAlive=${GameUtil.isAliveOfGame()} " +
                            "powerLog=${PowerLogListener.logFile?.path() ?: "none"} " +
                            "powerLogLength=${PowerLogListener.logFile?.length() ?: 0L} " +
                            "betaContinues=true pause=${PauseStatus.isPause}"
                    }
                    Thread.sleep(recheckDelay)
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
        if (!lifecycleStarted) startupFailureGeneration.incrementAndGet()
        lifecycleStarted = true
        startOptionalMonitor()
        startStartupFailureMonitor()
    }

    fun stop() {
        lifecycleStarted = false
        startupFailureGeneration.incrementAndGet()
        monitor?.cancel(true)
        monitor?.let(ScreenRecoveryRuntime::forget)
        monitor = null
        startupFailureMonitor?.cancel(true)
        startupFailureMonitor?.let(ScreenRecoveryRuntime::forget)
        startupFailureMonitor = null
        startupFailurePolicy.reset()
        startupFailureSinceMs = 0L
        lastObservedGamePid = null
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

    /** Beta-only, always-on guard for failed startup handoffs and native crash dialogs. */
    private fun startStartupFailureMonitor() {
        if (BuildChannel.identityToken(BuildInfo.RELEASE_CHANNEL) != "beta" ||
            !lifecycleStarted || startupFailureMonitor?.isDone == false
        ) return
        // This monitor is a core Beta safety path, not an optional recovery
        // extension. Its lifecycle must not depend on the user-configurable
        // ScreenRecoveryRuntime feature flag.
        val generation = startupFailureGeneration.get()
        val task = EXTRA_THREAD_POOL.scheduleWithFixedDelay({
            if (isStartupFailureGenerationCurrent(generation)) {
                runCatching { detectStartupFailure(generation) }
                    .onFailure { error -> log.error(error) { "BETA_STARTUP_RECOVERY_OBSERVATION_FAILED watchdog=RETAINED" } }
            }
        }, 10, 10, TimeUnit.SECONDS)
        startupFailureMonitor = task
        log.info {
            "BETA_STARTUP_RECOVERY_MONITOR_STARTED intervalMs=10000 " +
                "generation=$generation optionalExtensionsEnabled=${ScreenRecoveryRuntime.isEnabled()}"
        }
    }

    private fun isStartupFailureGenerationCurrent(generation: Long): Boolean =
        lifecycleStarted && startupFailureGeneration.get() == generation &&
            BuildChannel.identityToken(BuildInfo.RELEASE_CHANNEL) == "beta"

    internal fun startupFailureMonitorScheduledForTest(): Boolean =
        startupFailureMonitor?.let { !it.isDone && !it.isCancelled } == true

    private fun detectStartupFailure(recoveryGeneration: Long) {
        if (!isStartupFailureGenerationCurrent(recoveryGeneration) || !WorkTimeListener.working ||
            PauseStatus.isPause || PowerLogListener.replayingExistingLog
        ) return

        val now = System.currentTimeMillis()
        val pid = GameUtil.findGameProcessIdForDiagnostics()
        val processStartedAt = pid?.let { gamePid ->
            runCatching {
                ProcessHandle.of(gamePid).orElse(null)?.info()?.startInstant()?.orElse(null)?.toEpochMilli()
            }.getOrNull()
        }
        if (pid != lastObservedGamePid) {
            startupFailureSinceMs = processStartedAt ?: now
            lastObservedGamePid = pid
        } else if (startupFailureSinceMs == 0L) {
            startupFailureSinceMs = processStartedAt ?: now
        }

        val latestPowerLog = GameUtil.getLatestLogDir()?.resolve(club.xiaojiawei.hsscript.consts.GAME_WAR_LOG_NAME)
        val sessionLog = latestPowerLog?.takeIf { candidate ->
            candidate.isFile && candidate.canRead() && PowerLogSessionBindingPolicy.isSessionFileForProcess(
                powerLogPath = candidate.absolutePath,
                gameLogsRoot = candidate.parentFile?.parent,
                lastModifiedMs = candidate.lastModified(),
                processStartedAtMs = processStartedAt,
            )
        }
        if (sessionLog != null && sessionLog.length() > 0L &&
            PowerLogListener.logFile?.path() != sessionLog.absolutePath
        ) {
            val bound = runCatching { PowerLogListener.bindCurrentSessionIfAvailable() }.getOrDefault(false)
            log.info {
                "BETA_STARTUP_POWER_LOG_BIND result=${if (bound) "BOUND" else "PENDING"} " +
                    "pid=${pid ?: "none"} path=${sessionLog.absolutePath} length=${sessionLog.length()}"
            }
        }

        val powerLogLength = sessionLog?.length() ?: 0L
        val powerLogAge = sessionLog?.let { (now - it.lastModified()).coerceAtLeast(0L) } ?: Long.MAX_VALUE
        val powerLogIsCurrentSession = sessionLog != null
        val phase = WarEx.war.currentPhase
        val terminal = phase == WarPhaseEnum.GAME_OVER || GameUtil.isTerminalGameState()
        val liveMatch = WarEx.inWar || phase == WarPhaseEnum.REPLACE_CARD
        val dialog = WindowsApplicationErrorDialogProbe.detect()?.asPolicyEvidence()
        val hwnd = ScriptStatus.gameHWND
        val windowMatchesPid = pid != null && hwnd != null &&
            runCatching {
                GameUtil.isVerifiedCurrentGameWindow(hwnd, pid) &&
                    ProcessHandle.of(pid).orElse(null)?.isAlive == true
            }.getOrDefault(false)
        val stalledFrom = processStartedAt ?: startupFailureSinceMs
        val currentMode = Mode.currMode?.name ?: "NONE"
        val expectedMode = Mode.nextMode?.name ?: currentMode
        val handoffContext = StartupHandoffActivityTracker.Context(
            working = WorkTimeListener.working,
            paused = PauseStatus.isPause,
            automaticPause = PauseStatus.isAutomaticPause,
            recoveryPending = false,
            replaying = PowerLogListener.replayingExistingLog,
            inWar = liveMatch,
            terminalState = terminal,
            screen = if (currentMode in setOf("NONE", "STARTUP") && expectedMode in setOf("NONE", "STARTUP")) {
                NoProgressWatchdog.ScreenExpectation.STARTUP
            } else {
                NoProgressWatchdog.ScreenExpectation.UNKNOWN
            },
            mode = currentMode,
            expectedMode = expectedMode,
            gameProcessAlive = pid != null,
            powerLogPath = sessionLog?.absolutePath,
        )
        val startupChainActive = startupHandoffActivity.shouldDeferNoProgress(handoffContext, now)
        if (shouldDeferStartupFailureRecovery(startupChainActive, dialog != null)) {
            log.debug {
                "BETA_STARTUP_RECOVERY action=WAIT reason=startup-chain-active " +
                    "gamePid=${pid ?: "none"} powerLogCurrent=$powerLogIsCurrentSession"
            }
            return
        }
        val decision = startupFailurePolicy.observe(
            BetaStartupFailureRecoveryPolicy.Snapshot(
                nowMs = now,
                stalledForMs = (now - stalledFrom).coerceAtLeast(0L),
                working = WorkTimeListener.working,
                paused = PauseStatus.isPause,
                terminalState = terminal,
                liveMatch = liveMatch,
                processAlive = pid != null,
                currentPid = pid,
                processStartedAtMs = processStartedAt,
                gameWindowMatchesPid = windowMatchesPid,
                powerLogIsCurrentSession = powerLogIsCurrentSession,
                powerLogLength = powerLogLength,
                powerLogAgeMs = powerLogAge,
                knownUsableScreen = false,
                dialog = dialog,
            ),
        )
        logStartupFailureDecision(decision, pid, powerLogIsCurrentSession, powerLogLength, dialog)
        if (decision.action != BetaStartupFailureRecoveryPolicy.Action.WAIT) {
            dispatchStartupFailureRecovery(recoveryGeneration, decision, pid)
        }
    }

    internal fun shouldDeferStartupFailureRecovery(
        startupChainActive: Boolean,
        exactApplicationErrorDialogPresent: Boolean,
    ): Boolean = startupChainActive && !exactApplicationErrorDialogPresent

    private fun logStartupFailureDecision(
        decision: BetaStartupFailureRecoveryPolicy.Decision,
        pid: Long?,
        powerLogCurrent: Boolean,
        powerLogLength: Long,
        dialog: BetaStartupFailureRecoveryPolicy.DialogEvidence?,
    ) {
        val write = {
            "${if (decision.escalated) "BETA_STARTUP_RECOVERY_ESCALATED" else "BETA_STARTUP_RECOVERY"} " +
                "action=${decision.action} reason=${decision.reason} " +
                "attempt=${decision.attempt} retryDelayMs=${decision.retryDelayMs} " +
                "gamePid=${pid ?: "none"} powerLogCurrent=$powerLogCurrent powerLogLength=$powerLogLength " +
                "applicationErrorDialog=${dialog?.hwnd ?: "none"} " +
                "dialogHostPid=${dialog?.hostPid ?: "none"} dialogOwnerPid=${dialog?.ownerPid ?: "none"} " +
                "watchdog=RETAINED betaContinues=${decision.escalated} pause=${PauseStatus.isPause}"
        }
        when {
            decision.escalated -> log.error { write() }
            decision.action == BetaStartupFailureRecoveryPolicy.Action.WAIT -> log.debug { write() }
            else -> log.warn { write() }
        }
    }

    private fun dispatchStartupFailureRecovery(
        recoveryGeneration: Long,
        decision: BetaStartupFailureRecoveryPolicy.Decision,
        currentPid: Long?,
    ) {
        if (!startupFailureDispatchInFlight.compareAndSet(false, true)) return
        EXTRA_THREAD_POOL.execute {
            try {
                val terminalState = WarEx.war.currentPhase == WarPhaseEnum.GAME_OVER ||
                    GameUtil.isTerminalGameState()
                val liveMatch = WarEx.inWar || WarEx.war.currentPhase == WarPhaseEnum.REPLACE_CARD
                val allowsPersistentDialog = decision.reason in setOf(
                    "persistent-application-error-dialog-close",
                    "persistent-application-error-dialog-hard-restart",
                    "application-error-dialog-escalated-retry",
                )
                val exactDialogStillConfirmed = decision.currentApplicationErrorDialogHwnd?.let { hwnd ->
                    isCurrentApplicationErrorDialogConfirmed(currentPid, hwnd, allowsPersistentDialog)
                } == true
                if (!isStartupFailureGenerationCurrent(recoveryGeneration) || !WorkTimeListener.working ||
                    PauseStatus.isPause || BetaStartupFailureRecoveryDispatch.shouldBlockForGameState(
                        terminalState = terminalState,
                        liveMatch = liveMatch,
                        currentApplicationErrorDialogConfirmed = exactDialogStillConfirmed,
                    )
                ) return@execute
                val result = BetaStartupFailureRecoveryDispatch.dispatch(
                    action = decision.action,
                    rebindWindow = {
                        val rebound = runCatching { GameUtil.findGameHWND() }.getOrNull()
                        val verified = rebound != null && GameUtil.isVerifiedCurrentGameWindow(rebound)
                        if (verified) {
                            ScriptStatus.gameHWND = rebound
                        }
                        log.warn {
                            "BETA_STARTUP_RECOVERY_APPLIED action=REBIND_WINDOW result=" +
                                "${if (verified) "BOUND" else "NOT_FOUND_OR_UNVERIFIED"} " +
                                "pid=${currentPid ?: "none"} hwnd=${rebound ?: "none"}"
                        }
                        verified
                    },
                    restartClient = {
                        val evidence = if (decision.reason == "hearthstone-application-error-dialog") {
                            UnknownStateScreenshot.capture(
                                category = UnknownStateScreenshot.CATEGORY_SCREEN_RECOVERY_UNRESOLVED,
                                trigger = "beta-application-error-dialog",
                                state = "pid=${currentPid ?: "none"}",
                                phase = "beta-startup-failure-recovery",
                                label = "hearthstone-application-error-dialog",
                            )
                        } else {
                            null
                        }
                        val message = "BETA_STARTUP_RECOVERY_APPLIED action=RESTART_CLIENT " +
                            "reason=${decision.reason} attempt=${decision.attempt} " +
                            "evidence=${evidence?.file?.absolutePath ?: "none"}"
                        if (decision.escalated) log.warn { message } else log.error { message }
                        Core.restart(sync = true)
                        startStarterChainForRecovery(recoveryGeneration, decision)
                    },
                    restartStarterChain = {
                        startStarterChainForRecovery(recoveryGeneration, decision)
                    },
                    dismissPersistentDialog = {
                        val hwnd = decision.currentApplicationErrorDialogHwnd
                        val confirmed = hwnd != null && isCurrentApplicationErrorDialogConfirmed(
                            currentPid = currentPid,
                            expectedHwnd = hwnd,
                            allowDialogFromPreviousGameProcess = true,
                        )
                        val dismissed = confirmed &&
                            WindowsApplicationErrorDialogProbe.dismissExactBreakpointDialog(hwnd!!)
                        log.info {
                            "BETA_STARTUP_RECOVERY_DIALOG_CLOSE result=${if (dismissed) "VERIFIED_CLOSED" else "STILL_PRESENT_OR_UNVERIFIED"} " +
                                "dialog=${hwnd ?: "none"} pid=${currentPid ?: "none"}"
                        }
                        dismissed
                    },
                )
                if (result != BetaStartupFailureRecoveryDispatch.Result.NO_ACTION) {
                    log.info {
                        "BETA_STARTUP_RECOVERY_DISPATCH_RESULT result=$result " +
                            "action=${decision.action} attempt=${decision.attempt} " +
                            "watchdog=RETAINED pause=${PauseStatus.isPause}"
                    }
                }
            } catch (error: Throwable) {
                log.error(error) {
                    "BETA_STARTUP_RECOVERY_DISPATCH_FAILED action=${decision.action} " +
                        "attempt=${decision.attempt} retryDelayMs=${decision.retryDelayMs} " +
                        "watchdog=RETAINED"
                }
            } finally {
                startupFailureDispatchInFlight.set(false)
            }
        }
    }

    private fun isCurrentApplicationErrorDialogConfirmed(
        currentPid: Long?,
        expectedHwnd: Long,
        allowDialogFromPreviousGameProcess: Boolean = false,
    ): Boolean {
        if (currentPid == null) return false
        val process = ProcessHandle.of(currentPid).orElse(null) ?: return false
        if (!process.isAlive) return false
        val processStartedAt = process.info().startInstant().orElse(null)?.toEpochMilli() ?: return false
        val dialog = WindowsApplicationErrorDialogProbe.detect()?.asPolicyEvidence() ?: return false
        if (dialog.hwnd != expectedHwnd ||
            (!allowDialogFromPreviousGameProcess && dialog.firstSeenAtMs < processStartedAt) ||
            System.currentTimeMillis() - dialog.firstSeenAtMs <
            BetaStartupFailureRecoveryPolicy.APPLICATION_ERROR_CONFIRMATION_MS
        ) return false
        val gameHwnd = ScriptStatus.gameHWND ?: return false
        return runCatching { GameUtil.isVerifiedCurrentGameWindow(gameHwnd, currentPid) }.getOrDefault(false)
    }

    private fun startStarterChainForRecovery(
        recoveryGeneration: Long,
        decision: BetaStartupFailureRecoveryPolicy.Decision,
    ) {
        if (!isStartupFailureGenerationCurrent(recoveryGeneration)) return
        log.error {
            "BETA_STARTUP_RECOVERY_APPLIED action=RESTART_STARTER_CHAIN " +
                "reason=${decision.reason} attempt=${decision.attempt} " +
                "retryDelayMs=${decision.retryDelayMs} watchdog=RETAINED pause=${PauseStatus.isPause}"
        }
        NoProgressRecoveryDispatch.restartToStartup(
            recoverModeToStartup = {
                Mode.recover(ModeEnum.STARTUP, "beta-startup-${decision.reason}", enterStrategy = false)
            },
            startConfiguredStarterChain = {
                if (isStartupFailureGenerationCurrent(recoveryGeneration)) StarterConfig.starter.start()
            },
        )
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
        if (startupFailureMonitor?.isDone == false && decision.reason.contains("process-missing")) {
            log.info {
                "NO_PROGRESS_RECOVERY_DELEGATED reason=${decision.reason} " +
                    "owner=beta-startup-failure-monitor dispatch=false"
            }
            return
        }
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

