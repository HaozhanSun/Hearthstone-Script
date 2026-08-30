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
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

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
    private val fixedHotkeyStatePollerStarted = AtomicBoolean(false)
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
                    fixedHotkeyEdgeDetector.onKeyDown(keyboardData.vkCode)?.let(::onHotKey)
                } else if (keyboardMessage == WM_KEYUP || keyboardMessage == WM_SYSKEYUP) {
                    fixedHotkeyEdgeDetector.onKeyUp(keyboardData.vkCode)
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
     * JIntellitype registers a system hotkey, but its native callback was not
     * reliable while Hearthstone owned the foreground window.  A low-level
     * Windows keyboard hook receives the key event independently of the
     * foreground window, including injected E2E key events.  Rising-edge
     * detection prevents key-repeat from firing the control repeatedly while
     * a function key is held.
     */
    private fun startFixedHotkeyPoller() {
        startFixedHotkeyStatePoller()
        if (!fixedHotkeyHookStarted.compareAndSet(false, true)) return

        Thread({
            try {
                fixedHotkeyHook = User32.INSTANCE.SetWindowsHookEx(
                    WinUser.WH_KEYBOARD_LL,
                    fixedHotkeyHookCallback,
                    null,
                    0,
                )
                if (fixedHotkeyHook == null) {
                    throw IllegalStateException("SetWindowsHookEx returned null")
                }
                log.info { "固定开始/暂停热键已启动全局监听：F1/F2 hook=installed" }

                val message = WinUser.MSG()
                while (User32.INSTANCE.GetMessage(message, null, 0, 0) > 0) {
                    // The low-level hook callback is dispatched while this
                    // thread pumps its message queue.
                }
            } catch (error: Throwable) {
                log.warn(error) { "全局 F1/F2 监听失败" }
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

    /** JIntellitype remains as a compatibility path for injected E2E keys. */
    private fun registerFixedControls(hotkey: JIntellitype = JIntellitype.getInstance()) {
        runCatching {
            hotkey.registerHotKey(HOT_KEY_START_F1, 0, VK_F1)
            log.info { "固定开始热键已注册：F1（兼容注入事件）" }
        }.onFailure { error ->
            log.warn(error) { "注册固定开始热键 F1 兼容通道失败" }
        }

        runCatching {
            hotkey.registerHotKey(HOT_KEY_PAUSE_F2, 0, VK_F2)
            log.info { "固定暂停热键已注册：F2（兼容注入事件）" }
        }.onFailure { error ->
            log.warn(error) { "注册固定暂停热键 F2 兼容通道失败" }
        }
    }

    private fun dispatchFixedHotkey(id: Int) {
        val now = System.nanoTime()
        val previous = lastFixedHotkeyNanos.put(id, now)
        if (previous != null && now - previous < FIXED_HOTKEY_DEDUP_NANOS) return
        when (id) {
            HOT_KEY_START_F1 -> setPauseState(false, "F1")
            HOT_KEY_PAUSE_F2 -> setPauseState(true, "F2")
        }
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
    }

    private fun setPauseState(paused: Boolean, source: String) {
        if (paused) {
            log.info { "PAUSE_REQUESTED source=$source" }
            PauseStatus.isPause = true
            log.info { "PAUSE_ACTIVE source=$source" }
        } else {
            log.info { "RESUME_REQUESTED source=$source" }
            PauseStatus.isPause = false
            log.info { "RESUME_ACTIVE source=$source" }
        }
    }

    /**
     * Some supervised input bridges deliver function keys to the foreground
     * client without producing a low-level hook callback. Poll the physical
     * key state as a compatibility backstop; the same edge/dedup rules keep
     * this from double-firing when the hook path also receives the key.
     */
    private fun startFixedHotkeyStatePoller() {
        if (!fixedHotkeyStatePollerStarted.compareAndSet(false, true)) return

        Thread({
            var f1Down = false
            var f2Down = false
            try {
                while (true) {
                    val f1Now = (User32.INSTANCE.GetAsyncKeyState(VK_F1).toInt() and 0x8000) != 0
                    val f2Now = (User32.INSTANCE.GetAsyncKeyState(VK_F2).toInt() and 0x8000) != 0
                    if (f1Now && !f1Down) dispatchFixedHotkey(HOT_KEY_START_F1)
                    if (f2Now && !f2Down) dispatchFixedHotkey(HOT_KEY_PAUSE_F2)
                    f1Down = f1Now
                    f2Down = f2Now
                    Thread.sleep(25)
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                log.warn(error) { "固定 F1/F2 按键状态轮询失败" }
                fixedHotkeyStatePollerStarted.set(false)
            }
        }, "Global F1/F2 Hotkey State Poller").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 快捷键组合键按键事件
     * @param i
     */
    override fun onHotKey(i: Int) {
        when (i) {
            HOT_KEY_START_F1 -> dispatchFixedHotkey(HOT_KEY_START_F1)

            HOT_KEY_PAUSE_F2 -> dispatchFixedHotkey(HOT_KEY_PAUSE_F2)

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
        if (JIntellitype.isJIntellitypeSupported()) registerFixedControls()
    }

}
