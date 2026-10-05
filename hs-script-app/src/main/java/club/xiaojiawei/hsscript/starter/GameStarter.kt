package club.xiaojiawei.hsscript.starter

import club.xiaojiawei.hsscript.config.StarterConfig
import club.xiaojiawei.hsscript.consts.GAME_CN_NAME
import club.xiaojiawei.hsscript.consts.PLATFORM_CN_NAME
import club.xiaojiawei.hsscript.dll.CSystemDll
import club.xiaojiawei.hsscript.dll.User32ExDll
import club.xiaojiawei.hsscript.dll.User32ExDll.Companion.HWND_BOTTOM
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.status.Mode
import club.xiaojiawei.hsscript.status.LifecycleTrace
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ActionDispatchGate
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.status.ScreenStateRecovery
import club.xiaojiawei.hsscript.status.CurrentGameScreenReadinessPolicy
import club.xiaojiawei.hsscript.status.BetaScreenRecoveryService
import club.xiaojiawei.hsscript.utils.*
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.LAUNCH_PROGRAM_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import club.xiaojiawei.hsscriptbase.util.isFalse
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object PlatformCloseReadiness {
    fun shouldClose(
        gameAlive: Boolean,
        visibleGameWindow: Boolean,
        startupHandshakeConfirmed: Boolean,
        stableForMs: Long,
        requiredStabilityMs: Long,
    ): Boolean =
        gameAlive && visibleGameWindow && startupHandshakeConfirmed && stableForMs >= requiredStabilityMs
}


/**
 * 启动游戏
 * @author 肖嘉威
 * @date 2023/7/5 14:38
 */
class GameStarter : AbstractStarter() {

    /**
     * Battle.net can keep Hearthstone on the connection screen while its
     * launcher remains alive. Close it once Hearthstone has a live process and
     * window, rather than waiting for the login/startup state machine to leave
     * STARTUP or LOGIN (which may never happen in the affected case).
     */
    @Volatile
    private var platformCloseRequested = false

    private val startupProbeScheduled = AtomicBoolean(false)

    private var handoffState = GameStartupHandoffPolicy.State()

    /** Kept across starter-chain retries so a dead client cannot cause an unbounded loop. */
    @Volatile
    private var startupFailureAttempts = 0

    /** Rotate launch methods across the starter-chain retries; never pin to the configured last method. */
    @Volatile
    private var startupModeDispatches = 0

    @Volatile
    private var lastGameLaunchAt = 0L

    private companion object {
        private const val PLATFORM_CLOSE_STABILITY_MS = 20_000L
        private const val PLATFORM_CLOSE_MAX_WAIT_MS = 45_000L
        private const val PLATFORM_CLOSE_POLL_MS = 1_000L
    }

