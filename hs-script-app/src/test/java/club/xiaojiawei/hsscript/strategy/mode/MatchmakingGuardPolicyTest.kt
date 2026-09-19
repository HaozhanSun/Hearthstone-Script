package club.xiaojiawei.hsscript.strategy.mode

import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import kotlin.test.Test
import kotlin.test.assertEquals

class MatchmakingGuardPolicyTest {
    @Test
    fun `in-war FILL_DECK aborts retry before mulligan`() {
        assertEquals(
            MatchmakingGuardPolicy.Decision.ABORT_GAME_STARTED,
            MatchmakingGuardPolicy.decide(
                MatchmakingGuardPolicy.LiveGameEvidence(true, WarPhaseEnum.FILL_DECK, "game-1", 10),
            ),
        )
    }

    @Test
    fun `in-war GAME_TURN aborts retry and reconnect clicks`() {
        assertEquals(
            MatchmakingGuardPolicy.Decision.ABORT_GAME_STARTED,
            MatchmakingGuardPolicy.decide(
                MatchmakingGuardPolicy.LiveGameEvidence(true, WarPhaseEnum.GAME_TURN, "game-1", 500),
            ),
        )
    }

    @Test
    fun `power-log game identity also aborts before inWar flag catches up`() {
        assertEquals(
            MatchmakingGuardPolicy.Decision.ABORT_GAME_STARTED,
            MatchmakingGuardPolicy.decide(
                MatchmakingGuardPolicy.LiveGameEvidence(false, WarPhaseEnum.FILL_DECK, "game-1", 25),
            ),
        )
    }

    @Test
    fun `no active game preserves normal matchmaking retry`() {
        assertEquals(
            MatchmakingGuardPolicy.Decision.CONTINUE_MATCHMAKING,
            MatchmakingGuardPolicy.decide(
                MatchmakingGuardPolicy.LiveGameEvidence(false, WarPhaseEnum.FILL_DECK, "", 0),
            ),
        )
        assertEquals(
            MatchmakingGuardPolicy.Decision.CONTINUE_MATCHMAKING,
            MatchmakingGuardPolicy.decide(
                MatchmakingGuardPolicy.LiveGameEvidence(false, WarPhaseEnum.GAME_OVER, "stale-game", 500),
            ),
        )
        assertEquals(
            MatchmakingGuardPolicy.Decision.CONTINUE_MATCHMAKING,
            MatchmakingGuardPolicy.decide(
                MatchmakingGuardPolicy.LiveGameEvidence(false, WarPhaseEnum.FILL_DECK, "UNKNOWN", 1),
            ),
        )
    }
}
