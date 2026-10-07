package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.hsscript.bean.log.TagChangeEntity
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.enums.TagEnum
import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import club.xiaojiawei.hsscriptbase.enums.StepEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ReplaceCardRankSurrenderPhaseTest {

    @AfterEach
    fun reset() {
        ReplaceCardPhaseStrategy.resetForNewGame()
        MulliganRankDispatchBarrier.resetForTest()
    }

    @Test
    fun `DONE and MAIN_READY do not advance Mulligan while rank evidence is unresolved`() {
        val war = WarEx.war
        val oldPhase = war.currentPhase
        val oldTurnStep = war.currentTurnStep
        val oldMyGameId = war.me.gameId
        val oldWon = war.won
        val oldLost = war.lost
        val oldConceded = war.conceded
        try {
            ReplaceCardPhaseStrategy.resetForNewGame()
            war.currentPhase = WarPhaseEnum.REPLACE_CARD
            war.currentTurnStep = null
            war.me.gameId = "local-test-player"
            war.won = ""
            war.lost = ""
            war.conceded = ""
            MulliganRankDispatchBarrier.beginCurrentGame()
            assertEquals(
                MulliganRankDispatchBarrier.State.PENDING,
                MulliganRankDispatchBarrier.currentState(),
            )

            val mulliganDone = TagChangeEntity(
                tag = TagEnum.MULLIGAN_STATE,
                value = "DONE",
            ).apply { entity = war.me.gameId }
            assertFalse(invokePhaseHandler(mulliganDone))
            assertEquals(WarPhaseEnum.REPLACE_CARD, war.currentPhase)

            val mainReady = TagChangeEntity(tag = TagEnum.NEXT_STEP, value = StepEnum.MAIN_READY.name)
            assertFalse(invokePhaseHandler(mainReady))
            assertEquals(WarPhaseEnum.REPLACE_CARD, war.currentPhase)
            assertEquals(
                MulliganRankDispatchBarrier.State.PENDING,
                MulliganRankDispatchBarrier.currentState(),
                "the phase remains closed until fresh rank evidence or authoritative terminal evidence",
            )
        } finally {
            ReplaceCardPhaseStrategy.resetForNewGame()
            war.currentPhase = oldPhase
            war.currentTurnStep = oldTurnStep
            war.me.gameId = oldMyGameId
            war.won = oldWon
            war.lost = oldLost
            war.conceded = oldConceded
        }
    }

    private fun invokePhaseHandler(event: TagChangeEntity): Boolean {
        val handler = ReplaceCardPhaseStrategy::class.java.getDeclaredMethod(
            "dealTagChangeThenIsOver",
            String::class.java,
            TagChangeEntity::class.java,
        )
        handler.isAccessible = true
        return handler.invoke(ReplaceCardPhaseStrategy, "test-event", event) as Boolean
    }
}
