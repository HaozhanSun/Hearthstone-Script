package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.hsscript.bean.log.TagChangeEntity
import club.xiaojiawei.hsscriptbase.enums.StepEnum
import club.xiaojiawei.hsscript.enums.TagEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscript.strategy.AbstractPhaseStrategy
import club.xiaojiawei.hsscriptbase.config.log

/**
 * 特殊效果触发阶段（如开局的狼王、巴库、大主教等）
 * @author 肖嘉威
 * @date 2022/11/26 17:23
 */
object SpecialEffectTriggerPhaseStrategy : AbstractPhaseStrategy() {

    override fun dealTagChangeThenIsOver(line: String, tagChangeEntity: TagChangeEntity): Boolean {
        if (tagChangeEntity.tag == TagEnum.STEP) {
            log.info { "特殊效果阶段收到步骤：${tagChangeEntity.value}" }
            // Newer Power.log variants can omit MAIN_READY from the first
            // PowerTaskList batch and begin at a later MAIN_* step. Once the
            // game has entered the main turn pipeline, do not hold the log
            // listener in the pre-turn phase forever.
            if (tagChangeEntity.value == StepEnum.MAIN_READY.name ||
                tagChangeEntity.value == StepEnum.MAIN_START_TRIGGERS.name ||
                tagChangeEntity.value == StepEnum.MAIN_START.name ||
                tagChangeEntity.value == StepEnum.MAIN_ACTION.name
            ) {
                war.currentPhase = WarPhaseEnum.GAME_TURN
                return true
            }
        }
        return false
    }

}
