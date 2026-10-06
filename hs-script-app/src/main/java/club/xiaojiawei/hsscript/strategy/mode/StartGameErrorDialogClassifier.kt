package club.xiaojiawei.hsscript.strategy.mode

/** App-layer OCR text contract; provider output and visual modal evidence remain in hs-script-app. */
internal object StartGameErrorDialogClassifier {
    fun classifyModalText(
        text: String,
        confidence: Double? = null,
        modalVisible: Boolean = false,
    ): MatchmakingDialogRecoveryPolicy.Probe {
        if (confidence != null && (!confidence.isFinite() ||
                confidence < MatchmakingDialogRecoveryPolicy.MIN_OCR_CONFIDENCE)
        ) {
            return MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN
        }
        val normalized = normalize(text)
        val hasErrorHeading = normalized.contains("发生错误")
        val hasConfirm = normalized.contains("确定")
        val legacyStartFailure = normalized.contains("开始游戏") && normalized.contains("几分钟")
        val opponentStartFailure = normalized.contains("对手无法连接") &&
            normalized.contains("游戏无法继续") &&
            (normalized.contains("再试一次") || normalized.contains("请再试"))
        if (modalVisible && hasErrorHeading && hasConfirm && (legacyStartFailure || opponentStartFailure)) {
            return MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE
        }

        val partialError = hasErrorHeading || normalized.contains("开始游戏") ||
            normalized.contains("几分钟") || normalized.contains("对手无法连接") ||
            normalized.contains("游戏无法继续") || normalized.contains("无法重新连接") ||
            normalized.contains("请再试") || normalized.contains("请重试")
        return when {
            partialError || normalized.isBlank() -> MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN
            !modalVisible -> MatchmakingDialogRecoveryPolicy.Probe.NO_ERROR_DIALOG
            else -> MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN
        }
    }

    fun classify(
        title: String,
        body: String,
        confirm: String,
        confidences: List<Double?> = emptyList(),
    ): MatchmakingDialogRecoveryPolicy.Probe {
        if (confidences.any { confidence ->
                confidence != null && (!confidence.isFinite() || confidence < MatchmakingDialogRecoveryPolicy.MIN_OCR_CONFIDENCE)
            }
        ) {
            return MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN
        }
        val normalizedTitle = normalize(title)
        val normalizedBody = normalize(body)
        val normalizedConfirm = normalize(confirm)
        if (normalizedTitle.contains("发生错误") &&
            normalizedBody.contains("开始游戏") &&
            normalizedBody.contains("几分钟") &&
            normalizedConfirm.contains("确定")
        ) {
            return MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE
        }
        val combined = normalize(title + body + confirm)
        val partialTarget = normalizedTitle.contains("发生错误") ||
            combined.contains("开始游戏") || combined.contains("几分钟")
        return when {
            partialTarget -> MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN
            title.isNotBlank() && body.isNotBlank() && confirm.isNotBlank() ->
                MatchmakingDialogRecoveryPolicy.Probe.NO_ERROR_DIALOG
            else -> MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN
        }
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), "")
}
