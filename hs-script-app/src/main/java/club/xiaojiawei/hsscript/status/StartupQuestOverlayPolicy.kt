package club.xiaojiawei.hsscript.status

/** A click is only a dispatch attempt; entry to HUB requires a fresh trusted frame. */
internal object StartupQuestOverlayPolicy {
    const val MAX_DISMISS_DISPATCHES = 2

    enum class Observation { QUEST_OVERLAY, HUB, UNKNOWN }
    enum class Action { DISMISS_OVERLAY, ENTER_HUB, WAIT_FOR_TRUSTED_CAPTURE, BLOCK_UNTRUSTED, EXHAUSTED }

    fun decide(
        observation: Observation,
        captureTrusted: Boolean,
        dismissDispatches: Int,
    ): Action {
        if (!captureTrusted) return Action.BLOCK_UNTRUSTED
        return when (observation) {
            Observation.HUB -> Action.ENTER_HUB
            Observation.UNKNOWN -> Action.WAIT_FOR_TRUSTED_CAPTURE
            Observation.QUEST_OVERLAY ->
                if (dismissDispatches < MAX_DISMISS_DISPATCHES) Action.DISMISS_OVERLAY else Action.EXHAUSTED
        }
    }
}
