package club.xiaojiawei.hsscript.status.surrender

/** Terminal state parsed from one live CREATE_GAME-scoped Power.log segment. */
data class CurrentGameSurrenderTerminalEvidence(
    val gameIdentity: String,
    val ownEntityId: String,
    val opponentEntityId: String,
    val ownPlayState: String?,
    val opponentPlayState: String?,
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
        if (evidence.ownEntityId.isBlank() || evidence.opponentEntityId.isBlank() ||
            evidence.ownEntityId == evidence.opponentEntityId
        ) return false
        val ownState = evidence.ownPlayState?.uppercase() ?: return false
        val opponentState = evidence.opponentPlayState?.uppercase() ?: return false
        return ownState in setOf("CONCEDED", "LOST") &&
            opponentState == "WON" && evidence.finalGameOver && evidence.complete
    }
}
