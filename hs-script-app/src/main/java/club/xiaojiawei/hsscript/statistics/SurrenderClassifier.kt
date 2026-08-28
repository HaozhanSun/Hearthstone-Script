package club.xiaojiawei.hsscript.statistics

/**
 * Converts the two surrender signals available at game end into the nullable
 * value stored in statistics:
 *
 * - true: our player surrendered;
 * - false: the game was played to a result, or the opponent surrendered;
 * - null: a concession was observed but its owner could not be resolved.
 */
object SurrenderClassifier {

    fun classify(
        concededPlayerId: String?,
        ourGameId: String?,
        opponentGameId: String?,
        surrenderRequestedByUs: Boolean,
    ): Boolean? {
        val conceded = concededPlayerId.normalized()
        val ours = ourGameId.normalized()
        val opponent = opponentGameId.normalized()

        // A fast concede may reach GAME_OVER before Hearthstone has populated
        // war.me.gameId. The local request is then the ownership signal.
        if (surrenderRequestedByUs) return true
        if (conceded.isBlank()) return false
        if (ours.isNotBlank() && conceded == ours) return true
        if (opponent.isNotBlank() && conceded == opponent) return false

        // Never turn an unresolved concession into a false "played" label.
        return null
    }

    private fun String?.normalized(): String = this?.trim().orEmpty()
}
