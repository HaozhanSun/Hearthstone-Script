package club.xiaojiawei.hsscript.strategy.mode

import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscript.status.surrender.RankEligibilityCorePolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchmakingGuardPolicyTest {
    @Test
    fun `queue authorization requires active runtime and rank authorization`() {
        assertEquals(true, MatchmakingGuardPolicy.runtimeAllowsInput(working = true, paused = false))
        assertEquals(false, MatchmakingGuardPolicy.runtimeAllowsInput(working = true, paused = true))
        assertEquals(false, MatchmakingGuardPolicy.runtimeAllowsInput(working = false, paused = false))

        val allowedRank = RankEligibilityCorePolicy.Decision(true, "verified-exact-rank-5")
        val deniedRank = RankEligibilityCorePolicy.Decision(false, "rank-not-5-or-10")
        assertTrue(MatchmakingGuardPolicy.authorizeQueueInput(true, false, false, allowedRank).allowed)
        assertFalse(MatchmakingGuardPolicy.authorizeQueueInput(true, false, false, deniedRank).allowed)
        assertFalse(MatchmakingGuardPolicy.authorizeQueueInput(false, false, false, allowedRank).allowed)
        assertFalse(MatchmakingGuardPolicy.authorizeQueueInput(true, true, false, allowedRank).allowed)
        assertFalse(MatchmakingGuardPolicy.authorizeQueueInput(true, false, true, allowedRank).allowed)
    }

    @Test
    fun `fresh rank authorization happens before any pre match queue click`() {
        val relative = Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "strategy", "mode", "TournamentModeStrategy.kt",
        )
        val file = listOf(Path.of("."), Path.of("hs-script-app"))
            .map { it.resolve(relative) }
            .first { Files.isRegularFile(it) }
        val source = Files.readString(file)
        val startMatching = source.substringAfter("fun startMatching() {").substringBefore("private fun abortMatchmakingIfGameStarted")

        assertTrue(startMatching.contains("PreMatchRankGate.evaluate"))
        assertTrue(startMatching.contains("CurrentRankDetector.detect"))
        assertTrue(startMatching.contains("trigger = \"pre-match-deck-selection\""))
        assertTrue(startMatching.contains("MatchmakingGuardPolicy.dispatchIfAuthorized(queueAuthorization)"))
        assertTrue(startMatching.contains("expectedMode = ModeEnum.TOURNAMENT.name"))
        assertTrue(startMatching.contains("expectedInWar = false"))
        val authorizationIndex = startMatching.indexOf("PreMatchRankGate.evaluate")
        val denialIndex = startMatching.indexOf("if (!dispatchMatchmaking)")
        val firstQueueClickIndex = startMatching.indexOf("clickMatchmakingControl(START_RECT)")
        assertTrue(authorizationIndex >= 0 && denialIndex > authorizationIndex)
        assertTrue(firstQueueClickIndex > denialIndex, "all matchmaking input must follow the fail-closed gate")
        assertTrue(startMatching.contains("rankPolicy=FRESH_EXACT_5_OR_10"))
        assertFalse(startMatching.contains("POST_MULLIGAN"))
    }

    @Test
    fun `deny decision cannot dispatch matchmaking input and exact ranks can`() {
        var dispatches = 0
        val deny = MatchmakingGuardPolicy.QueueAuthorization(false, "rank-not-5-or-10")
        assertFalse(MatchmakingGuardPolicy.dispatchIfAuthorized(deny) { dispatches++ })
        assertEquals(0, dispatches, "rank denial must send no queue input")

        for (rank in listOf(5, 10)) {
            val allow = MatchmakingGuardPolicy.QueueAuthorization(true, "verified-exact-rank-$rank")
            assertTrue(MatchmakingGuardPolicy.dispatchIfAuthorized(allow) { dispatches++ })
        }
        assertEquals(2, dispatches)
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
