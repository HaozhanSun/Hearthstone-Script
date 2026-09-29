package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Entity
import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.util.CardDBUtil
import club.xiaojiawei.hsscriptcardsdk.util.CardIdentityCatalog
import java.util.concurrent.ConcurrentHashMap

/**
 * Machine-readable classification for the two independent card failures that
 * are easy to confuse in MCTS logs:
 *
 *  - a card may have a valid identity but no description-parser interceptor;
 *  - an entity snapshot may be incomplete/invalid and must not be planned as
 *    an ordinary minion merely because CardAction's generic fallback exists.
 *
 * The classifier has no side effects and deliberately does not invent card
 * text or game transitions.  It is shared by the scanner and offline tests.
 */
enum class MctsCardSnapshotStatus {
    VALID,
    UNKNOWN_ENTITY_NAME,
    UNKNOWN_CARD_TYPE,
    INVALID_CARD_TYPE,
    MISSING_CARD_ID,
}

private enum class OpaqueEffectSafety {
    KNOWN_NO_TARGET,
    UNSAFE_TARGET_OR_CHOICE,
    UNKNOWN_METADATA,
}

object MctsCardDiagnostics {
    const val GENERIC_OPAQUE_FALLBACK_PRIOR = -1_000_000.0
    private val targetHintCache = ConcurrentHashMap<String, Int>()
    private val minionStatCache = ConcurrentHashMap<String, Boolean>()
    private val targetOrChoiceHints = listOf(
        "目标",
        "选择",
        "抉择",
        "发现",
        "一个敌方",
        "一个友方",
        "一个角色",
        "一个敌人",
        "一个随从",
        "敌方随从",
        "友方随从",
        "敌方英雄",
        "友方英雄",
        "所有敌方",
        "所有友方",
    )
    // Some legacy database texts omit the target noun even though the
    // executable parser creates a directed action. Keep those known cards
    // fail-closed until the live action metadata is available.
    private val knownTargetedSpellIds = setOf(
        "CS2_029",
        "CORE_CS2_029",
    )
    fun displayName(card: Card): String =
        card.entityName.takeUnless { it.isBlank() || Entity.isUnknownEntityName(it) }
            ?: CardIdentityCatalog.lookup(card.cardId)?.name
            ?: card.cardId.takeUnless { it.isBlank() }?.let { "未知卡牌($it)" }
            ?: "未知卡牌"

    fun snapshotStatus(card: Card): MctsCardSnapshotStatus = when {
        card.cardId.isBlank() -> MctsCardSnapshotStatus.MISSING_CARD_ID
        card.cardType === CardTypeEnum.INVALID -> MctsCardSnapshotStatus.INVALID_CARD_TYPE
        card.cardType === CardTypeEnum.UNKNOWN -> MctsCardSnapshotStatus.UNKNOWN_CARD_TYPE
        Entity.isUnknownEntityName(card.entityName) -> MctsCardSnapshotStatus.UNKNOWN_ENTITY_NAME
        else -> MctsCardSnapshotStatus.VALID
    }

    fun isFatalSnapshot(status: MctsCardSnapshotStatus): Boolean =
        status === MctsCardSnapshotStatus.MISSING_CARD_ID ||
            status === MctsCardSnapshotStatus.INVALID_CARD_TYPE ||
            status === MctsCardSnapshotStatus.UNKNOWN_CARD_TYPE

    /** Spells and battlecry minions need text semantics beyond generic play. */
    fun requiresDescriptionAction(card: Card): Boolean =
        card.cardType === CardTypeEnum.SPELL || card.isBattlecry

    /**
     * Unknown minions may use the ordinary generic-play fallback only when
     * the live entity or the local card DB supplies usable combat stats.
     * Zero/default stats are not enough evidence: they are also what an
     * incomplete entity snapshot uses for missing attack/health.
     */
    fun hasUsableUnknownMinionStats(card: Card): Boolean {
        if (card.cardType !== CardTypeEnum.MINION) return false
        if (card.atc > 0 && card.health > 0) return true
        val cardId = card.cardId
        if (cardId.isBlank()) return false
        return minionStatCache.getOrPut(cardId) {
            runCatching { CardDBUtil.queryCardById(cardId).firstOrNull() }
                .getOrNull()
                ?.let { dbCard ->
                    val attack = dbCard.attack
                    val health = dbCard.health
                    attack != null && attack > 0 && health != null && health > 0
                } == true
        }
    }

