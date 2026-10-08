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
    fun `runtime authorization remains a lower-level runtime-only guard`() {
        assertEquals(true, MatchmakingGuardPolicy.runtimeAllowsInput(working = true, paused = false))
        assertEquals(false, MatchmakingGuardPolicy.runtimeAllowsInput(working = true, paused = true))
        assertEquals(false, MatchmakingGuardPolicy.runtimeAllowsInput(working = false, paused = false))

        assertTrue(MatchmakingGuardPolicy.authorizeQueueInput(true, false, false).allowed)
        assertFalse(MatchmakingGuardPolicy.authorizeQueueInput(false, false, false).allowed)
        assertFalse(MatchmakingGuardPolicy.authorizeQueueInput(true, true, false).allowed)
        assertFalse(MatchmakingGuardPolicy.authorizeQueueInput(true, false, true).allowed)
    }

    @Test
    fun `fresh rank OCR gates all pre match queue input`() {
        val relative = Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "strategy", "mode", "TournamentModeStrategy.kt",
        )
        val file = listOf(Path.of("."), Path.of("hs-script-app"))
            .map { it.resolve(relative) }
            .first { Files.isRegularFile(it) }
        val source = Files.readString(file)
        val startMatching = source.substringAfter("fun startMatching() {").substringBefore("private fun abortMatchmakingIfGameStarted")

        val runtimeGuardIndex = startMatching.indexOf("MATCHMAKING_REQUEST_IGNORED")
        val firstTraceIndex = startMatching.indexOf("matchmakingTraceSequence.incrementAndGet()")
        assertTrue(runtimeGuardIndex >= 0 && runtimeGuardIndex < firstTraceIndex,
            "paused runtime exits before rank OCR, trace allocation, and blocked-matchmaking spam")
        assertTrue(startMatching.contains("PauseStatus.isPause || !WorkTimeListener.working"))
        assertTrue(startMatching.contains("PreMatchRankGate.evaluate"))
        assertTrue(startMatching.contains("CurrentRankDetector.detect"))
        assertTrue(startMatching.contains("pre-match-deck-selection-rank-gate"))
        assertTrue(startMatching.contains("MatchmakingGuardPolicy.dispatchIfAuthorized(queueAuthorization)"))
        val authorizationIndex = startMatching.indexOf("PreMatchRankGate.evaluate")
        val denialIndex = startMatching.indexOf("if (!dispatchMatchmaking)")
        val firstQueueClickIndex = startMatching.indexOf("clickMatchmakingControl(START_RECT)")
        assertTrue(authorizationIndex >= 0 && denialIndex > authorizationIndex)
        assertTrue(firstQueueClickIndex > denialIndex, "all matchmaking input must follow the fail-closed gate")
        assertTrue(startMatching.contains("rankPolicy=FRESH_EXACT_5_OR_10"))
        assertFalse(startMatching.contains("POST_MULLIGAN"))
    }

    @Test
    fun `denied runtime cannot dispatch queue but a valid runtime does`() {
        var dispatches = 0
        val deny = MatchmakingGuardPolicy.authorizeQueueInput(false, false, false)
        assertFalse(MatchmakingGuardPolicy.dispatchIfAuthorized(deny) { dispatches++ })
        val allow = MatchmakingGuardPolicy.authorizeQueueInput(true, false, false)
        assertTrue(MatchmakingGuardPolicy.dispatchIfAuthorized(allow) { dispatches++ })
        assertEquals(1, dispatches)
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
