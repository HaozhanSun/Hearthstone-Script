package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.strategy.AbstractPhaseStrategy
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import java.util.concurrent.atomic.AtomicBoolean

internal class ScreenRecoveryAttemptTracker(private val maxUnresolvedAttempts: Int = 2) {
    data class Decision(val unresolvedAttempts: Int, val shouldPause: Boolean)

    private var fingerprint: String? = null
    private var unresolvedAttempts = 0

    fun record(fingerprint: String, recovered: Boolean): Decision {
        if (this.fingerprint != fingerprint) {
            this.fingerprint = fingerprint
            unresolvedAttempts = 0
        }
        if (recovered) {
            unresolvedAttempts = 0
            return Decision(unresolvedAttempts, shouldPause = false)
        }
        unresolvedAttempts++
        return Decision(
            unresolvedAttempts = unresolvedAttempts,
            shouldPause = unresolvedAttempts >= maxUnresolvedAttempts.coerceAtLeast(1),
        )
    }

    fun reset() {
        fingerprint = null
        unresolvedAttempts = 0
    }
}

/**
 * Low-noise process/window heartbeat used to distinguish a hidden JavaFX
 * window from a terminated JVM or an unhandled worker-thread failure.
 */
object LifecycleTrace {
    private const val GAME_OVER_STUCK_TIMEOUT_MS = 30_000L
    private const val STATE_RECOVERY_TIMEOUT_MS = 30_000L
    private const val STATE_RECOVERY_RETRY_INTERVAL_MS = 20_000L
    private const val STATE_MONITOR_INTERVAL_MS = 10_000L
    private const val MAX_UNRESOLVED_STATE_RECOVERY_ATTEMPTS = 2

    internal fun unresolvedRecoveryPauseBoundMsForTest(): Long =
        STATE_RECOVERY_TIMEOUT_MS + STATE_RECOVERY_RETRY_INTERVAL_MS + STATE_MONITOR_INTERVAL_MS

    @Volatile
    private var mainWindowShowing = false

    @Volatile
    private var running = false

    private var gameOverStuckSince = 0L
    private var gameOverStuckLogPosition = Long.MIN_VALUE
    private var gameOverRecoveryRequested = false

    private var stateRecoverySince = 0L
    private var stateRecoveryFingerprint = ""
    private var stateRecoveryAttemptAt = 0L
    private val stateRecoveryAttemptTracker = ScreenRecoveryAttemptTracker(MAX_UNRESOLVED_STATE_RECOVERY_ATTEMPTS)
    private val stateRecoveryInFlight = AtomicBoolean(false)

    fun start() {
        if (running) return
        BetaScreenRecoveryService.start()
        running = true
        Thread {
            var lastState = ""
            while (running) {
                detectStuckGameOver()
                detectStuckStateRecovery()
                val state = snapshot()
                if (state != lastState) {
                    log.info { "LIFECYCLE_STATE $state" }
                    lastState = state
                } else {
                    log.info { "LIFECYCLE_HEARTBEAT $state" }
                }
                try {
                    Thread.sleep(STATE_MONITOR_INTERVAL_MS)
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
        BetaScreenRecoveryService.stop()
        mark("monitor-stop reason=$reason")
    }

    fun markMainWindow(showing: Boolean, reason: String) {
        mainWindowShowing = showing
        mark("main-window showing=$showing reason=$reason")
    }

    fun mark(reason: String) {
        log.info { "LIFECYCLE_EVENT pid=${ProcessHandle.current().pid()} reason=$reason" }
    }

    /**
     * Process liveness is not enough: a JavaFX window can remain visible while
     * the phase machine is wedged.  A GAME_OVER state with no active war and
     * an unchanged Power.log cursor for 30 seconds is an actionable anomaly.
     * In E2E mode, schedule the bounded stale-result-page recovery task once.
     */
    private fun detectStuckGameOver() {
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
    private fun detectStuckStateRecovery() {
        if (!WorkTimeListener.working || PauseStatus.isPause || WarEx.inWar ||
            PowerLogListener.replayingExistingLog
        ) {
            stateRecoverySince = 0L
            stateRecoveryFingerprint = ""
            stateRecoveryAttemptAt = 0L
            stateRecoveryAttemptTracker.reset()
            return
        }

        val fingerprint = stateFingerprint()
        val now = System.currentTimeMillis()
        if (fingerprint != stateRecoveryFingerprint) {
            stateRecoveryFingerprint = fingerprint
            stateRecoverySince = now
            stateRecoveryAttemptAt = 0L
            stateRecoveryAttemptTracker.reset()
            return
        }
        if (stateRecoverySince == 0L) stateRecoverySince = now

        val stuckFor = now - stateRecoverySince
        if (stuckFor < STATE_RECOVERY_TIMEOUT_MS ||
            now - stateRecoveryAttemptAt < STATE_RECOVERY_RETRY_INTERVAL_MS ||
            !stateRecoveryInFlight.compareAndSet(false, true)
        ) {
            return
        }

        stateRecoveryAttemptAt = now
        log.info { "SCREEN_RECOVERY_SCHEDULED stuckForMs=$stuckFor state=$fingerprint" }
        EXTRA_THREAD_POOL.execute {
            var recovered = false
            try {
                recovered = ScreenStateRecovery.inspectAndRecover(
                    stuckFor,
                    fingerprint,
                ) {
                    stateFingerprint() == fingerprint &&
                        WorkTimeListener.working &&
                        !PauseStatus.isPause &&
                        !WarEx.inWar
                }
            } catch (error: Throwable) {
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
                if (stateFingerprint() == fingerprint && !PauseStatus.isPause) {
                    val decision = stateRecoveryAttemptTracker.record(fingerprint, recovered)
                    if (decision.shouldPause) {
                        PauseStatus.setAutomaticPause(true)
                        log.warn {
                            "SCREEN_RECOVERY_PAUSED reason=unresolved-attempt-limit " +
                                "attempts=${decision.unresolvedAttempts} state=$fingerprint inputDispatch=false"
                        }
                    }
                }
                stateRecoveryInFlight.set(false)
            }
        }
    }

    private fun stateFingerprint(): String = listOf(
            Mode.currMode?.name ?: "NONE",
            Mode.nextMode?.name ?: "NONE",
            WarEx.war.currentPhase.name,
            WarEx.war.currentTurnStep?.name ?: "NONE",
            WarEx.warCount.toString(),
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

    // Thin compatibility API: optional Beta orchestration is owned by its service.
    fun markStartupRequested(reason: String, now: Long = System.currentTimeMillis()) =
        BetaScreenRecoveryService.markStartupRequested(reason, now)

    fun markStartupHandoffAttempt(reason: String, now: Long = System.currentTimeMillis()) =
        BetaScreenRecoveryService.markStartupHandoffAttempt(reason, now)

    internal fun startupRecoveryGraceRemainingMs(now: Long): Long =
        BetaScreenRecoveryService.startupRecoveryGraceRemainingMs(now)

    internal fun stopRecoveryCascade(rootCause: String) =
        BetaScreenRecoveryService.stopRecoveryCascade(rootCause)

    internal fun recoveryCascadeSuppressed(): Boolean =
        BetaScreenRecoveryService.recoveryCascadeSuppressed()

    fun recordForegroundFailure(target: String, actual: String? = null) =
        BetaScreenRecoveryService.recordForegroundFailure(target, actual)

    fun recordForegroundRecovered() = BetaScreenRecoveryService.recordForegroundRecovered()

    fun requestActionRecovery(reason: String) = BetaScreenRecoveryService.requestActionRecovery(reason)
}
