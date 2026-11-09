package club.xiaojiawei.hsscript.status

/** A dismissed Event Quest fence is released only by a trusted, safe destination frame. */
internal object EventQuestOverlayFenceReleasePolicy {
    fun allows(
        captureTrusted: Boolean,
        questOverlayVisible: Boolean,
        exactHubModeChooser: Boolean,
        deckReadyVisible: Boolean,
        freshEmptySessionLoginTransition: Boolean,
        activeQueueVisual: Boolean = false,
        activeGameSessionReady: Boolean = false,
        activeGame: Boolean,
        activeQueue: Boolean,
        reconnectDialog: Boolean,
        terminalOrResult: Boolean,
        mandatoryRankGuard: Boolean,
        working: Boolean,
        paused: Boolean,
    ): Boolean {
        val safeMenuTransition = (exactHubModeChooser || deckReadyVisible || freshEmptySessionLoginTransition) &&
            !activeGame && !activeQueue
        // A queue or gameplay transition is safe to release only when the
        // fresh authorized frame proves the quest panel is gone and provides
        // corresponding live evidence. Releasing the fence dispatches no
        // input; it prevents the old startup-modal fence from blocking the
        // newly active queue/game indefinitely.
        val safeQueueTransition = activeQueue && activeQueueVisual
        val safeGameTransition = activeGame && activeGameSessionReady
        return captureTrusted && !questOverlayVisible &&
            (safeMenuTransition || safeQueueTransition || safeGameTransition) &&
            !reconnectDialog && !terminalOrResult && !mandatoryRankGuard && working && !paused
    }
}
