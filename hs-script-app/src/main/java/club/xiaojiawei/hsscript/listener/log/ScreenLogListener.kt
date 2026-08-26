package club.xiaojiawei.hsscript.listener.log

import club.xiaojiawei.hsscript.consts.GAME_MODE_LOG_NAME
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.core.Core
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.Mode
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.util.isFalse
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 游戏界面监听器
 * @author 肖嘉威
 * @date 2023/7/5 14:55
 */
object ScreenLogListener :
    AbstractLogListener(GAME_MODE_LOG_NAME, 0, 50L, TimeUnit.MILLISECONDS) {

    private const val CURR_MODE_STR = "currMode="

    private const val CURR_MODE_STR_LEN = CURR_MODE_STR.length

    private const val NEXT_MODE_STR = "nextMode="

    private const val NEXT_MODE_STR_LEN = NEXT_MODE_STR.length

    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSS")

    override fun dealOldLog() {
        var line: String?
        var index = 0
        var finalCurrMode: ModeEnum? = null
        var finalNextMode: ModeEnum? = null
        while (true) {
            line = logFile!!.readLine()
            if (line == null) break
            if ((line.indexOf(CURR_MODE_STR).also { index = it }) != -1) {
                finalCurrMode = ModeEnum.fromString(line.substring(index + CURR_MODE_STR_LEN))
                finalNextMode = null
            } else if ((line.indexOf(NEXT_MODE_STR).also { index = it }) != -1) {
                val blankIndex = line.indexOf(" ", index)
                finalNextMode = ModeEnum.fromString(line.substring(index + NEXT_MODE_STR_LEN, blankIndex))
                finalCurrMode = null
            }
        }
        if (ScreenLogStartupPolicy.shouldRestoreHistoricalState()) {
            // This is reserved for a caller that has independently verified
            // that the log and the current Hearthstone process belong to the
            // same live session.  Normal startup must not use it.
            finalCurrMode?.let { Mode.currMode = it } ?: let {
                finalNextMode?.let { Mode.nextMode = it }
            }
            log.warn {
                "已显式启用LoadingScreen历史模式恢复：调用方必须保证日志与当前进程一致 " +
                    "historicalCurr=${finalCurrMode ?: "NONE"} " +
                    "historicalNext=${finalNextMode ?: "NONE"}"
            }
        } else {
            // LoadingScreen.log is append-only for the Hearthstone process.
            // Its last historical scene is not proof that the current pixels
            // are in that scene. The live listener will accept only a fresh
            // scene event.
            if (shouldPreserveActiveGameMode(WarEx.inWar)) {
                // Power.log is the authoritative source for an already active
                // game.  During startup recovery it may finish after this
                // listener has read LoadingScreen.log; resetting Mode here
                // would erase the recovered GAMEPLAY state and leave MCTS
                // permanently gated by mode=NONE.
                Mode.recover(ModeEnum.GAMEPLAY, "active-power-log-game", enterStrategy = false)
                log.info {
                    "忽略LoadingScreen历史模式状态：保留Power.log确认的活跃对局 " +
                        "historicalCurr=${finalCurrMode ?: "NONE"} " +
                        "historicalNext=${finalNextMode ?: "NONE"}"
                }
            } else {
                Mode.reset()
                log.info {
                    "忽略LoadingScreen历史模式状态：避免将旧日志误当成当前界面 " +
                        "historicalCurr=${finalCurrMode ?: "NONE"} " +
                        "historicalNext=${finalNextMode ?: "NONE"}"
                }
            }
        }

    }

    internal fun shouldPreserveActiveGameMode(inWar: Boolean): Boolean = inWar

    private var dealing = false

    fun resetDealing() {
        dealing = false
    }


    override fun dealNewLog() {
        if (dealing) return
        dealing = true
        logFile?.let {
            var line: String?
            while (!PauseStatus.isPause && WorkTimeListener.working) {
                line = it.readLine()
                if (line.isNullOrEmpty()) {
                    break
                }
                resolveLog(line)
            }
        }
        dealing = false
    }

    private fun resolveLog(line: String?) {
        line?.let { l ->
            var index: Int
            if ((l.indexOf(CURR_MODE_STR).also { index = it }) != -1) {
                runCatching {
                    val logTime = LocalTime.parse(l.substring(2, 18), formatter)
                    val nowTime = LocalTime.now()
                    val logDiffTime =
                        Duration.between(logTime, nowTime).toMillis()
                    if (logDiffTime > 1500) {
                        log.warn { "${GAME_MODE_LOG_NAME}日志实际打印时间与输出时间相差过大，diff:${logDiffTime}，log:${l}，logTime:${logTime}，nowTime:${nowTime}" }
                    }
                }.onFailure {
                    log.warn { "日志打印时间解析出错" }
                }
                Mode.currMode = ModeEnum.fromString(l.substring(index + CURR_MODE_STR_LEN))
            } else if ((l.indexOf(NEXT_MODE_STR).also { index = it }) != -1) {
                val blankIndex = l.indexOf(" ", index)
                Mode.nextMode = ModeEnum.fromString(l.substring(index + NEXT_MODE_STR_LEN, blankIndex))
            } else if (l.contains("OnDestroy()")) {
                Thread.sleep(2000)
                GameUtil.isAliveOfGame().isFalse {
                    log.info { "检测到游戏关闭，准备重启游戏" }
                    Core.restart()
                }
            }
        }
    }
}
