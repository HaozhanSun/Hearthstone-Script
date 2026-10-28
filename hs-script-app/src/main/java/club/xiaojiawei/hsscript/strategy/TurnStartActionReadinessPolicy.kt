package club.xiaojiawei.hsscript.strategy

import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.enums.StepEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscriptbase.util.RandomUtil

/** Readiness fence and short animation settle for actions at the start of our turn. */
internal object TurnStartActionReadinessPolicy {
    const val MIN_ANIMATION_SETTLE_MS = RandomUtil.TURN_START_SETTLE_MIN_MS
    const val MAX_ANIMATION_SETTLE_MS = RandomUtil.TURN_START_SETTLE_MAX_MS

    fun isReady(
        mode: ModeEnum?,
        activeGame: Boolean,
        isMyTurn: Boolean,
        phase: WarPhaseEnum?,
        step: StepEnum?,
        currentPlayerIsMe: Boolean,
        playerValid: Boolean,
        paused: Boolean,
        terminal: Boolean,
    ): Boolean = mode == ModeEnum.GAMEPLAY && activeGame && isMyTurn &&
        phase == WarPhaseEnum.GAME_TURN && step == StepEnum.MAIN_ACTION &&
        currentPlayerIsMe && playerValid && !paused && !terminal
}
