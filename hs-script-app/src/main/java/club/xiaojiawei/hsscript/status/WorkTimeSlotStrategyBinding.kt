package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.WorkTimeRule

data class WorkTimeSlotStrategyDecision(
    val deckSlot: Int?,
    val strategyId: String?,
    val selectionReason: String,
    val fallbackReason: String? = null,
) {
    val accepted: Boolean = strategyId != null
}

object WorkTimeSlotStrategyBinding {
    fun resolve(
        rule: WorkTimeRule?,
        deckSlot: Int?,
        maxDeckSlots: Int,
        strategyExists: (String) -> Boolean,
    ): WorkTimeSlotStrategyDecision {
        if (rule == null) {
            return WorkTimeSlotStrategyDecision(deckSlot, null, "no-active-rule", "global-strategy")
        }
        if (deckSlot == null) return fallback(rule, null, "no-deck-slot", strategyExists)
        if (deckSlot !in 1..maxDeckSlots) return fallback(rule, deckSlot, "invalid-deck-slot", strategyExists)

        val paired = rule.pairedStrategyIds[deckSlot]?.takeIf { it.isNotBlank() }
        if (paired != null) {
            return if (strategyExists(paired)) {
                WorkTimeSlotStrategyDecision(deckSlot, paired, "paired-slot-strategy")
            } else {
                WorkTimeSlotStrategyDecision(deckSlot, null, "refused", "paired-strategy-missing")
            }
        }
        return fallback(rule, deckSlot, "no-paired-strategy", strategyExists)
    }

    private fun fallback(
        rule: WorkTimeRule,
        deckSlot: Int?,
        reason: String,
        strategyExists: (String) -> Boolean,
    ): WorkTimeSlotStrategyDecision {
        val strategy = rule.strategyId.takeIf { it.isNotBlank() && strategyExists(it) }
        return WorkTimeSlotStrategyDecision(
            deckSlot,
            strategy,
            if (strategy == null) "no-strategy" else "rule-strategy-fallback",
            reason,
        )
    }
}
