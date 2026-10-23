package club.xiaojiawei.hsscript.listener

import club.xiaojiawei.hsscript.bean.HotKey
import club.xiaojiawei.hsscript.dll.CSystemDll
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.utils.ConfigExUtil
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import com.melloware.jintellitype.HotkeyListener
import com.melloware.jintellitype.JIntellitype
import com.melloware.jintellitype.JIntellitypeConstants
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides whether Windows has supplied sufficient provenance for a fixed F1/F2
 * control. `WH_KEYBOARD_LL` is the only channel used here because it carries
 * the injected-input flags. An unflagged event is the strongest distinction
 * Windows exposes in this process; callers must describe it as
 * Windows-unflagged, not as cryptographically proven physical input.
 */
internal object FixedHotkeyInputPolicy {
    const val LLKHF_LOWER_IL_INJECTED = 0x00000002
    const val LLKHF_INJECTED = 0x00000010

    enum class Decision {
        ACCEPT_WINDOWS_UNFLAGGED,
        IGNORE_INJECTED,
        IGNORE_HOOK_UNAVAILABLE,
    }

    fun decideLowLevel(flags: Int): Decision =
        if ((flags and (LLKHF_INJECTED or LLKHF_LOWER_IL_INJECTED)) != 0) {
            Decision.IGNORE_INJECTED
        } else {
            Decision.ACCEPT_WINDOWS_UNFLAGGED
        }

