package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.WorkTimeRule
import club.xiaojiawei.hsscript.bean.WorkTimeRuleSet
import kotlin.math.roundToInt

const val DEFAULT_PIRATE_DEMON_HUNTER_STRATEGY_ID = "e71234fa-7-pirate-demon-hunter-mcts-global-plan-2f0f-4d4d-a5cf"
const val DEFAULT_PIRATE_WARRIOR_STRATEGY_ID = "e71234fa-8-pirate-warrior-mcts-9b1f-4d29-8f4f"
const val DEFAULT_ELEMENTAL_MAGE_STRATEGY_ID = "e71234fa-11-elemental-mage-mcts-v1-1-9b1f-4d29-8f4f"
const val DEFAULT_SLOT_STRATEGY_ASSIGNMENT_SEED = "work-time-slot-strategy-binding-v1"

data class WorkTimeRuleSlotStrategyNormalizationEvent(
    val ruleSetId: String,
    val ruleSetName: String,
    val ruleIndex: Int,
    val beforeDeckPos: Set<Int>,
    val afterDeckSlot: Int?,
    val pairedStrategyId: String?,
    val assignmentReason: String,
)

data class WorkTimeRuleSlotStrategyNormalizationResult(
    val ruleSets: List<WorkTimeRuleSet>,
    val events: List<WorkTimeRuleSlotStrategyNormalizationEvent>,
)

object WorkTimeRuleSlotStrategyNormalizer {
    const val DEFAULT_PRESET_ONE_ID = "presets-one"

    val defaultPairedStrategyIds = mapOf(
        1 to DEFAULT_PIRATE_DEMON_HUNTER_STRATEGY_ID,
        2 to DEFAULT_PIRATE_WARRIOR_STRATEGY_ID,
    )

    fun pairedStrategyId(deckSlot: Int): String? = defaultPairedStrategyIds[deckSlot]

    fun normalizeRulesForDefaultPreset(rules: List<WorkTimeRule>): List<WorkTimeRule> {
        val ruleSet = WorkTimeRuleSet("预设1", rules, DEFAULT_PRESET_ONE_ID)
        normalize(listOf(ruleSet))
        return ruleSet.getTimeRules()
    }

    fun normalize(ruleSets: List<WorkTimeRuleSet>): WorkTimeRuleSlotStrategyNormalizationResult {
        val events = mutableListOf<WorkTimeRuleSlotStrategyNormalizationEvent>()
        ruleSets.forEach { ruleSet ->
            val rules = ruleSet.getTimeRules()
            val seeded = ruleSet.id == DEFAULT_PRESET_ONE_ID && rules.size > 1
            val distribution = if (seeded) distributedSlots(rules.size) else emptyList()
            rules.forEachIndexed { index, rule ->
                val before = rule.deckPos.toSet()
                val beforePairs = rule.pairedStrategyIds.toMap()
                val slot = when {
                    seeded -> distribution[index]
                    before.size == 1 && before.single() in 1..9 -> before.single()
                    else -> distributedSlots(rules.size).getOrElse(index) { 1 }
                }
                val reason = when {
                    seeded -> "preset-one-seeded-distribution:$DEFAULT_SLOT_STRATEGY_ASSIGNMENT_SEED"
                    before.size == 1 && before.single() in 1..9 -> "preserve-single-slot"
                    before.isEmpty() -> "empty-slot-seeded-default:$DEFAULT_SLOT_STRATEGY_ASSIGNMENT_SEED"
                    else -> "legacy-multi-slot-normalized:$DEFAULT_SLOT_STRATEGY_ASSIGNMENT_SEED"
                }
                rule.deckPos = setOf(slot)
                rule.pairedStrategyIds = pairedStrategyId(slot)?.let { mapOf(slot to it) } ?: emptyMap()
                if (before != rule.deckPos || beforePairs != rule.pairedStrategyIds) {
                    events += WorkTimeRuleSlotStrategyNormalizationEvent(
                        ruleSet.id, ruleSet.getName(), index, before, slot,
                        rule.pairedStrategyIds[slot], reason,
                    )
                }
            }
        }
        return WorkTimeRuleSlotStrategyNormalizationResult(ruleSets, events)
    }

    fun assignmentFor(ruleSet: WorkTimeRuleSet): List<Int> =
        if (ruleSet.id == DEFAULT_PRESET_ONE_ID && ruleSet.getTimeRules().size > 1) {
            distributedSlots(ruleSet.getTimeRules().size)
        } else {
            ruleSet.getTimeRules().map { it.deckPos.singleOrNull()?.takeIf { slot -> slot in 1..9 } ?: 1 }
        }

    private fun distributedSlots(size: Int): List<Int> {
        if (size <= 0) return emptyList()
        val warriorCount = (size / 3.0).roundToInt().coerceIn(1, size)
        val offset = Math.floorMod(DEFAULT_SLOT_STRATEGY_ASSIGNMENT_SEED.hashCode(), size)
        val warriorIndices = (0 until warriorCount).map { n ->
            Math.floorMod(offset + ((n + 0.5) * size / warriorCount).toInt(), size)
        }.toMutableSet()
        var probe = 0
        while (warriorIndices.size < warriorCount) {
            warriorIndices += Math.floorMod(offset + probe++, size)
        }
        return (0 until size).map { if (it in warriorIndices) 2 else 1 }
    }
}
