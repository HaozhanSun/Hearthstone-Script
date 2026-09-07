package club.xiaojiawei.hsscriptcardsdk.mcts

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MctsTurnPhaseFenceTest {
    @Test
    fun `a newly payable earlier phase cannot reopen after hero attack`() {
        val fence = MctsTurnPhaseFence()

        fence.observe(MctsActionOrderPhase.MINION_ATTACK)
        fence.observe(MctsActionOrderPhase.HERO_ATTACK)

        assertFalse(
            fence.allows(
                MctsActionOrderPhase.MINION_PLAY,
                isEndTurn = false,
                endTurnLegal = true,
            ),
        )
        assertFalse(
            fence.allows(
                MctsActionOrderPhase.HERO_POWER,
                isEndTurn = false,
                endTurnLegal = true,
            ),
        )
        assertTrue(
            fence.allows(
                phase = null,
                isEndTurn = true,
                endTurnLegal = true,
            ),
        )

        fence.startNewCycle()
        assertTrue(
            fence.allows(
                MctsActionOrderPhase.MINION_PLAY,
                isEndTurn = false,
                endTurnLegal = true,
            ),
        )
    }

    @Test
    fun `post hero attack location is the only allowed exception and closes the cycle`() {
        val fence = MctsTurnPhaseFence()
        fence.observe(MctsActionOrderPhase.HERO_ATTACK)

        assertTrue(
            fence.allows(
                MctsActionOrderPhase.POST_HERO_ATTACK_LOCATION,
                isEndTurn = false,
                endTurnLegal = true,
            ),
        )
        fence.observe(MctsActionOrderPhase.POST_HERO_ATTACK_LOCATION)

        assertFalse(
            fence.allows(
                MctsActionOrderPhase.POST_HERO_ATTACK_LOCATION,
                isEndTurn = false,
                endTurnLegal = true,
            ),
        )
        assertFalse(
            fence.allows(
                MctsActionOrderPhase.MINION_PLAY,
                isEndTurn = false,
                endTurnLegal = true,
            ),
        )
    }
}
