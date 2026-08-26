package club.xiaojiawei.hsscript.listener

import club.xiaojiawei.hsscript.bean.HotKey
import club.xiaojiawei.hsscript.dll.CSystemDll
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.utils.ConfigExUtil
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import com.melloware.jintellitype.HotkeyListener
import com.melloware.jintellitype.JIntellitype
import com.melloware.jintellitype.JIntellitypeConstants

/**
 * 热键监听器
 * @author 肖嘉威
 * @date 2022/12/11 11:23
 */
object GlobalHotkeyListener : HotkeyListener {

    private const val HOT_KEY_EXIT = 111

    private const val HOT_KEY_TOGGLE = 222

    private const val HOT_KEY_CONSOLE = 333

    private const val HOT_KEY_START_F1 = 444

    private const val HOT_KEY_PAUSE_F2 = 445

    // Windows virtual-key codes.  F1/F2 are deliberately fixed controls so
    // they work even when the configurable legacy toggle key is unset.
    private const val VK_F1 = 0x70

    private const val VK_F2 = 0x71

    init {
        JIntellitype.getInstance().addHotKeyListener(this)
    }

    fun reload() {
        unregister()
        register()
    }

    private fun register() {
        if (JIntellitype.isJIntellitypeSupported()) {
            val hotkey = JIntellitype.getInstance()

            registerFixedControls(hotkey)

            ConfigExUtil.getExitHotKey()?.let {
                if (it.keyCode != 0) {
                    hotkey.registerHotKey(HOT_KEY_EXIT, it.modifier, it.keyCode)
                    log.info { "退出热键：$it" }
                }
            }
            ConfigExUtil.getPauseHotKey()?.let {
                if (it.keyCode != 0) {
                    hotkey.registerHotKey(HOT_KEY_TOGGLE, it.modifier, it.keyCode)
                    log.info { "可配置开始/暂停切换热键：$it" }
                }
            }
            if (ConfigUtil.getBoolean(ConfigEnum.ENABLE_CONSOLE_HOTKEY)){
                val hotKey = HotKey(JIntellitypeConstants.MOD_ALT, 'A'.code)
                hotkey.registerHotKey(HOT_KEY_CONSOLE, hotKey.modifier, hotKey.keyCode)
                log.info { "控制台热键：${hotKey}" }
            }
        } else {
            log.warn { "当前系统不支持设置热键" }
        }
    }

    private fun unregister() {
        if (JIntellitype.isJIntellitypeSupported()) {
            val hotkey = JIntellitype.getInstance()
            hotkey.unregisterHotKey(HOT_KEY_START_F1)
            hotkey.unregisterHotKey(HOT_KEY_PAUSE_F2)
            hotkey.unregisterHotKey(HOT_KEY_TOGGLE)
            hotkey.unregisterHotKey(HOT_KEY_EXIT)
            hotkey.unregisterHotKey(HOT_KEY_CONSOLE)
        }
    }

    /**
     * Register the two fixed controls independently from the user-configured
     * hotkeys.  The E2E runner uses this path too: it must be possible to
     * pause/resume a run without enabling the exit or console hotkeys.
     */
    private fun registerFixedControls(hotkey: JIntellitype = JIntellitype.getInstance()) {
        runCatching {
            hotkey.registerHotKey(HOT_KEY_START_F1, 0, VK_F1)
            log.info { "固定开始热键已注册：F1" }
        }.onFailure { error ->
            log.warn(error) { "注册固定开始热键 F1 失败" }
        }

        runCatching {
            hotkey.registerHotKey(HOT_KEY_PAUSE_F2, 0, VK_F2)
            log.info { "固定暂停热键已注册：F2" }
        }.onFailure { error ->
            log.warn(error) { "注册固定暂停热键 F2 失败" }
        }
    }

    private fun setPauseState(paused: Boolean, source: String) {
        PauseStatus.isPause = paused
        log.info {
            "捕捉到热键[$source]，${if (paused) "暂停脚本" else "开始脚本"}"
        }
    }

    /**
     * 快捷键组合键按键事件
     * @param i
     */
    override fun onHotKey(i: Int) {
        when (i) {
            HOT_KEY_START_F1 -> setPauseState(false, "F1")

            HOT_KEY_PAUSE_F2 -> setPauseState(true, "F2")

            HOT_KEY_EXIT -> {
                SystemUtil.notice("捕捉到热键，关闭程序")
                log.info { "捕捉到热键，关闭程序" }
                unregister()
                SystemUtil.shutdownSoft()
            }

            HOT_KEY_TOGGLE -> setPauseState(!PauseStatus.isPause, "可配置切换键")

            HOT_KEY_CONSOLE -> {
                CSystemDll.INSTANCE.developer(true)
            }
        }
    }

    val launch: Unit by lazy {
        register()
    }

    /** Fixed F1/F2 controls for supervised/E2E launches. */
    val fixedControlsLaunch: Unit by lazy {
        if (JIntellitype.isJIntellitypeSupported()) {
            registerFixedControls()
        } else {
            log.warn { "当前系统不支持设置固定 F1/F2 热键" }
        }
    }

}
