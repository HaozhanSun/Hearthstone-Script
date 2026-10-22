package club.xiaojiawei.hsscript.bean

import club.xiaojiawei.hsscriptbase.config.log

internal data class AttackInputTraceStart(
    val attackId: String,
    val previousAttackId: String?,
)

/** Process-local IDs for correlating consecutive attack input sequences. */
internal class AttackInputTraceIds {
    private var sequence = 0L
    private var previousAttackId: String? = null

    @Synchronized
    fun next(): AttackInputTraceStart {
        val attackId = "attack-${++sequence}"
        val start = AttackInputTraceStart(attackId, previousAttackId)
        previousAttackId = attackId
        return start
    }
}

internal object AttackInputTimingTrace {
    private val ids = AttackInputTraceIds()

    fun begin(): String {
        val start = ids.next()
        start.previousAttackId?.let { previousId ->
            log.info {
                "GAMEPLAY_ATTACK_TIMING attackId=$previousId stage=NEXT_ATTACK_START nextAttackId=${start.attackId}"
            }
        }
        log.info {
            "GAMEPLAY_ATTACK_TIMING attackId=${start.attackId} stage=ATTACK_START"
        }
        return start.attackId
    }

    fun stage(attackId: String, stage: String, detail: String = "") {
        log.info {
            "GAMEPLAY_ATTACK_TIMING attackId=$attackId stage=$stage $detail".trimEnd()
        }
    }
}
