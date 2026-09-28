package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.config.StarterConfig
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.ocr.OcrProviderKind
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.strategy.AbstractModeStrategy
import club.xiaojiawei.hsscript.strategy.AbstractPhaseStrategy
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Low-noise process/window heartbeat used to distinguish a hidden JavaFX
 * window from a terminated JVM or an unhandled worker-thread failure.
 */
object LifecycleTrace {
    private const val GAME_OVER_STUCK_TIMEOUT_MS = 30_000L
    private const val STATE_RECOVERY_TIMEOUT_MS = 30_000L
    private const val STATE_RECOVERY_RETRY_INTERVAL_MS = 30_000L
    private const val STARTUP_RECOVERY_GRACE_MS = 3_000L

    @Volatile
    private var mainWindowShowing = false

    @Volatile
    private var running = false

    private var gameOverStuckSince = 0L
    private var gameOverStuckLogPosition = Long.MIN_VALUE
    private var gameOverRecoveryRequested = false

    private var stateRecoverySince = 0L
    private var stateRecoveryFingerprint = ""
    private var stateRecoveryPowerLogPosition = Long.MIN_VALUE
    private var stateRecoveryAttemptAt = 0L
    // The lifecycle poller resets this when state changes while recovery runs
    // on EXTRA_THREAD_POOL. Keep the bounded retry counter race-free.
    private val stateRecoveryForegroundDeferrals = AtomicInteger(0)
    private val stateRecoveryInFlight = AtomicBoolean(false)

    private val noProgressWatchdog = NoProgressWatchdog()
    private val startupHandoffActivity = StartupHandoffActivityTracker()
    private val recoveryCascadeGuard = RecoveryCascadeGuard()
    private val foregroundFailureCount = AtomicInteger(0)
    private val foregroundRecoveryPending = AtomicBoolean(false)
    @Volatile
    private var noProgressBoundPid: Long? = null
    @Volatile
    private var noProgressBoundPowerLogPath: String? = null

    @Volatile
    private var startupRecoveryGraceUntil = 0L

