package club.xiaojiawei.hsscript.strategy.mode

/** Pure admission policy for the exact Hearthstone start-game error dialog. */
internal object MatchmakingDialogRecoveryPolicy {
    const val MAX_ATTEMPTS = 20

    enum class Probe { ERROR_DIALOG_VISIBLE, NO_ERROR_DIALOG, UNKNOWN }
    enum class Action { CLICK_CONFIRM, WAIT_AND_RETRY, CONFIRMED_DISMISSED, STOP_NOT_PRESENT, CANCEL, EXHAUSTED }

    data class Context(
        val paused: Boolean,
        val tournamentMode: Boolean,
        val gameStarted: Boolean,
    )

    data class Decision(val action: Action, val reason: String)

    fun decide(context: Context, probe: Probe, completedAttempts: Int, priorClickSent: Boolean): Decision {
        if (context.paused) return Decision(Action.CANCEL, "paused")
        if (!context.tournamentMode) return Decision(Action.CANCEL, "mode-changed")
        if (context.gameStarted) return Decision(Action.CANCEL, "game-started")
        if (probe == Probe.NO_ERROR_DIALOG) {
            return if (priorClickSent) {
                Decision(Action.CONFIRMED_DISMISSED, "post-click-dialog-disappeared")
            } else {
                Decision(Action.STOP_NOT_PRESENT, "dialog-not-present")
            }
        }
        if (completedAttempts >= MAX_ATTEMPTS) return Decision(Action.EXHAUSTED, "attempt-limit")
        return when (probe) {
            Probe.ERROR_DIALOG_VISIBLE -> Decision(Action.CLICK_CONFIRM, "exact-error-dialog-visible")
            Probe.UNKNOWN -> Decision(Action.WAIT_AND_RETRY, "dialog-state-unverified")
            Probe.NO_ERROR_DIALOG -> error("handled above")
        }
    }
}

/** Fail-closed text contract; generic errors and other dialogs are not clickable evidence. */
internal object StartGameErrorDialogClassifier {
    fun classify(title: String, body: String, confirm: String): MatchmakingDialogRecoveryPolicy.Probe {
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
