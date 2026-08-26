package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.consts.GAME_CN_NAME
import club.xiaojiawei.hsscript.consts.PLATFORM_CN_NAME
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.GameLogModeEnum
import club.xiaojiawei.hsscript.utils.ConfigExUtil
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.go
import club.xiaojiawei.hsscriptbase.config.log
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinDef.HWND
import javafx.beans.property.ObjectProperty
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import java.io.File
import java.io.RandomAccessFile

/**
 * @author 肖嘉威
 * @date 2025/4/3 14:25
 */
object ScriptStatus {

    /**
     * 仅供测试使用
     */
    var testMode = false

    /**
     * aot模式
     */
    var aotMode = false

    var fileLogLevel = ConfigExUtil.getFileLogLevel().toInt()

    var programArgs: List<String> = emptyList()

    /**
     * 炉石安装路径是否有效
     */
    var isValidGameInstallPath = true

    /**
     * 战网程序路径是否有效
     */
    var isValidPlatformProgramPath = true

    /**
     * 使用哪种模式读取游戏日志
     */
    var gameLogMode: GameLogModeEnum = GameLogModeEnum.DISK

    private val gameHWNDInner = ReadOnlyObjectWrapper<HWND?>(null)

    private val e2eRun: Boolean
        get() = RuntimeSafety.safeNative

    /**
     * 游戏窗口句柄
     */
    var gameHWND: HWND?
        set(value) = gameHWNDInner.set(value)
        get() {
            var hWND = gameHWNDInner.get()
            // E2E uses a real desktop window but deliberately avoids the
            // legacy native hooks.  Repeated User32.IsWindow calls from this
            // getter occur on many strategy/input threads and can terminate
            // the JVM without a Java exception when Unity replaces a surface.
            // The handle is captured by the normal game-window discovery
            // path, so keep it stable during E2E and let the lifecycle monitor
            // report a stale window instead of calling JNA on every access.
            if (e2eRun) return hWND
            if (!User32.INSTANCE.IsWindow(hWND) && !PauseStatus.isPause) {
                if (hWND != null) {
                    log.info { "${GAME_CN_NAME}窗口句柄已经失效，尝试更新句柄" }
                }
                hWND = GameUtil.findGameHWND()
                go {
                    gameHWNDInner.set(hWND)
                }
            }
            return hWND
        }

    fun gameHWNDReadOnlyProperty(): ReadOnlyObjectProperty<HWND?> = gameHWNDInner.readOnlyProperty

    fun gameHWNDProperty(): ObjectProperty<HWND?> = gameHWNDInner

    private val platformHWNDInner = ReadOnlyObjectWrapper<HWND?>(null)

    /**
     * 战网窗口句柄
     */
    var platformHWND: HWND?
        set(value) = platformHWNDInner.set(value)
        get() {
            var hWND = platformHWNDInner.get()
            if (e2eRun) return hWND
            if (!User32.INSTANCE.IsWindow(hWND) && !PauseStatus.isPause) {
                if (hWND != null) {
                    log.info { "${PLATFORM_CN_NAME}窗口句柄已经失效，尝试更新句柄" }
                }
                hWND = GameUtil.findPlatformHWND()
                go {
                    platformHWNDInner.set(hWND)
                }
            }
            return hWND
        }

    fun platformHWNDReadOnlyProperty(): ReadOnlyObjectProperty<HWND?> = platformHWNDInner.readOnlyProperty

    fun platformHWNDProperty(): ObjectProperty<HWND?> = platformHWNDInner

    /**
     * 游戏窗口信息
     */
    val GAME_RECT: WinDef.RECT = WinDef.RECT()

    var maxLogSizeKB: Int = ConfigUtil.getInt(ConfigEnum.GAME_LOG_LIMIT)

    var maxLogSizeB: Int = maxLogSizeKB * 1024

    fun reloadLogSize(newMaxLogSizeKB: Int = ConfigUtil.getInt(ConfigEnum.GAME_LOG_LIMIT)) {
        maxLogSizeKB = newMaxLogSizeKB
        maxLogSizeB = maxLogSizeKB * 1024
    }
}
