package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Entity
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
     * Bounded brave fallback for an otherwise valid but unresolved hand card.
     *
     * The fallback is intentionally shared by the tree builder and the live
     * actionable-card scan.  Minions and weapons have a safe generic play
     * gesture: Hearthstone resolves their battlecry/equip effect after the
     * card is played.  A spell is only eligible when it does not advertise a
     * discover/choice interaction; blindly clicking a targeted or choice card
     * is more likely to create a stuck turn than to make progress.  A missing
     * entity/card type is always fail-closed.
     */
    fun braveOpaqueFallbackAllowed(card: Card): Boolean {
        if (!card.isUncertain || card.cardId.isBlank() || card.entityId.isBlank() || card.cost < 0) {
            return false
        }
        return when (card.cardType) {
            // A battlecry minion may open a target/choice/drag prompt.  The
            // generic play gesture is safe only for a minion whose live
            // snapshot does not advertise that interaction.
            CardTypeEnum.MINION -> hasUsableUnknownMinionStats(card) &&
                !card.isBattlecry && !card.isChooseOne && !hasTargetOrChoiceText(card)
            CardTypeEnum.WEAPON -> true
            // A generic spell click is only safe when the local DB (or the
            // live name) positively tells us that no target/choice is needed.
            // An empty DB lookup is not evidence of a no-target spell: it is
            // the metadata-missing case that used to allow unsafe clicks.
            CardTypeEnum.SPELL -> !card.isDiscover && !card.isBattlecry && !card.isTradeable &&
                opaqueEffectSafety(card) === OpaqueEffectSafety.KNOWN_NO_TARGET
            else -> false
        }
    }

    /** Stable reason for a refused brave fallback; null means eligible. */
    fun opaqueFallbackBlockReason(card: Card): String? {
        if (!card.isUncertain || card.cardId.isBlank() || card.entityId.isBlank() || card.cost < 0) {
            return "invalid-opaque-fallback-identity"
        }
        return when (card.cardType) {
            CardTypeEnum.SPELL -> when {
                card.isDiscover -> "unsafe-discover"
                card.isBattlecry -> "unsafe-battlecry-spell"
                card.isTradeable -> "tradeable-spell-needs-explicit-choice"
                opaqueEffectSafety(card) === OpaqueEffectSafety.UNSAFE_TARGET_OR_CHOICE ->
                    "unsafe-target-or-choice"
                opaqueEffectSafety(card) === OpaqueEffectSafety.UNKNOWN_METADATA ->
                    "unknown-effect-metadata"
                else -> null
            }
            CardTypeEnum.MINION -> when {
                !hasUsableUnknownMinionStats(card) -> "missing-or-invalid-minion-stats"
                card.isBattlecry -> "unsafe-battlecry-minion"
                card.isChooseOne -> "unsafe-choose-one-minion"
                hasTargetOrChoiceText(card) -> "unsafe-target-or-choice"
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
}
