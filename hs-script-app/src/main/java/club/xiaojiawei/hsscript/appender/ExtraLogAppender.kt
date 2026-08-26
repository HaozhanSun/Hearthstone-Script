package club.xiaojiawei.hsscript.appender

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import java.util.concurrent.ArrayBlockingQueue

/**
 * 额外的日志Appender
 *
 * @author 肖嘉威 xjw580@qq.com
 * @date 2022/9/28 上午10:11
 */
class ExtraLogAppender : UnsynchronizedAppenderBase<ILoggingEvent>() {

    companion object {

        val logQueue = ArrayBlockingQueue<ILoggingEvent>(100)
        private val uiMessageLock = Any()
        private var lastUiMessage = ""
        private var lastUiMessageAt = 0L
        private var lastUiPhaseMessage = ""

    }

    override fun append(event: ILoggingEvent) {
        if (event.level.levelInt >= Level.INFO_INT && shouldShowInUi(event)) {
            // The file appender receives every event.  The UI queue is only a
            // concise operational feed; dropping the oldest item is safer
            // than throwing when a noisy card/parser burst fills the queue.
            if (!logQueue.offer(event)) {
                logQueue.poll()
                logQueue.offer(event)
            }
        }
    }

    private fun shouldShowInUi(event: ILoggingEvent): Boolean {
        val message = event.formattedMessage ?: ""
        if (message.contains("E2E_INPUT_")) return false
        if (message.contains("行为类-解析卡牌") && event.level.levelInt <= Level.WARN_INT) return false

        // A phase handler can process many Power.log batches while the game
        // remains in one phase. The detailed batches stay in hs_script.log;
        // the UI only needs the first phase entry, then the next transition.
        if (message.startsWith("当前处于：")) {
            synchronized(uiMessageLock) {
                if (message == lastUiPhaseMessage) return false
                lastUiPhaseMessage = message
            }
        } else if (message.contains("已重置游戏状态")) {
            synchronized(uiMessageLock) {
                lastUiPhaseMessage = ""
            }
        }

        if (event.level.levelInt >= Level.ERROR_INT) return true
        if (event.level.levelInt >= Level.WARN_INT) return true

        val importantMarkers = arrayOf(
            "当前处于", "当前模式", "开始匹配", "匹配失败", "已完成第", "已重置游戏状态",
            "GAME_RESULT_SCREENSHOT", "MULLIGAN_SCREENSHOT", "E2E_", "RANK_POLICY", "RANK_OCR", "收到换牌输入", "换牌选择",
            "自动换牌动作已提交", "自动换牌线程结束", "换牌阶段确认完成", "脚本",
            "触发投降", "策略请求投降", "投降", "执行出牌策略", "阶段转换已确认"
        )
        val noisyMarkers = arrayOf(
            "E2E_INPUT", "点击", "鼠标", "Area", "行为类-解析卡牌", "等待", "水晶数",
            "调用卡组策略", "卡牌代码", "卡牌描述", "忽略对手换牌阶段事件",
            "换牌阶段事件", "执行换牌策略完毕", "换掉起始卡牌"
        )
        val important = importantMarkers.any(message::contains) && noisyMarkers.none(message::contains)
        if (!important) return false

        // Phase and strategy callbacks can emit the same INFO line several
        // times while one Power.log batch is being assembled. Keep the first
        // copy in the UI and leave every copy in hs_script.log.
        val now = System.currentTimeMillis()
        synchronized(uiMessageLock) {
            if (message == lastUiMessage && now - lastUiMessageAt < 5_000L) return false
            lastUiMessage = message
            lastUiMessageAt = now
        }
        return true
    }

}
