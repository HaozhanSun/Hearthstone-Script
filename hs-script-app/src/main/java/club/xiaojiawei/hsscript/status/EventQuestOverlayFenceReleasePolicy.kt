package club.xiaojiawei.hsscript.status

/** A dismissed Event Quest fence is released only by a trusted, safe destination frame. */
internal object EventQuestOverlayFenceReleasePolicy {
    fun allows(
        captureTrusted: Boolean,
        questOverlayVisible: Boolean,
        exactHubModeChooser: Boolean,
        deckReadyVisible: Boolean,
        freshEmptySessionLoginTransition: Boolean,
        activeGame: Boolean,
        activeQueue: Boolean,
        reconnectDialog: Boolean,
        terminalOrResult: Boolean,
        mandatoryRankGuard: Boolean,
        working: Boolean,
        paused: Boolean,
    ): Boolean = captureTrusted && !questOverlayVisible &&
        (exactHubModeChooser || deckReadyVisible || freshEmptySessionLoginTransition) &&
        !activeGame && !activeQueue && !reconnectDialog && !terminalOrResult &&
        !mandatoryRankGuard && working && !paused
}
