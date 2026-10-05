package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.bean.GameRect
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.consts.*
import club.xiaojiawei.hsscript.dll.CSystemDll
import club.xiaojiawei.hsscript.dll.Win32ProcessImagePath
import club.xiaojiawei.hsscript.dll.height
import club.xiaojiawei.hsscript.dll.width
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.ProgramPermissionEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscript.status.Mode
import club.xiaojiawei.hsscript.status.RuntimeSafety
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ActionDispatchGate
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.status.E2ETrace
import club.xiaojiawei.hsscript.status.ScreenStateRecovery
import club.xiaojiawei.hsscript.status.ScreenWatchdog
import club.xiaojiawei.hsscript.status.ScreenWatchdogKind
import club.xiaojiawei.hsscript.status.ScreenWatchdogRecoveryAction
import club.xiaojiawei.hsscript.status.ResultPageDismissalPolicy
import club.xiaojiawei.hsscript.status.ResultScreenObservation
import club.xiaojiawei.hsscript.status.StrategyDefaultDeckSlotBindings
import club.xiaojiawei.hsscript.status.surrender.SurrenderPolicy
import club.xiaojiawei.hsscript.status.surrender.NeverSurrenderPolicy
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderRecoveryPolicy
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import club.xiaojiawei.hsscript.strategy.phase.GameOverPhaseStrategy
import club.xiaojiawei.hsscript.strategy.phase.ReplaceCardPhaseStrategy
import club.xiaojiawei.hsscript.utils.GameUtil.CHOOSE_ONE_RECTS
import club.xiaojiawei.hsscript.utils.SystemUtil.delay
import club.xiaojiawei.hsscriptbase.bean.LRunnable
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.enums.StepEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import club.xiaojiawei.hsscriptbase.util.isFalse
import club.xiaojiawei.hsscriptbase.util.randomSelectOrNull
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Tlhelp32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.platform.win32.WinUser.SWP_NOMOVE
import com.sun.jna.platform.win32.WinUser.SWP_NOZORDER
import com.sun.jna.ptr.IntByReference
import java.awt.Point
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min


/**
 * 游戏工具类
 * @author 肖嘉威
 * @date 2022/11/27 1:42
 */
object GameUtil {

    private val e2eWindowDiscoveryLogGate = E2EWindowDiscoveryLogGate()

    /**
     * Keep the executable path and its arguments as separate values. The
     * one-string Runtime.exec overload tokenizes on whitespace, which breaks
     * Battle.net installations under paths such as "OneDrive - Duke
     * University" and can surface as a misleading Windows Script Host error.
     */
    internal fun buildPlatformCommand(platformPath: String, launchGame: Boolean): List<String> =
        buildPlatformCommand(platformPath, launchGame, AppRuntimeChannel.UNKNOWN, "")

    internal fun buildPlatformCommand(
        platformPath: String,
        launchGame: Boolean,
        runtimeChannel: AppRuntimeChannel,
        gamePath: String,
    ): List<String> {
        require(platformPath.isNotBlank()) { "platformPath must not be blank" }
        return if (launchGame) {
            if (runtimeChannel == AppRuntimeChannel.BETA || runtimeChannel == AppRuntimeChannel.RELEASE_CANDIDATE) {
                require(gamePath.isNotBlank()) { "gamePath must not be blank for Beta launch" }
                listOf(platformPath, "--game=hs_beta", "--gamepath=$gamePath", "-uid", "hs_beta")
            } else {
                listOf(platformPath, "--exec=launch WTCG")
            }
        } else {
            listOf(platformPath)
        }
    }

    internal fun isVerifiedCurrentGameWindow(hwnd: WinDef.HWND?): Boolean {
        if (hwnd == null || !User32.INSTANCE.IsWindow(hwnd) || !User32.INSTANCE.IsWindowVisible(hwnd)) return false
        val ownerPid = windowProcessId(hwnd).toLong()
        if (ownerPid <= 0L) return false
        val ownerProcessName = runCatching {
            ProcessHandle.of(ownerPid)
                .filter { it.isAlive }
                .flatMap { it.info().command() }
                .map { File(it).name }
                .orElse(null)
        }.getOrNull()
        return GameWindowDiscoveryPolicy.isVerifiedGameWindow(
            ownerPid = ownerPid,
            ownerProcessName = ownerProcessName,
            valid = true,
            visible = true,
            expectedProcessName = GAME_PROGRAM_NAME,
        )
    }

    internal fun isVerifiedCurrentGameWindow(hwnd: WinDef.HWND?, expectedPid: Long): Boolean =
        hwnd != null && isVerifiedCurrentGameWindow(hwnd) &&
            GameWindowDiscoveryPolicy.belongsToProcess(windowProcessId(hwnd).toLong(), expectedPid)

    internal fun isSurrenderStateConfirmed(mode: ModeEnum?, inWar: Boolean): Boolean =
        SurrenderPolicy.hasConfirmedGameState(mode, inWar)

    /**
     * Terminal state always wins over a late surrender request.  The phase
     * and turn markers are authoritative in the event stream; the result IDs
     * cover the short interval where PLAYSTATE has arrived but phase parsing
     * has not caught up.  A live settlement task is also a terminal-page
     * marker and must not be reused as a surrender opportunity.
     */
    internal fun isTerminalGameState(): Boolean =
        WAR.currentPhase == WarPhaseEnum.GAME_OVER ||
            WAR.currentTurnStep == StepEnum.FINAL_GAMEOVER ||
            WAR.won.isNotBlank() ||
            WAR.lost.isNotBlank() ||
            WAR.conceded.isNotBlank() ||
            gameEndTasks.hasTerminalPageTask()

    /**
     * A new CREATE_GAME/TURN=1 boundary supersedes any result-page cleanup
     * task left by the previous game. Without this reset, a stale task makes
     * the next rank-triggered surrender look terminal and silently rejects
     * the request before it can send input.
     */
    @Synchronized
    fun resetForNewGame() {
        cancelGameEndTask()
    }

    /** Input sequence for each bounded, positively-confirmed result-page attempt. */
    internal fun staleResultInputForAttempt(attempt: Int, maxAttempts: Int = 5): ResultPageDismissalPolicy.Input? =
        ResultPageDismissalPolicy.inputForClickAttempt(attempt, maxAttempts)

    /**
     * Safe-native mode deliberately avoids the injected/native window helper.
     * A non-null sentinel lets the existing lifecycle/input plumbing continue
     * while Robot sends screen coordinates; no native call is made with it.
     */
    private val SAFE_INPUT_WINDOW = WinDef.HWND(Pointer.createConstant(1))

    private val GAME_CLASS_NAME_W by lazy { WString("UnityWndClass") }

    val CENTER_RECT: GameRect by lazy { GameRect(-0.1, 0.1, 0.1, -0.1) }

    val RIGHT_CENTER_RECT: GameRect by lazy { GameRect(0.4, 0.5, 0.1, -0.1) }

    val CONFIRM_RECT: GameRect by lazy { GameRect(-0.0546, 0.0601, 0.2709, 0.3222) }

    /**
     * The mulligan confirm button's lower visual glow is not consistently
     * clickable across client builds. Keep the randomized click in the
     * narrow, reliable upper-center hit area. The small range preserves
     * natural variation without wandering outside the actual hit target.
     */
    val MULLIGAN_CONFIRM_RECT: GameRect by lazy { GameRect(-0.0025, 0.0025, 0.2900, 0.2950) }

    val END_TURN_RECT: GameRect by lazy { GameRect(0.3700, 0.4525, -0.0572, -0.0181) }

    /**
     * The post-game result screen has its own "click to continue" control.
     * It is not the in-game end-turn button, so using END_TURN_RECT here can
     * leave the visible client on the result screen while the log listener
     * starts parsing the next Power.log state.
     */
    private val GAME_END_CONTINUE_RECT: GameRect by lazy { GameRect(-0.0900, 0.0900, 0.4100, 0.4800) }

    val RECONNECT_RECT: GameRect by lazy { GameRect(-0.1845, -0.0396, 0.2282, 0.2904) }

    val CANCEL_CONNECT_RECT: GameRect by lazy { GameRect(0.0266, 0.1714, 0.2282, 0.2904) }

    val SURRENDER_RECT: GameRect by lazy { GameRect(-0.0629, 0.0607, -0.1677, -0.1279) }

    /**
     * 游戏进度已保存，请重启游戏的按钮
     */
    private val RESTART_GAME_RECT by lazy { GameRect(-0.0365, 0.0302, 0.0878, 0.1272) }

    /** Calibrated interior of the left "现在认输" choice; separate from both continue and restart controls. */
    internal val SURRENDER_CONFIRMATION_ACCEPT_RECT by lazy { GameRect(-0.095, -0.025, 0.095, 0.125) }

    internal fun surrenderConfirmationAcceptRectForTest(): GameRect = SURRENDER_CONFIRMATION_ACCEPT_RECT

    //    表情
    val THANK_RECT: GameRect by lazy { GameRect(-0.1604, -0.0404, 0.1153, 0.1502) }
    val PRAISE_RECT: GameRect by lazy { GameRect(-0.1930, -0.0730, 0.1971, 0.2320) }
    val GREET_RECT: GameRect by lazy { GameRect(-0.1907, -0.0707, 0.2799, 0.3148) }
    val THREATEN_RECT: GameRect by lazy { GameRect(0.0754, 0.1954, 0.2830, 0.3180) }
    val ERROR_RECT: GameRect by lazy { GameRect(0.0786, 0.1986, 0.1981, 0.2331) }
    val WONDER_RECT: GameRect by lazy { GameRect(0.0444, 0.1644, 0.1174, 0.1523) }

    val RIVAL_HERO_RECT: GameRect by lazy { GameRect(-0.0453, 0.0488, -0.3620, -0.2355) }
    val MY_HERO_RECT: GameRect by lazy { GameRect(-0.0357, 0.0342, 0.1978, 0.2691) }

    val RIVAL_POWER_RECT: GameRect by lazy { GameRect(0.0840, 0.1554, -0.3260, -0.2338) }
    val MY_POWER_RECT: GameRect by lazy { GameRect(0.0855, 0.1569, 0.2254, 0.3176) }

    /**
     * 星舰发射
     */
    val STARSHIP_LAUNCH_RECT: GameRect by lazy { GameRect(0.0180, 0.1268, 0.2723, 0.4089) }

    /**
     * 星舰取消发射
     */
    val STARSHIP_CANCEL_LAUNCH_RECT: GameRect by lazy { GameRect(-0.1107, -0.0295, 0.3106, 0.4120) }

    /**
     * 牌库
     */
    val DECK_RECT by lazy { GameRect(0.4376, 0.4688, 0.0346, 0.1645) }

    /**
     * 每日任务描述
     */
    val DAILY_TASK_DESC_RECTS by lazy {
        arrayOf(
            GameRect(-0.2932, -0.1573, -0.1239, -0.0374),
            GameRect(-0.1146, 0.0183, -0.1239, -0.0374),
            GameRect(0.0606, 0.1945, -0.1239, -0.0374),
        )
    }

    /**
     * 每日任务进度
     */
    val DAILY_TASK_PROGRESS_RECTS by lazy {
        arrayOf(
            GameRect(-0.2609, -0.1800, -0.0396, -0.0095),
            GameRect(-0.0903, -0.0094, -0.0396, -0.0095),
            GameRect(0.0876, 0.1685, -0.0396, -0.0095),
        )
    }

    /**
     * 每周任务描述
     */
    val WEEKLY_TASK_DESC_RECTS by lazy {
        arrayOf(
            GameRect(-0.2932, -0.1573, 0.1730, 0.2653),
            GameRect(-0.1146, 0.0183, 0.1730, 0.2653),
            GameRect(0.0606, 0.1945, 0.1730, 0.2653),
        )
    }

    /**
     * 每周任务进度
     */
    val WEEKLY_TASK_PROGRESS_RECTS by lazy {
        arrayOf(
            GameRect(-0.2609, -0.1800, 0.2607, 0.2910),
            GameRect(-0.0903, -0.0094, 0.2607, 0.2910),
            GameRect(0.0876, 0.1685, 0.2607, 0.2910),
        )
    }

    /**
     * 抉择
     */
    private val CHOOSE_ONE_RECTS by lazy {
        arrayOf(
            GameRect(-0.2030, -0.0364, -0.1775, 0.1677), GameRect(0.0412, 0.2030, -0.1732, 0.1656)
        )
    }

