package club.xiaojiawei.hsscript.strategy

import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.enums.StepEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnStartActionReadinessPolicyTest {
    @Test
    fun `authoritative main action state permits only a short bounded animation settle`() {
        val state = readiness()

        assertTrue(state)
        assertTrue(
            TurnStartActionReadinessPolicy.MIN_ANIMATION_SETTLE_MS in 1..1_500,
        )
        assertTrue(
            TurnStartActionReadinessPolicy.MAX_ANIMATION_SETTLE_MS in
                TurnStartActionReadinessPolicy.MIN_ANIMATION_SETTLE_MS..1_500,
        )
        repeat(100) {
            assertTrue(
                RandomUtil.getTurnStartDelay() in
                    TurnStartActionReadinessPolicy.MIN_ANIMATION_SETTLE_MS..
                    TurnStartActionReadinessPolicy.MAX_ANIMATION_SETTLE_MS,
            )
        }
    }

    @Test
    fun `stale phase turn identity pause and terminal evidence all fail closed`() {
        assertFalse(readiness(mode = ModeEnum.TOURNAMENT))
        assertFalse(readiness(activeGame = false))
        assertFalse(readiness(isMyTurn = false))
        assertFalse(readiness(phase = WarPhaseEnum.REPLACE_CARD))
        assertFalse(readiness(step = StepEnum.MAIN_READY))
        assertFalse(readiness(currentPlayerIsMe = false))
        assertFalse(readiness(playerValid = false))
        assertFalse(readiness(paused = true))
        assertFalse(readiness(terminal = true))
    }

    @Test
    fun `fresh readiness is rechecked after animation settle before actions can continue`() {
        val readyBeforeSettle = readiness()
        val readyAfterSettle = readiness(paused = true)

        assertTrue(readyBeforeSettle)
        assertFalse(readyAfterSettle)
    }

    private fun readiness(
        mode: ModeEnum? = ModeEnum.GAMEPLAY,
        activeGame: Boolean = true,
        isMyTurn: Boolean = true,
        phase: WarPhaseEnum? = WarPhaseEnum.GAME_TURN,
        step: StepEnum? = StepEnum.MAIN_ACTION,
        currentPlayerIsMe: Boolean = true,
        playerValid: Boolean = true,
        paused: Boolean = false,
        terminal: Boolean = false,
    ): Boolean = TurnStartActionReadinessPolicy.isReady(
        mode = mode,
        activeGame = activeGame,
        isMyTurn = isMyTurn,
        phase = phase,
        step = step,
        currentPlayerIsMe = currentPlayerIsMe,
        playerValid = playerValid,
        paused = paused,
        terminal = terminal,
    )
}
