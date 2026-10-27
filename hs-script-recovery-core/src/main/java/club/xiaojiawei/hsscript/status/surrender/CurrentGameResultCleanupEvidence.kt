package club.xiaojiawei.hsscript.status.surrender

/** A local terminal result may authorize result-page cleanup only for one complete, current game. */
object CurrentGameResultCleanupEvidence {
    fun authorizes(evidence: CurrentGameSurrenderTerminalEvidence?): Boolean {
        if (evidence == null || evidence.gameIdentity.isBlank() || evidence.ownEntityId.isBlank() ||
            !evidence.finalGameOver || !evidence.complete
        ) return false
        val own = evidence.ownPlayState?.uppercase() ?: return false
        if (own !in setOf("WON", "LOST", "CONCEDED")) return false
        val opponentId = evidence.opponentEntityId?.takeIf(String::isNotBlank) ?: return true
        if (opponentId == evidence.ownEntityId) return false
        val opponent = evidence.opponentPlayState?.uppercase() ?: return false
        return when (own) {
            "WON" -> opponent in setOf("LOST", "CONCEDED")
            "LOST", "CONCEDED" -> opponent == "WON"
            else -> false
        }
    }
}
