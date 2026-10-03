package club.xiaojiawei.hsscript.strategy.mode

import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchmakingGuardPolicyTest {
    @Test
    fun `queue input gate depends on runtime only and not rank evidence`() {
        assertEquals(true, MatchmakingGuardPolicy.runtimeAllowsInput(working = true, paused = false))
        assertEquals(false, MatchmakingGuardPolicy.runtimeAllowsInput(working = true, paused = true))
        assertEquals(false, MatchmakingGuardPolicy.runtimeAllowsInput(working = false, paused = false))
    }

    @Test
    fun `rank inspection is absent from the pre match queue path`() {
        val relative = Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "strategy", "mode", "TournamentModeStrategy.kt",
        )
        val file = listOf(Path.of("."), Path.of("hs-script-app"))
            .map { it.resolve(relative) }
            .first { Files.isRegularFile(it) }
        val source = Files.readString(file)
        val startMatching = source.substringAfter("fun startMatching() {").substringBefore("private fun abortMatchmakingIfGameStarted")

        assertTrue(startMatching.contains("MatchmakingGuardPolicy.runtimeAllowsInput"))
        assertTrue(startMatching.contains("rankPolicy=POST_MULLIGAN"))
        assertFalse(startMatching.contains("CurrentRankDetector"))
        assertFalse(startMatching.contains("RankEligibilityPolicy"))
        assertFalse(startMatching.contains("pre-match-deck-selection"))
    }

    @Test
    fun `in-war FILL_DECK aborts retry before mulligan`() {
        assertEquals(
            MatchmakingGuardPolicy.Decision.ABORT_GAME_STARTED,
            MatchmakingGuardPolicy.decide(
                MatchmakingGuardPolicy.LiveGameEvidence(true, WarPhaseEnum.FILL_DECK.name, "game-1", 10),
            ),
        )
    }

    @Test
    fun `in-war GAME_TURN aborts retry and reconnect clicks`() {
        assertEquals(
            MatchmakingGuardPolicy.Decision.ABORT_GAME_STARTED,
            MatchmakingGuardPolicy.decide(
                MatchmakingGuardPolicy.LiveGameEvidence(true, WarPhaseEnum.GAME_TURN.name, "game-1", 500),
            ),
        )
    }

    @Test
    fun `power-log game identity also aborts before inWar flag catches up`() {
        assertEquals(
            MatchmakingGuardPolicy.Decision.ABORT_GAME_STARTED,
            MatchmakingGuardPolicy.decide(
                MatchmakingGuardPolicy.LiveGameEvidence(false, WarPhaseEnum.FILL_DECK.name, "game-1", 25),
            ),
        )
    }

    @Test
    fun `no active game preserves normal matchmaking retry`() {
        assertEquals(
            MatchmakingGuardPolicy.Decision.CONTINUE_MATCHMAKING,
            MatchmakingGuardPolicy.decide(
                MatchmakingGuardPolicy.LiveGameEvidence(false, WarPhaseEnum.FILL_DECK.name, "", 0),
            ),
        )
        assertEquals(
            MatchmakingGuardPolicy.Decision.CONTINUE_MATCHMAKING,
            MatchmakingGuardPolicy.decide(
                MatchmakingGuardPolicy.LiveGameEvidence(false, WarPhaseEnum.GAME_OVER.name, "stale-game", 500),
            ),
        )
        assertEquals(
            MatchmakingGuardPolicy.Decision.CONTINUE_MATCHMAKING,
            MatchmakingGuardPolicy.decide(
                MatchmakingGuardPolicy.LiveGameEvidence(false, WarPhaseEnum.FILL_DECK.name, "UNKNOWN", 1),
            ),
        )
    }
}
