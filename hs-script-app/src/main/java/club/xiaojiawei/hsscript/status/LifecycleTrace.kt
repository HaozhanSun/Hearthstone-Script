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

/**
 * Low-noise process/window heartbeat used to distinguish a hidden JavaFX
 * window from a terminated JVM or an unhandled worker-thread failure.
 */
object LifecycleTrace {
    private const val GAME_OVER_STUCK_TIMEOUT_MS = 30_000L
    private const val STATE_RECOVERY_TIMEOUT_MS = 30_000L
    private const val STATE_RECOVERY_RETRY_INTERVAL_MS = 30_000L

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
    private val stateRecoveryInFlight = AtomicBoolean(false)

    fun start() {
        if (running) return
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
            return
        }

        val fingerprint = stateFingerprint()
        val now = System.currentTimeMillis()
        if (fingerprint != stateRecoveryFingerprint) {
            stateRecoveryFingerprint = fingerprint
            stateRecoverySince = now
            stateRecoveryAttemptAt = 0L
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
            try {
                ScreenStateRecovery.inspectAndRecover(
                    stuckFor,
                    fingerprint,
                ) {
                    stateFingerprint() == fingerprint &&
                        WorkTimeListener.working &&
                        !PauseStatus.isPause &&
                        !WarEx.inWar
                }
            } catch (error: Throwable) {
                log.warn(error) { "SCREEN_RECOVERY_FAILED reason=worker-exception" }
            } finally {
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
}
