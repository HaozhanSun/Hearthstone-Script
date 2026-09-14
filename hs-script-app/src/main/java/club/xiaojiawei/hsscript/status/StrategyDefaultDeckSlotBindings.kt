package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.WorkTimeRule
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.config.log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

data class StrategyDeckSlotChoice(
    val deckSlots: List<Int>,
    val assignmentReason: String,
    val strategyId: String?,
)

object StrategyDefaultDeckSlotBindings {
    const val MIN_DECK_SLOT = 1
    const val MAX_DECK_SLOT = 9

    val builtInDefaults: Map<String, Int> =
        mapOf(
            DEFAULT_PIRATE_DEMON_HUNTER_STRATEGY_ID to 1,
            DEFAULT_PIRATE_WARRIOR_STRATEGY_ID to 2,
        )

    private val objectMapper = jacksonObjectMapper()

    fun defaultBindingsJson(): String = serialize(builtInDefaults)

    fun serialize(bindings: Map<String, Int>): String =
        objectMapper.writeValueAsString(sanitize(bindings))

    fun deserialize(raw: String): Map<String, Int> =
        runCatching { objectMapper.readValue<Map<String, Int>>(raw) }
            .getOrElse {
                log.warn(it) { "STRATEGY_DEFAULT_DECK_SLOT_PARSE_FAILED" }
                builtInDefaults
            }

    fun sanitize(
        bindings: Map<String, Int>,
        maxDeckSlots: Int = MAX_DECK_SLOT,
        knownStrategyIds: Set<String>? = null,
    ): Map<String, Int> =
        bindings
            .asSequence()
            .map { (strategyId, slot) -> strategyId.trim() to slot }
            .filter { (strategyId, slot) ->
                strategyId.isNotBlank() &&
                    slot in MIN_DECK_SLOT..maxDeckSlots &&
                    (knownStrategyIds == null || strategyId in knownStrategyIds)
            }
            .distinctBy { it.first }
            .associate { it }

    fun withBinding(
        bindings: Map<String, Int>,
        strategyId: String?,
        deckSlot: Int?,
        maxDeckSlots: Int = MAX_DECK_SLOT,
    ): Map<String, Int> {
        val normalizedStrategyId = strategyId?.trim().orEmpty()
        if (normalizedStrategyId.isBlank() || deckSlot == null || deckSlot !in MIN_DECK_SLOT..maxDeckSlots) {
            return sanitize(bindings, maxDeckSlots)
        }
        return sanitize(bindings + (normalizedStrategyId to deckSlot), maxDeckSlots)
    }

    fun readBindings(maxDeckSlots: Int = MAX_DECK_SLOT): Map<String, Int> =
        sanitize(deserialize(ConfigUtil.getString(ConfigEnum.STRATEGY_DEFAULT_DECK_SLOTS)), maxDeckSlots)

    fun deckSlotForStrategy(
        strategyId: String?,
        maxDeckSlots: Int = MAX_DECK_SLOT,
        bindings: Map<String, Int> = readBindings(maxDeckSlots),
    ): Int? =
        strategyId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { bindings[it] }
            ?.takeIf { it in MIN_DECK_SLOT..maxDeckSlots }

    fun storeBinding(
        strategyId: String,
        deckSlot: Int,
        maxDeckSlots: Int = MAX_DECK_SLOT,
    ): Map<String, Int> {
        val updated = withBinding(readBindings(maxDeckSlots), strategyId, deckSlot, maxDeckSlots)
        ConfigUtil.putString(ConfigEnum.STRATEGY_DEFAULT_DECK_SLOTS, serialize(updated))
        return updated
    }

    fun chooseDeckSlots(
        rule: WorkTimeRule?,
        strategyId: String?,
        globalDeckSlots: List<Int>,
        maxDeckSlots: Int = MAX_DECK_SLOT,
        bindings: Map<String, Int> = readBindings(maxDeckSlots),
    ): StrategyDeckSlotChoice {
        val explicitSlots = explicitRuleSlots(rule, maxDeckSlots)
        if (explicitSlots.isNotEmpty()) {
            return StrategyDeckSlotChoice(
                deckSlots = explicitSlots,
                assignmentReason = "schedule-explicit-deck-slot",
                strategyId = rule?.strategyId?.takeIf { it.isNotBlank() } ?: strategyId,
            )
        }

        val effectiveStrategyId = rule?.strategyId?.takeIf { it.isNotBlank() } ?: strategyId?.takeIf { it.isNotBlank() }
        deckSlotForStrategy(effectiveStrategyId, maxDeckSlots, bindings)?.let { slot ->
            return StrategyDeckSlotChoice(
                deckSlots = listOf(slot),
                assignmentReason = "strategy-default-deck-slot",
                strategyId = effectiveStrategyId,
            )
        }

        val validGlobalSlots = normalizeSlots(globalDeckSlots, maxDeckSlots)
        if (validGlobalSlots.isNotEmpty()) {
            return StrategyDeckSlotChoice(
                deckSlots = validGlobalSlots,
                assignmentReason = if (validGlobalSlots.size == 1) {
                    "single-configured-deck-slot"
                } else {
                    "random-configured-deck-slot"
                },
                strategyId = effectiveStrategyId,
            )
        }

        return StrategyDeckSlotChoice(
            deckSlots = listOf(MIN_DECK_SLOT),
            assignmentReason = "safe-default-deck-slot",
            strategyId = effectiveStrategyId,
        )
    }

    private fun explicitRuleSlots(
        rule: WorkTimeRule?,
        maxDeckSlots: Int,
    ): List<Int> {
        if (rule == null) return emptyList()
        val deckSlots = normalizeSlots(rule.deckPos.toList(), maxDeckSlots)
        if (deckSlots.isNotEmpty()) return deckSlots
        return normalizeSlots(rule.pairedStrategyIds.keys.toList(), maxDeckSlots)
    }

    private fun normalizeSlots(
        slots: List<Int>,
        maxDeckSlots: Int,
    ): List<Int> =
        slots
            .filter { it in MIN_DECK_SLOT..maxDeckSlots }
            .distinct()
}