    /**
     * A generic minion click is safe only when its combat snapshot is usable
     * and a battlecry does not require a target or a choice that the
     * simulator cannot provide.  Non-battlecry minions need no effect
     * semantics; battlecry minions must have authoritative text in the local
     * database and that text must be target/choice-free.
     */
    fun safeOpaqueMinionFallbackAllowed(card: Card): Boolean {
        if (card.cardType !== CardTypeEnum.MINION || card.cardId.isBlank() || card.entityId.isBlank() || card.cost < 0) {
            return false
        }
        if (card.isDiscover || card.isChooseOne || !hasUsableUnknownMinionStats(card)) return false
        if (!card.isBattlecry) return true
        return opaqueEffectSafety(card) === OpaqueEffectSafety.KNOWN_NO_TARGET
    }

    /**
     * Bounded brave fallback for an otherwise valid but unresolved hand card.
     *
     * The fallback is intentionally shared by the tree builder and the live
     * actionable-card scan.  The executor can initiate the normal hand-card
     * gesture even when the semantic parser has no interceptor; Hearthstone
     * then owns the battlecry, target, discover, trade, or choice prompt.
     * The simulator does not invent those effects: the action is heavily
     * penalized, marked for re-plan, and must never outrank a modeled action.
     * A missing identity, type, or minion combat snapshot remains fail-closed.
     */
    fun braveOpaqueFallbackAllowed(card: Card): Boolean {
        if ((!card.isUncertain && !card.action.common) || card.cardId.isBlank() || card.entityId.isBlank() || card.cost < 0) {
            return false
        }
        if (card.isDiscover || card.isChooseOne) return false
        return when (card.cardType) {
            CardTypeEnum.MINION -> safeOpaqueMinionFallbackAllowed(card)
            CardTypeEnum.WEAPON -> true
            // A generic spell click is only safe when the local database
            // positively proves that it has no target/choice semantics. The
            // simulator cannot invent a target or resolve a choice prompt.
            CardTypeEnum.SPELL -> opaqueEffectSafety(card) === OpaqueEffectSafety.KNOWN_NO_TARGET
            else -> false
        }
    }

    /** Stable reason for a refused brave fallback; null means eligible. */
    fun opaqueFallbackBlockReason(card: Card): String? {
        if ((!card.isUncertain && !card.action.common) || card.cardId.isBlank() || card.entityId.isBlank() || card.cost < 0) {
            return "invalid-opaque-fallback-identity"
        }
        if (card.isDiscover || card.isChooseOne) return "unsafe-target-or-choice"
        return when (card.cardType) {
            CardTypeEnum.SPELL -> when (opaqueEffectSafety(card)) {
                OpaqueEffectSafety.KNOWN_NO_TARGET -> null
                OpaqueEffectSafety.UNSAFE_TARGET_OR_CHOICE -> "unsafe-target-or-choice"
                OpaqueEffectSafety.UNKNOWN_METADATA -> "unknown-effect-metadata"
            }
            CardTypeEnum.MINION -> when {
                !hasUsableUnknownMinionStats(card) -> "missing-or-invalid-minion-stats"
                card.isDiscover || card.isChooseOne || (card.isBattlecry && opaqueEffectSafety(card) === OpaqueEffectSafety.UNSAFE_TARGET_OR_CHOICE) -> "unsafe-target-or-choice"
                card.isBattlecry && opaqueEffectSafety(card) === OpaqueEffectSafety.UNKNOWN_METADATA -> "unknown-effect-metadata"
                else -> null
            }
            CardTypeEnum.WEAPON -> null
            else -> "unsupported-card-type"
        }
    }

