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
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.status.ScreenStateRecovery
import club.xiaojiawei.hsscript.utils.*
import club.xiaojiawei.hsscript.listener.WorkTimeListener
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

    public override fun execStart() {
        log.info { "开始检查$GAME_CN_NAME" }
        val gameHWND = ScriptStatus.gameHWND
        if (gameHWND != null && User32.INSTANCE.IsWindow(gameHWND)) {
            next(gameHWND)
            return
        }
        // Re-discover an already-open game before starting the retry loop.
        // ScriptStatus is reset between launcher stages, so checking only the
        // cached handle made a visible Hearthstone window look absent and
        // triggered repeated launch attempts.
        if (System.getProperty("hs.script.e2e") == "true") {
            GameUtil.findGameHWND()?.let { discoveredWindow ->
                GameUtil.resolveRealGameWindow(discoveredWindow)?.let { realWindow ->
                    log.info { "E2E已发现现有炉石窗口，跳过重复启动" }
                    next(realWindow)
                    return
                }
                log.warn {
                    "E2E_WINDOW_DISCOVERY_REJECTED handle=$discoveredWindow " +
                        "reason=screen-only-sentinel action=wait-for-real-window"
                }
            }
        }
        var startTime = System.currentTimeMillis()
        var firstLogLaunch = true
        var firstLogSecondaryLaunch = true
        addTask(
            LAUNCH_PROGRAM_THREAD_POOL.scheduleWithFixedDelay(
                {
                    do {
                        if (startTime == -1L) break
                        val diffTime = System.currentTimeMillis() - startTime
                        if (diffTime > 30_000) {
                            log.warn { "启动${GAME_CN_NAME}失败次数过多，重新执行启动器链" }
                            startTime = -1L
                            EXTRA_THREAD_POOL.schedule({
                                GameUtil.killGame(true)
                                if (System.getProperty("hs.script.e2e") == "true") {
                                    // The E2E fallback cannot distinguish the
                                    // user's Battle.net session from a process
                                    // started by this attempt. Never terminate
                                    // every Battle.net.exe during an automatic
                                    // retry; doing so made the launcher look
                                    // like it crashed and prevented recovery.
                                    log.warn { "E2E启动重试：保留现有战网进程，不执行全量Battle.net终止" }
                                } else {
                                    GameUtil.killLoginPlatform()
                                    GameUtil.killPlatform()
                                }
                                StarterConfig.starter.start()
                                }, RandomUtil.getInteractionDelay(1000).toLong(), TimeUnit.MILLISECONDS)
                            stopTask()
                            break
                        }
                        if (GameUtil.isAliveOfGame()) {
//                    游戏刚启动时可能找不到窗口句柄
                            GameUtil.findGameHWND()?.let { discoveredWindow ->
                                GameUtil.resolveRealGameWindow(discoveredWindow)?.let { realWindow ->
                                    next(realWindow)
                                } ?: let {
                                    log.warn {
                                        "E2E_WINDOW_DISCOVERY_REJECTED handle=$discoveredWindow " +
                                            "reason=screen-only-sentinel action=wait-for-real-window"
                                    }
                                }
                            } ?: let {
                                if (diffTime > 10_000) {
                                    log.info { "${GAME_CN_NAME}已在运行，但未找到对应窗口句柄" }
                                }
                            }
                        } else {
                            if (diffTime > 10_000) {
                                val startupModeEnum = ConfigExUtil.getGameStartupMode().last()
                                if (firstLogSecondaryLaunch) {
                                    firstLogSecondaryLaunch = false
                                    log.info { "以${startupModeEnum.name}方式启动$GAME_CN_NAME" }
                                }
                                startupModeEnum.exec()
                            } else {
                                val startupModeEnum = ConfigExUtil.getGameStartupMode().first()
                                if (firstLogLaunch) {
                                    firstLogLaunch = false
                                    log.info { "以${startupModeEnum.name}方式启动$GAME_CN_NAME" }
                                }
                                startupModeEnum.exec()
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


    private fun next(gameHWND: HWND) {
        updateGameMsg(gameHWND)
        scheduleStartupScreenProbe()
        closePlatformAfterGameIsReady()
        if (ConfigEnum.PREVENT_ADMIN_LAUNCH_GAME.getBoolean() && GameUtil.getGameProgramPermission()
                .isAdministration()
        ) {
            log.warn { "${GAME_CN_NAME}正在以管理员权限运行" }
        } else {
            log.info { GAME_CN_NAME + "正在运行" }
        }
        if (ConfigEnum.CLOSE_PLATFORM_AFTER_START_GAME.getBoolean()) {
            go {
                var count = 0
                while (Mode.currMode === ModeEnum.STARTUP || Mode.currMode === ModeEnum.LOGIN) {
                    if (count++ > 15) {
                        return@go
                    }
                    SystemUtil.delay(RandomUtil.getInteractionDelay(1000))
                }
                GameUtil.killPlatform()
            }
        } else if (ConfigEnum.BOTTOM_PLACEMENT_PLATFORM_AFTER_START_GAME.getBoolean()) {
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
                    log.info {
                        "STARTUP_SCREEN_PROBE attempt=$attempt " +
                            "gameWindow=${ScriptStatus.gameHWND != null} " +
                            "working=${WorkTimeListener.working} war=${WarEx.inWar}"
                    }
                    val result = runCatching {
                        ScreenStateRecovery.inspectAndRecover(
                            stuckForMs = 0L,
                            stateFingerprint = "STARTUP_PROBE",
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
            log.info { "${GAME_CN_NAME}进程和窗口已就绪，立即关闭${PLATFORM_CN_NAME}以避免卡在连接界面" }
            GameUtil.killPlatform()
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
