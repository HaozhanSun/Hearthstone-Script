package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.strategy.AbstractPhaseStrategy
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.consts.LOG_PATH
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import club.xiaojiawei.hsscriptbase.const.BuildChannel
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-noise process/window heartbeat used to distinguish a hidden JavaFX
 * window from a terminated JVM or an unhandled worker-thread failure.
 */
object LifecycleTrace {
    private const val GAME_OVER_STUCK_TIMEOUT_MS = 30_000L
    private const val STATE_RECOVERY_TIMEOUT_MS = StuckStateRecoveryCadencePolicy.STATE_TIMEOUT_MS
    private const val MATCHMAKING_SCREEN_RECOVERY_SUPPRESSION_MS = 120_000L
    private const val MATCHMAKING_INPUT_INTENT_WINDOW_MS = 15 * 60_000L
    private const val MATCHMAKING_ENTRY_RECOVERY_GUARD_MS = 60_000L

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
    private val terminalRecoveryInFlight = AtomicBoolean(false)
    @Volatile
    private var terminalRecoveryLatched = false

    @Volatile
    private var activeMatchmakingScreenObservedAt = 0L

    @Volatile
    private var matchmakingInputIntentAt = 0L

    fun start() {
        if (running) return
        BetaScreenRecoveryService.start()
        running = true
        writeGuardianState()
        Thread {
            var lastState = ""
            while (running) {
                detectStuckGameOver()
                detectStuckTerminalResult()
                detectStuckStateRecovery()
                val state = snapshot()
                writeGuardianState()
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
        BetaScreenRecoveryService.stop()
        mark("monitor-stop reason=$reason")
    }

    /** Persist the last supervised-run intent for the launcher-level guardian. */
    fun markGuardianStopped(reason: String) {
        log.info {
            "GUARDIAN_INTENT intent=STOPPED reason=$reason pid=${ProcessHandle.current().pid()} " +
                "thread=${Thread.currentThread().name}"
        }
        writeGuardianState(overrideIntent = "STOPPED", reason = reason)
    }

    /** Manual pause is an explicit instruction not to resurrect the script process. */
    fun markGuardianManualPause(paused: Boolean) {
        writeGuardianState(overrideIntent = if (paused) "PAUSED_MANUAL" else null)
    }

    private fun writeGuardianState(overrideIntent: String? = null, reason: String = "heartbeat") {
        if (System.getProperty("hs.script.launch.source") != "shortcut") return
        runCatching {
            val working = WorkTimeListener.working
            val manuallyPaused = PauseStatus.pauseOrigin == PauseStatus.Origin.MANUAL && PauseStatus.isPause
            val intent = overrideIntent ?: when {
                manuallyPaused -> "PAUSED_MANUAL"
                working -> "RUNNING"
                else -> "STOPPED"
            }
            val healthSnapshot = runCatching {
                GuardianHealthSnapshot(
                    gameAlive = GameUtil.isAliveOfGame(),
                    activeGame = WarEx.inWar,
                    activeMatchmaking = hasRecentActiveMatchmakingScreen() ||
                        hasRecentMatchmakingInputIntent(),
                )
            }.getOrNull()
            val statePath = System.getProperty("hs.script.guardian-state.path")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: Path.of(LOG_PATH, "guardian-state.json")
            val appLogPath = Path.of(LOG_PATH, "hs_script.log")
            val appLogWriteAt = runCatching {
                Files.getLastModifiedTime(appLogPath).toInstant()
            }.getOrNull()
            val appLogAgeMs = appLogWriteAt?.let { java.time.Duration.between(it, java.time.Instant.now()).toMillis().coerceAtLeast(0) }
            val directory = statePath.parent ?: Path.of(LOG_PATH)
            Files.createDirectories(directory)
            val target = statePath
            val temporary = directory.resolve("guardian-state.${ProcessHandle.current().pid()}.tmp")
            val deploymentId = System.getProperty("hs.script.deployment.id", "unknown").replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val processStart = ProcessHandle.current().info().startInstant().orElse(java.time.Instant.EPOCH)
            val json = """{"schema":3,"pid":${ProcessHandle.current().pid()},"processStartTimeUtc":"$processStart","deploymentId":"$deploymentId","releaseChannel":"${BuildInfo.RELEASE_CHANNEL}","intent":"$intent","working":$working,"paused":${PauseStatus.isPause},"pauseOrigin":"${PauseStatus.pauseOrigin}","gameAlive":${healthSnapshot?.gameAlive ?: false},"activeGame":${healthSnapshot?.activeGame ?: false},"activeMatchmaking":${healthSnapshot?.activeMatchmaking ?: false},"healthSnapshotKnown":${healthSnapshot != null},"appLogPath":"${appLogPath.toAbsolutePath().toString().replace("\\", "\\\\")}","lastAppLogWriteAtUtc":${appLogWriteAt?.let { "\"$it\"" } ?: "null"},"appLogAgeMs":${appLogAgeMs ?: "null"},"heartbeatAtUtc":"${java.time.Instant.now()}","reason":"${reason.replace(Regex("[^A-Za-z0-9_. -]"), "_")}"}"""
            Files.writeString(temporary, json)
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { error ->
            log.warn(error) { "GUARDIAN_STATE_WRITE_FAILED" }
        }
    }

    private data class GuardianHealthSnapshot(
        val gameAlive: Boolean,
        val activeGame: Boolean,
        val activeMatchmaking: Boolean,
    )

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

    /** Prefer complete current-game Power.log proof over stale queue evidence or OCR on Beta. */
    private fun detectStuckTerminalResult() {
        val terminalPending = PowerLogListener.isCurrentGameTerminalCleanupPending()
        if (!terminalPending) {
            terminalRecoveryLatched = false
            return
        }
        if (!BetaTerminalResultReconciliationPolicy.shouldReconcile(
                channel = BuildInfo.RELEASE_CHANNEL,
                phase = WarEx.war.currentPhase.name,
                activeGame = WarEx.inWar,
                completeCurrentGameTerminal = PowerLogListener.hasCurrentGameCompleteTerminalPowerLogEvidence(),
                resultAlreadyHandled = false,
            ) || terminalRecoveryLatched || !terminalRecoveryInFlight.compareAndSet(false, true)
        ) return

        terminalRecoveryLatched = true
        log.warn {
            "BETA_TERMINAL_RESULT_RECONCILIATION_SCHEDULED " +
                "phase=${WarEx.war.currentPhase} inWar=${WarEx.inWar} source=current-game-power-log " +
                "action=bounded-result-cleanup"
        }
        EXTRA_THREAD_POOL.execute {
            try {
                PowerLogListener.reconcileStaleTerminalResultForBeta()
            } catch (error: Throwable) {
                log.error(error) { "BETA_TERMINAL_RESULT_RECONCILIATION_FAILED" }
            } finally {
                terminalRecoveryInFlight.set(false)
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
            PowerLogListener.replayingExistingLog || terminalRecoveryInFlight.get()
        ) {
            stateRecoverySince = 0L
            stateRecoveryFingerprint = ""
            stateRecoveryAttemptAt = 0L
            return
        }

        val now = System.currentTimeMillis()
        if (shouldSuppressStateRecoveryForMatchmaking(activeMatchmakingScreenObservedAt, now)) {
            stateRecoverySince = 0L
            stateRecoveryFingerprint = ""
            stateRecoveryAttemptAt = 0L
            return
        }
        if (activeMatchmakingScreenObservedAt != 0L) {
            // Suppression expires once per observation; start a fresh stale-state
            // interval before inspecting the same stale Power.log phase again.
            activeMatchmakingScreenObservedAt = 0L
            stateRecoverySince = now
            stateRecoveryFingerprint = stateFingerprint()
            stateRecoveryAttemptAt = now
            return
        }

        val fingerprint = stateFingerprint()
        if (fingerprint != stateRecoveryFingerprint) {
            stateRecoveryFingerprint = fingerprint
            stateRecoverySince = now
            stateRecoveryAttemptAt = 0L
            return
        }
        if (stateRecoverySince == 0L) stateRecoverySince = now

        val stuckFor = now - stateRecoverySince
        if (!StuckStateRecoveryCadencePolicy.shouldProbe(
                channel = BuildInfo.RELEASE_CHANNEL,
                stillStuck = true,
                stuckForMs = stuckFor,
                lastAttemptAtMs = stateRecoveryAttemptAt,
                nowMs = now,
            ) ||
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

    /** The active queue UI is expected while Power.log still reports the previous FILL_DECK phase. */
    fun markActiveMatchmakingScreenObserved(now: Long = System.currentTimeMillis()) {
        activeMatchmakingScreenObservedAt = now
        mark("visible-matchmaking-observed; suppressing stale-phase recovery for ${MATCHMAKING_SCREEN_RECOVERY_SUPPRESSION_MS}ms")
    }

    /** A freshly confirmed deck-selection destination ends the prior queue-search evidence window. */
    fun clearActiveMatchmakingScreenObserved(reason: String) {
        activeMatchmakingScreenObservedAt = 0L
        matchmakingInputIntentAt = 0L
        mark("active-matchmaking-evidence-cleared reason=$reason")
    }

    /** A current exact reconnect screen supersedes only the previous queue-screen observation. */
    fun supersedeActiveMatchmakingScreenForReconnect(
        reason: String = "exact-reconnect-screen",
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val hadRecentQueueScreen = hasRecentActiveMatchmakingScreen(now)
        activeMatchmakingScreenObservedAt = 0L
        mark("active-matchmaking-screen-superseded reason=$reason staleInputIntentRetained=${
            hasRecentMatchmakingInputIntent(now)
        }")
        return hadRecentQueueScreen
    }

    /** A recent Start dispatch permits capture while the new queue has no Power.log yet. */
    fun markMatchmakingInputDispatched(
        dispatchAccepted: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!dispatchAccepted) {
            mark("matchmaking-input-dispatch-rejected; queue intent not recorded")
            return
        }
        matchmakingInputIntentAt = now
        PowerLogListener.markNewMatchmakingInputAfterTerminalResult()
        mark("matchmaking-input-dispatched; pre-session capture enabled for ${MATCHMAKING_INPUT_INTENT_WINDOW_MS}ms")
    }

    internal fun shouldSuppressStateRecoveryForMatchmaking(observedAt: Long, now: Long): Boolean =
        observedAt > 0L && now >= observedAt && now - observedAt < MATCHMAKING_SCREEN_RECOVERY_SUPPRESSION_MS

    /** Queue evidence also protects the normal matchmaking input path. */
    internal fun hasRecentActiveMatchmakingScreen(now: Long = System.currentTimeMillis()): Boolean =
        shouldSuppressStateRecoveryForMatchmaking(activeMatchmakingScreenObservedAt, now)

    internal fun hasRecentMatchmakingInputIntent(now: Long = System.currentTimeMillis()): Boolean =
        matchmakingInputIntentAt > 0L && now >= matchmakingInputIntentAt &&
            now - matchmakingInputIntentAt < MATCHMAKING_INPUT_INTENT_WINDOW_MS

    internal fun resetMatchmakingEvidenceForTest() {
        activeMatchmakingScreenObservedAt = 0L
        matchmakingInputIntentAt = 0L
    }

    /** Protect mode transitions while asynchronous matchmaking entry settles. */
    internal fun hasActiveMatchmakingEntryRecoveryGuard(now: Long = System.currentTimeMillis()): Boolean =
        matchmakingInputIntentAt > 0L && now >= matchmakingInputIntentAt &&
            now - matchmakingInputIntentAt < MATCHMAKING_ENTRY_RECOVERY_GUARD_MS

    private fun snapshot(): String = runCatching {
        val scriptPid = ProcessHandle.current().pid()
        val gamePid = GameUtil.findGameProcessIdForDiagnostics()
        val gameProcessStartedAt = gamePid?.let {
            runCatching { GameUtil.findProcessStartedAtForDiagnostics(it) }.getOrNull()
        }
        val diskPowerLog = PowerLogListener.logFile
        val powerLog = diskPowerLog?.path()?.let(::File)
        val latestPowerLog = GameUtil.getLatestLogDir()?.resolve(club.xiaojiawei.hsscript.consts.GAME_WAR_LOG_NAME)
        val powerLogCurrentSession = gamePid != null && powerLog != null && latestPowerLog != null &&
            powerLog.canonicalFile == latestPowerLog.canonicalFile &&
            PowerLogSessionBindingPolicy.isCurrentSession(
                powerLogPath = powerLog.absolutePath,
                gameLogsRoot = latestPowerLog.parentFile?.parent,
                length = powerLog.length(),
                lastModifiedMs = powerLog.lastModified(),
                processStartedAtMs = gameProcessStartedAt,
            )
        val logState = if (diskPowerLog == null) {
            "logFile=none"
        } else {
            "logFile=${diskPowerLog.path()} logPos=${diskPowerLog.getPosition()} logLen=${diskPowerLog.length()}"
        }
        "pid=$scriptPid scriptPid=$scriptPid gamePid=${gamePid ?: "none"} " +
            "gameAlive=${gamePid != null} powerLogCurrentSession=$powerLogCurrentSession " +
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
