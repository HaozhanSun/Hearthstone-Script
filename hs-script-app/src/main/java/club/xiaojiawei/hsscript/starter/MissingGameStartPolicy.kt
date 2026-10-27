package club.xiaojiawei.hsscript.starter

/** Safe decision for an operator/UI Start request when the capture window remains open. */
internal object MissingGameStartPolicy {
    enum class Decision { ATTACH_EXISTING_GAME, START_CONFIGURED_CHAIN, BLOCK_PAUSED, BLOCK_ACTIVE_OR_AMBIGUOUS }
    enum class DispatchResult { NO_ACTION, BLOCKED, ATTACHED_EXISTING_GAME, STARTER_CHAIN_STARTED }

    fun decide(
        gameAlive: Boolean,
        paused: Boolean,
        activeGame: Boolean,
        terminal: Boolean,
        activeOrRecentQueue: Boolean,
    ): Decision = when {
        paused -> Decision.BLOCK_PAUSED
        gameAlive -> Decision.ATTACH_EXISTING_GAME
        activeGame || terminal || activeOrRecentQueue -> Decision.BLOCK_ACTIVE_OR_AMBIGUOUS
        else -> Decision.START_CONFIGURED_CHAIN
    }

    /** Testable side-effect boundary used by the UI Start action. */
    fun dispatch(
        decision: Decision,
        startStillAuthorized: () -> Boolean,
        attachExistingGame: () -> Unit,
        startConfiguredChain: () -> Unit,
    ): DispatchResult = when (decision) {
        Decision.BLOCK_PAUSED, Decision.BLOCK_ACTIVE_OR_AMBIGUOUS -> DispatchResult.BLOCKED
        Decision.ATTACH_EXISTING_GAME -> {
            attachExistingGame()
            DispatchResult.ATTACHED_EXISTING_GAME
        }
        Decision.START_CONFIGURED_CHAIN -> {
            if (!startStillAuthorized()) DispatchResult.BLOCKED
            else {
                startConfiguredChain()
                DispatchResult.STARTER_CHAIN_STARTED
            }
        }
    }
}
