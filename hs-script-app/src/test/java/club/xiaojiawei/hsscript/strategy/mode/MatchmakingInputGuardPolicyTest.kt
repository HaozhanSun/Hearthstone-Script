package club.xiaojiawei.hsscript.strategy.mode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MatchmakingInputGuardPolicyTest {

    @Test
    fun `fresh matchmaking blocks duplicate start even when Power log is empty`() {
        assertEquals(
            MatchmakingInputGuardPolicy.Decision.BLOCK_ACTIVE_SEARCH,
            MatchmakingInputGuardPolicy.decide(
                observedScreen = "MATCHMAKING",
                recentActiveSearchEvidence = false,
            ),
        )
    }

    @Test
    fun `recent authoritative search evidence blocks OCR miss`() {
        assertEquals(
            MatchmakingInputGuardPolicy.Decision.BLOCK_ACTIVE_SEARCH,
            MatchmakingInputGuardPolicy.decide(
                observedScreen = "UNKNOWN",
                recentActiveSearchEvidence = true,
            ),
        )
    }

    @Test
    fun `deck selection without active search evidence allows initial start`() {
        assertEquals(
            MatchmakingInputGuardPolicy.Decision.ALLOW_INPUT,
            MatchmakingInputGuardPolicy.decide(
                observedScreen = "DECK_SELECTION",
                recentActiveSearchEvidence = false,
            ),
        )
    }

    @Test
    fun `unknown without authoritative search evidence remains compatible with startup`() {
        assertEquals(
            MatchmakingInputGuardPolicy.Decision.ALLOW_INPUT,
            MatchmakingInputGuardPolicy.decide(
                observedScreen = "UNKNOWN",
                recentActiveSearchEvidence = false,
            ),
        )
    }

    @Test
    fun `fresh deck selection wins over expired search evidence`() {
        assertEquals(
            MatchmakingInputGuardPolicy.Decision.ALLOW_INPUT,
            MatchmakingInputGuardPolicy.decide(
                observedScreen = "DECK_SELECTION",
                recentActiveSearchEvidence = true,
            ),
        )
    }
}
