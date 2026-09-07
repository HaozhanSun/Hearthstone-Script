package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MctsActionAvailabilityTest {
    @Test
    fun `hero power with zero mana is not playable`() {
        assertFalse(MctsActionAvailability.isHeroPowerPlayable(1, 0, true))
        assertTrue(MctsActionAvailability.isHeroPowerPlayable(0, 0, true))
        assertFalse(MctsActionAvailability.isHeroPowerPlayable(0, 0, false))
    }

    @Test
    fun `mana and full-board predicates are shared across callers`() {
        assertTrue(MctsActionAvailability.isCostPayable(2, 2))
        assertFalse(MctsActionAvailability.isCostPayable(3, 2))
        assertTrue(MctsActionAvailability.isPermanentPlayBlockedByFullBoard(CardTypeEnum.MINION, true))
        assertTrue(MctsActionAvailability.isPermanentPlayBlockedByFullBoard(CardTypeEnum.LOCATION, true))
        assertFalse(MctsActionAvailability.isPermanentPlayBlockedByFullBoard(CardTypeEnum.SPELL, true))
        assertFalse(MctsActionAvailability.isPermanentPlayBlockedByFullBoard(CardTypeEnum.MINION, false))
    }
}
