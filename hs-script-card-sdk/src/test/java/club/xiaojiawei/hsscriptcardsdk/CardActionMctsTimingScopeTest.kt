package club.xiaojiawei.hsscriptcardsdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum

class CardActionMctsTimingScopeTest {

    @Test
    fun `MCTS timing scope is enabled only inside the scoped action`() {
        assertFalse(CardAction.isMctsNoTargetMinionPlayTiming())

        val result = CardAction.withMctsNoTargetMinionPlayTiming {
            assertTrue(CardAction.isMctsNoTargetMinionPlayTiming())
            "action-result"
        }

        assertEquals("action-result", result)
        assertFalse(CardAction.isMctsNoTargetMinionPlayTiming())
    }

    @Test
    fun `nested MCTS timing scope restores outer value`() {
        CardAction.withMctsNoTargetMinionPlayTiming {
            assertTrue(CardAction.isMctsNoTargetMinionPlayTiming())
            CardAction.withMctsNoTargetMinionPlayTiming {
                assertTrue(CardAction.isMctsNoTargetMinionPlayTiming())
            }
            assertTrue(CardAction.isMctsNoTargetMinionPlayTiming())
        }

        assertFalse(CardAction.isMctsNoTargetMinionPlayTiming())
    }

    @Test
    fun `MCTS timing scope is restored when action throws`() {
        val failure = runCatching {
            CardAction.withMctsNoTargetMinionPlayTiming {
                error("expected test failure")
            }
        }

        assertTrue(failure.isFailure)
        assertFalse(CardAction.isMctsNoTargetMinionPlayTiming())
    }

    @Test
    fun `short settle is limited to scoped minion plays`() {
        assertFalse(CardAction.shouldUseMctsShortSettleForNoTargetPlay(CardTypeEnum.MINION))
        CardAction.withMctsNoTargetMinionPlayTiming {
            assertTrue(CardAction.shouldUseMctsShortSettleForNoTargetPlay(CardTypeEnum.MINION))
            assertFalse(CardAction.shouldUseMctsShortSettleForNoTargetPlay(CardTypeEnum.SPELL))
            assertFalse(CardAction.shouldUseMctsShortSettleForNoTargetPlay(CardTypeEnum.WEAPON))
            assertFalse(CardAction.shouldUseMctsShortSettleForNoTargetPlay(null))
        }
        assertFalse(CardAction.shouldUseMctsShortSettleForNoTargetPlay(CardTypeEnum.MINION))
    }
}
