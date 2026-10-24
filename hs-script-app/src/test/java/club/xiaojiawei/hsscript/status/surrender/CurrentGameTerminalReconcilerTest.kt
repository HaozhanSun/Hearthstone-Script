package club.xiaojiawei.hsscript.status.surrender

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CurrentGameTerminalReconcilerTest {
    @AfterTest
    fun cleanup() {
        MandatoryRankSurrenderGuard.resetForTest()
        MulliganRankDispatchBarrier.resetForTest()
    }

    @Test
    fun `current production terminal tail releases barrier through verified local identity`() {
        val tracker = CurrentGamePowerLogTerminalTracker()
        val lines = listOf(
            "D 18:35:31.6715316 GameState.DebugPrintPower() - CREATE_GAME",
            "D 18:35:31.6715316 PowerTaskList.DebugPrintPower() -     CREATE_GAME",
            "D 18:35:31.6715316 PowerTaskList.DebugPrintPower() -         Player EntityID=2 PlayerID=1 GameAccountId=[hi=144115193835963207 lo=36936081]",
            "D 18:35:31.6715316 PowerTaskList.DebugPrintPower() -         Player EntityID=3 PlayerID=2 GameAccountId=[hi=144115193835963207 lo=693579657]",
            "D 18:35:31.6715316 GameState.DebugPrintGame() - PlayerID=1, PlayerName=laz#12793",
            "D 18:35:31.6715316 GameState.DebugPrintGame() - PlayerID=2, PlayerName=Claus#11857",
            "D 18:35:31.6715316 GameState.DebugPrintPower() - TAG_CHANGE Entity=laz#12793 tag=PLAYSTATE value=PLAYING",
            "D 18:36:36.8229217 GameState.DebugPrintPower() - TAG_CHANGE Entity=laz#12793 tag=PLAYSTATE value=CONCEDED",
            "D 18:36:36.8229217 GameState.DebugPrintPower() - TAG_CHANGE Entity=laz#12793 tag=PLAYSTATE value=LOST",
            "D 18:36:36.8229217 GameState.DebugPrintPower() - TAG_CHANGE Entity=Claus#11857 tag=PLAYSTATE value=WON",
            "D 18:36:36.8229217 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=STEP value=FINAL_GAMEOVER",
            "D 18:36:36.8229217 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE",
        )
        lines.forEach(tracker::observeLine)

        val identity = requireNotNull(tracker.currentGameIdentity("laz#12793"))
        MandatoryRankSurrenderGuard.begin(identity)
        var accepted: CurrentGameSurrenderTerminalEvidence? = null
        val reconciler = CurrentGameTerminalReconciler(tracker) { accepted = it }

        // Simulate the live race: WAR's local id is stale while Power.log has
        // already supplied the complete result block for the same game.
        val evidence = reconciler.reconcile("stale-war-entity", "Claus#11857")

        assertNotNull(evidence)
        assertEquals("laz#12793", evidence.ownEntityId)
        assertEquals("LOST", evidence.ownPlayState)
        assertEquals("WON", evidence.opponentPlayState)
        assertEquals(evidence, accepted)
        assertFalse(MandatoryRankSurrenderGuard.isPending())
        assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
    }

    @Test
    fun `incomplete terminal tail leaves barrier fail closed`() {
        val tracker = CurrentGamePowerLogTerminalTracker()
        tracker.observeLine("CREATE_GAME gameId=current")
        tracker.observeLine("TAG_CHANGE Entity=self tag=PLAYSTATE value=CONCEDED")
        val identity = requireNotNull(tracker.currentGameIdentity("self"))
        MandatoryRankSurrenderGuard.begin(identity)

        assertEquals(null, CurrentGameTerminalReconciler(tracker).reconcile("self", "opponent"))
        assertTrue(MandatoryRankSurrenderGuard.isPending())
        assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
    }
}