    /**
     * Use authoritative local card text when it exists to prevent an opaque
     * click from opening a target/choice prompt.  Missing DB text is treated
     * as unknown effect metadata for spells, so the generic spell click is
     * refused and the collector can surface the card for later definition.
     */
    private fun opaqueEffectSafety(card: Card): OpaqueEffectSafety {
        val cardId = card.cardId
        if (cardId.isBlank()) return OpaqueEffectSafety.UNKNOWN_METADATA
        if (cardId in knownTargetedSpellIds) return OpaqueEffectSafety.UNSAFE_TARGET_OR_CHOICE
        val visibleName = displayName(card).replace(" ", "").replace("\n", "")
        if (visibleName.isNotBlank() && targetOrChoiceHints.any(visibleName::contains)) {
            return OpaqueEffectSafety.UNSAFE_TARGET_OR_CHOICE
        }
        return targetHintCache.getOrPut(cardId) {
            val dbCard = runCatching { CardDBUtil.queryCardById(cardId).firstOrNull() }.getOrNull()
            when {
                dbCard == null || dbCard.text.isBlank() -> 2
                targetOrChoiceHints.any(dbCard.text.replace(" ", "").replace("\n", "")::contains) -> 1
                else -> 0
            }
        }.let {
            when (it) {
                1 -> OpaqueEffectSafety.UNSAFE_TARGET_OR_CHOICE
                2 -> OpaqueEffectSafety.UNKNOWN_METADATA
                else -> OpaqueEffectSafety.KNOWN_NO_TARGET
            }
        }
    }

    private fun hasTargetOrChoiceText(card: Card): Boolean =
        opaqueEffectSafety(card) === OpaqueEffectSafety.UNSAFE_TARGET_OR_CHOICE

    /**
     * Route labels are intentionally stable: replay tooling can aggregate
     * them without depending on Kotlin class names.
     */
    fun actionRoute(
        snapshotStatus: MctsCardSnapshotStatus,
        requiresDescriptionAction: Boolean,
        actionIsCommon: Boolean,
        parsedActionCount: Int,
        opaqueFallbackAllowed: Boolean,
        decisionModelInstalled: Boolean,
        opaqueFallbackBlockReason: String? = null,
    ): String = when {
        isFatalSnapshot(snapshotStatus) -> "FAIL_CLOSED_INVALID_SNAPSHOT"
        !opaqueFallbackAllowed && opaqueFallbackBlockReason == "unknown-effect-metadata" ->
            "FAIL_CLOSED_UNKNOWN_EFFECT_METADATA"
        !opaqueFallbackAllowed && opaqueFallbackBlockReason == "unsafe-target-or-choice" ->
            "FAIL_CLOSED_UNSAFE_TARGET_OR_CHOICE"
        requiresDescriptionAction && actionIsCommon && opaqueFallbackAllowed ->
            "OPAQUE_FALLBACK"
        requiresDescriptionAction && actionIsCommon && decisionModelInstalled ->
            "FAIL_CLOSED_PARSER_UNAVAILABLE"
        parsedActionCount > 0 && !actionIsCommon -> "PARSED"
        parsedActionCount > 0 -> "COMMON_GENERIC"
        opaqueFallbackAllowed -> "OPAQUE_FALLBACK"
        else -> "NO_PLAY_ACTION"
    }

    fun safeAction(route: String): String = when (route) {
        "PARSED" -> "EXECUTE_PARSED"
        "COMMON_GENERIC" -> "EXECUTE_GENERIC"
        "OPAQUE_FALLBACK" -> "EXECUTE_GENERIC_WITH_REPLAN"
        "FAIL_CLOSED_INVALID_SNAPSHOT",
        "FAIL_CLOSED_PARSER_UNAVAILABLE",
        "FAIL_CLOSED_UNKNOWN_EFFECT_METADATA",
        "FAIL_CLOSED_UNSAFE_TARGET_OR_CHOICE" -> "SKIP_UNRECOGNIZED"
        else -> "SKIP_NO_ACTION"
    }

    /**
     * Keep the app-side live scan on the same route contract as the MCTS
     * root scan. A generic CardAction fallback must not resurrect a
     * parser-sensitive card that the tree deliberately filtered out.
     */
    fun isLiveActionableRoute(route: String, hasLegalParsedAction: Boolean): Boolean = when (route) {
        "OPAQUE_FALLBACK" -> true
        "PARSED", "COMMON_GENERIC" -> hasLegalParsedAction
        else -> false
    }

    /**
     * Generic opaque cards are executable last resorts, not normal semantic
     * candidates.  Their simulator spends mana/removes the card but cannot
     * prove an unknown effect or target, so all experimental MCTS selectors
     * must put them behind modeled actions.  The live turn guard can still
     * reach this route when it is the only affordable action.
     */
    fun genericOpaqueFallbackPrior(action: Action): Double {
        val card = action.creator ?: return 0.0
        return if (action is PlayAction && action.recalculate && card.action.common &&
            braveOpaqueFallbackAllowed(card)
        ) {
            GENERIC_OPAQUE_FALLBACK_PRIOR
        } else {
            0.0
        }
    }
}