    fun start() {
        if (running) return
        ScreenRecoveryRuntime.initialize()
        running = true
        Thread {
            var lastState = ""
            var lastRecoveryGeneration = -1L
            while (running) {
                val generation = ScreenRecoveryRuntime.generation()
                val recoveryToken = ScreenRecoveryRuntime.tokenOrNull()
                if (generation != lastRecoveryGeneration) {
                    if (recoveryToken != null) resetRecoveryState()
                    lastRecoveryGeneration = generation
                }
                if (recoveryToken != null) {
                    detectStuckGameOver(recoveryToken)
                    detectStuckStateRecovery(recoveryToken)
                    detectNoProgress(recoveryToken)
                }
                val state = snapshot()
                if (state != lastState) {
                    log.info { "LIFECYCLE_STATE $state" }
                    lastState = state
                } else {
                    log.info { "LIFECYCLE_HEARTBEAT $state" }
                }
                try {
                    Thread.sleep(10_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }.apply {
            name = "Lifecycle Monitor"
            isDaemon = true
            start()
        }
        mark("monitor-started")
    }

    fun stop(reason: String) {
        running = false
        mark("monitor-stop reason=$reason")
    }

    fun markMainWindow(showing: Boolean, reason: String) {
        mainWindowShowing = showing
        mark("main-window showing=$showing reason=$reason")
    }

    /**
     * Starting the client is intentionally asynchronous: the ordinary starter
     * chain must attach the log listeners and discover the first mode before a
     * screen-recovery OCR pass is allowed to intervene.  Recovery remains a
     * fallback when this grace period expires without a state transition.
     */
    fun markStartupRequested(reason: String, now: Long = System.currentTimeMillis()) {
        startupRecoveryGraceUntil = now + STARTUP_RECOVERY_GRACE_MS
        mark("startup-recovery-grace reason=$reason until=$startupRecoveryGraceUntil")
    }

    /** Record a normal GameStarter dispatch attempt, not game/platform acceptance. */
    fun markStartupHandoffAttempt(reason: String, now: Long = System.currentTimeMillis()) {
        startupHandoffActivity.recordAttempt(now)
        log.debug { "STARTUP_HANDOFF_ACTIVITY_SIGNAL reason=$reason acceptance=not-confirmed" }
    }

    internal fun startupRecoveryGraceRemainingMs(now: Long): Long =
        (startupRecoveryGraceUntil - now).coerceAtLeast(0L)

    fun mark(reason: String) {
        log.info { "LIFECYCLE_EVENT pid=${ProcessHandle.current().pid()} reason=$reason" }
    }

    /** Fence recovery after a terminal startup/no-progress pause. */
    internal fun stopRecoveryCascade(rootCause: String) {
        if (!ScreenRecoveryRuntime.isEnabled()) return
        if (recoveryCascadeGuard.trip(rootCause)) {
            log.error {
                "RECOVERY_CASCADE_STOP rootCause=$rootCause " +
                    "followOn=screen-recovery,unknown-state-screenshot,platform-close-skipped " +
                    "action=SUPPRESS_UNTIL_RESUME"
            }
        }
    }

    /**
     * Admission check for delayed recovery callbacks. A callback queued just
     * before a terminal pause must not clear that pause and dispatch input.
     */
    internal fun recoveryCascadeSuppressed(): Boolean =
        ScreenRecoveryRuntime.isEnabled() && recoveryCascadeGuard.suppressWhilePaused(PauseStatus.isPause)

    internal fun shouldObserveNoProgress(
        working: Boolean,
        automaticPause: Boolean,
        replaying: Boolean,
        paused: Boolean,
        recoveryPending: Boolean,
    ): Boolean = (working || automaticPause) && !replaying &&
        (!paused || automaticPause || recoveryPending)

    /**
     * Called by guarded desktop input when Windows repeatedly leaves another
     * top-level window foreground.  A live JVM and a growing diagnostic log
     * are not enough in this case: no click was actually dispatched.
     */
    fun recordForegroundFailure(target: String, actual: String? = null) {
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

    /** Used when a turn-end replan exhausted without a confirmed dispatch. */
    fun requestActionRecovery(reason: String) {
        if (!ScreenRecoveryRuntime.isEnabled()) return
        log.error { "NO_PROGRESS_ACTION_RECOVERY_REQUESTED reason=$reason dispatch=false" }
        if (foregroundRecoveryPending.compareAndSet(false, true)) {
            AbstractModeStrategy.cancelAllTask()
            PauseStatus.isPause = true
        }
    }

    /**
     * Process liveness is not enough: a JavaFX window can remain visible while
     * the phase machine is wedged.  A GAME_OVER state with no active war and
     * an unchanged Power.log cursor for 30 seconds is an actionable anomaly.
     * In E2E mode, schedule the bounded stale-result-page recovery task once.
     */
    private fun detectStuckGameOver(recoveryToken: Long) {
        if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return
        val phase = WarEx.war.currentPhase
        val position = PowerLogListener.logFile?.getPosition() ?: Long.MIN_VALUE
        if (phase != WarPhaseEnum.GAME_OVER || WarEx.inWar || position == Long.MIN_VALUE) {
            gameOverStuckSince = 0L
            gameOverStuckLogPosition = Long.MIN_VALUE
            gameOverRecoveryRequested = false
            return
        }

        val now = System.currentTimeMillis()
        if (gameOverStuckLogPosition != position) {
            gameOverStuckLogPosition = position
            gameOverStuckSince = now
            gameOverRecoveryRequested = false
            return
        }
        if (gameOverStuckSince == 0L) gameOverStuckSince = now

        val stuckFor = now - gameOverStuckSince
        if (stuckFor >= GAME_OVER_STUCK_TIMEOUT_MS && !gameOverRecoveryRequested) {
            gameOverRecoveryRequested = true
            log.warn {
                "LIFECYCLE_ANOMALY phase=GAME_OVER inWar=false " +
                    "powerLogPosition=$position stuckForMs=$stuckFor " +
                    "replaying=${PowerLogListener.replayingExistingLog}"
            }
            if (System.getProperty("hs.script.e2e") == "true") {
                runCatching { GameUtil.dismissStaleGameEndScreen() }
                    .onFailure { error ->
                        log.warn(error) { "LIFECYCLE_ANOMALY stale-result recovery scheduling failed" }
                    }
            }
        }
    }

    /**
     * LoadingScreen.log can stop emitting transitions while the client is
     * still showing a usable page. If the state machine has not changed for
     * 30 seconds, inspect the visible Hearthstone window and recover only from
     * a high-confidence known screen. Active games are deliberately excluded:
     * a turn can legitimately last more than 30 seconds and must never be
     * interrupted by this fallback.
     */
    private fun detectStuckStateRecovery(recoveryToken: Long) {
        if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return
        if (recoveryCascadeGuard.suppressWhilePaused(PauseStatus.isPause)) {
            return
        }
        if ((!WorkTimeListener.working && !PauseStatus.isAutomaticPause) ||
            (!PauseStatus.canRunAutomaticRecovery()) ||
            WarEx.inWar ||
            PowerLogListener.replayingExistingLog
        ) {
            stateRecoverySince = 0L
            stateRecoveryFingerprint = ""
            stateRecoveryPowerLogPosition = Long.MIN_VALUE
            stateRecoveryAttemptAt = 0L
            stateRecoveryForegroundDeferrals.set(0)
            return
        }

        val fingerprint = stateFingerprint()
        val now = System.currentTimeMillis()
        val powerLogPosition = PowerLogListener.logFile?.getPosition() ?: Long.MIN_VALUE
        if (fingerprint != stateRecoveryFingerprint ||
            (powerLogPosition != Long.MIN_VALUE && powerLogPosition != stateRecoveryPowerLogPosition)
        ) {
            stateRecoveryFingerprint = fingerprint
            stateRecoveryPowerLogPosition = powerLogPosition
            stateRecoverySince = now
            stateRecoveryAttemptAt = 0L
            stateRecoveryForegroundDeferrals.set(0)
            return
        }
        if (stateRecoverySince == 0L) stateRecoverySince = now

        val startupHandshake = !WarEx.inWar &&
            Mode.currMode == null &&
            PowerLogListener.logFile?.let { it.length() <= 1L } != false &&
            WarEx.war.currentPhase == WarPhaseEnum.FILL_DECK
        val startupGraceRemaining = startupRecoveryGraceRemainingMs(now)
        if (startupHandshake && startupGraceRemaining > 0L) {
            log.info {
                "SCREEN_RECOVERY_DEFERRED reason=startup-grace " +
                    "remainingMs=$startupGraceRemaining state=$fingerprint"
            }
            return
        }

        val stuckFor = now - stateRecoverySince
        if (stuckFor < STATE_RECOVERY_TIMEOUT_MS ||
            now - stateRecoveryAttemptAt < STATE_RECOVERY_RETRY_INTERVAL_MS ||
            !stateRecoveryInFlight.compareAndSet(false, true)
        ) {
            return
        }

        stateRecoveryAttemptAt = now
        log.info {
            "SCREEN_RECOVERY_SCHEDULED stuckForMs=$stuckFor state=$fingerprint " +
                "powerLogPosition=$powerLogPosition reason=no-power-log-progress"
        }
        val taskRef = AtomicReference<java.util.concurrent.Future<*>?>()
        val task = EXTRA_THREAD_POOL.submit {
            try {
                if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return@submit
                val recoveryResult = ScreenStateRecovery.inspectAndRecover(
                    stuckFor,
                    fingerprint,
                ) {
                    stateFingerprint() == fingerprint &&
                        (WorkTimeListener.working || PauseStatus.isAutomaticPause) &&
                        PauseStatus.canRunAutomaticRecovery() &&
                        !WarEx.inWar
                }
                when (recoveryResult) {
                    ScreenStateRecovery.InspectionResult.DEFERRED_GAME_FOREGROUND -> {
                        val decision = ScreenRecoveryFocusRetryPolicy.afterForegroundFailure(
                            stateRecoveryForegroundDeferrals.getAndIncrement(),
                        )
                        stateRecoverySince = System.currentTimeMillis()
                        log.warn {
                            "SCREEN_RECOVERY_FOREGROUND_BACKOFF decision=$decision " +
                                "attempts=${stateRecoveryForegroundDeferrals.get()} " +
                                "state=$fingerprint pause=${PauseStatus.isPause} working=${WorkTimeListener.working}"
                        }
                    }
                    else -> stateRecoveryForegroundDeferrals.set(0)
                }
            } catch (error: Throwable) {
                if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return@submit
                val evidence = UnknownStateScreenshot.capture(
                    category = UnknownStateScreenshot.CATEGORY_SCREEN_RECOVERY_UNRESOLVED,
                    trigger = "screen-recovery-worker-failure",
                    state = fingerprint,
                    phase = "stuck-screen-recovery-worker",
                    label = "screen-recovery-worker-failure",
                )
                log.warn(error) { "SCREEN_RECOVERY_FAILED reason=worker-exception" }
                log.warn {
                    "SCREEN_RECOVERY_FAILURE_SCREENSHOT " +
                        "path=${evidence?.file?.absolutePath ?: "not-saved"} " +
                        "link=${evidence?.link ?: "none"}"
                }
            } finally {
                stateRecoveryInFlight.set(false)
                taskRef.get()?.let(ScreenRecoveryRuntime::forget)
            }
        }
        taskRef.set(task)
        ScreenRecoveryRuntime.track(recoveryToken, task)
    }

    /**
     * Observe authoritative Power.log progress independently of strategy
     * workers.  This catches the failure mode where retry/error logging keeps
     * growing while the game window is not receiving input.
     */
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
        val powerLog = PowerLogListener.logFile
        val powerLogPath = powerLog?.path()
        val powerLogPosition = powerLog?.getPosition() ?: Long.MIN_VALUE
        val powerLogLength = powerLog?.length() ?: 0L
        val powerLogAge = powerLogPath?.let { path ->
            runCatching { (now - java.io.File(path).lastModified()).coerceAtLeast(0L) }
                .getOrNull()
        } ?: Long.MAX_VALUE
        val currentPid = GameUtil.findGameProcessIdForDiagnostics()
        if (noProgressBoundPid == null && currentPid != null) noProgressBoundPid = currentPid
        if (noProgressBoundPowerLogPath == null && powerLogPath != null) {
            noProgressBoundPowerLogPath = powerLogPath
        }
        val screen = when {
            WarEx.war.currentPhase == WarPhaseEnum.GAME_OVER || GameUtil.isTerminalGameState() ->
                NoProgressWatchdog.ScreenExpectation.RESULT
            Mode.nextMode == ModeEnum.STARTUP || powerLog == null ->
                NoProgressWatchdog.ScreenExpectation.STARTUP
            recoveryPending && WarEx.inWar -> NoProgressWatchdog.ScreenExpectation.ACTIVE_GAMEPLAY
            WarEx.inWar && !WarEx.war.isMyTurn -> NoProgressWatchdog.ScreenExpectation.OPPONENT_TURN
            WarEx.inWar && AbstractPhaseStrategy.dealing -> NoProgressWatchdog.ScreenExpectation.ANIMATION
            WarEx.inWar -> NoProgressWatchdog.ScreenExpectation.ACTIVE_GAMEPLAY
            else -> NoProgressWatchdog.ScreenExpectation.MENU_OR_MATCHING
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
                        "decision=RESTART reason=${decision.reason} dispatch=false"
                }
                Mode.recover(ModeEnum.STARTUP, "no-progress-${decision.reason}", enterStrategy = false)
                StarterConfig.starter.start()
            }
            NoProgressWatchdog.RecoveryAction.STARTUP_RETRY_BACKOFF -> {
                log.warn {
                    "NO_PROGRESS_STARTUP_RETRY_BACKOFF attempt=${decision.recoveryAttempt} " +
                        "reason=${decision.reason} watchdogRearmed=true automaticPause=false " +
                        "startupRetryPolicy=bounded-rate"
                }
            }
            NoProgressWatchdog.RecoveryAction.ESCALATE_PAUSE -> {
                if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return
                log.error {
                    "NO_PROGRESS_ESCALATED attempt=${decision.recoveryAttempt} " +
                        "reason=${decision.reason} dispatch=false terminal=PAUSE"
                }
                stopRecoveryCascade(decision.reason)
                AbstractModeStrategy.cancelAllTask()
                foregroundRecoveryPending.set(false)
                PauseStatus.isPause = true
            }
        }
    }

    private fun resetRecoveryState() {
        gameOverStuckSince = 0L
        gameOverStuckLogPosition = Long.MIN_VALUE
        gameOverRecoveryRequested = false
        stateRecoverySince = 0L
        stateRecoveryFingerprint = ""
        stateRecoveryPowerLogPosition = Long.MIN_VALUE
        stateRecoveryAttemptAt = 0L
        stateRecoveryForegroundDeferrals.set(0)
        noProgressWatchdog.reset()
        noProgressBoundPid = null
        noProgressBoundPowerLogPath = null
        foregroundFailureCount.set(0)
        foregroundRecoveryPending.set(false)
    }

    /**
     * This value is emitted in screen-recovery logs and used to detect that
     * the lifecycle is stuck. Keep it deterministic, but name every field so
     * operators do not have to remember the old positional format.
     */
    private fun stateFingerprint(): String = listOf(
            "currMode=${Mode.currMode?.name ?: "NONE"}",
            "nextMode=${Mode.nextMode?.name ?: "NONE"}",
            "warPhase=${WarEx.war.currentPhase.name}",
            "turnStep=${WarEx.war.currentTurnStep?.name ?: "NONE"}",
            "warCount=${WarEx.warCount}",
        ).joinToString("|")

    private fun snapshot(): String = runCatching {
        val powerLog = PowerLogListener.logFile
        val logState = if (powerLog == null) {
            "logFile=none"
        } else {
            "logFile=${powerLog.path()} logPos=${powerLog.getPosition()} logLen=${powerLog.length()}"
        }
            "pid=${ProcessHandle.current().pid()} " +
            "pause=${PauseStatus.isPause} " +
            "pauseOrigin=${PauseStatus.pauseOrigin.name} " +
            "working=${WorkTimeListener.working} " +
            "mainWindowShowing=$mainWindowShowing " +
            "mode=${Mode.currMode?.name ?: "NONE"} " +
            "inWar=${WarEx.inWar} " +
            "warPhase=${WarEx.war.currentPhase.name} " +
            "myTurn=${WarEx.war.isMyTurn} " +
            "phaseDealing=${AbstractPhaseStrategy.dealing} " +
            "replaying=${PowerLogListener.replayingExistingLog} " +
            logState + " " +
            "warCount=${WarEx.warCount}"
    }.getOrElse { "pid=${ProcessHandle.current().pid()} snapshotError=${it.javaClass.simpleName}:${it.message}" }
}
