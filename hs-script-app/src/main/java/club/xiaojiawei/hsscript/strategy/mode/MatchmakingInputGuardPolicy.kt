package club.xiaojiawei.hsscript.strategy.mode

/**
 * Prevents a second queue-start sequence from sending input while the client
 * is already searching. Power.log may still be empty or report the previous
 * phase during this UI state, so visual/lifecycle evidence takes precedence.
 */
internal object MatchmakingInputGuardPolicy {

    enum class Decision {
        ALLOW_INPUT,
        BLOCK_ACTIVE_SEARCH,
    }

    fun decide(observedScreen: String?, recentActiveSearchEvidence: Boolean): Decision {
        if (observedScreen.equals("MATCHMAKING", ignoreCase = true) ||
            (recentActiveSearchEvidence && isUncertain(observedScreen))
        ) {
            return Decision.BLOCK_ACTIVE_SEARCH
        }
        return Decision.ALLOW_INPUT
    }

    private fun isUncertain(screen: String?): Boolean =
        screen == null || screen.equals("UNKNOWN", ignoreCase = true)
}
