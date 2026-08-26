package club.xiaojiawei.hsscript.service

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscript.dll.CSystemDll
import club.xiaojiawei.hsscript.dll.User32ExDll
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.status.RuntimeSafety
import club.xiaojiawei.hsscriptbase.util.isTrue
import com.sun.jna.platform.win32.User32
import javafx.beans.value.ChangeListener

/**
 * @author 肖嘉威
 * @date 2025/3/24 17:21
 */
object UpdateGameWindowService : Service<Boolean>() {
    private val e2eRun: Boolean
        get() = RuntimeSafety.safeNative

    private fun limitWindowResize(enabled: Boolean) {
        if (e2eRun) {
            log.info { "E2E_NATIVE_SKIP limitWindowResize enabled=$enabled" }
            return
        }
        CSystemDll.INSTANCE.limitWindowResize(ScriptStatus.gameHWND, enabled)
    }

    override val isRunning: Boolean
        get() {
            return thread?.isAlive == true
        }

    private var thread: Thread? = null

    private val workingChangeListener: ChangeListener<Boolean> by lazy {
        ChangeListener { _, _, working ->
            limitWindowResize(working && !ConfigUtil.getBoolean(ConfigEnum.UPDATE_GAME_WINDOW))
        }
    }

    override fun execStart(): Boolean {
        WorkTimeListener.addWorkStatusListener(workingChangeListener)
        limitWindowResize(false)
        if (e2eRun) {
            // The E2E runner uses real AWT input and the game window already
            // has a valid rectangle from startup.  Do not keep a background
            // JNA IsWindow/IsIconic poll alive during the run: when the game
            // changes Unity surfaces, this native polling path can terminate
            // the JVM without a Java exception or shutdown hook.
            log.info { "E2E_NATIVE_SKIP UpdateGameWindow polling" }
            return true
        }
        thread =
            Thread({
                while (thread?.isInterrupted == false) {
                    try {
                        Thread.sleep(1000)
                        if (WorkTimeListener.working) {
                            val hwnd = ScriptStatus.gameHWND
                            if (User32.INSTANCE.IsWindow(hwnd) && !User32ExDll.INSTANCE.IsIconic(hwnd)) {
                                GameUtil.updateGameRect(hwnd)
                            }
                        }
                    } catch (e: Exception) {
                        if (e !is InterruptedException) {
                            log.error(e) { "" }
                        }
                    }
                }
            }, "Update GameWindow Thread").apply {
                start()
            }
        return true
    }

    override fun execStop(): Boolean {
        WorkTimeListener.removeWorkStatusListener(workingChangeListener)
        limitWindowResize(WorkTimeListener.working)
        thread?.let {
            it.isAlive.isTrue {
                it.interrupt()
            }
            thread = null
        }
        return true
    }

    override fun getStatus(value: Boolean?): Boolean =
        value?:ConfigUtil.getBoolean(ConfigEnum.UPDATE_GAME_WINDOW)
}
