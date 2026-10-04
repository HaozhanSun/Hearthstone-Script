package club.xiaojiawei.hsscript.status.surrender

/** Terminal state parsed from one live CREATE_GAME-scoped Power.log segment. */
data class CurrentGameSurrenderTerminalEvidence(
    val gameIdentity: String,
    val ownEntityId: String,
    val opponentEntityId: String?,
    val ownPlayState: String?,
    val opponentPlayState: String?,
    val ownConceded: Boolean = false,
    val finalGameOver: Boolean,
    val complete: Boolean,
)

/** Shared fail-closed rule used by production Power.log handling and offline replay. */
object MandatorySurrenderTerminalEvidence {
    fun authorizes(
        requestedGameIdentity: String?,
        evidence: CurrentGameSurrenderTerminalEvidence?,
    ): Boolean {
        if (requestedGameIdentity.isNullOrBlank() || evidence == null) return false
        if (requestedGameIdentity != evidence.gameIdentity) return false
        if (evidence.ownEntityId.isBlank()) return false
        val opponentEntityId = evidence.opponentEntityId?.takeIf { it.isNotBlank() }
        if (opponentEntityId == evidence.ownEntityId) return false
        val ownState = evidence.ownPlayState?.uppercase() ?: return false
        val opponentWon = opponentEntityId != null && evidence.opponentPlayState?.uppercase() == "WON"
        val locallyConceded = evidence.ownConceded || ownState == "CONCEDED"
        val terminalResultIsConsistent = when {
            opponentEntityId == null -> locallyConceded
            else -> ownState in setOf("CONCEDED", "LOST") && opponentWon
        }
        return terminalResultIsConsistent && evidence.finalGameOver && evidence.complete
    }
}
