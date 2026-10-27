package club.xiaojiawei.hsscript.starter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MissingGameStartPolicyTest {
    @Test
    fun `processless startup can relaunch only with no pause game queue or terminal evidence`() {
        assertEquals(
            MissingGameStartPolicy.Decision.START_CONFIGURED_CHAIN,
            MissingGameStartPolicy.decide(
                gameAlive = false,
                paused = false,
                activeGame = false,
                terminal = false,
                activeOrRecentQueue = false,
            ),
        )
        assertEquals(
            MissingGameStartPolicy.Decision.ATTACH_EXISTING_GAME,
            MissingGameStartPolicy.decide(true, false, false, false, false),
        )
        assertEquals(
            MissingGameStartPolicy.Decision.BLOCK_PAUSED,
            MissingGameStartPolicy.decide(false, true, false, false, false),
        )
        listOf(
            MissingGameStartPolicy.decide(false, false, true, false, false),
            MissingGameStartPolicy.decide(false, false, false, true, false),
            MissingGameStartPolicy.decide(false, false, false, false, true),
        ).forEach { decision ->
            assertEquals(MissingGameStartPolicy.Decision.BLOCK_ACTIVE_OR_AMBIGUOUS, decision)
        }
    }

    @Test
    fun `UI Start on an absent game dispatches the configured chain and rechecks safety after delay`() {
        val initialDecision = MissingGameStartPolicy.decide(
            gameAlive = false,
            paused = false,
            activeGame = false,
            terminal = false,
            activeOrRecentQueue = false,
        )
        var starts = 0
        var attaches = 0
        val started = MissingGameStartPolicy.dispatch(
            decision = initialDecision,
            startStillAuthorized = { true },
            attachExistingGame = { attaches++ },
            startConfiguredChain = { starts++ },
        )
        assertEquals(MissingGameStartPolicy.DispatchResult.STARTER_CHAIN_STARTED, started)
        assertEquals(1, starts, "the absent-game UI Start decision must invoke the configured starter chain")
        assertEquals(0, attaches)

        val becameUnsafe = MissingGameStartPolicy.dispatch(
            decision = initialDecision,
            startStillAuthorized = {
                MissingGameStartPolicy.decide(
                    gameAlive = false,
                    paused = false,
                    activeGame = false,
                    terminal = false,
                    activeOrRecentQueue = true,
                ) == MissingGameStartPolicy.Decision.START_CONFIGURED_CHAIN
            },
            attachExistingGame = { attaches++ },
            startConfiguredChain = { starts++ },
        )
        assertEquals(MissingGameStartPolicy.DispatchResult.BLOCKED, becameUnsafe)
        assertEquals(1, starts, "a queue that appears during the UI countdown prevents launch")
        assertTrue(attaches == 0)
    }
}