    /** JIntellitype and key-state polling cannot expose per-event injection flags. */
    fun hookUnavailable(): Decision = Decision.IGNORE_HOOK_UNAVAILABLE
}

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

    private val fixedHotkeyHookStarted = AtomicBoolean(false)
    @Volatile
    private var fixedHotkeyHook: WinUser.HHOOK? = null
    private val fixedHotkeyHookCallback = object : WinUser.LowLevelKeyboardProc {
        override fun callback(
            nCode: Int,
            wParam: WinDef.WPARAM,
            keyboardData: WinUser.KBDLLHOOKSTRUCT,
        ): WinDef.LRESULT {
            if (nCode >= 0) {
                val keyboardMessage = wParam.toInt()
                if (keyboardMessage == WM_KEYDOWN || keyboardMessage == WM_SYSKEYDOWN) {
                    handleFixedLowLevelKeyEvent(
                        virtualKey = keyboardData.vkCode,
                        scanCode = keyboardData.scanCode,
                        flags = keyboardData.flags,
                        keyDown = true,
                    )
                } else if (keyboardMessage == WM_KEYUP || keyboardMessage == WM_SYSKEYUP) {
                    handleFixedLowLevelKeyEvent(
                        virtualKey = keyboardData.vkCode,
                        scanCode = keyboardData.scanCode,
                        flags = keyboardData.flags,
                        keyDown = false,
                    )
                }
            }
            val nativeKeyboardData = Pointer.nativeValue(keyboardData.getPointer())
            return User32.INSTANCE.CallNextHookEx(
                fixedHotkeyHook,
                nCode,
                wParam,
                WinDef.LPARAM(nativeKeyboardData),
            )
        }
    }
    private val fixedHotkeyEdgeDetector = FixedHotkeyEdgeDetector()
    private val lastFixedHotkeyNanos = ConcurrentHashMap<Int, Long>()

    private const val WM_KEYDOWN = 0x0100
    private const val WM_KEYUP = 0x0101
    private const val WM_SYSKEYDOWN = 0x0104
    private const val WM_SYSKEYUP = 0x0105
    private const val FIXED_HOTKEY_DEDUP_NANOS = 150_000_000L

    init {
        JIntellitype.getInstance().addHotKeyListener(this)
    }

    fun reload() {
        unregister()
        register()
    }

    private fun register() {
        startFixedHotkeyPoller()
        if (JIntellitype.isJIntellitypeSupported()) {
            val hotkey = JIntellitype.getInstance()

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

    /** Fixed controls intentionally use only the low-level hook. */
    private fun startFixedHotkeyPoller() {
        if (!fixedHotkeyHookStarted.compareAndSet(false, true)) return

        Thread({
            try {
                log.info { "固定 F1/F2 全局钩子启动：正在注册 WH_KEYBOARD_LL" }
                fixedHotkeyHook = User32.INSTANCE.SetWindowsHookEx(
                    WinUser.WH_KEYBOARD_LL,
                    fixedHotkeyHookCallback,
                    null,
                    0,
                )
                if (fixedHotkeyHook == null) {
                    val errorCode = Kernel32.INSTANCE.GetLastError()
                    throw IllegalStateException("SetWindowsHookEx returned null, win32Error=$errorCode")
                }
                log.info {
                    "固定开始/暂停热键已启动全局监听：F1/F2 hook=installed " +
                        "thread=${Thread.currentThread().id}"
                }

                val message = WinUser.MSG()
                while (User32.INSTANCE.GetMessage(message, null, 0, 0) > 0) {
                    // The low-level hook callback is dispatched while this
                    // thread pumps its message queue.
                }
            } catch (error: Throwable) {
                log.warn(error) {
                    "FIXED_HOTKEY_UNAVAILABLE source=WH_KEYBOARD_LL " +
                        "fallback=disabled decision=${FixedHotkeyInputPolicy.hookUnavailable()} " +
                        "reason=${error.javaClass.simpleName} manualF1F2=unavailable"
                }
                fixedHotkeyHookStarted.set(false)
            } finally {
                fixedHotkeyHook?.let(User32.INSTANCE::UnhookWindowsHookEx)
                fixedHotkeyHook = null
            }
        }, "Global F1/F2 Hotkey Hook").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleFixedLowLevelKeyEvent(
        virtualKey: Int,
        scanCode: Int,
        flags: Int,
        keyDown: Boolean,
    ): FixedHotkeyInputPolicy.Decision? {
        if (virtualKey != VK_F1 && virtualKey != VK_F2) return null
        val decision = FixedHotkeyInputPolicy.decideLowLevel(flags)
        val key = if (virtualKey == VK_F1) "F1" else "F2"
        if (decision != FixedHotkeyInputPolicy.Decision.ACCEPT_WINDOWS_UNFLAGGED) {
            if (keyDown) {
                log.warn {
                    "HOTKEY_INPUT_IGNORED source=WH_KEYBOARD_LL key=$key provenance=injected " +
                        "scanCode=$scanCode flags=0x${flags.toString(16)} decision=$decision action=none"
                }
            }
            return decision
        }
        if (!keyDown) {
            fixedHotkeyEdgeDetector.onKeyUp(virtualKey)
            return decision
        }
        fixedHotkeyEdgeDetector.onKeyDown(virtualKey)?.let { id ->
            dispatchFixedHotkey(id, "WH_KEYBOARD_LL/windows-unflagged", scanCode, flags)
        } ?: log.info {
            "HOTKEY_INPUT_IGNORED source=WH_KEYBOARD_LL key=$key provenance=windows-unflagged " +
                "scanCode=$scanCode flags=0x${flags.toString(16)} decision=key-repeat action=none"
        }
        return decision
    }

    private fun dispatchFixedHotkey(id: Int, source: String, scanCode: Int, flags: Int) {
        val now = System.nanoTime()
        val previous = lastFixedHotkeyNanos.put(id, now)
        if (previous != null && now - previous < FIXED_HOTKEY_DEDUP_NANOS) {
            log.info {
                "HOTKEY_INPUT_IGNORED source=$source key=${if (id == HOT_KEY_START_F1) "F1" else "F2"} " +
                    "provenance=windows-unflagged scanCode=$scanCode flags=0x${flags.toString(16)} " +
                    "decision=deduplicated action=none"
            }
            return
        }
        log.info {
            "HOTKEY_INPUT_ACCEPTED source=$source key=${if (id == HOT_KEY_START_F1) "F1" else "F2"} " +
                "provenance=windows-unflagged scanCode=$scanCode flags=0x${flags.toString(16)} " +
                "action=${if (id == HOT_KEY_START_F1) "resume" else "pause"}"
        }
        when (id) {
            HOT_KEY_START_F1 -> setPauseState(false, "F1/$source")
            HOT_KEY_PAUSE_F2 -> setPauseState(true, "F2/$source")
        }
    }

    internal fun pressF2ForTest() =
        handleFixedLowLevelKeyEvent(VK_F2, 0x3c, 0, keyDown = true)

    internal fun releaseF2ForTest() =
        handleFixedLowLevelKeyEvent(VK_F2, 0x3c, 0, keyDown = false)

    internal fun pressInjectedF2ForTest() =
        handleFixedLowLevelKeyEvent(VK_F2, 0x3c, FixedHotkeyInputPolicy.LLKHF_INJECTED, keyDown = true)

    internal fun releaseInjectedF2ForTest() =
        handleFixedLowLevelKeyEvent(VK_F2, 0x3c, FixedHotkeyInputPolicy.LLKHF_INJECTED, keyDown = false)

    internal fun pressF1ForTest() =
        handleFixedLowLevelKeyEvent(VK_F1, 0x3b, 0, keyDown = true)

    internal fun releaseF1ForTest() =
        handleFixedLowLevelKeyEvent(VK_F1, 0x3b, 0, keyDown = false)

    internal fun resetFixedHotkeyStateForTest() {
        fixedHotkeyEdgeDetector.reset()
        lastFixedHotkeyNanos.clear()
    }

    internal class FixedHotkeyEdgeDetector {
        private var f1Down = false
        private var f2Down = false

        fun onKeyDown(virtualKey: Int): Int? = when (virtualKey) {
            VK_F1 -> if (f1Down) null else {
                f1Down = true
                HOT_KEY_START_F1
            }
            VK_F2 -> if (f2Down) null else {
                f2Down = true
                HOT_KEY_PAUSE_F2
            }
            else -> null
        }

        fun onKeyUp(virtualKey: Int) {
            when (virtualKey) {
                VK_F1 -> f1Down = false
                VK_F2 -> f2Down = false
            }
        }

        fun reset() {
            f1Down = false
            f2Down = false
        }
    }

    private fun setPauseState(paused: Boolean, source: String) {
        if (paused) {
            log.warn {
                "PAUSE_REQUESTED source=$source pauseBefore=${PauseStatus.isPause} " +
                    "workingBefore=${WorkTimeListener.working}"
            }
        } else {
            log.info {
                "RESUME_REQUESTED source=$source pauseBefore=${PauseStatus.isPause} " +
                    "workingBefore=${WorkTimeListener.working}"
            }
        }
        if (paused) WorkTimeListener.working = false
        PauseStatus.setManualPause(paused)
        log.info {
            "捕捉到热键[$source]，${if (paused) "暂停脚本" else "开始脚本"}"
        }
        log.info {
                "${if (paused) "PAUSE_ACTIVE" else "RESUME_ACTIVE"} source=$source " +
                "pause=${PauseStatus.isPause} working=${WorkTimeListener.working}"
        }
    }

    /**
     * 快捷键组合键按键事件
     * @param i
     */
    override fun onHotKey(i: Int) {
        when (i) {
            HOT_KEY_START_F1, HOT_KEY_PAUSE_F2 -> log.warn {
                "HOTKEY_INPUT_IGNORED source=JIntellitype " +
                    "key=${if (i == HOT_KEY_START_F1) "F1" else "F2"} provenance=unverifiable " +
                    "decision=${FixedHotkeyInputPolicy.hookUnavailable()} action=none"
            }

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
        startFixedHotkeyPoller()
    }

}
