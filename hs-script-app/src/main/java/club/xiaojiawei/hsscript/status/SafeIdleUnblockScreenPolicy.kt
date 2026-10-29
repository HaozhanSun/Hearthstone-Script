package club.xiaojiawei.hsscript.status

/** Screens eligible for the idle-only top-left unblock ladder after fresh structural proof. */
internal object SafeIdleUnblockScreenPolicy {
    fun matches(
        screenKind: String?,
        screenMode: String?,
        hubModeChooserVisual: Boolean,
        eventQuestOverlayVisual: Boolean,
    ): Boolean = when (screenKind) {
        "TOURNAMENT" -> screenMode == "HUB" && hubModeChooserVisual
        "EVENT_QUEST_OVERLAY" -> eventQuestOverlayVisual
        else -> false
    }
}