    public override fun execStart() {
        platformCloseRequested = false
        startupProbeScheduled.set(false)
        handoffState = GameStartupHandoffPolicy.State()
        log.info { "开始检查$GAME_CN_NAME" }
        val gameHWND = ScriptStatus.gameHWND
        if (gameHWND != null && GameUtil.isVerifiedCurrentGameWindow(gameHWND)) {
            next(gameHWND)
            return
        }
        // Re-discover an already-open game before starting the retry loop.
        // ScriptStatus is reset between launcher stages, so checking only the
        // cached handle made a visible Hearthstone window look absent and
        // triggered repeated launch attempts.
        GameUtil.findGameHWND()?.takeIf(GameUtil::isVerifiedCurrentGameWindow)?.let {
            // Match the upstream starter's fast path: a visible existing
            // Hearthstone client is already the handoff target, regardless of
            // whether this run was started by the E2E harness or by F1/UI.
            // Restricting this probe to E2E caused normal F1 starts to launch a
            // second client and wait through a needless process-exit/retry.
            log.info { "已发现现有炉石窗口，跳过重复启动" }
            next(it)
            return
        }
        var startTime = System.currentTimeMillis()
        addTask(
            LAUNCH_PROGRAM_THREAD_POOL.scheduleWithFixedDelay(
                {
                    do {
                        if (startTime == -1L) break
                        val diffTime = System.currentTimeMillis() - startTime
                        if (diffTime > 30_000 && !GameUtil.isAliveOfGame()) {
                            val now = System.currentTimeMillis()
                            startupFailureAttempts++
                            val decision = GameStartupRecoveryPolicy.decide(
                                gameAlive = false,
                                startupConfirmed = false,
                                now = now,
                                lastLaunchAt = lastGameLaunchAt,
                            )
                            log.warn {
                                "GAME_STARTUP_HANDOFF_FAILED attempt=$startupFailureAttempts " +
                                    "decision=$decision gameAlive=false platformAlive=${GameUtil.isAliveOfPlatform()} " +
                                    "powerLog=${PowerLogListener.logFile?.path() ?: "none"} " +
                                    "powerLogLength=${PowerLogListener.logFile?.length() ?: 0L}"
                            }
                            startTime = -1L
                            val retryDelay = GameStartupRecoveryPolicy.retryDelayMs(
                                now,
                                lastGameLaunchAt,
                                startupFailureAttempts,
                            )
                            log.warn {
                                "GAME_STARTUP_RETRY action=RESTART_STARTER_CHAIN reason=handoff-not-confirmed " +
                                    "platformPreserved=true attempt=$startupFailureAttempts retryDelayMs=$retryDelay"
                            }
                            EXTRA_THREAD_POOL.schedule({
                                // The launcher's existing process is reused by PlatformStarter. Do not kill a
                                // late-starting client during the retry delay.
                                when (
                                    GameStartupRecoveryPolicy.retryAction(
                                        startupConfirmed = startupHandshakeConfirmed(),
                                        gameAlive = GameUtil.isAliveOfGame(),
                                    )
                                ) {
                                    GameStartupRecoveryPolicy.RetryAction.REATTACH_GAME_STARTER -> {
                                        log.info {
                                            "GAME_STARTUP_RETRY action=REATTACH_GAME_STARTER " +
                                                "reason=game-process-alive-window-pending attempt=$startupFailureAttempts"
                                        }
                                        execStart()
                                    }
                                    GameStartupRecoveryPolicy.RetryAction.STARTER_CHAIN -> {
                                        StarterConfig.starter.start()
                                    }
                                    GameStartupRecoveryPolicy.RetryAction.NONE -> Unit
                                }
                            }, retryDelay, TimeUnit.MILLISECONDS)
                            stopTask()
                            break
                        }
                        if (GameUtil.isAliveOfGame()) {
//                    游戏刚启动时可能找不到窗口句柄
                            GameUtil.findGameHWND()?.takeIf(GameUtil::isVerifiedCurrentGameWindow)?.let {
                                val evaluation = GameStartupHandoffPolicy.observe(
                                    state = handoffState,
                                    processAlive = true,
                                    windowFound = true,
                                    nowMs = System.currentTimeMillis(),
                                )
                                handoffState = evaluation.state
                                if (evaluation.decision == GameStartupHandoffPolicy.Decision.HANDOFF) {
                                    log.info {
                                        "GAME_STARTUP_HANDOFF_CONFIRMED " +
                                            "stableObservations=${evaluation.state.stableObservations}"
                                    }
                                    next(it)
                                } else {
                                    log.info {
                                        "GAME_STARTUP_HANDOFF_WAIT " +
                                            "stableObservations=${evaluation.state.stableObservations} " +
                                            "required=${GameStartupHandoffPolicy.REQUIRED_STABLE_OBSERVATIONS}"
                                    }
                                }
                            } ?: let {
                                val evaluation = GameStartupHandoffPolicy.observe(
                                    state = handoffState,
                                    processAlive = true,
                                    windowFound = false,
                                    nowMs = System.currentTimeMillis(),
                                )
                                handoffState = evaluation.state
                                if (evaluation.decision == GameStartupHandoffPolicy.Decision.WAIT &&
                                    handoffState.lastObservedAtMs != null
                                ) {
                                    log.info {
                                        "GAME_STARTUP_HANDOFF_TRANSIENT_WINDOW_LOSS " +
                                            "graceMs=${GameStartupHandoffPolicy.PROCESS_LOSS_GRACE_MS}"
                                    }
                                } else if (diffTime > 10_000) {
                                    log.info { "${GAME_CN_NAME}已在运行，但未找到对应窗口句柄" }
                                }
                            }
                        } else {
                            val evaluation = GameStartupHandoffPolicy.observe(
                                state = handoffState,
                                processAlive = false,
                                windowFound = false,
                                nowMs = System.currentTimeMillis(),
                            )
                            handoffState = evaluation.state
                            if (evaluation.decision == GameStartupHandoffPolicy.Decision.WAIT &&
                                handoffState.lastObservedAtMs != null
                            ) {
                                log.info {
                                    "GAME_STARTUP_HANDOFF_TRANSIENT_PROCESS_LOSS " +
                                        "graceMs=${GameStartupHandoffPolicy.PROCESS_LOSS_GRACE_MS}"
                                }
                            } else if (diffTime > 10_000) {
                                val now = System.currentTimeMillis()
                                val decision = GameStartupRecoveryPolicy.decide(
                                    gameAlive = false,
                                    startupConfirmed = false,
                                    now = now,
                                    lastLaunchAt = lastGameLaunchAt,
                                )
                                if (decision == GameStartupRecoveryPolicy.Decision.RETRY_GAME_HANDOFF) {
                                    lastGameLaunchAt = now
                                    dispatchStartupMode(lane = "secondary")
                                }
                            } else {
                                val now = System.currentTimeMillis()
                                val decision = GameStartupRecoveryPolicy.decide(
                                    gameAlive = false,
                                    startupConfirmed = false,
                                    now = now,
                                    lastLaunchAt = lastGameLaunchAt,
                                )
                                if (decision == GameStartupRecoveryPolicy.Decision.RETRY_GAME_HANDOFF) {
                                    lastGameLaunchAt = now
                                    dispatchStartupMode(lane = "primary")
                                }
                            }
                            SystemUtil.delay(RandomUtil.getInteractionDelay(500))
                        }
                    } while (false)
                },
                RandomUtil.getInteractionDelay(100).toLong(),
                RandomUtil.getInteractionDelay(500).toLong(),
                TimeUnit.MILLISECONDS,
            ),
        )
    }