    /**
     * 时间线选择([0]:回溯，[1]:维持)
     */
    private val TIMELINE_RECTS by lazy {
        arrayOf(
            GameRect(-0.4699, -0.3743, 0.1917, 0.2889), GameRect(-0.2965, -0.2009, 0.1917, 0.2889)
        )
    }

    private val FOUR_DISCOVER_RECTS by lazy {
        arrayOf(
            // The four-card opening hand uses the compact horizontal
            // selection layout in the current client.  At 1920x1080 the
            // centers are approximately x=577, 834, 1090, and 1345.
            GameRect(-0.3332, -0.1911, -0.1702, 0.1160),
            GameRect(-0.1570, -0.0149, -0.1702, 0.1160),
            GameRect(0.0182, 0.1603, -0.1702, 0.1160),
            GameRect(0.1934, 0.3355, -0.1702, 0.1160),
        )
    }

    private val THREE_DISCOVER_RECTS by lazy {
        arrayOf(
            GameRect(-0.3037, -0.1595, -0.1702, 0.1160),
            GameRect(-0.0666, 0.0741, -0.1702, 0.1160),
            GameRect(0.1656, 0.3106, -0.1702, 0.1160),
        )
    }

    private val MY_HAND_DECK_RECTS by lazy {
        arrayOf(
            arrayOf(
                GameRect(-0.0693, 0.0136, 0.3675, 0.5000),
            ),
            arrayOf(
                GameRect(-0.1149, -0.0316, 0.3675, 0.5000),
                GameRect(-0.0242, 0.0590, 0.3675, 0.5000),
            ),
            arrayOf(
                GameRect(-0.1599, -0.0767, 0.3675, 0.5000),
                GameRect(-0.0693, 0.0140, 0.3675, 0.5000),
                GameRect(0.0214, 0.1047, 0.3675, 0.5000),
            ),
            arrayOf(
                GameRect(-0.1930, -0.1307, 0.3855, 0.5000),
                GameRect(-0.1092, -0.0347, 0.3742, 0.5000),
                GameRect(-0.0208, 0.0507, 0.3814, 0.4995),
                GameRect(0.0744, 0.1425, 0.4158, 0.5000),
            ),
            arrayOf(
                GameRect(-0.2034, -0.1471, 0.4116, 0.5000),
                GameRect(-0.1338, -0.0704, 0.3888, 0.5000),
                GameRect(-0.0704, -0.0071, 0.3698, 0.5000),
                GameRect(0.0077, 0.0604, 0.3935, 0.5000),
                GameRect(0.0858, 0.1456, 0.4144, 0.5000),
            ),
            arrayOf(
                GameRect(-0.2115, -0.1672, 0.4144, 0.5000),
                GameRect(-0.1514, -0.1028, 0.3964, 0.5000),
                GameRect(-0.0975, -0.0448, 0.3755, 0.5000),
                GameRect(-0.0384, 0.0087, 0.3755, 0.5000),
                GameRect(0.0270, 0.0671, 0.3812, 0.4990),
                GameRect(0.0903, 0.1579, 0.4240, 0.5000),
            ),
            arrayOf(
                GameRect(-0.2179, -0.1799, 0.4192, 0.5000),
                GameRect(-0.1640, -0.1232, 0.4040, 0.5000),
                GameRect(-0.1155, -0.0690, 0.3869, 0.5000),
                GameRect(-0.0712, -0.0233, 0.3717, 0.5000),
                GameRect(-0.0152, 0.0235, 0.3755, 0.5000),
                GameRect(0.0418, 0.0727, 0.3821, 0.5000),
                GameRect(0.0956, 0.1617, 0.4211, 0.5000),
            ),
            arrayOf(
                GameRect(-0.2210, -0.1901, 0.4259, 0.5000),
                GameRect(-0.1746, -0.1394, 0.4125, 0.5000),
                GameRect(-0.1324, -0.0916, 0.3973, 0.5000),
                GameRect(-0.0912, -0.0490, 0.3745, 0.5000),
                GameRect(-0.0469, -0.0103, 0.3688, 0.5000),
                GameRect(0.0038, 0.0326, 0.3745, 0.5000),
                GameRect(0.0534, 0.0759, 0.4040, 0.5000),
                GameRect(0.1030, 0.1536, 0.4163, 0.4990),
            ),
            arrayOf(
                GameRect(-0.2274, -0.1964, 0.4335, 0.5000),
                GameRect(-0.1820, -0.1496, 0.4335, 0.5000),
                GameRect(-0.1429, -0.1099, 0.4059, 0.5000),
                GameRect(-0.1060, -0.0687, 0.3888, 0.5000),
                GameRect(-0.0712, -0.0346, 0.3698, 0.5000),
                GameRect(-0.0268, 0.0034, 0.3745, 0.5000),
                GameRect(0.0186, 0.0502, 0.3764, 0.4563),
                GameRect(0.0639, 0.0942, 0.3878, 0.4610),
                GameRect(0.1083, 0.1653, 0.4125, 0.5000),
            ),
            arrayOf(
                GameRect(-0.2305, -0.2024, 0.4401, 0.5000),
                GameRect(-0.1894, -0.1598, 0.4401, 0.5000),
                GameRect(-0.1524, -0.1250, 0.4097, 0.5000),
                GameRect(-0.1176, -0.0859, 0.3964, 0.5000),
                GameRect(-0.0859, -0.0522, 0.3726, 0.5000),
                GameRect(-0.0511, -0.0208, 0.3726, 0.5000),
                GameRect(-0.0089, 0.0207, 0.3740, 0.4501),
                GameRect(0.0302, 0.0583, 0.3783, 0.4515),
                GameRect(0.0692, 0.0974, 0.3926, 0.4610),
                GameRect(0.1093, 0.1677, 0.4163, 0.5000),
            ),
        )
    }

    private val MY_PLAY_DECK_RECTS by lazy {
        arrayOf<Array<GameRect>>(
            //            偶数
            arrayOf(
                GameRect(-0.2689, -0.2111, -0.0033, 0.1050),
                GameRect(-0.1731, -0.1153, -0.0033, 0.1050),
                GameRect(-0.0773, -0.0195, -0.0033, 0.1050),
                GameRect(0.0195, 0.0773, -0.0033, 0.1050),
                GameRect(0.1153, 0.1731, -0.0033, 0.1050),
                GameRect(0.2111, 0.2689, -0.0033, 0.1050),
            ), //            奇数
            arrayOf(
                GameRect(-0.3156, -0.2578, -0.0041, 0.1043),
                GameRect(-0.2204, -0.1626, -0.0041, 0.1043),
                GameRect(-0.1257, -0.0691, -0.0041, 0.1043),
                GameRect(-0.0299, 0.0267, -0.0041, 0.1043),
                GameRect(0.0691, 0.1257, -0.0041, 0.1043),
                GameRect(0.1626, 0.2204, -0.0041, 0.1043),
                GameRect(0.2578, 0.3156, -0.0041, 0.1043),
            ),
        )
    }

    private val RIVAL_PLAY_DECK_RECTS by lazy {
        arrayOf<Array<GameRect>>(
            //            偶数
            arrayOf(
                GameRect(-0.2689, -0.2111, -0.1730, -0.0716),
                GameRect(-0.1731, -0.1153, -0.1730, -0.0716),
                GameRect(-0.0773, -0.0195, -0.1730, -0.0716),
                GameRect(0.0195, 0.0773, -0.1730, -0.0716),
                GameRect(0.1153, 0.1731, -0.1730, -0.0716),
                GameRect(0.2111, 0.2689, -0.1730, -0.0716),
            ),
            //            奇数
            arrayOf(
                GameRect(-0.3156, -0.2578, -0.1730, -0.0716),
                GameRect(-0.2204, -0.1626, -0.1730, -0.0716),
                GameRect(-0.1257, -0.0691, -0.1730, -0.0716),
                GameRect(-0.0299, 0.0267, -0.1730, -0.0716),
                GameRect(0.0691, 0.1257, -0.1730, -0.0716),
                GameRect(0.1626, 0.2204, -0.1730, -0.0716),
                GameRect(0.2578, 0.3156, -0.1730, -0.0716),
            ),
        )
    }

    private val DECK_POS_RECTS by lazy {
        arrayOf(
            GameRect(-0.4108, -0.2487, -0.2782, -0.2019),
            GameRect(-0.2368, -0.0833, -0.2782, -0.2019),
            GameRect(-0.0672, 0.0863, -0.2782, -0.2019),
            GameRect(-0.4034, -0.2498, -0.0699, 0.0065),
            GameRect(-0.2368, -0.0833, -0.0699, 0.0065),
            GameRect(-0.0672, 0.0863, -0.0699, 0.0065),
            GameRect(-0.4003, -0.2468, 0.1384, 0.2148),
            GameRect(-0.2337, -0.0802, 0.1384, 0.2148),
            GameRect(-0.0672, 0.0863, 0.1384, 0.2148),
        )
    }

    private val gameEndTasks = GameEndTaskRegistry()

    fun getDailyTaskDescRect(index: Int): GameRect? = DAILY_TASK_DESC_RECTS.getOrNull(index)

    fun getDailyTaskProgressRect(index: Int): GameRect? = DAILY_TASK_PROGRESS_RECTS.getOrNull(index)

    fun getWeaklyTaskDescRect(index: Int): GameRect? = WEEKLY_TASK_DESC_RECTS.getOrNull(index)

    fun getWeaklyTaskProgressRect(index: Int): GameRect? = WEEKLY_TASK_PROGRESS_RECTS.getOrNull(index)

    /**
     * 保持时间线
     */
    fun keepTimeline() = TIMELINE_RECTS[1].lClick()

    /**
     * 回溯时间线
     */
    fun rewindTimeline() = TIMELINE_RECTS[0].lClick()

    /**
     * 谢谢表情
     */
    fun sendThankEmoji() {
        log.info { "发送谢谢表情" }
        MY_HERO_RECT.rClick()
        SystemUtil.delayMedium()
        THANK_RECT.lClick(false)
        SystemUtil.delayShortMedium()
    }

    /**
     * 问候表情
     */
    fun sendGreetEmoji() {
        log.info { "发送问候表情" }
        MY_HERO_RECT.rClick()
        SystemUtil.delayMedium()
        GREET_RECT.lClick(false)
        SystemUtil.delayShortMedium()
    }

    /**
     * 失误表情
     */
    fun sendErrorEmoji() {
        log.info { "发送失误表情" }
        MY_HERO_RECT.rClick()
        SystemUtil.delayMedium()
        ERROR_RECT.lClick(false)
        SystemUtil.delayShortMedium()
    }

    /**
     * 获取抉择位置
     * @param index 范围：0-[CHOOSE_ONE_RECTS.size]
     */
    fun getChooseOneCardRect(index: Int): GameRect = CHOOSE_ONE_RECTS.getOrElse(index) { GameRect.INVALID }

    fun getThreeDiscoverCardRect(index: Int): GameRect = THREE_DISCOVER_RECTS.getOrElse(index) { GameRect.INVALID }

    fun getFourDiscoverCardRect(index: Int): GameRect = FOUR_DISCOVER_RECTS.getOrElse(index) { GameRect.INVALID }

