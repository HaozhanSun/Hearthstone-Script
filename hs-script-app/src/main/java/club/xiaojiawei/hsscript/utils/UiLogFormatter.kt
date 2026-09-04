package club.xiaojiawei.hsscript.utils

/**
 * Converts the operational log feed into short messages suitable for the
 * small, always-visible application window. The file appenders still receive
 * the original message, so this class deliberately only changes presentation.
 */
object UiLogFormatter {

    private val whitespace = Regex("\\s+")
    private val keyValue = Regex("(?:^|\\s)([A-Za-z][A-Za-z0-9_]*)=([^\\s]+)")

    /**
     * Machine-readable post-mortem diagnostics that belong in hs_script.log,
     * not in the compact operational feed shown in the main window.
     */
    private val hiddenUiPrefixes = setOf(
        "PERSISTENT_STREAK_GUARD_",
        // These are machine-readable E2E safety diagnostics. Keep them in
        // the file/console evidence, but do not flood the compact user feed.
        "E2E_NATIVE_SKIP",
        "E2E_SAFE_TERMINATE",
    )

    fun isHiddenFromUi(message: String?): Boolean =
        message?.trim()?.let { raw -> hiddenUiPrefixes.any(raw::startsWith) } == true

    fun format(message: String?): String {
        val raw = message?.trim().orEmpty()
        if (raw.isEmpty()) return ""

        return when {
            raw.startsWith("MULLIGAN_SCREENSHOT_FAILED") ->
                "换牌截图保存失败 · ${gameAndStage(raw)}"
            raw.startsWith("MULLIGAN_SCREENSHOT") ->
                "换牌截图已保存 · ${gameAndStage(raw)}"
            raw.startsWith("GAME_RESULT_SCREENSHOT_FAILED") ->
                "结算截图保存失败 · ${gameNumber(raw)}"
            raw.startsWith("GAME_RESULT_SCREENSHOT") ->
                "结算截图已保存 · ${gameAndOutcome(raw)}"
            raw.startsWith("RANK_OCR_EVIDENCE") ->
                "等级截图已保存 · 识别结果待确认"
            raw.startsWith("RANK_OCR") -> formatRankOcr(raw)
            raw.startsWith("RANK_POLICY") -> formatRankPolicy(raw)
            raw.startsWith("MCTS_NEW_DECK_CARD") -> formatDeckCardSummary(raw)
            raw.startsWith("SCREEN_RECOVERY") -> formatRecovery(raw)
            raw.startsWith("UNKNOWN_STATE_SCREENSHOT") -> "未知画面截图已保存"
            raw.startsWith("DEBUG_SCREENSHOT") -> "调试截图已保存"
            raw.startsWith("E2E_") -> "端到端诊断 · ${raw.substringBefore(' ')}"
            else -> simplify(raw)
        }
    }

    private fun formatRankOcr(raw: String): String {
        val tier = tierLabel(value(raw, "tier"))
        val rank = value(raw, "rank")?.takeUnless { it.isUnknownValue() }
        val text = value(raw, "text")?.takeUnless { it.isUnknownValue() }
        val recognized = when {
            tier != null && rank != null -> "$tier${rank}级"
            tier != null -> "$tier · 等级待确认"
            else -> "段位等级待确认"
        }
        return listOfNotNull(
            "等级识别",
            text?.let { "OCR=$it" },
            recognized,
        ).joinToString(" · ")
    }

    private fun formatRankPolicy(raw: String): String {
        val rank = value(raw, "rank")
        val tier = tierLabel(value(raw, "tier"))
        val decision = when {
            raw.contains("TRIGGER", ignoreCase = true) -> "触发投降"
            raw.contains("CONTINUE", ignoreCase = true) -> "继续运行"
            else -> "已评估"
        }
        val level = if (tier != null && rank != null) " · $tier${rank}级" else ""
        return "等级策略$level · $decision"
    }

    private fun formatDeckCardSummary(raw: String): String {
        val count = value(raw, "count")
        val tuning = value(raw, "manualTuning")
        val tuningText = when (tuning?.uppercase()) {
            "UNSET" -> "手动调优未设置"
            "SET" -> "已启用手动调优"
            else -> null
        }
        return listOfNotNull("MCTS · 新牌组已识别${count?.let { " · ${it}张牌" }}", tuningText).joinToString(" · ")
    }

    private fun formatRecovery(raw: String): String {
        val state = value(raw, "state")
        return if (state.isNullOrBlank()) "屏幕恢复 · 已触发" else "屏幕恢复 · 当前状态 $state"
    }

    private fun gameAndStage(raw: String): String {
        val game = gameNumber(raw)
        val stage = when (value(raw, "stage")) {
            "pre-confirm" -> "确认前"
            "post-confirm" -> "确认后"
            else -> value(raw, "stage")
        }
        return listOfNotNull(game.takeIf { it.isNotBlank() }, stage).joinToString(" · ")
    }

    private fun gameAndOutcome(raw: String): String {
        val game = gameNumber(raw)
        val outcome = value(raw, "outcome")
        return listOfNotNull(game.takeIf { it.isNotBlank() }, outcome).joinToString(" · ")
    }

    private fun gameNumber(raw: String): String = value(raw, "game")?.let { "第${it}局" }.orEmpty()

    private fun tierLabel(value: String?): String? = when (value?.uppercase()) {
        "BRONZE" -> "青铜"
        "SILVER" -> "白银"
        "GOLD" -> "黄金"
        "PLATINUM" -> "白金"
        "DIAMOND" -> "钻石"
        "LEGEND" -> "传说"
        "UNKNOWN", "<BLANK>", "<EMPTY>" -> null
        else -> value
    }

    private fun value(raw: String, name: String): String? =
        keyValue.findAll(raw).firstOrNull { it.groupValues[1] == name }?.groupValues?.get(2)

    private fun String.isUnknownValue(): Boolean =
        isBlank() || equals("UNKNOWN", ignoreCase = true) ||
            equals("<BLANK>", ignoreCase = true) || equals("<EMPTY>", ignoreCase = true)

    private fun simplify(raw: String): String {
        var result = raw
            .replace(Regex("\\s+(?:path|screenshotLink|link)=.+$"), " · 详细路径见脚本日志")
            .replace(Regex("\\s+regions=\\S+"), "")
            .replace(Regex("\\s+cards=\\S+"), "")
            .replace(whitespace, " ")
            .trim()

        // Keep ordinary Chinese messages untouched, but make the remaining
        // diagnostic key/value feed less cryptic when it is shown in the UI.
        if (result.contains('=') && result.length < 180) {
            result = result.replace(Regex("\\b([A-Za-z][A-Za-z0-9_]*)="), "$1：")
        }
        return result
    }
}