    private fun dispatchStartupMode(lane: String) {
        val selection = GameStartupModeSequencePolicy.select(
            configuredModes = ConfigExUtil.getGameStartupMode(),
            attemptIndex = startupModeDispatches,
            launcherWindowAvailable = GameUtil.findPlatformHWND() != null,
        )
        // Both normal startup modes have side effects: PLATFORM_ARG launches
        // Battle.net and PLATFORM_MESSAGE clicks its UI. Keep them behind the
        // same hard pause/working gate so PLATFORM_ARG cannot continue after
        // lifecycle escalation has stopped MESSAGE dispatch.
        if (!ActionDispatchGate.allow("startup.handoff")) {
            log.warn {
                "GAME_STARTUP_HANDOFF_BLOCKED lane=$lane mode=${selection.mode.name} " +
                    "reason=action-dispatch-gate attemptNotRecorded=true"
            }
            return
        }
        startupModeDispatches++
        LifecycleTrace.markStartupHandoffAttempt(
            "lane=$lane attempt=${selection.attempt} mode=${selection.mode.name}",
        )
        log.info {
            "GAME_STARTUP_HANDOFF_DISPATCH attempt=${selection.attempt} lane=$lane " +
                "configuredMode=${selection.configuredMode.name} mode=${selection.mode.name} " +
                "fallbackApplied=${selection.fallbackApplied} acceptance=awaiting-game-process-window"
        }
        runCatching { selection.mode.exec() }
            .onFailure { error ->
                log.warn(error) {
                    "GAME_STARTUP_HANDOFF_DISPATCH_FAILED attempt=${selection.attempt} " +
                        "mode=${selection.mode.name}"
                }
            }
    }


    private fun next(gameHWND: HWND) {
        handoffState = GameStartupHandoffPolicy.State()
        startupModeDispatches = 0
        updateGameMsg(gameHWND)
        scheduleStartupHandoffWatchdog()
        scheduleStartupScreenProbe()
        closePlatformAfterGameIsReady()
        if (ConfigEnum.PREVENT_ADMIN_LAUNCH_GAME.getBoolean() && GameUtil.getGameProgramPermission()
                .isAdministration()
        ) {
            log.warn { "${GAME_CN_NAME}正在以管理员权限运行" }
        } else {
            log.info { GAME_CN_NAME + "正在运行" }
        }
        if (!ConfigEnum.CLOSE_PLATFORM_AFTER_START_GAME.getBoolean() &&
            ConfigEnum.BOTTOM_PLACEMENT_PLATFORM_AFTER_START_GAME.getBoolean()
        ) {
//        将战网窗口置底
            User32ExDll.INSTANCE.SetWindowPos(
                ScriptStatus.platformHWND,
                HWND_BOTTOM,
                0,
                0,
                0,
                0,
                SWP_NOACTIVATE xor SWP_NOMOVE xor SWP_NOSIZE
            )
        }

        startNextStarter()
    }

