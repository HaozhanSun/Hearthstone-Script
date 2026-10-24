package club.xiaojiawei.hsscript.status.surrender

/**
 * Releases the mandatory-surrender barrier when the current game's complete
 * Power.log terminal block arrives after the result worker has started.
 *
 * WAR can briefly expose an empty or stale local entity while GAME_OVER is
 * being assembled. The tracker has already bound the local account from the
 * verified mulligan input, so retry that identity before falling back to the
 * own-player-only terminal proof. Every candidate still passes the same-game
 * guard and complete terminal evidence checks.
 */
internal class CurrentGameTerminalReconciler(
    private val tracker: CurrentGamePowerLogTerminalTracker,
    private val onAccepted: (CurrentGameSurrenderTerminalEvidence) -> Unit = {},
) {
    fun reconcile(
        ownEntityId: String,
        opponentEntityId: String?,
    ): CurrentGameSurrenderTerminalEvidence? {
        if (!MandatoryRankSurrenderGuard.isPending() ||
            !tracker.hasCompleteTerminalEvidence()
        ) return null

        val candidates = sequenceOf(
            tracker.currentGameSurrenderEvidence(ownEntityId, opponentEntityId),
            tracker.currentGameSurrenderEvidence("", opponentEntityId),
            tracker.currentGameSurrenderEvidence("", null),
        ).filterNotNull().distinctBy {
            listOf(it.gameIdentity, it.ownEntityId, it.opponentEntityId, it.ownPlayState, it.opponentPlayState)
        }

        for (evidence in candidates) {
            if (MandatoryRankSurrenderGuard.reconcileTerminalEvidence(evidence) != null) {
                onAccepted(evidence)
                return evidence
            }
        }
        return null
    }
}