    /**
     * 左击套牌位置
     */
    fun lClickDeckPos(count: Int = 1) {
        val scheduleSnapshot =
            if (ConfigUtil.getBoolean(ConfigEnum.WORK_TIME_RULE_HIGH_PRIORITY)) {
                WorkTimeListener.currentScheduleRuleSnapshot()
            } else {
                null
            }
        val globalDeckPos = ConfigExUtil.getChooseDeckPos()
        val activeScheduleRule = scheduleSnapshot?.rule
        val e2eDeckPosition = System.getProperty("hs.script.e2e.deck-position")
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        val deckSlotChoice = StrategyDefaultDeckSlotBindings.chooseDeckSlots(
            rule = scheduleSnapshot?.rule,
            strategyId = if (scheduleSnapshot == null) DeckStrategyManager.currentDeckStrategyProperty.get()?.id() else null,
            globalDeckSlots = globalDeckPos,
            maxDeckSlots = DECK_POS_RECTS.size,
        )
        val chooseDeckPos = e2eDeckPosition?.let { listOf(it) } ?: deckSlotChoice.deckSlots

        if (chooseDeckPos.isEmpty()) {
            DeckStrategyManager.clearScheduleDeckSlotSelection("no-deck-slot-configured")
            return
        }
        val invalidDeckPos = chooseDeckPos.filter { it !in 1..DECK_POS_RECTS.size }
        if (invalidDeckPos.isNotEmpty()) {
            log.warn {
                "SCHEDULE_SLOT_STRATEGY_FALLBACK reason=invalid-deck-slot " +
                    "ruleSet=${scheduleSnapshot?.ruleSetId ?: "?"} ruleIndex=${scheduleSnapshot?.ruleIndex ?: "?"} " +
                    "invalidSlots=${invalidDeckPos.joinToString(",")} maxDeckSlots=${DECK_POS_RECTS.size}"
            }
        }
        val validDeckPos = chooseDeckPos.filter { it in 1..DECK_POS_RECTS.size }
        if (validDeckPos.isEmpty()) {
            DeckStrategyManager.clearScheduleDeckSlotSelection("invalid-deck-slot")
            return
        }
        val deckSelectionReason = e2eDeckPosition?.let { "e2e-override" } ?: deckSlotChoice.assignmentReason
        val deckPos = validDeckPos.randomSelectOrNull() ?: let {
            log.warn { "没有设置可用卡组位" }
            return
        }
        if (scheduleSnapshot != null) {
            DeckStrategyManager.recordScheduleDeckSlotSelection(
                ruleSnapshot = scheduleSnapshot,
                deckSlot = deckPos,
                maxDeckSlots = DECK_POS_RECTS.size,
                assignmentReason = deckSelectionReason,
            )
        } else {
            DeckStrategyManager.clearScheduleDeckSlotSelection("global-deck-slot")
        }
        log.info {
            "DECK_POSITION_SELECTION source=${when {
                e2eDeckPosition != null -> "e2e-override"
                activeScheduleRule != null -> "schedule"
                else -> "global"
            }} " +
                "scheduleRule=${activeScheduleRule?.strategyId ?: "none"} " +
                "candidates=${chooseDeckPos.sorted()} selected=$deckPos " +
                "highPriority=${ConfigUtil.getBoolean(ConfigEnum.WORK_TIME_RULE_HIGH_PRIORITY)}"
        }
        DECK_POS_RECTS.getOrNull(deckPos - 1)?.let { rect ->
            repeat(count) {
                rect.lClick()
                SystemUtil.delayTiny()
            }
        }
    }

    /**
     * Click one explicitly resolved deck slot.
     *
     * This is intentionally separate from [lClickDeckPos].  The latter's
     * argument is the number of clicks and its slot is selected from the
     * active schedule/global candidates.  Recovery and strategy entry already
     * have a strategy-scoped slot and must not recalculate or randomize it.
     */
    fun lClickDeckSlot(deckSlot: Int) {
        val rect = DECK_POS_RECTS.getOrNull(deckSlot - 1)
        if (rect == null) {
            log.warn { "DECK_POSITION_SELECTION_SKIPPED source=explicit-strategy-slot invalidSlot=$deckSlot" }
            return
        }
        log.info { "DECK_POSITION_SELECTION source=explicit-strategy-slot selected=$deckSlot" }
        rect.lClick()
        SystemUtil.delayTiny()
    }

    fun getMyHandCardRect(
        index: Int,
        size: Int,
    ): GameRect = if (index < 0 || index > size - 1 || size > MY_HAND_DECK_RECTS.size) {
        GameRect.INVALID
    } else MY_HAND_DECK_RECTS[size - 1][index]

    fun getMyPlayCardRect(
        index: Int,
        size: Int,
    ): GameRect = getPlayCardRect(index, size, MY_PLAY_DECK_RECTS)

    fun getRivalPlayCardRect(
        index: Int,
        size: Int,
    ): GameRect = getPlayCardRect(index, size, RIVAL_PLAY_DECK_RECTS)

    private fun getPlayCardRect(
        index: Int,
        size: Int,
        gameRects: Array<Array<GameRect>>,
    ): GameRect {
        var i = index
        val s = max(size, 0)
        val rects: Array<GameRect> = gameRects[s and 1]
        val offset: Int = (rects.size - s) shr 1
        i = max((offset + i), 0)
        i = min(i, (rects.size - 1))
        return rects[i]
    }

    /**
     * 选择哪张发现牌
     */
    fun chooseDiscoverCard(
        index: Int,
        discoverCardSize: Int,
    ) = if (discoverCardSize >= 4) {
        getFourDiscoverCardRect(Math.clamp(index.toLong(), 0, 3)).lClick()
    } else {
        getThreeDiscoverCardRect(Math.clamp(index.toLong(), 0, 2)).lClick()
    }

    fun leftButtonClick(
        point: Point,
        recoveryCapability: MandatoryRankSurrenderGuard.RecoveryCapability? = null,
    ) =
        MouseUtil.leftButtonClick(
            point,
            ScriptStatus.gameHWND,
            recoveryCapability = recoveryCapability,
        )

    fun rightButtonClick(point: Point) = MouseUtil.rightButtonClick(point, ScriptStatus.gameHWND)

    fun moveMouse(
        startPos: Point?,
        endPos: Point,
    ) = MouseUtil.moveMouseByHuman(startPos, endPos, ScriptStatus.gameHWND)

    fun moveMouse(endPos: Point) = MouseUtil.moveMouseByHuman(endPos, ScriptStatus.gameHWND)

    /**
     * 如果战网不在运行则相当于启动战网，如果战网已经运行则为启动炉石
     */
    @Suppress("DEPRECATION")
    fun launchPlatformAndGame() {
        try {
            val platformPath = ConfigUtil.getString(ConfigEnum.PLATFORM_PATH)
            if (platformPath.isBlank()) {
                log.error { PLATFORM_CN_NAME + "路径为空" }
                return
            }
            val forceNormalUser = RuntimeSafety.safeNative
            val preventAdminLaunch = ConfigUtil.getBoolean(ConfigEnum.PREVENT_ADMIN_LAUNCH_GAME)
            val platformArguments = buildPlatformCommand(
                platformPath = platformPath,
                launchGame = true,
                runtimeChannel = AppRuntimeChannelDetector.installedChannel(),
                gamePath = ConfigUtil.getString(ConfigEnum.GAME_PATH),
            ).drop(1)
            if (NormalUserPlatformLaunch.shouldUseHelper(preventAdminLaunch)) {
                log.info {
                    "NORMAL_USER_PLATFORM_LAUNCH_REQUEST source=config " +
                        "controllerElevatedLaunchIsolated=true acceptance=awaiting-game-process-window"
                }
                val helper = NormalUserPlatformLaunch.startFromCurrentJar(
                    platformExecutable = platformPath,
                    platformArguments = platformArguments,
                )
                log.info {
                    "NORMAL_USER_PLATFORM_LAUNCH_HELPER_STARTED helperPid=${helper.pid()} " +
                        "controllerPid=${ProcessHandle.current().pid()} acceptance=awaiting-game-process-window"
                }
            } else {
                val process = ProcessBuilder(listOf(platformPath) + platformArguments).start()
                log.info {
                    "PLATFORM_ARG_STARTUP_DISPATCH pid=${process.pid()} " +
                        "source=${if (forceNormalUser) "safe-native-direct" else "default-direct"} " +
                        "acceptance=awaiting-game-process-window"
                }
            }
        } catch (e: IOException) {
            log.error(e) { "启动${PLATFORM_CN_NAME}及${GAME_CN_NAME}异常" }
        }
    }

    @Suppress("DEPRECATION")
    fun launchPlatform() {
        try {
            val platformPath = ConfigUtil.getString(ConfigEnum.PLATFORM_PATH)
            if (platformPath.isBlank()) {
                log.error { PLATFORM_CN_NAME + "路径为空" }
                return
            }
            ProcessBuilder(buildPlatformCommand(platformPath, launchGame = false)).start()
        } catch (e: IOException) {
            log.error(e) { "启动${PLATFORM_CN_NAME}异常" }
        }
    }

    /**
     * 计算我方是否已达对方斩杀线
     */
    fun reachingMyHeroDeadLine(): Boolean {
        WAR.me.playArea.hero?.let { myHero ->
            val rivalAllDamage = (WAR.rival.playArea.cards.sumOf {
                if (it.canAttack()) {
                    it.atc * (if (it.isMegaWindfury) 4 else if (it.isWindFury) 2 else 1)
                } else 0
            }) + (WAR.rival.playArea.hero?.let { rivalHero ->
                if (rivalHero.canAttack()) {
                    rivalHero.atc * (if (rivalHero.isMegaWindfury) 4 else if (rivalHero.isWindFury) 2 else 1)
                } else 0
            } ?: 0)
            val myTauntBlood = WAR.me.playArea.cards.sumOf { card -> if (card.isTaunt) card.blood() else 0 }
            val myHeroBlood = myHero.blood()
            if (rivalAllDamage - myHeroBlood - myTauntBlood >= 0){
                log.info { "敌方已能斩杀我方，敌方伤害:${rivalAllDamage}，我方血量:${myHeroBlood}，我方嘲讽随从血量:${myTauntBlood}" }
                return true
            }
        }
        return false
    }

    /**
     * 计算我方是否已达对方斩杀线并做相应动作
     */
    fun triggerReachingMyHeroDeadLine() {
        if (reachingMyHeroDeadLine()) {
            surrender()
        }
    }

    /**
     * 游戏里投降
     */
    fun surrender(
        skipEndTurn: Boolean = false,
        reason: String? = null,
        mandatoryRank: Boolean = false,
        rankSurrenderCapability: MulliganRankDispatchBarrier.SurrenderCapability? = null,
    ): Boolean {
        if (PowerLogListener.replayingExistingLog) {
            log.info { "Power.log恢复回放：跳过历史投降请求" }
            return false
        }
        if (isTerminalGameState()) {
            log.info {
                "SURRENDER_ACTION_BLOCKED reason=terminal-state-priority " +
                    "phase=${WAR.currentPhase.name} step=${WAR.currentTurnStep?.name ?: "NONE"} " +
                    "won=${WAR.won.isNotBlank()} lost=${WAR.lost.isNotBlank()} " +
                    "conceded=${WAR.conceded.isNotBlank()} terminalPageTask=${gameEndTasks.hasTerminalPageTask()} dispatch=false"
            }
            return false
        }
        SurrenderPolicy.surrenderDispatchBlockReason(mandatoryRank)?.let { blockReason ->
            log.info {
                "SURRENDER_ACTION_BLOCKED reason=$blockReason " +
                    "requestedReason=${reason?.takeIf { it.isNotBlank() } ?: "unspecified"} " +
                    "mandatoryRank=$mandatoryRank dispatch=false queue=false retry=false"
            }
            return false
        }
        if (NeverSurrenderPolicy.blockSurrender("GameUtil.surrender", mandatoryRank)) return false
//        SystemUtil.frontWindow(ScriptStaticData.getGameHWND());
//        按ESC键弹出投降界面
//        ScriptStaticData.ROBOT.keyPress(27);
//        ScriptStaticData.ROBOT.keyRelease(27);
        if (gameEndTasks.hasTerminalPageTask()) {
            log.warn {
                "SURRENDER_ACTION_BLOCKED reason=stale-settlement-task " +
                    "settlementTask=true dispatch=false pause=false continue=true"
            }
            return false
        }
        if (gameEndTasks.hasSurrenderRecoveryTask()) {
            log.info {
                "SURRENDER_ACTION_BLOCKED reason=surrender-recovery-already-running " +
                    "dispatch=false queue=false retry=existing-task"
            }
            return false
        }
        val initialMode = Mode.currMode
        val initialInWar = WarEx.inWar
        if (!isSurrenderStateConfirmed(initialMode, initialInWar)) {
            log.warn {
                "投降请求忽略：未确认有效对局状态 " +
                    "mode=${initialMode?.name ?: "null"} inWar=$initialInWar " +
                    "warCount=${WarEx.warCount} " +
                    "reason=mode-not-gameplay-and-war-not-active"
            }
            return false
        }
        // Validate every ordinary executor precondition before consuming the
        // one-shot rank capability. A rejected request must remain retryable;
        // otherwise a stale settlement task or transient mode update could
        // burn the only authorization while no surrender request was queued.
        if (!ActionDispatchGate.allow("surrender.request", rankSurrenderCapability = rankSurrenderCapability)) return false
        // Keep a process-local ownership signal for statistics. A fast
        // surrender can reach GAME_OVER before PLAYSTATE=CONCEDED is parsed
        // or before war.me has been assigned its game id.
        WarEx.surrenderRequested = true
        val mandatoryRankSurrenderCapability = if (mandatoryRank) {
            MandatoryRankSurrenderGuard.begin(
                PowerLogListener.currentGameSurrenderIdentity(WarEx.war.me.gameId),
            )
        } else null
        WarEx.surrenderReason = reason?.takeIf { it.isNotBlank() }
        if (System.getProperty("hs.script.e2e") == "true") {
            E2ETrace.markSurrenderRequested(reason)
        }
        log.info {
            "SURRENDER_EXECUTOR_REQUESTED reason=${reason?.takeIf { it.isNotBlank() } ?: "unspecified"} " +
                "skipEndTurn=$skipEndTurn"
        }
        val warCount = WarEx.warCount
        if (!skipEndTurn) {
            delay(RandomUtil.getActionInterval(1000))
        }
        val isGamePlay = Mode.currMode === ModeEnum.GAMEPLAY
        val surrenderRetryInterval = RandomUtil.getActionInterval(500).toLong()
        var surrenderAttempts = 0
        var consecutiveUnknownRankScreens = 0
        val maxSurrenderAttempts = 30
        val surrenderStartedAt = System.currentTimeMillis()
        val mandatoryPostClickProbe = ScreenWatchdog.MandatorySurrenderPostClickProbe()
        val surrenderFutureRef = AtomicReference<ScheduledFuture<*>?>()
        val surrenderStopRequested = AtomicBoolean(false)
        val surrenderFuture = EXTRA_THREAD_POOL.scheduleWithFixedDelay(
                {
                    fun stopSurrenderTask() {
                        surrenderStopRequested.set(true)
                        surrenderFutureRef.get()?.let { task -> gameEndTasks.cancel(task) }
                    }
                    if (mandatoryRank && MandatoryRankSurrenderGuard.isTerminalCleanupPending()) {
                        stopSurrenderTask()
                        log.info {
                            "RANK_SURRENDER_STALE_RETRY_CANCELLED reason=terminal-proof-already-accepted " +
                                "ordinaryInput=false requeue=false"
                        }
                        return@scheduleWithFixedDelay
                    }
                    if (surrenderStopRequested.get()) return@scheduleWithFixedDelay
                    fun currentSurrenderTerminalCleanupCapability(): MandatoryRankSurrenderGuard.TerminalCleanupCapability? {
                        MandatoryRankSurrenderGuard.existingTerminalCleanupCapability()?.let { return it }
                        val ownEntityId = WarEx.war.me.gameId
                        val opponentEntityId = WarEx.war.rival.gameId
                        val evidence = PowerLogListener.currentGameSurrenderTerminalEvidence(
                            ownEntityId,
                            opponentEntityId,
                        )
                        return MandatoryRankSurrenderGuard.authorizeTerminalCleanup(evidence)
                    }
                    fun completeMandatoryRankSurrender(
                        evidence: String,
                        cleanupCapability: MandatoryRankSurrenderGuard.TerminalCleanupCapability? = null,
                    ): Boolean {
                        if (!mandatoryRank) return true
                        val authorizedCleanup = cleanupCapability ?: currentSurrenderTerminalCleanupCapability()
                        if (MandatoryRankSurrenderGuard.confirmCompleted(evidence, authorizedCleanup)) {
                            log.info { "RANK_SURRENDER_RECOVERY_COMPLETED evidence=$evidence requeueAllowed=true" }
                            return true
                        }
                        MandatoryRankSurrenderGuard.markRecoveryUncertain()
                        log.warn {
                            "RANK_SURRENDER_RECOVERY_WAIT reason=terminal-proof-not-authorized " +
                                "screenEvidence=$evidence ordinaryInput=false requeue=false"
                        }
                        return false
                    }
                    if (PauseStatus.isPause) {
                        if (mandatoryRank) {
                            log.info {
                                "RANK_SURRENDER_RECOVERY_WAIT reason=manual-pause " +
                                    "retry=false ordinaryInput=false requeue=false pause=true"
                            }
                            return@scheduleWithFixedDelay
                        }
                        stopSurrenderTask()
                    } else if (SurrenderPolicy.currentRankContinueAuthorized()) {
                        stopSurrenderTask()
                        log.info {
                            "SURRENDER_RETRY_BLOCKED reason=verified-rank-eligibility " +
                                "attempts=$surrenderAttempts dispatch=false retry=false action=CONTINUE_GAME"
                        }
                    } else if (!ActionDispatchGate.allow("surrender.retry", mandatoryRankSurrenderCapability)) {
                        stopSurrenderTask()
                    } else if (WarEx.warCount > warCount || (isGamePlay && Mode.currMode !== ModeEnum.GAMEPLAY)) {
                        if (WarEx.warCount > warCount || isTerminalGameState()) {
                            stopSurrenderTask()
                            if (mandatoryRank) {
                                MandatoryRankSurrenderGuard.markRecoveryUncertain()
                                log.info {
                                    "RANK_SURRENDER_RECOVERY_WAIT reason=powerlog-terminal-awaiting-visible-transition " +
                                        "ordinaryInput=false requeue=false pause=false"
                                }
                            }
                        } else if (mandatoryRank) {
                            MandatoryRankSurrenderGuard.markRecoveryUncertain()
                            log.warn {
                                "RANK_SURRENDER_RECOVERY_WAIT reason=mode-transition-without-terminal " +
                                    "ordinaryInput=false requeue=false pause=false"
                            }
                        } else {
                            stopSurrenderTask()
                        }
                    } else if (!isSurrenderStateConfirmed(Mode.currMode, WarEx.inWar)) {
                        if (mandatoryRank) {
                            MandatoryRankSurrenderGuard.markRecoveryUncertain()
                            log.warn {
                                "RANK_SURRENDER_RECOVERY_WAIT reason=war-state-unconfirmed " +
                                    "ordinaryInput=false requeue=false pause=false"
                            }
                            return@scheduleWithFixedDelay
                        }
                        log.warn {
                            "投降任务终止：有效对局状态已丢失，停止坐标输入 " +
                                "mode=${Mode.currMode?.name ?: "null"} inWar=${WarEx.inWar} " +
                                "warCount=${WarEx.warCount} " +
                                "reason=mode-not-gameplay-and-war-not-active"
                        }
                        stopSurrenderTask()
                    } else if (mandatoryRank) {
                        if (!MandatoryRankSurrenderRecoveryPolicy.hasRetryBudget(surrenderAttempts)) {
                            MandatoryRankSurrenderGuard.markRecoveryUncertain()
                            log.error {
                                "RANK_SURRENDER_RECOVERY_STOP reason=retry-budget-exhausted " +
                                    "attempts=$surrenderAttempts maxAttempts=${MandatoryRankSurrenderRecoveryPolicy.MAX_RETRY_ATTEMPTS} " +
                                    "retry=false ordinaryInput=false requeue=false pause=false"
                            }
                            stopSurrenderTask()
                            return@scheduleWithFixedDelay
                        }
                        val postClickProbe = mandatoryPostClickProbe.shouldBypassCooldown()
                        val watchdogTiming = ScreenWatchdog.shouldInspect(
                            startedAt = surrenderStartedAt,
                            attempts = surrenderAttempts + 1,
                            bypassCooldownForMandatorySurrenderPostClick = postClickProbe,
                        )
                        if (!watchdogTiming.shouldInspect) {
                            log.info {
                                "RANK_SURRENDER_RECOVERY_WAIT reason=${watchdogTiming.reason} " +
                                    "ordinaryInput=false requeue=false pause=false"
                            }
                            return@scheduleWithFixedDelay
                        }
                        surrenderAttempts = MandatoryRankSurrenderRecoveryPolicy.attemptsAfterInspectionStart(
                            surrenderAttempts,
                            inspectionStarted = true,
                        )
                        if (postClickProbe) mandatoryPostClickProbe.markProbeStarted()
                        val state = "mode=${Mode.currMode?.name ?: "NONE"}|inWar=${WarEx.inWar}|" +
                            "warPhase=${WarEx.war.currentPhase.name}|myTurn=${WarEx.war.isMyTurn}|" +
                            "myMulliganInput=${ReplaceCardPhaseStrategy.isRankInspectionReady()}|" +
                            "warCount=${WarEx.warCount}"
                        val observation = ScreenWatchdog.inspectForSurrender(
                            state = state,
                            attempts = surrenderAttempts,
                            mandatoryRankSurrender = true,
                        )
                        // OCR may take long enough for Power.log terminal proof
                        // to arrive while this scheduled callback is in flight.
                        // Discard that stale observation before its policy can
                        // relabel the released barrier or dispatch a click.
                        if (MandatoryRankSurrenderGuard.isTerminalCleanupPending()) {
                            stopSurrenderTask()
                            log.info {
                                "RANK_SURRENDER_STALE_RETRY_CANCELLED reason=terminal-proof-accepted-during-inspection " +
                                    "observedScreen=${observation.kind} ordinaryInput=false requeue=false " +
                                    "screenshot=${observation.screenshotPath ?: "not-saved"}"
                            }
                            return@scheduleWithFixedDelay
                        }
                        val decision = MandatoryRankSurrenderRecoveryPolicy.decide(observation.kind)
                        val unknownObservationOrdinal = if (
                            decision.action == MandatoryRankSurrenderRecoveryPolicy.Action.OBSERVE_ONLY
                        ) {
                            if (consecutiveUnknownRankScreens < Int.MAX_VALUE) consecutiveUnknownRankScreens++
                            consecutiveUnknownRankScreens
                        } else {
                            consecutiveUnknownRankScreens = 0
                            0
                        }
                        val emitScreenStep = unknownObservationOrdinal == 0 ||
                            MandatoryRankSurrenderRecoveryPolicy.shouldEmitUnknownObservationDiagnostic(
                                unknownObservationOrdinal,
                            )
                        if (emitScreenStep) {
                            log.info {
                                "RANK_SURRENDER_SCREEN_STEP screen=${observation.kind} " +
                                    "action=${decision.action} reason=${decision.reason} provider=${observation.provider} " +
                                    "evidenceReason=${observation.reason} " +
                                    "unknownObservationOrdinal=${unknownObservationOrdinal.takeIf { it > 0 } ?: "reset"} " +
                                    "screenshot=${observation.screenshotPath ?: "not-saved"}"
                            }
                        }
                        val recoveryCapability = requireNotNull(mandatoryRankSurrenderCapability)
                        when (decision.action) {
                            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SETTINGS -> {
                                if (ActionDispatchGate.allow("surrender.retry.open-settings", recoveryCapability)) {
                                    lClickSettingsForMandatoryRankSurrender(recoveryCapability)
                                    mandatoryPostClickProbe.markClickDispatched()
                                }
                            }
                            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SURRENDER -> {
                                if (ActionDispatchGate.allow("surrender.retry.select-surrender", recoveryCapability)) {
                                    SURRENDER_RECT.lClickForMandatoryRankSurrender(recoveryCapability)
                                    mandatoryPostClickProbe.markClickDispatched()
                                }
                            }
                            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_CONFIRMATION -> {
                                if (decision.confirmationTarget == MandatoryRankSurrenderRecoveryPolicy.ConfirmationTarget.ACCEPT_NOW &&
                                    ActionDispatchGate.allow("surrender.retry.confirm.accept-now", recoveryCapability)
                                ) {
                                    SURRENDER_CONFIRMATION_ACCEPT_RECT.lClickCenterForMandatoryRankSurrender(recoveryCapability)
                                    mandatoryPostClickProbe.markClickDispatched()
                                    log.info {
                                        "RANK_SURRENDER_CONFIRMATION_INPUT target=ACCEPT_NOW " +
                                            "button=现在认输 dispatch=requested acceptance=awaiting-current-game-powerlog"
                                    }
                                } else {
                                    log.warn {
                                        "RANK_SURRENDER_CONFIRMATION_INPUT_BLOCKED reason=unrecognized-target " +
                                            "target=${decision.confirmationTarget ?: "UNKNOWN"} " +
                                            "continueButton=never-clicked dispatch=false"
                                    }
                                }
                            }
                            MandatoryRankSurrenderRecoveryPolicy.Action.OBSERVE_ONLY -> {
                                MandatoryRankSurrenderGuard.markRecoveryUncertain()
                                if (emitScreenStep) {
                                    log.warn {
                                        "RANK_SURRENDER_RECOVERY_WAIT reason=screen-unconfirmed " +
                                            "ordinaryInput=false requeue=false pause=false " +
                                            "screen=${observation.kind} ocrReason=${observation.reason} " +
                                            "unknownObservationOrdinal=$unknownObservationOrdinal " +
                                            "barrier=SURRENDER_REQUIRED " +
                                            "screenshot=${observation.screenshotPath ?: "not-saved"}"
                                    }
                                }
                            }
                            MandatoryRankSurrenderRecoveryPolicy.Action.COMPLETE_WIN,
                            MandatoryRankSurrenderRecoveryPolicy.Action.COMPLETE_LOSS,
                            MandatoryRankSurrenderRecoveryPolicy.Action.COMPLETE_RESULT,
                            -> {
                                val terminalKind = when (decision.action) {
                                    MandatoryRankSurrenderRecoveryPolicy.Action.COMPLETE_WIN -> ScreenWatchdogKind.WIN
                                    MandatoryRankSurrenderRecoveryPolicy.Action.COMPLETE_LOSS -> ScreenWatchdogKind.LOST
                                    else -> ScreenWatchdogKind.RESULT
                                }
                                GameOverPhaseStrategy.forceTerminalFromScreenWatchdog(
                                    terminalKind,
                                    observation.screenshotPath ?: observation.reason,
                                )
                                if (decision.action == MandatoryRankSurrenderRecoveryPolicy.Action.COMPLETE_RESULT) {
                                    val cleanupCapability = MandatoryRankSurrenderGuard
                                        .existingTerminalCleanupCapability()
                                    if (mandatoryRank && cleanupCapability == null) {
                                        MandatoryRankSurrenderGuard.markRecoveryUncertain()
                                        log.warn {
                                            "RANK_SURRENDER_RECOVERY_WAIT reason=result-without-current-game-powerlog-proof " +
                                                "ordinaryInput=false requeue=false"
                                        }
                                        return@scheduleWithFixedDelay
                                    }
                                    stopSurrenderTask()
                                    dismissStaleGameEndScreen(
                                        resultAlreadyObserved = true,
                                        terminalCleanupCapability = cleanupCapability,
                                    )
                                } else {
                                    stopSurrenderTask()
                                }
                            }
                            MandatoryRankSurrenderRecoveryPolicy.Action.COMPLETE_MAIN_MENU -> {
                                if (!completeMandatoryRankSurrender("SCREEN_MAIN_MENU")) {
                                    return@scheduleWithFixedDelay
                                }
                                stopSurrenderTask()
                                Mode.recover(ModeEnum.HUB, "mandatory-rank-surrender-main-menu-confirmed", enterStrategy = true)
                            }
                            MandatoryRankSurrenderRecoveryPolicy.Action.COMPLETE_MATCHMAKING -> {
                                if (!completeMandatoryRankSurrender("SCREEN_MATCHMAKING")) {
                                    return@scheduleWithFixedDelay
                                }
                                stopSurrenderTask()
                                Mode.recover(ModeEnum.TOURNAMENT, "mandatory-rank-surrender-matchmaking-confirmed", enterStrategy = false)
                            }
                        }
                        return@scheduleWithFixedDelay
                    } else if (++surrenderAttempts > maxSurrenderAttempts) {
                        // Never keep clicking a potentially stale coordinate
                        // forever.  If the game did not leave GAMEPLAY after
                        // bounded retries, stop only this surrender request;
                        // the normal game worker must remain active.
                        log.error {
                            "投降重试熔断：未观察到对局结束或模式切换，停止盲点 " +
                                "attempts=$surrenderAttempts mode=${Mode.currMode} " +
                                "inWar=${WarEx.inWar} warCount=${WarEx.warCount} " +
                                "action=STOP_SURRENDER_AND_CONTINUE pause=false dispatch=false"
                        }
                        stopSurrenderTask()
                    } else {
                        val watchdogTiming = ScreenWatchdog.shouldInspect(
                            startedAt = surrenderStartedAt,
                            attempts = surrenderAttempts,
                        )
                        if (watchdogTiming.shouldInspect) {
                            val state = "mode=${Mode.currMode?.name ?: "NONE"}|inWar=${WarEx.inWar}|" +
                                "warPhase=${WarEx.war.currentPhase.name}|myTurn=${WarEx.war.isMyTurn}|" +
                                "warCount=${WarEx.warCount}"
                            val observation = ScreenWatchdog.inspectForSurrender(
                                state = state,
                                attempts = surrenderAttempts,
                            )
                            log.warn {
                                "RECOVERY_ACTION source=screen-watchdog action=${observation.action} " +
                                    "kind=${observation.kind} provider=${observation.provider} " +
                                    "screenshot=${observation.screenshotPath ?: "not-saved"}"
                            }
                            when (observation.action) {
                                ScreenWatchdogRecoveryAction.CONTINUE_ACTION -> Unit
                                ScreenWatchdogRecoveryAction.STOP_SURRENDER_NO_ACTION -> {
                                    if (MandatoryRankSurrenderRecoveryPolicy.shouldWaitForMoreEvidence(mandatoryRank, false)) {
                                        MandatoryRankSurrenderGuard.markRecoveryUncertain()
                                        log.warn {
                                            "RANK_SURRENDER_RECOVERY_WAIT reason=screen-no-action " +
                                                "ordinaryInput=false requeue=false pause=false " +
                                                "screenshot=${observation.screenshotPath ?: "not-saved"}"
                                        }
                                        return@scheduleWithFixedDelay
                                    }
                                    stopSurrenderTask()
                                    log.info {
                                        "SCREEN_WATCHDOG_CANCELLED reason=${observation.reason} " +
                                            "provider=${observation.provider} screenshot=${observation.screenshotPath ?: "not-saved"}"
                                    }
                                    return@scheduleWithFixedDelay
                                }
                                ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECORD_WIN,
                                ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECORD_LOSS,
                                -> {
                                    stopSurrenderTask()
                                    GameOverPhaseStrategy.forceTerminalFromScreenWatchdog(
                                        observation.kind,
                                        observation.screenshotPath ?: observation.reason,
                                    )
                                    return@scheduleWithFixedDelay
                                }
                                ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_CLEAR_RESULT -> {
                                    if (!GameOverPhaseStrategy.forceTerminalFromScreenWatchdog(
                                            ScreenWatchdogKind.RESULT,
                                            observation.screenshotPath ?: observation.reason,
                                        )
                                    ) {
                                        if (mandatoryRank) {
                                            MandatoryRankSurrenderGuard.markRecoveryUncertain()
                                        } else {
                                            stopSurrenderTask()
                                        }
                                        log.error {
                                            "SCREEN_WATCHDOG_RESULT_BLOCKED reason=authoritative-terminal-unavailable " +
                                                "state=$state retry=false dispatch=false"
                                        }
                                        return@scheduleWithFixedDelay
                                    }
                                    Mode.recover(ModeEnum.GAMEPLAY, "screen-watchdog-result-page", enterStrategy = false)
                                    val cleanupCapability = MandatoryRankSurrenderGuard
                                        .existingTerminalCleanupCapability()
                                    if (mandatoryRank && cleanupCapability == null) {
                                        MandatoryRankSurrenderGuard.markRecoveryUncertain()
                                        log.warn {
                                            "RANK_SURRENDER_RECOVERY_WAIT reason=result-without-current-game-powerlog-proof " +
                                                "ordinaryInput=false requeue=false"
                                        }
                                        return@scheduleWithFixedDelay
                                    }
                                    stopSurrenderTask()
                                    dismissStaleGameEndScreen(
                                        resultAlreadyObserved = true,
                                        terminalCleanupCapability = cleanupCapability,
                                    )
                                    return@scheduleWithFixedDelay
                                }
                                ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECOVER_MATCHMAKING -> {
                                    if (!completeMandatoryRankSurrender("SCREEN_MATCHMAKING")) {
                                        return@scheduleWithFixedDelay
                                    }
                                    stopSurrenderTask()
                                    Mode.recover(ModeEnum.TOURNAMENT, "screen-watchdog-matchmaking", enterStrategy = false)
                                    return@scheduleWithFixedDelay
                                }
                                ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECOVER_MAIN_MENU -> {
                                    if (!completeMandatoryRankSurrender("SCREEN_MAIN_MENU")) {
                                        return@scheduleWithFixedDelay
                                    }
                                    stopSurrenderTask()
                                    Mode.recover(ModeEnum.HUB, "screen-watchdog-main-menu", enterStrategy = true)
                                    return@scheduleWithFixedDelay
                                }
                                ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RESUME_GAMEPLAY -> {
                                    if (MandatoryRankSurrenderRecoveryPolicy.shouldWaitForMoreEvidence(mandatoryRank, false)) {
                                        MandatoryRankSurrenderGuard.markRecoveryUncertain()
                                        log.warn {
                                            "RANK_SURRENDER_RECOVERY_WAIT reason=active-game-still-visible " +
                                                "kind=${observation.kind} ordinaryInput=false requeue=false pause=false " +
                                                "screenshot=${observation.screenshotPath ?: "not-saved"}"
                                        }
                                        return@scheduleWithFixedDelay
                                    }
                                    stopSurrenderTask()
                                    log.warn {
                                        "SCREEN_WATCHDOG_ACTIVE_GAME_RESUME " +
                                            "reason=${observation.reason} kind=${observation.kind} " +
                                            "state=$state action=RESUME_NORMAL_GAMEPLAY " +
                                            "dispatch=false retry=false replan=false"
                                    }
                                    return@scheduleWithFixedDelay
                                }
                                ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_CONTINUE_UNKNOWN -> {
                                    if (MandatoryRankSurrenderRecoveryPolicy.shouldWaitForMoreEvidence(mandatoryRank, false)) {
                                        MandatoryRankSurrenderGuard.markRecoveryUncertain()
                                        log.warn {
                                            "RANK_SURRENDER_RECOVERY_WAIT reason=unknown-or-capture-failed " +
                                                "kind=${observation.kind} provider=${observation.provider} " +
                                                "ordinaryInput=false requeue=false pause=false " +
                                                "screenshot=${observation.screenshotPath ?: "not-saved"}"
                                        }
                                        return@scheduleWithFixedDelay
                                    }
                                    stopSurrenderTask()
                                    log.warn {
                                        "SCREEN_WATCHDOG_BLOCKED reason=unknown-or-capture-failed " +
                                            "kind=${observation.kind} attempts=$surrenderAttempts " +
                                            "screenshot=${observation.screenshotPath ?: "not-saved"} " +
                                            "action=STOP_SURRENDER_AND_CONTINUE pause=false dispatch=false"
                                    }
                                    return@scheduleWithFixedDelay
                                }
                            }
                        } else if (watchdogTiming.reason.startsWith("cooldown")) {
                            log.warn {
                                "SCREEN_WATCHDOG_THROTTLED reason=${watchdogTiming.reason} " +
                                    "attempts=$surrenderAttempts"
                            }
                            return@scheduleWithFixedDelay
                        }
                        log.info {
                            "投降尝试 #$surrenderAttempts/$maxSurrenderAttempts " +
                                "mode=${Mode.currMode} inWar=${WarEx.inWar}"
                        }
                        if (!skipEndTurn) {
                            if (!ActionDispatchGate.allow("surrender.retry.before-end-turn")) {
                                stopSurrenderTask()
                                return@scheduleWithFixedDelay
                            }
                            END_TURN_RECT.lClick()
                        }
                        SystemUtil.delayTiny()
                        if (!ActionDispatchGate.allow("surrender.retry.before-menu")) {
                            stopSurrenderTask()
                            return@scheduleWithFixedDelay
                        }
                        lClickSettings()
                        SystemUtil.delayShortMedium()
                        if (!ActionDispatchGate.allow("surrender.retry.before-confirm")) {
                            stopSurrenderTask()
                            return@scheduleWithFixedDelay
                        }
                        SURRENDER_RECT.lClick()
                        SystemUtil.delayTiny()
                        if (!ActionDispatchGate.allow("surrender.retry.before-restart")) {
                            stopSurrenderTask()
                            return@scheduleWithFixedDelay
                        }
                        RESTART_GAME_RECT.lClick()
                    }
                },
                0,
                surrenderRetryInterval,
                TimeUnit.MILLISECONDS,
            )
        surrenderFutureRef.set(surrenderFuture)
        gameEndTasks.add(surrenderFuture, GameEndTaskRegistry.Kind.SURRENDER_RECOVERY)
        if (surrenderStopRequested.get()) gameEndTasks.cancel(surrenderFuture)
        return true
    }

    /**
     * 点击回合结束按钮
     */
    fun lClickTurnOver(isCancel: Boolean = true) = END_TURN_RECT.lClick(isCancel)

    /**
     * 点击设置按钮
     */
    fun lClickSettings() {
        val width = ScriptStatus.GAME_RECT.right - ScriptStatus.GAME_RECT.left
        val height = ScriptStatus.GAME_RECT.bottom - ScriptStatus.GAME_RECT.top
        val rightMargin = 0.0072992700729927
        val bottomMargin = 0.015625
        leftButtonClick(Point((width - width * rightMargin).toInt(), (height - height * bottomMargin).toInt()))
    }

    private fun lClickSettingsForMandatoryRankSurrender(
        recoveryCapability: MandatoryRankSurrenderGuard.RecoveryCapability,
    ) {
        val width = ScriptStatus.GAME_RECT.right - ScriptStatus.GAME_RECT.left
        val height = ScriptStatus.GAME_RECT.bottom - ScriptStatus.GAME_RECT.top
        val rightMargin = 0.0072992700729927
        val bottomMargin = 0.015625
        leftButtonClick(
            Point((width - width * rightMargin).toInt(), (height - height * bottomMargin).toInt()),
            recoveryCapability = recoveryCapability,
        )
    }

    fun cancelAction() = MouseUtil.rightButtonClick(ScriptStatus.gameHWND)

    fun lClickCenter() = CENTER_RECT.lClick()

    fun lClickRightCenter() = RIGHT_CENTER_RECT.lClick()

    fun rClickCenter() = CENTER_RECT.rClick()

    fun reconnectAction() = RECONNECT_RECT.lClick()

    /**
     * 点掉游戏结束结算页面
     */
    fun addGameEndTask(
        terminalCleanupCapability: MandatoryRankSurrenderGuard.TerminalCleanupCapability? = null,
    ) {
        cancelGameEndTask()
        log.info { "点掉${GAME_CN_NAME}结束结算页面" }
        if (terminalCleanupCapability != null) {
            // While mandatory-rank recovery is pending, use the bounded,
            // postchecked result-page path. Never let the legacy repeating
            // end-turn clicker inherit the one-purpose terminal capability.
            dismissStaleGameEndScreen(
                resultAlreadyObserved = true,
                terminalCleanupCapability = terminalCleanupCapability,
            )
            return
        }
        if (Mode.currMode === ModeEnum.GAMEPLAY) {
            val gameEndClickInterval = RandomUtil.getActionInterval(1000).toLong()
            gameEndTasks.add(
                EXTRA_THREAD_POOL.scheduleWithFixedDelay(
                    LRunnable {
                        if (PauseStatus.isPause) {
                            cancelGameEndTask()
                        } else if (Mode.currMode !== ModeEnum.GAMEPLAY) {
                            cancelGameEndTask()
                        } else {
                            if (isE2ERun()) {
                                log.info { "结算页：使用前台确认的真实输入点击继续，清理上一局结算状态" }
                                MouseUtil.leftButtonClickForRecovery(GAME_END_CONTINUE_RECT.getCenterClickPos())
                            } else {
                                END_TURN_RECT.lClick()
                            }
                        }
                    },
                    gameEndClickInterval,
                    gameEndClickInterval,
                    TimeUnit.MILLISECONDS,
                ),
                GameEndTaskRegistry.Kind.TERMINAL_PAGE,
            )
        } else {
            (0 until 3).forEach { _ ->
                END_TURN_RECT.lClick()
                SystemUtil.delayShort()
            }
        }
    }

    /**
     * Clear a result page that was already visible when the script started.
     *
     * The normal result handler is entered from the live Power.log result
     * event.  A restart can instead begin with Hearthstone still showing the
     * previous result page, so no new result event is emitted and the normal
     * handler never gets a chance to click it.  Keep this recovery task
     * bounded and cancel it as soon as a new game is detected; it must never
     * become a permanent clicker that can touch a later live game.
     */
    fun dismissStaleGameEndScreen(
        resultAlreadyObserved: Boolean = false,
        terminalCleanupCapability: MandatoryRankSurrenderGuard.TerminalCleanupCapability? = null,
    ) {
        val terminalCleanupAuthorized =
            MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(terminalCleanupCapability)
        if (ResultPageDismissalPolicy.shouldStopWorker(
                paused = PauseStatus.isPause,
                gameplayMode = Mode.currMode === ModeEnum.GAMEPLAY,
                terminalCleanupCapabilityValid = terminalCleanupAuthorized,
            ) || (WarEx.inWar && !resultAlreadyObserved)
        ) {
            log.info {
                "RESULT_PAGE_DISMISSAL_BLOCKED reason=unsafe-start " +
                    "mode=${Mode.currMode} inWar=${WarEx.inWar} resultAlreadyObserved=$resultAlreadyObserved " +
                    "terminalCleanupAuthorized=$terminalCleanupAuthorized"
            }
            return
        }

        val attempt = AtomicInteger(0)
        val clickAttempts = AtomicInteger(0)
        val startingWarCount = WarEx.warCount
        val interval = if (terminalCleanupAuthorized) 2_000L else RandomUtil.getActionInterval(800).toLong()
        lateinit var future: ScheduledFuture<*>
        future = EXTRA_THREAD_POOL.scheduleWithFixedDelay(
            {
                val number = attempt.incrementAndGet()
                val terminalCleanupStillAuthorized =
                    MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(terminalCleanupCapability)
                val newGameDetected = WarEx.warCount > startingWarCount
                if (ResultPageDismissalPolicy.shouldStopWorker(
                        paused = PauseStatus.isPause,
                        gameplayMode = Mode.currMode === ModeEnum.GAMEPLAY,
                        terminalCleanupCapabilityValid = terminalCleanupStillAuthorized,
                        newGameDetected = newGameDetected,
                    )
                ) {
                    if (newGameDetected) {
                        log.info {
                            "RESULT_PAGE_DISMISSAL_CANCELLED reason=new-game-detected " +
                                "startWarCount=$startingWarCount currentWarCount=${WarEx.warCount}"
                        }
                    }
                    future.cancel(false)
                    gameEndTasks.remove(future)
                    return@scheduleWithFixedDelay
                }

                    // A queued Robot event is not proof the client accepted it.
                    // When Power.log still says inWar, require a fresh positive
                    // result-page observation before every click; never turn an
                    // UNKNOWN postcheck into a success or a speculative input.
                    val screenObservation = if (resultAlreadyObserved || number > 1) {
                        ScreenStateRecovery.observeResultScreenForRecovery()
                    } else ResultScreenObservation(null, captureAuthorized = false)
                    val visible = screenObservation.resultVisible
                    val maxAttempts = if (terminalCleanupStillAuthorized) 2 else 5
                    val clickNumber = clickAttempts.get()
                    when (ResultPageDismissalPolicy.decide(
                            inWar = WarEx.inWar,
                            resultPageVisible = visible,
                            attempt = number,
                            maxAttempts = maxAttempts,
                            clickAttempts = clickNumber,
                            terminalCleanupAuthorized = terminalCleanupStillAuthorized,
                            captureAuthorized = screenObservation.captureAuthorized,
                        )
                    ) {
                        ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED -> {
                            log.info { "RESULT_PAGE_DISMISSAL_CONFIRMED source=visible-screen-postcheck attempt=$number" }
                            if (terminalCleanupCapability != null &&
                                MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(terminalCleanupCapability)
                            ) {
                                val completed = MandatoryRankSurrenderGuard.confirmCompleted(
                                    "SCREEN_RESULT_DISMISSED",
                                    terminalCleanupCapability,
                                )
                                log.info {
                                    "RANK_SURRENDER_RECOVERY_COMPLETED evidence=SCREEN_RESULT_DISMISSED " +
                                        "requeueAllowed=$completed rankPreflightRequired=true"
                                }
                            }
                            future.cancel(false)
                            gameEndTasks.remove(future)
                            return@scheduleWithFixedDelay
                        }
                        ResultPageDismissalPolicy.Decision.BLOCKED_UNCONFIRMED_DURING_WAR -> {
                            log.warn {
                                "RESULT_PAGE_DISMISSAL_BLOCKED reason=result-page-not-confirmed-during-war " +
                                    "attempt=$number postcheck=UNKNOWN dispatch=false"
                            }
                            future.cancel(false)
                            gameEndTasks.remove(future)
                            return@scheduleWithFixedDelay
                        }
                        ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION -> {
                            log.info {
                                "RESULT_PAGE_DISMISSAL_WAIT reason=screen-transition-unconfirmed " +
                                    "probe=$number clickAttempts=$clickNumber postcheck=${visible ?: "UNKNOWN"} dispatch=false " +
                                    "captureAuthorized=${screenObservation.captureAuthorized} " +
                                    "terminalCleanupAuthorized=$terminalCleanupStillAuthorized"
                            }
                            return@scheduleWithFixedDelay
                        }
                        ResultPageDismissalPolicy.Decision.EXHAUSTED -> {
                            log.error { "RESULT_PAGE_DISMISSAL_FAILED reason=bounded-retries-exhausted attempt=$number confirmed=false" }
                            future.cancel(false)
                            gameEndTasks.remove(future)
                            return@scheduleWithFixedDelay
                        }
                        ResultPageDismissalPolicy.Decision.DISPATCH_CLICK -> {
                            if (terminalCleanupStillAuthorized && !screenObservation.captureAuthorized) {
                                log.warn {
                                    "RESULT_PAGE_DISMISSAL_BLOCKED reason=terminal-fallback-requires-current-authorized-capture " +
                                        "attempt=$number dispatch=false"
                                }
                                return@scheduleWithFixedDelay
                            }
                            clickAttempts.incrementAndGet()
                        }
                    }

                runCatching {
                    val clickNumber = clickAttempts.get()
                    log.info { "E2E恢复：尝试关闭旧结算页面 #$clickNumber" }
                    // Match the known-working Unity sequence: one centered
                    // click, one foreground-verified Enter fallback, then
                    // bounded clicks in the control. SendInput acceptance is
                    // never UI acceptance; only the fresh result postcheck
                    // below can finish this task.
                    when (staleResultInputForAttempt(clickNumber, maxAttempts)) {
                        ResultPageDismissalPolicy.Input.CENTER_CLICK -> {
                            log.info { "E2E恢复：结果页使用稳定中心点" }
                            val accepted = MouseUtil.leftButtonClickForRecovery(
                                GAME_END_CONTINUE_RECT.getCenterClickPos(),
                                terminalCleanupCapability = terminalCleanupCapability,
                            )
                            log.info {
                                "RESULT_PAGE_DISMISSAL_INPUT input=CENTER_CLICK dispatchAccepted=$accepted " +
                                    "acceptance=awaiting-current-client-postcheck"
                            }
                        }
                        ResultPageDismissalPolicy.Input.KEYBOARD_ENTER -> {
                            log.info { "E2E恢复：结果页中心点击未确认，使用一次前台 SendInput Enter 后备输入" }
                            val accepted = MouseUtil.pressEnterForRecovery(terminalCleanupCapability)
                            log.info {
                                "RESULT_PAGE_DISMISSAL_INPUT input=KEYBOARD_ENTER dispatchAccepted=$accepted " +
                                    "acceptance=awaiting-current-client-postcheck"
                            }
                        }
                        ResultPageDismissalPolicy.Input.RETRY_CLICK -> {
                            log.info { "E2E恢复：结果页继续控件有界重试" }
                            val accepted = MouseUtil.leftButtonClickForRecovery(
                                GAME_END_CONTINUE_RECT.getClickPos(),
                                terminalCleanupCapability = terminalCleanupCapability,
                            )
                            log.info {
                                "RESULT_PAGE_DISMISSAL_INPUT input=RETRY_CLICK dispatchAccepted=$accepted " +
                                    "acceptance=awaiting-current-client-postcheck"
                            }
                        }
                        null -> log.error {
                            "RESULT_PAGE_DISMISSAL_FAILED reason=invalid-attempt-target attempt=$number " +
                                "dispatch=false confirmed=false"
                        }
                    }
                }.onFailure { error ->
                    log.warn(error) { "E2E恢复：关闭旧结算页面尝试失败 #$number" }
                }
            },
            interval,
            interval,
            TimeUnit.MILLISECONDS,
        )
        gameEndTasks.add(future, GameEndTaskRegistry.Kind.TERMINAL_PAGE)
    }

    fun hidePlatformWindow() {
        val platformHWND = findPlatformHWND()
        if (platformHWND != null && !User32.INSTANCE.ShowWindow(platformHWND, WinUser.SW_MINIMIZE)) {
            log.warn { "最小化${PLATFORM_CN_NAME}窗口异常，错误代码：" + Kernel32.INSTANCE.GetLastError() }
        }
    }

    /**
     * The E2E runner must not use the project's legacy native process helpers.
     * Those helpers are useful in the normal desktop build, but a failed native
     * call can terminate the JVM without a Kotlin exception.  ProcessHandle is
     * part of the JDK and gives the test runner a safe, observable fallback.
     */
    private fun isE2ERun(): Boolean = RuntimeSafety.safeNative

    private fun processName(processPath: String): String = File(processPath).name

    private fun isProcessRunningSafely(processPath: String): Boolean {
        val expected = processName(processPath)
        val processHandleFound = ProcessHandle.allProcesses().use { processes ->
            processes.iterator().asSequence().any { handle ->
                handle.info().command().map { command ->
                    File(command).name.equals(expected, ignoreCase = true)
                }.orElse(false)
            }
        }
        if (processHandleFound) return true

        // ProcessHandle may hide another-elevation/session processes.
        // tasklist is a read-only Windows fallback that keeps the supervisor
        // from relaunching an already-visible game.
        return runCatching {
            val process = ProcessBuilder("tasklist", "/FI", "IMAGENAME eq $expected", "/NH")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(3, TimeUnit.SECONDS)
            val found = output.lineSequence().any { line ->
                line.trimStart().startsWith("\"$expected\"", ignoreCase = true) ||
                    line.trimStart().startsWith(expected, ignoreCase = true)
            }
            if (System.getProperty("hs.script.e2e") == "true") {
                log.info { "E2E_PROCESS_CHECK expected=$expected processHandle=$processHandleFound tasklistFound=$found" }
            }
            found
        }.getOrElse {
            if (System.getProperty("hs.script.e2e") == "true") {
                log.warn(it) { "E2E_PROCESS_CHECK_FAILED expected=$expected" }
            }
            false
        }
    }

    private fun terminateProcessSafely(processPath: String): Boolean {
        val expected = processName(processPath)
        var found = false
        ProcessHandle.allProcesses().use { processes ->
            processes.forEach { handle ->
                val command = handle.info().command().orElse("")
                if (File(command).name.equals(expected, ignoreCase = true)) {
                    found = true
                    log.info { "E2E_SAFE_TERMINATE target=$expected pid=${handle.pid()}" }
                    handle.destroy()
                }
            }
        }
        return found
    }

    fun isAliveOfGame(): Boolean = if (isE2ERun()) {
        isProcessRunningSafely(GAME_PROGRAM_NAME)
    } else {
        CSystemDll.INSTANCE.isProcessRunning(GAME_PROGRAM_NAME)
    }

    fun isAliveOfPlatform(): Boolean = if (isE2ERun()) {
        isProcessRunningSafely(PLATFORM_PROGRAM_NAME)
    } else {
        CSystemDll.INSTANCE.isProcessRunning(PLATFORM_PROGRAM_NAME)
    }

    /** Read-only process lineage used by the bounded lifecycle watchdog. */
    fun findGameProcessIdForDiagnostics(): Long? {
        val windowOwnerPid = runCatching {
            ScriptStatus.gameHWND
                ?.takeIf(::isVerifiedCurrentGameWindow)
                ?.let { windowProcessId(it).toLong() }
        }.getOrNull()
        // ProcessHandle can omit executable metadata for a live elevated game.
        // Enumerate all native process-name matches instead of calling
        // findProcessId(), which returns only one PID and cannot detect
        // ambiguity when multiple Hearthstone clients are present.
        val nativeProcessPids = findNativeGameProcessPidsForDiagnostics()
        return GameWindowDiscoveryPolicy.selectDiagnosticPid(
            windowOwnerPid = windowOwnerPid,
            nativeProcessPids = nativeProcessPids,
        )
    }

    private fun findNativeGameProcessPidsForDiagnostics(): List<Long>? = runCatching {
        val snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(
            Tlhelp32.TH32CS_SNAPPROCESS,
            WinDef.DWORD(0),
        ) ?: return@runCatching null
        if (Pointer.nativeValue(snapshot.pointer) == Pointer.nativeValue(WinBase.INVALID_HANDLE_VALUE.pointer)) {
            return@runCatching null
        }
        try {
            val pids = mutableListOf<Long>()
            val entry = Tlhelp32.PROCESSENTRY32()
            entry.dwSize = WinDef.DWORD(entry.size().toLong())
            var hasEntry = Kernel32.INSTANCE.Process32First(snapshot, entry)
            while (hasEntry) {
                val executableName = String(entry.szExeFile).substringBefore('\u0000')
                if (executableName.equals(GAME_PROGRAM_NAME, ignoreCase = true)) {
                    entry.th32ProcessID.toLong().takeIf { it > 0L }?.let(pids::add)
                }
                hasEntry = Kernel32.INSTANCE.Process32Next(snapshot, entry)
            }
            pids.distinct()
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot)
        }
    }.getOrNull()

    /** Return a PID only when the legacy injector's process-name target is unambiguous. */
    fun findUniqueGameProcessIdForNameBasedInjection(): Long? =
        GameWindowDiscoveryPolicy.selectUniqueProcessForNameBasedInjection(
            findNativeGameProcessPidsForDiagnostics(),
        )

    /** Read process creation time even when ProcessHandle cannot enumerate an elevated client. */
    fun findProcessStartedAtForDiagnostics(pid: Long): Long? {
        if (pid <= 0L || pid > Int.MAX_VALUE) return null
        val processHandleStart = runCatching {
            ProcessHandle.of(pid).orElse(null)?.info()?.startInstant()?.orElse(null)?.toEpochMilli()
        }.getOrNull()
        if (processHandleStart != null) {
            return GameWindowDiscoveryPolicy.selectProcessStartedAtMs(processHandleStart, null)
        }

        val nativeStart = runCatching {
            val process = Kernel32.INSTANCE.OpenProcess(
                WinNT.PROCESS_QUERY_LIMITED_INFORMATION,
                false,
                pid.toInt(),
            ) ?: return@runCatching null
            try {
                val created = WinBase.FILETIME()
                val exited = WinBase.FILETIME()
                val kernel = WinBase.FILETIME()
                val user = WinBase.FILETIME()
                if (Kernel32.INSTANCE.GetProcessTimes(process, created, exited, kernel, user)) {
                    created.toDate().time
                } else {
                    null
                }
            } finally {
                Kernel32.INSTANCE.CloseHandle(process)
            }
        }.getOrNull()
        return GameWindowDiscoveryPolicy.selectProcessStartedAtMs(null, nativeStart)
    }


    fun getGameProgramPermission(): ProgramPermissionEnum {
        if (!isAliveOfGame()) return ProgramPermissionEnum.NOT_RUNNING
        if (isE2ERun()) return ProgramPermissionEnum.NORMAL
        return if (CSystemDll.isProcessElevated(GAME_PROGRAM_NAME)) ProgramPermissionEnum.ADMINISTRATION else ProgramPermissionEnum.NORMAL
    }

    fun getPlatformProgramPermission(): ProgramPermissionEnum {
        if (!isAliveOfPlatform()) return ProgramPermissionEnum.NOT_RUNNING
        if (isE2ERun()) return ProgramPermissionEnum.NORMAL
        return if (CSystemDll.isProcessElevated(PLATFORM_PROGRAM_NAME)) ProgramPermissionEnum.ADMINISTRATION else ProgramPermissionEnum.NORMAL
    }

    fun findGameHWND(): WinDef.HWND? {
        // Find the visible title first.  The launcher can leave a valid
        // Hearthstone window with a class name different from UnityWndClass;
        // relying on the class caused the E2E runner to repeatedly relaunch a
        // game that was already on screen.
        val titleCandidates = listOfNotNull(
            SystemUtil.findHWND(null, GAME_CN_NAME),
            User32.INSTANCE.FindWindow(null, GAME_CN_NAME),
            SystemUtil.findHWND(null, GAME_US_NAME),
            User32.INSTANCE.FindWindow(null, GAME_US_NAME),
            SystemUtil.findHWND("UnityWndClass", GAME_CN_NAME),
            SystemUtil.findHWND("UnityWndClass", GAME_US_NAME),
        )
        val nativeCandidate = runCatching {
            CSystemDll.INSTANCE.findWindowsByProcessName(GAME_PROGRAM_NAME)
        }.getOrNull()
        // Keep the stable process-name fallback available in every mode, but
        // validate each HWND by its own owner PID instead of comparing against
        // an arbitrary first Hearthstone.exe returned by ProcessHandle.allProcesses().
        val hwnd = (titleCandidates + listOfNotNull(nativeCandidate))
            .firstOrNull(::isVerifiedCurrentGameWindow)
            ?: discoverGameWindowByOwner()
        val e2eRun = isE2ERun()
        val gameAliveWithoutWindow = e2eRun && hwnd == null && isAliveOfGame()
        if (e2eRun) logE2EWindowDiscovery(hwnd, gameAliveWithoutWindow)
        if (hwnd != null) return hwnd
        if (gameAliveWithoutWindow) {
            // The discovery state already recorded this fallback once at WARN
            // and every identical poll at DEBUG. Do not add a second INFO
            // line for the same condition on every input request.
            log.debug { "SAFE_NATIVE_INPUT_WINDOW fallback=screen-coordinates game=$GAME_PROGRAM_NAME" }
            return SAFE_INPUT_WINDOW
        }
        return null
    }

    /** EnumWindows fallback for startup windows whose localized title is not ready yet. */
    private fun discoverGameWindowByOwner(): WinDef.HWND? {
        val candidates = mutableListOf<GameWindowCandidate>()
        var order = 0
        val enumerated = runCatching {
            User32.INSTANCE.EnumWindows(WinUser.WNDENUMPROC { hwnd, _ ->
                val currentOrder = order++
                val ownerPid = windowProcessId(hwnd).toLong()
                val processName = runCatching {
                    ProcessHandle.of(ownerPid)
                        .filter { it.isAlive }
                        .flatMap { it.info().command() }
                        .map { File(it).name }
                        .orElse(null)
                }.getOrNull()
                val rect = WinDef.RECT()
                val hasRect = User32.INSTANCE.GetClientRect(hwnd, rect)
                val width = if (hasRect) (rect.right - rect.left).toLong().coerceAtLeast(0L) else 0L
                val height = if (hasRect) (rect.bottom - rect.top).toLong().coerceAtLeast(0L) else 0L
                val title = CharArray(512).also { User32.INSTANCE.GetWindowText(hwnd, it, it.size) }
                    .concatToString().trimEnd('\u0000').takeIf(String::isNotBlank)
                candidates += GameWindowCandidate(
                    handle = Pointer.nativeValue(hwnd.pointer),
                    ownerPid = ownerPid,
                    ownerProcessName = processName,
                    valid = User32.INSTANCE.IsWindow(hwnd),
                    visible = User32.INSTANCE.IsWindowVisible(hwnd),
                    owned = User32.INSTANCE.GetWindow(hwnd, WinDef.DWORD(WinUser.GW_OWNER.toLong())) != null,
                    title = title,
                    clientArea = width * height,
                    enumerationOrder = currentOrder,
                )
                true
            }, null)
        }.getOrDefault(false)
        if (!enumerated) return null
        val selected = GameWindowDiscoveryPolicy.select(
            candidates = candidates,
            expectedProcessName = GAME_PROGRAM_NAME,
            preferredTitles = setOf(GAME_CN_NAME, GAME_US_NAME),
        ) ?: return null
        return WinDef.HWND(Pointer(selected.handle))
            .takeIf(::isVerifiedCurrentGameWindow)
    }

    private fun logE2EWindowDiscovery(hwnd: WinDef.HWND?, gameAliveWithoutWindow: Boolean) {
        val processId = hwnd?.let(::windowProcessId)
        val state = when {
            hwnd != null -> E2EWindowDiscoveryLogGate.State.FOUND
            gameAliveWithoutWindow -> E2EWindowDiscoveryLogGate.State.FALLBACK_COORDINATES
            else -> E2EWindowDiscoveryLogGate.State.MISSING
        }
        val decision = e2eWindowDiscoveryLogGate.classify(state, hwnd?.toString(), processId)
        val message =
            "E2E_WINDOW_DISCOVERY state=$state handle=${hwnd ?: "null"} " +
                "pid=${processId ?: 0} process=$GAME_PROGRAM_NAME decision=$decision"
        when (decision) {
            E2EWindowDiscoveryLogGate.Decision.INFO_STATE_CHANGE -> log.info { message }
            E2EWindowDiscoveryLogGate.Decision.WARN_FAILURE_CHANGE -> log.warn { message }
            E2EWindowDiscoveryLogGate.Decision.DEBUG_DUPLICATE -> log.debug { "$message duplicate=true" }
        }
    }

    private fun windowProcessId(hwnd: WinDef.HWND): Int {
        val pid = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid)
        return pid.value
    }

    fun findPlatformHWND(): WinDef.HWND? {
        val configuredPlatformPath = ConfigUtil.getString(ConfigEnum.PLATFORM_PATH)
        val candidates = mutableListOf<PlatformWindowCandidate>()
        var enumerationOrder = 0
        val enumerationSucceeded = runCatching {
            User32.INSTANCE.EnumWindows(WinUser.WNDENUMPROC { hwnd, _ ->
                val order = enumerationOrder++
                val className = CharArray(256).also { User32.INSTANCE.GetClassName(hwnd, it, it.size) }
                    .concatToString().trimEnd('\u0000')
                val title = CharArray(512).also { User32.INSTANCE.GetWindowText(hwnd, it, it.size) }
                    .concatToString().trimEnd('\u0000').trim()
                if (setOf("Chrome_WidgetWin_0", "Chrome_WidgetWin_1")
                        .any { it.equals(className, ignoreCase = true) } &&
                    setOf(PLATFORM_CN_NAME, PLATFORM_US_NAME).any { it.equals(title, ignoreCase = true) }
                ) {
                    val ownerPid = windowProcessId(hwnd)
                    val ownerImagePath = Win32ProcessImagePath.query(ownerPid)
                    val clientRect = WinDef.RECT()
                    val hasClientRect = User32.INSTANCE.GetClientRect(hwnd, clientRect)
                    candidates += PlatformWindowCandidate(
                        handle = Pointer.nativeValue(hwnd.pointer),
                        className = className,
                        title = title,
                        ownerImageCandidate = BattleNetOwnerIdentityPolicy.isImageCandidate(
                            configuredPlatformPath,
                            ownerImagePath,
                        ),
                        valid = User32.INSTANCE.IsWindow(hwnd),
                        visible = User32.INSTANCE.IsWindowVisible(hwnd),
                        clientWidth = if (hasClientRect) clientRect.right - clientRect.left else 0,
                        clientHeight = if (hasClientRect) clientRect.bottom - clientRect.top else 0,
                        enumerationOrder = order,
                    )
                }
                true
            }, null)
        }.getOrDefault(false)
        if (!enumerationSucceeded) return null

        val selected = PlatformWindowDiscoveryPolicy.select(
            candidates = candidates,
            expectedClassNames = setOf("Chrome_WidgetWin_0", "Chrome_WidgetWin_1"),
            expectedTitles = setOf(PLATFORM_CN_NAME, PLATFORM_US_NAME),
        ) ?: return null
        val hwnd = WinDef.HWND(Pointer(selected.handle))
        log.debug {
            "PLATFORM_WINDOW_DISCOVERY selected hwnd=$hwnd title=${selected.title} " +
                "client=${selected.clientWidth}x${selected.clientHeight} candidates=${candidates.size}"
        }
        return hwnd.takeIf { User32.INSTANCE.IsWindow(it) && User32.INSTANCE.IsWindowVisible(it) }
    }

    fun findLoginPlatformHWND(): WinDef.HWND? = SystemUtil.findHWND("Qt5151QWindowIcon", PLATFORM_LOGIN_CN_NAME)

    /**
     * 更新游戏窗口信息
     */
    fun updateGameRect(gameHWND: WinDef.HWND? = ScriptStatus.gameHWND) {
        if (isE2ERun()) {
            val screenSize = java.awt.Toolkit.getDefaultToolkit().screenSize
            ScriptStatus.GAME_RECT.apply {
                left = 0
                top = 0
                right = screenSize.width
                bottom = screenSize.height
            }
            val rect = ScriptStatus.GAME_RECT
            log.info {
                "E2E_WINDOW_RECT native-check-skipped hwnd=$gameHWND " +
                    "client=(${rect.left},${rect.top})-(${rect.right},${rect.bottom}) " +
                    "size=${rect.width()}x${rect.height()}"
            }
            return
        }
        SystemUtil.updateRECT(gameHWND, ScriptStatus.GAME_RECT)
        if (System.getProperty("hs.script.e2e") == "true") {
            val rect = ScriptStatus.GAME_RECT
            log.info {
                "E2E_WINDOW_RECT hwnd=$gameHWND valid=${gameHWND != null && User32.INSTANCE.IsWindow(gameHWND)} " +
                    "client=(${rect.left},${rect.top})-(${rect.right},${rect.bottom}) " +
                    "size=${rect.width()}x${rect.height()}"
            }
        }
        if (ConfigUtil.getBoolean(ConfigEnum.AUTO_REFRESH_GAME_TASK) && User32.INSTANCE.IsWindow(gameHWND)) {
            ScriptStatus.GAME_RECT.run {
                val height = bottom - top
                val width = right - left
                val ratio = width.toDouble() / height
                val minRatio = GameRationConst.GAME_WINDOW_MIN_WIDTH_HEIGHT_RATIO
                val maxRatio = GameRationConst.GAME_WINDOW_MAX_WIDTH_HEIGHT_RATIO
                if (ratio !in minRatio..maxRatio) {
                    legalizationGameWindowSize(height, gameHWND)
                    SystemUtil.updateRECT(gameHWND, ScriptStatus.GAME_RECT)
                    log.warn { "${GAME_CN_NAME}窗口宽高比不在[${minRatio},${maxRatio}]之间，已自动调整为[${ScriptStatus.GAME_RECT.width()},${ScriptStatus.GAME_RECT.height()}]" }
                }
            }
        }
//        println("left:${ScriptStatus.GAME_RECT.left}, right:${ScriptStatus.GAME_RECT.right}, top:${ScriptStatus.GAME_RECT.top}, bottom:${ScriptStatus.GAME_RECT.bottom}")
    }

    fun legalizationGameWindowSize(newHeight: Int, gameHWND: WinDef.HWND? = ScriptStatus.gameHWND) {
        val newWidth =
            (newHeight * GameRationConst.GAME_WINDOW_APPROPRIATE_WIDTH_HEIGHT_RATIO).toInt()
        log.info { "newWidth:$newWidth , newHeight:$newHeight" }
        User32.INSTANCE.SetWindowPos(
            gameHWND,
            null,
            0,
            0,
            newWidth,
            newHeight,
            SWP_NOMOVE or SWP_NOZORDER
        )
    }

    /**
     * 通过此方式停止的游戏，screen.log监听器可能无法监测到游戏被关闭
     */
    fun killGame(sync: Boolean = false) {
        val exec = {
            if (isE2ERun()) {
                val terminated = terminateProcessSafely(GAME_PROGRAM_NAME)
                if (terminated) {
                    delay(RandomUtil.getInteractionDelay(1000))
                    log.info { "${GAME_CN_NAME}已关闭（E2E安全进程路径）" }
                } else {
                    log.info { "${GAME_CN_NAME}不在运行（E2E安全进程路径）" }
                }
            } else if (isAliveOfGame()) {
                kotlin.runCatching {
                    for (i in 0 until 2) {
                        CSystemDll.INSTANCE.quitWindow(ScriptStatus.gameHWND)
                        delay(RandomUtil.getInteractionDelay(2000))
                        if (!isAliveOfGame()) return@runCatching
                    }
                    for (i in 0 until 2) {
                        CSystemDll.INSTANCE.killProcessByName(GAME_PROGRAM_NAME)
                        delay(RandomUtil.getInteractionDelay(2000))
                        if (!isAliveOfGame()) return@runCatching
                    }
                }.onSuccess {
                    if (isAliveOfGame()) {
                        log.error { "${GAME_CN_NAME}关闭失败" }
                    } else {
                        log.info { "${GAME_CN_NAME}已关闭" }
                    }
                }.onFailure {
                    log.error(it) { "关闭${GAME_CN_NAME}异常" }
                }
            } else {
                log.info { "${GAME_CN_NAME}不在运行" }
            }
        }
        if (sync) {
            exec()
        } else {
            exec.goWithResult()
        }
    }

    fun killPlatform() {
        if (isE2ERun()) {
            if (!mayTerminatePlatformAfterGameLaunch()) return
            val terminated = terminateProcessSafely(PLATFORM_PROGRAM_NAME)
            log.info {
                if (terminated) "${PLATFORM_CN_NAME}已关闭（E2E安全进程路径）"
                else "${PLATFORM_CN_NAME}不在运行（E2E安全进程路径）"
            }
            return
        }
        val platformHWND: WinDef.HWND? = findPlatformHWND()
        val loginPlatformHWND: WinDef.HWND? = findLoginPlatformHWND()
        if (platformHWND != null || loginPlatformHWND != null) {
            CSystemDll.INSTANCE.quitWindow(platformHWND)
            CSystemDll.INSTANCE.quitWindow(loginPlatformHWND)
            log.info { "${PLATFORM_CN_NAME}已关闭" }
        } else {
            log.info { "${PLATFORM_CN_NAME}不在运行" }
        }
    }

    fun killLoginPlatform() {
        if (isE2ERun()) {
            if (!mayTerminatePlatformAfterGameLaunch()) return
            val terminated = terminateProcessSafely(PLATFORM_PROGRAM_NAME)
            log.info {
                if (terminated) "${PLATFORM_LOGIN_CN_NAME}已关闭（E2E安全进程路径）"
                else "${PLATFORM_LOGIN_CN_NAME}不在运行（E2E安全进程路径）"
            }
            return
        }
        val loginPlatformHWND: WinDef.HWND? = findLoginPlatformHWND()
        if (loginPlatformHWND == null) {
            log.info { "${PLATFORM_LOGIN_CN_NAME}不在运行" }
        } else {
            CSystemDll.INSTANCE.quitWindow(loginPlatformHWND)
            log.info { "${PLATFORM_LOGIN_CN_NAME}已关闭" }
        }
    }

    /**
     * E2E launch recovery must never tear down Battle.net while it is still
     * needed to start Hearthstone. Only allow platform cleanup after both the
     * Hearthstone process and its window have been observed.
     */
    private fun mayTerminatePlatformAfterGameLaunch(): Boolean {
        val gameAlive = isAliveOfGame()
        val gameWindow = findGameHWND()
        if (!gameAlive || gameWindow == null) {
            log.warn {
                "E2E_PLATFORM_TERMINATE_SKIPPED " +
                    "reason=hearthstone-not-confirmed-running processAlive=$gameAlive windowFound=${gameWindow != null}"
            }
            return false
        }
        return true
    }

    private fun cancelGameEndTask() {
        gameEndTasks.cancelAll()
    }

    /**
     * 获取游戏最新日志目录
     */
    fun getLatestLogDir(): File? {
        val files: Array<File> = getAllLogDir()
        Arrays.sort(files, Comparator.comparing { obj: File -> obj.name })
        return files.lastOrNull()
    }

    /**
     * 获取游戏所有日志目录
     */
    fun getAllLogDir(): Array<File> {
        val gameLogDir = Path.of(ConfigUtil.getString(ConfigEnum.GAME_PATH), GAME_LOG_DIR).toFile()
        return if (gameLogDir.exists() && gameLogDir.isDirectory) gameLogDir.listFiles() ?: arrayOf() else arrayOf()
    }

}