    private fun startupHandshakeConfirmed(): Boolean {
        return GameStartupHandoffPolicy.startupHandshakeConfirmed(WarEx.inWar, Mode.currMode)
    }

    /**
     * The original starter cancels its polling task as soon as a window is
     * found.  If that process then exits during the Battle.net handoff, no
     * code was left watching it and recovery waited forever for screen OCR.
     * Keep a small, process-only supervisor alive through the handshake.
     */
    private fun scheduleStartupHandoffWatchdog() {
        BetaScreenRecoveryService.scheduleStartupHandoffWatchdog(
            startupHandshakeConfirmed = ::startupHandshakeConfirmed,
            nextFailureAttempt = {
                startupFailureAttempts++
                startupFailureAttempts
            },
            clearFailureAttempts = { startupFailureAttempts = 0 },
            lastGameLaunchAt = { lastGameLaunchAt },
            retryStarterChain = { StarterConfig.starter.start() },
        )
    }

    /**
     * A restart can find Hearthstone already on a usable non-login page.  At
     * this point the game window has been discovered and its bounds have been
     * refreshed, so screen recovery can inspect the real client instead of
     * racing JavaFX initialization.  Keep the probe bounded and retry while
     * the log listeners finish attaching; normal lifecycle recovery remains
     * the long-stall fallback.
     */
    private fun scheduleStartupScreenProbe() {
        if (!startupProbeScheduled.compareAndSet(false, true)) return
        log.info {
            "STARTUP_SCREEN_PROBE_SCHEDULED gameWindow=${ScriptStatus.gameHWND != null} " +
                "working=${WorkTimeListener.working} paused=${PauseStatus.isPause}"
        }
        EXTRA_THREAD_POOL.execute {
            var attempt = 0
            try {
                // The starter chain discovers Hearthstone before the log listeners
                // attach. Give those listeners a short head start, but do not wait
                // for the 30-second stale-screen fallback.
                Thread.sleep(2_500L)
                val deadline = System.currentTimeMillis() + 15_000L
                while (System.currentTimeMillis() < deadline && !PauseStatus.isPause) {
                    attempt++
                    val gameWindow = ScriptStatus.gameHWND
                    val gameWindowVerified = gameWindow != null &&
                        GameUtil.isVerifiedCurrentGameWindow(gameWindow)
                    val attachedPowerLog = PowerLogListener.logFile
                    val currentSessionPowerLog = GameUtil.getLatestLogDir()?.resolve("Power.log")
                    val powerLogLength = attachedPowerLog?.length() ?: 0L
                    val probeReady = CurrentGameScreenReadinessPolicy.isReady(
                        gameWindowVerified = gameWindowVerified,
                        attachedPowerLogPath = attachedPowerLog?.path(),
                        currentSessionPowerLogPath = currentSessionPowerLog?.absolutePath,
                        powerLogLength = powerLogLength,
                    )
                    if (!probeReady) {
                        val reason = when {
                            !gameWindowVerified -> "game-window-unverified"
                            attachedPowerLog == null -> "power-log-unbound"
                            attachedPowerLog.path() != currentSessionPowerLog?.absolutePath -> "power-log-not-current-session"
                            powerLogLength <= 0L -> "current-power-log-empty"
                            else -> "startup-readiness-unknown"
                        }
                        log.info {
                            "STARTUP_SCREEN_PROBE_DEFERRED attempt=$attempt reason=$reason " +
                                "gameWindow=$gameWindow gameWindowVerified=$gameWindowVerified " +
                                "powerLog=${attachedPowerLog?.path() ?: "none"} " +
                                "currentPowerLog=${currentSessionPowerLog?.absolutePath ?: "none"} " +
                                "powerLogLength=$powerLogLength"
                        }
                        Thread.sleep(1_000L)
                        continue
                    }
                    log.info {
                        "STARTUP_SCREEN_PROBE attempt=$attempt " +
                            "gameWindow=$gameWindow gameWindowVerified=$gameWindowVerified " +
                            "powerLog=${attachedPowerLog?.path() ?: "none"} powerLogLength=$powerLogLength " +
                            "working=${WorkTimeListener.working} war=${WarEx.inWar}"
                    }
                    val result = runCatching {
                        ScreenStateRecovery.inspectAndRecover(
                            stuckForMs = 0L,
                            stateFingerprint = "STARTUP_PROBE",
                            startupProbe = true,
                        )
                    }
                    var applied = false
                    result.onSuccess {
                        applied = it
                        LifecycleTrace.mark("startup-screen-probe attempt=$attempt applied=$it")
                    }.onFailure { error ->
                        log.warn(error) { "STARTUP_SCREEN_PROBE_FAILED attempt=$attempt" }
                    }
                    if (applied) return@execute
                    Thread.sleep(2_000L)
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                log.info { "STARTUP_SCREEN_PROBE_INTERRUPTED attempts=$attempt" }
            } finally {
                log.info {
                    "STARTUP_SCREEN_PROBE_FINISHED attempts=$attempt " +
                        "working=${WorkTimeListener.working} paused=${PauseStatus.isPause}"
                }
            }
        }
    }

    private fun closePlatformAfterGameIsReady() {
        if (!ConfigEnum.CLOSE_PLATFORM_AFTER_START_GAME.getBoolean()) return
        if (platformCloseRequested) return
        platformCloseRequested = true
        go {
            val waitStartedAt = System.currentTimeMillis()
            var stableSince = 0L
            log.info {
                "PLATFORM_CLOSE_WAIT reason=game-startup-handshake " +
                    "stabilityMs=$PLATFORM_CLOSE_STABILITY_MS maxWaitMs=$PLATFORM_CLOSE_MAX_WAIT_MS"
            }
            while (System.currentTimeMillis() - waitStartedAt < PLATFORM_CLOSE_MAX_WAIT_MS) {
                if (!GameUtil.isAliveOfGame()) {
                    log.warn {
                        "PLATFORM_CLOSE_SKIPPED reason=game-exited-before-stable-readiness " +
                            "waitedMs=${System.currentTimeMillis() - waitStartedAt}"
                    }
                    return@go
                }
                val discovered = GameUtil.findGameHWND()
                val usableWindow = discovered != null &&
                    User32.INSTANCE.IsWindow(discovered) &&
                    User32.INSTANCE.IsWindowVisible(discovered)
                if (usableWindow) {
                    if (stableSince == 0L) {
                        stableSince = System.currentTimeMillis()
                        log.info { "PLATFORM_CLOSE_READINESS_OBSERVED hwnd=$discovered" }
                    }
                    if (PlatformCloseReadiness.shouldClose(
                            gameAlive = true,
                            visibleGameWindow = true,
                            startupHandshakeConfirmed = startupHandshakeConfirmed(),
                            stableForMs = System.currentTimeMillis() - stableSince,
                            requiredStabilityMs = PLATFORM_CLOSE_STABILITY_MS,
                        )
                    ) {
                        log.info {
                            "PLATFORM_CLOSE_EXECUTE reason=game-window-stable " +
                                "stableMs=${System.currentTimeMillis() - stableSince} hwnd=$discovered"
                        }
                        GameUtil.killPlatform()
                        return@go
                    }
                    if (System.currentTimeMillis() - stableSince >= PLATFORM_CLOSE_STABILITY_MS &&
                        !startupHandshakeConfirmed()
                    ) {
                        log.info {
                            "PLATFORM_CLOSE_DEFERRED reason=handshake-not-confirmed " +
                                "stableMs=${System.currentTimeMillis() - stableSince} " +
                                "mode=${Mode.currMode?.name ?: "NONE"} inWar=${WarEx.inWar}"
                        }
                    }
                } else {
                    stableSince = 0L
                }
                SystemUtil.delay(PLATFORM_CLOSE_POLL_MS.toInt())
            }
            log.warn {
                "PLATFORM_CLOSE_SKIPPED reason=game-readiness-timeout " +
                    "waitedMs=${System.currentTimeMillis() - waitStartedAt} gameAlive=${GameUtil.isAliveOfGame()}"
            }
        }
    }

    private fun updateGameMsg(gameHWND: HWND) {
        ScriptStatus.gameHWND = gameHWND
        ScriptStatus.platformHWND = GameUtil.findPlatformHWND()
        GameUtil.updateGameRect()
        go {
            SystemUtil.delay(RandomUtil.getInteractionDelay(3000))
            GameUtil.updateGameRect()
            if (!ConfigUtil.getBoolean(ConfigEnum.UPDATE_GAME_WINDOW) &&
                System.getProperty("hs.script.e2e") != "true"
            ) {
                CSystemDll.INSTANCE.limitWindowResize(gameHWND, true)
            }
        }
    }
}
