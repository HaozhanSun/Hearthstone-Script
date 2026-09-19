package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.bean.WarScoreCalculatorBuilder
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsActionOrderPhase
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsDecisionModel
import club.xiaojiawei.hsscriptcardsdk.mcts.defaultMctsActionOrderPhase
import club.xiaojiawei.hsscriptcardsdk.mcts.PirateDamageAuraPolicy
import club.xiaojiawei.hsscriptcardsdk.mcts.CardTimingPolicy
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsCardDiagnostics
import kotlin.math.max
import java.util.concurrent.ConcurrentHashMap

/**
 * Isolated card model for the screenshot Pirate Warrior list.
 *
 * The first three timing rules are intentionally explicit and ordered:
 * the first-turn quest deadline, playable Ship's Cannon, then Treasure
 * Distributor.  The rest of the model remains a soft prior so combat and
 * generated-card choices can still be decided by MCTS.
 */
object PirateWarriorMctsModel : MctsDecisionModel {
    const val APPLAUSE = "ETC_372"
    const val APPLAUSE_COST = 2
    const val TREASURE_DISTRIBUTOR = "TOY_518"
    const val QUESTLINE = "SW_028"
    const val QUEST_REWARD = "SW_028t5"
    const val PATCHES_THE_PIRATE = "CFM_637"
    const val PARACHUTE_BRIGAND = "DRG_056"
    const val SHIPS_CANNON = "GVG_075"
    const val SOUTHSEA_DECKHAND = "CORE_CS2_146"
    const val NZOTHS_FIRST_MATE = "OG_312"
    const val SOUTHSEA_CAPTAIN = "NEW1_027"
    const val HOZEN_ROUGHHOUSER = "VAC_938"
    const val RAGEWING = "YOD_032"
    const val JUGGERNAUT = "SW_028t6"
    const val BATTLEFIELD = "AV_661"

    const val HOOKFIST = "CORE_NX2_028"
    const val ANCHOR = "DRG_025"
    const val FRONTLINE_AXE = "BAR_844"
    const val BLASTPOWDER_ENGINEER = "CAP_104"
    const val CANNONMASTER = "CAP_107"
    const val HOOK_N_HEAVE = "CAP_105"
    const val CAPTAIN_CROWLEY = "CAP_106"

    enum class OpeningCannonCoinStep {
        NONE,
        PLAY_COIN,
        PLAY_CANNON,
        PLAY_QUEST,
        PLAY_PIRATE,
    }

    private data class OpeningHandCardSnapshot(
        val entityId: String,
        val cardId: String,
        val cost: Int,
        val pirate: Boolean,
    )

    private data class OpeningHandSnapshot(
        val cards: List<OpeningHandCardSnapshot>,
        val coinPresentAtStart: Boolean,
    )

    private val openingHandSnapshots = ConcurrentHashMap<String, OpeningHandSnapshot>()

    fun registerOpeningHandSnapshot(war: War, cards: Collection<Card>) {
        openingHandSnapshots[openingKey(war)] = OpeningHandSnapshot(
            cards = cards.map {
                OpeningHandCardSnapshot(
                    entityId = it.entityId,
                    cardId = it.cardId,
                    cost = it.cost,
                    pirate = isPirate(it),
                )
            },
            coinPresentAtStart = war.me.handArea.cards.any(::isCoin),
        )
    }

    fun clearOpeningHandSnapshot(war: War) {
        openingHandSnapshots.remove(openingKey(war))
    }

    /** Deterministic state for the narrowly-scoped going-second opening line. */
    fun openingCannonCoinStep(war: War): OpeningCannonCoinStep {
        val snapshot = openingHandSnapshots[openingKey(war)] ?: return OpeningCannonCoinStep.NONE
        if (!snapshot.coinPresentAtStart) return OpeningCannonCoinStep.NONE
        val originalCannon = snapshot.cards.firstOrNull { isCardId(it.cardId, SHIPS_CANNON) }
            ?: return OpeningCannonCoinStep.NONE
        val originalPirate = snapshot.cards.firstOrNull {
            it.pirate && it.cost == 1 && !isCardId(it.cardId, SHIPS_CANNON)
        } ?: return OpeningCannonCoinStep.NONE
        val cannon = war.me.handArea.cards.firstOrNull { it.entityId == originalCannon.entityId }
        val pirate = war.me.handArea.cards.firstOrNull { it.entityId == originalPirate.entityId }
        if (pirate == null || !isPirate(pirate) || pirate.cost != 1) return OpeningCannonCoinStep.NONE

        if (isFirstTurn(war)) {
            if (cannon == null) return OpeningCannonCoinStep.NONE
            val coin = war.me.handArea.cards.firstOrNull(::isCoin)
            if (
                coin != null &&
                cannon.cost > war.me.usableResource &&
                cannon.cost <= war.me.usableResource + 1 &&
                hasGeneratedLegalPlay(coin, war) &&
                !hasGeneratedLegalPlay(cannon, war)
            ) return OpeningCannonCoinStep.PLAY_COIN
            if (
                coin == null &&
                war.me.tempResources > 0 &&
                hasGeneratedLegalPlay(cannon, war) &&
                war.me.playArea.cards.none { it.entityId == cannon.entityId }
            ) return OpeningCannonCoinStep.PLAY_CANNON
            return OpeningCannonCoinStep.NONE
        }

        if (war.me.turn != 2 || !hasPlayedOpeningCannon(war, originalCannon.entityId)) {
            return OpeningCannonCoinStep.NONE
        }
        val quest = war.me.handArea.cards.firstOrNull {
            isCard(it, QUESTLINE) && isPlayable(it, war)
        }
        if (quest != null) return OpeningCannonCoinStep.PLAY_QUEST

        val pirateStillPlayable = hasGeneratedLegalPlay(pirate, war)
        if (hasPlayedQuestline(war) && pirateStillPlayable) return OpeningCannonCoinStep.PLAY_PIRATE
        return OpeningCannonCoinStep.NONE
    }

    private enum class FrontlineAxeTarget {
        HERO,
        MINION,
        UNKNOWN,
    }

    /**
     * Safe, known-meaning fallback cards. New CAP cards are intentionally not
     * included: known text is not the same as a verified local action/state
     * transition, so an unknown parser result must fail closed.
     */
    private val opaqueKnownCards = setOf(
        SHIPS_CANNON,
        QUESTLINE,
        QUEST_REWARD,
        TREASURE_DISTRIBUTOR,
        BATTLEFIELD,
    )

    fun isCard(card: Card, id: String): Boolean =
        card.cardId == id ||
            card.cardId == "CORE_$id" ||
            (id.startsWith("CORE_") && card.cardId == id.removePrefix("CORE_")) ||
            card.cardId.startsWith("${id}t") ||
            card.cardId.startsWith("CORE_${id}t")

    private fun isCardId(cardId: String, id: String): Boolean =
        cardId == id ||
            cardId == "CORE_$id" ||
            (id.startsWith("CORE_") && cardId == id.removePrefix("CORE_")) ||
            cardId.startsWith("${id}t") ||
            cardId.startsWith("CORE_${id}t")

    private fun isCoin(card: Card): Boolean = card.isCoinCard || card.cardId == "COIN"

    private fun openingKey(war: War): String =
        "${war.me.gameId}|${war.me.playerId}|${war.startTime}"

    private fun hasGeneratedLegalPlay(card: Card, war: War): Boolean {
        if ((!card.isUncertain && card.cardId.isBlank()) || card.cost > war.me.usableResource) return false
        if (usesBoardSlot(card) && freeSlots(war) == 0) return false
        return runCatching { card.action.generatePlayActions(war, war.me) }
            .getOrDefault(emptyList())
            .any { isActionLegal(it, war) } ||
            (!isCoin(card) && (canCreateOpaqueAction(card, war) || MctsCardDiagnostics.braveOpaqueFallbackAllowed(card)))
    }

    private fun hasPlayedOpeningCannon(war: War, entityId: String): Boolean =
        war.me.playArea.cards.any { it.entityId == entityId && isCard(it, SHIPS_CANNON) }

    private fun hasPlayedQuestline(war: War): Boolean =
        sequenceOf(
            war.me.playArea.cards,
            war.me.graveyardArea.cards,
            war.me.setasideArea.cards,
            war.me.removedfromgameArea.cards,
        ).flatten().any { isCard(it, QUESTLINE) }

    fun isQuestReward(card: Card): Boolean = isCard(card, QUEST_REWARD)

    fun isPirate(card: Card): Boolean =
        card.cardRace === CardRaceEnum.PIRATE ||
            card.cardRace === CardRaceEnum.ALL ||
            isCard(card, SOUTHSEA_DECKHAND)

    /** Conservative projection for 掌声雷动's distinct friendly minion types. */
    fun friendlyMinionTypes(war: War): Set<CardRaceEnum> =
        war.me.playArea.cards.asSequence()
            .filter { it.cardType === CardTypeEnum.MINION && it.isAlive() }
            .map { it.cardRace }
            .filter { it !== CardRaceEnum.UNKNOWN && it !== CardRaceEnum.ALL }
            .toSet()

    fun projectedFriendlyMinionTypes(action: Action, war: War): Set<CardRaceEnum> {
        val projected = runCatching { simulateOnClone(action, war)?.let(::friendlyMinionTypes) }
            .getOrNull()
            ?.toMutableSet()
            ?: friendlyMinionTypes(war).toMutableSet()
        val creator = action.creator
        if (action is PlayAction && creator?.cardType === CardTypeEnum.MINION) {
            creator.cardRace
                .takeUnless { it === CardRaceEnum.UNKNOWN || it === CardRaceEnum.ALL }
                ?.let(projected::add)
        }
        return projected
    }

    data class ApplauseDrawValuation(
        val currentTypes: Set<CardRaceEnum>,
        val projectedTypes: Set<CardRaceEnum>,
        val drawCount: Int,
        val manaAfterAction: Int,
        val leavesTwoMana: Boolean,
    )

    fun applauseDrawValuation(action: Action, war: War): ApplauseDrawValuation {
        val current = friendlyMinionTypes(war)
        val projected = projectedFriendlyMinionTypes(action, war)
        val manaAfter = (war.me.usableResource - (action.creator?.cost ?: 0)).coerceAtLeast(0)
        return ApplauseDrawValuation(current, projected, 1 + projected.size, manaAfter, manaAfter >= APPLAUSE_COST)
    }

    override fun canCreateOpaqueAction(card: Card, war: War): Boolean =
        card.entityId.isNotBlank() &&
            (MctsCardDiagnostics.braveOpaqueFallbackAllowed(card) ||
                (!card.isUncertain && opaqueKnownCards.any { isCard(card, it) }))

    override fun preDispatchWaitMillis(action: Action, war: War): Long =
        if (action is PlayAction && action.creator?.let(::isQuestReward) == true) 5_000L else 0L

    override fun shouldRetryAfterUnconfirmedDispatch(action: Action, war: War, attempt: Int): Boolean =
        action is PlayAction && action.creator?.let(::isQuestReward) == true && attempt == 0

    /** Keep Patches available only as a last-resort action; mulligan removes it. */
    override fun actionPrior(action: Action, war: War): Double {
        val card = action.creator ?: return 0.0
        if (isCard(card, PATCHES_THE_PIRATE)) return -1_000.0

        // The live card cost already includes the opponent-board reduction.
        // A zero-cost Aredar Brute must be consumed before another action can
        // remove an enemy body and make the same card more expensive.
        if (CardTimingPolicy.isAredarBrute(card) && card.cost == 0) return 1_000.0

        if (PirateConditionalDamageSpellPolicy.isAction(action)) {
            return PirateConditionalDamageSpellPolicy.softPrior(action, war)
        }
        if (action is PlayAction && isWeaponEquipCard(card)) {
            return if (hasEquippedWeapon(war)) -1_000.0 else 22.0
        }

        val otherPirates = otherPirates(war, card)
        val attackablePirates = war.me.playArea.cards.count { isPirate(it) && it.canAttack() }
        val freeSlots = freeSlots(war)

        return when {
            isCard(card, PirateHeroAttackTargetPolicy.NU_LING_NAGA) ->
                PirateHeroAttackTargetPolicy.nuLingNagaPlayPrior(action, war)
            isQuestReward(card) -> 120.0
            isCard(card, QUESTLINE) && isFirstTurn(war) -> 110.0
            isCard(card, SHIPS_CANNON) -> 100.0
            isCard(card, TREASURE_DISTRIBUTOR) -> 90.0 + otherPirates * 2.0
            isCard(card, APPLAUSE) -> applauseSpellPrior(action, war)
            isCard(card, BATTLEFIELD) -> {
                val friendlyMinions = war.me.playArea.cards.count { it.cardType === CardTypeEnum.MINION }
                // Battlefield's delayed buff is weak without an established
                // board. Keep it as a legal fallback, but deprioritize it
                // until at least two friendly minions are present.
                if (friendlyMinions <= 1) -22.0 else 4.0 + friendlyMinions
            }
            isCard(card, CANNONMASTER) -> if (freeSlots > 0) 36.0 else -36.0
            isCard(card, BLASTPOWDER_ENGINEER) ->
                if (otherPirates > 0 || attackablePirates > 0) 28.0 else 8.0
            isCard(card, SOUTHSEA_CAPTAIN) ->
                if (otherPirates > 0) 30.0 + attackablePirates * 3.0 else -18.0
            isCard(card, HOZEN_ROUGHHOUSER) ->
                if (otherPirates > 0) 28.0 + attackablePirates * 3.0 else -18.0
            isCard(card, HOOK_N_HEAVE) -> if (freeSlots >= 2) 24.0 else -30.0
            isCard(card, CAPTAIN_CROWLEY) -> if (freeSlots >= 3) 26.0 else -100.0
            isCard(card, RAGEWING) -> if (card.cost <= 1) 24.0 else 5.0
            isCard(card, HOOKFIST) -> when {
                hasWeapon(war) || canPlayWeaponThisTurn(war, card) -> 18.0
                !hasOtherPlayableMinion(war, card) -> 4.0
                else -> -24.0
            }
            isCard(card, ANCHOR) || isCard(card, FRONTLINE_AXE) ->
                if (hasWeapon(war)) 4.0 else 16.0
            action is PowerAction && card.cardType === CardTypeEnum.HERO_POWER ->
                if (hasOtherUsefulNonHeroPowerAction(war)) -1_000.0 else -10.0
            isFrontlineAxeHeroAttack(action, war) -> when (frontlineAxeTarget(action, war)) {
                FrontlineAxeTarget.MINION -> if (PirateHeroAttackTargetPolicy.isLegal(action, war)) {
                    if (frontlineAxeCanKill(action, war)) 30.0 else 10.0
                } else -1_000.0
                FrontlineAxeTarget.HERO -> if (PirateLethalAttackPolicy.isLethalFaceAction(action, war)) {
                    80.0
                } else if (PirateHeroAttackTargetPolicy.isLegal(action, war)) {
                    20.0
                } else {
                    -1_000.0
                }
                FrontlineAxeTarget.UNKNOWN -> -1_000.0
            }
            action is AttackAction && isPirate(card) ->
                effectivePirateAttack(card, war) * 0.45 +
                    PirateHeroAttackTargetPolicy.nuLingNagaAttackPrior(action, war)
            action is AttackAction && card.cardType === CardTypeEnum.MINION ->
                PirateHeroAttackTargetPolicy.nuLingNagaAttackPrior(action, war)
            else -> applauseSetupPriorOrNull(action, war) ?: 0.0
        }
    }

    /**
     * Hard sequencing, not a prior: if a legal priority action exists, the
     * current search node is restricted to that action. The first-turn quest
     * deadline is checked before Cannon because the quest is the first-action
     * requirement on turn one; Cannon remains P0 once that deadline is clear.
     */
    override fun isMandatoryAction(action: Action, war: War): Boolean {
        val zeroCostAredar = war.me.handArea.cards.firstOrNull {
            CardTimingPolicy.isAredarBrute(it) && isPlayable(it, war) && it.cost == 0
        }
        if (zeroCostAredar != null) {
            return action is PlayAction && action.creator?.let {
                CardTimingPolicy.isAredarBrute(it) && it.cost == 0
            } == true
        }

        // Nu Ling Naga must remain the last friendly-minion attacker. Keep
        // this ahead of the combo/setup mandatory filter so another legal
        // minion attack gets its death-trigger window first.
        if (PirateAttackOrderPolicy.shouldDeferNuLingNagaAttack(action, war)) return false
        if (PirateAttackOrderPolicy.shouldDeferAdrenalineFiendAttack(action, war)) return false
        if (PirateAttackOrderPolicy.shouldDeferHozenRoughhouserAttack(action, war)) return false
        if (PirateAttackOrderPolicy.shouldDeferTreasureDistributorAttack(action, war)) return false

        when (openingCannonCoinStep(war)) {
            OpeningCannonCoinStep.PLAY_COIN ->
                return action is PlayAction && action.creator?.let(::isCoin) == true
            OpeningCannonCoinStep.PLAY_CANNON ->
                return action is PlayAction && action.creator?.let { isCard(it, SHIPS_CANNON) } == true
            OpeningCannonCoinStep.PLAY_QUEST ->
                return action is PlayAction && action.creator?.let { isCard(it, QUESTLINE) } == true
            OpeningCannonCoinStep.PLAY_PIRATE -> {
                val pirateEntityId = war.me.handArea.cards.firstOrNull {
                    isPirate(it) && it.cost == 1 &&
                        openingHandSnapshots[openingKey(war)]?.cards?.any { snapshot ->
                            snapshot.entityId == it.entityId && snapshot.pirate && snapshot.cost == 1
                        } == true
                }?.entityId
                return action is PlayAction && action.creator?.entityId == pirateEntityId
            }
            OpeningCannonCoinStep.NONE -> Unit
        }

        val questReward = war.me.handArea.cards.firstOrNull {
            isQuestReward(it) && isPlayable(it, war)
        }
        if (questReward != null) {
            return action is PlayAction && action.creator?.let(::isQuestReward) == true
        }

        val quest = war.me.handArea.cards.firstOrNull {
            isCard(it, QUESTLINE) && isPlayable(it, war)
        }
        if (isFirstTurn(war) && quest != null) {
            return action is PlayAction && action.creator?.let { isCard(it, QUESTLINE) } == true
        }

        val cannon = war.me.handArea.cards.firstOrNull { isCannonPlayable(it, war) }
        if (cannon != null) {
            return action is PlayAction && action.creator?.let { isCard(it, SHIPS_CANNON) } == true
        }

        val distributor = war.me.handArea.cards.firstOrNull {
            isCard(it, TREASURE_DISTRIBUTOR) && isPlayable(it, war)
        }
        if (distributor != null) {
            return action is PlayAction && action.creator?.let { isCard(it, TREASURE_DISTRIBUTOR) } == true
        }

        // When Treasure Distributor is already alive, Hook N' Heave is no
        // longer an ordinary discover spell: its two summoned Pirates receive
        // the Distributor attack bonus.  Make that value explicit at the root
        // so a weapon or an unrelated minion cannot win the phase selection.
        if (isDistributorHookNHeaveAction(action, war)) {
            return true
        }

        // A visible Taunt permits hero-first combat after hand plays. Consume
        // an otherwise usable hero power before the hero's Taunt attack; the
        // next re-plan then exposes friendly minion attacks. Combined-damage
        // routes stay on the existing setup-first path.
        if (allowsTauntEarlyHeroAction(war)) {
            if (PirateAttackOrderPolicy.hasUsableHeroPowerAction(war)) {
                return PirateAttackOrderPolicy.isHeroPowerAction(action)
            }
            return action is AttackAction && action.creator?.cardType === CardTypeEnum.HERO
        }

        // Once a fresh re-plan exposes a friendly minion that can finish an
        // enemy minion, do not let the MCTS face-damage prior skip that kill.
        // This is the case that previously left a 5-attack minion at 2 health
        // on board while the next Pirate attacked the enemy hero.
        if (PirateAttackOrderPolicy.hasDirectFriendlyMinionKillAction(war, ::attackDamage)) {
            return PirateAttackOrderPolicy.isDirectFriendlyMinionKillAction(
                action,
                war,
                ::attackDamage,
            )
        }

        // If the hero needs friendly minion damage to finish the selected
        // highest-threat enemy, commit those setup attacks to that target
        // before allowing the hero attack phase to begin.
        if (PirateHeroAttackTargetPolicy.requiresFriendlySetupAttack(war)) {
            return PirateHeroAttackTargetPolicy.isRequiredFriendlySetupAttack(action, war)
        }
        return false
    }

    /**
     * Hard legality that the generic action generator cannot express: the
     * Frontline Axe face restriction and Captain Crowley's three-slot summon
     * requirement. This is intentionally separate from actionPrior.
     */
    override fun isActionLegal(action: Action, war: War): Boolean {
        if (PirateLethalAttackPolicy.isLethalFaceAction(action, war)) return true

        if (!PirateHeroAttackTargetPolicy.isLegal(action, war)) return false

        if (PirateAttackOrderPolicy.isUnkillableTauntMinionAttack(action, war, ::attackDamage)) {
            return false
        }

        if (isNoBenefitMinionAttack(action, war)) return false

        if (isFrontlineAxeHeroAttack(action, war)) {
            return when (frontlineAxeTarget(action, war)) {
                // Frontline Axe draws only after a minion kill. Do not spend
                // durability on a non-kill; a friendly setup attack gets a
                // fresh re-plan and can expose the kill route later.
                FrontlineAxeTarget.MINION -> frontlineAxeCanKill(action, war)
                FrontlineAxeTarget.HERO -> true
                FrontlineAxeTarget.UNKNOWN -> false
            }
        }

        val creator = action.creator
        if (creator != null && action is PlayAction && isWeaponEquipCard(creator)) {
            return !hasEquippedWeapon(war)
        }
        if (creator != null && action is PlayAction && isCard(creator, BATTLEFIELD) &&
            friendlyMinionCount(war) < 2
        ) {
            // AV_661 can be exposed through the opaque fallback when its
            // parser fails. Enforce its two-minion requirement as legality,
            // not merely as a negative prior.
            return false
        }
        if (creator != null && action is PlayAction && isCard(creator, HOOK_N_HEAVE) &&
            freeSlots(war) < 2
        ) {
            // Hook N' Heave summons two Pirates. This is hard legality rather
            // than a soft prior: allowing it with one or zero slots silently
            // loses a summoned Pirate and makes the discover action misleading.
            return false
        }
        if (creator != null && action is PlayAction && isCard(creator, CAPTAIN_CROWLEY)) {
            return freeSlots(war) >= 3
        }

        return true
    }

    override fun isLethalAction(action: Action, war: War): Boolean =
        PirateLethalAttackPolicy.isLethalFaceAction(action, war)

    /**
     * Hookfist-3000 draws a card and grants armor after a hero attack. Keep
     * its own attack behind a legal hero attack, but leave it available when
     * the hero cannot legally attack this state.
     */
    private fun shouldDeferHookfistAttack(action: Action, war: War): Boolean =
        action is AttackAction &&
            action.creator?.let { isCard(it, HOOKFIST) } == true &&
            hasLegalHeroAttack(war)

    /** Keep Warrior's armor power behind all useful Pirate Warrior work. */
    override fun isDeferredAction(action: Action, war: War): Boolean {
        if (shouldDeferHookfistAttack(action, war)) return true
        if (PirateAttackOrderPolicy.shouldDeferTreasureDistributorAttack(action, war)) return true

        val creator = action.creator
        if (action is PlayAction && creator != null && isWeaponEquipCard(creator)) {
            return hasEquippedWeapon(war)
        }
        if (PirateAttackOrderPolicy.shouldDeferNuLingNagaAttack(action, war)) return true
        if (PirateAttackOrderPolicy.shouldDeferAdrenalineFiendAttack(action, war)) return true
        if (PirateAttackOrderPolicy.shouldDeferHozenRoughhouserAttack(action, war)) return true

        if (isHeroPowerAction(action)) {
            return PirateAttackOrderPolicy.hasAdrenalineFiend(war) && hasOtherUsefulNonHeroPowerAction(war)
        }

        if (isFrontlineAxeHeroAttack(action, war) &&
            frontlineAxeTarget(action, war) == FrontlineAxeTarget.MINION
        ) {
            return hasOtherUsefulNonAxeAction(war)
        }

        // Ragewing is a last-card timing play in this deck. Keep it legal as
        // a fallback, but do not let it pre-empt a stronger generated action.
        if (action is PlayAction && creator?.let { isCard(it, RAGEWING) } == true) {
            return CardTimingPolicy.shouldDefer(creator, war) || hasOtherPlayableAction(war, creator)
        }

        // Parachute Brigand is intentionally the last card we play. It is
        // still retained by MonteCarloTreeNode when it is the only useful
        // action, so the free-effect minion cannot strand the turn.
        if (action is PlayAction && creator?.let { isCard(it, BATTLEFIELD) } == true &&
            friendlyMinionCount(war) < 2
        ) return true
        if (action is PlayAction && creator?.let { isCard(it, PARACHUTE_BRIGAND) } == true) {
            return hasOtherPlayableAction(war, creator)
        }
        return false
    }

    override fun actionFilterReason(action: Action, war: War): String? {
        val zeroCostAredar = war.me.handArea.cards.any {
            CardTimingPolicy.isAredarBrute(it) && isPlayable(it, war) && it.cost == 0
        }
        if (zeroCostAredar &&
            !(action is PlayAction && action.creator?.let {
                CardTimingPolicy.isAredarBrute(it) && it.cost == 0
            } == true)
        ) {
            return "aredar-zero-cost-priority-before-opponent-board-changes"
        }
        if (PirateAttackOrderPolicy.isUnkillableTauntMinionAttack(action, war, ::attackDamage)) {
            return "TAUNT_ATTACK_BLOCKED reason=friendly-minion-cannot-kill-and-would-be-sacrificed"
        }
        if (action is PlayAction && action.creator?.let { isCard(it, HOOK_N_HEAVE) } == true &&
            freeSlots(war) < 2
        ) {
            return "hook-n-heave-blocked-requires-two-friendly-slots"
        }
        if (isDistributorHookNHeaveAction(action, war)) {
            return "treasure-distributor-hook-n-heave-priority"
        }
        if (PirateHeroAttackTargetPolicy.isHeroAttackBlockedByUnkillableTaunt(action, war)) {
            return "TAUNT_BLOCKED reason=hero-cannot-kill-and-face-illegal"
        }
        if (action is PlayAction && action.creator?.let(::isWeaponEquipCard) == true &&
            hasEquippedWeapon(war)
        ) {
            return "weapon-equip-card-blocked-while-weapon-equipped"
        }
        if (isNoBenefitMinionAttack(action, war)) {
            return "minion-attack-no-lethal-or-tactical-benefit"
        }
        if (shouldDeferHookfistAttack(action, war)) {
            return "hookfist-attack-deferred-behind-hero-attack"
        }
        if (PirateAttackOrderPolicy.shouldDeferTreasureDistributorAttack(action, war)) {
            return "treasure-distributor-attack-deferred-behind-other-pirates"
        }
        if (PirateAttackOrderPolicy.shouldDeferHozenRoughhouserAttack(action, war)) {
            return "hozen-roughhouser-attack-deferred-behind-other-pirates"
        }
        if (isFrontlineAxeHeroAttack(action, war) &&
            frontlineAxeTarget(action, war) == FrontlineAxeTarget.MINION &&
            !frontlineAxeCanKill(action, war)
        ) {
            return "frontline-axe-minion-target-not-killable"
        }
        if (isFrontlineAxeHeroAttack(action, war) &&
            frontlineAxeTarget(action, war) == FrontlineAxeTarget.MINION &&
            hasOtherUsefulNonAxeAction(war)
        ) {
            return "frontline-axe-attack-deferred-behind-other-action"
        }
        val creator = action.creator
        if (action is PlayAction && creator?.let { isCard(it, RAGEWING) } == true &&
            (CardTimingPolicy.shouldDefer(creator, war) || hasOtherPlayableAction(war, creator))
        ) {
            return "ragewing-deferred-behind-other-action"
        }
        return null
    }

    /**
     * Do not expose a weapon-equipping hand card while another weapon is
     * still equipped. This catches Enzo's First Mate even though it is a
     * MINION rather than a WEAPON card.
     */
    override fun shouldDefer(card: Card, war: War): Boolean {
        if (isWeaponEquipCard(card) && hasEquippedWeapon(war)) return true

        // This card must be a last-resort play.  Applying the rule here, at
        // the hand-scan boundary, is important: the later attack/power phase
        // is still visible.  If it is deferred only after the phase fence,
        // MCTS can see the card as the sole MINION_PLAY candidate and then
        // resurrect it as the fallback before the attack phase is searched.
        if (isCard(card, RAGEWING)) {
            return CardTimingPolicy.shouldDefer(card, war) || hasOtherPlayableAction(war, card)
        }
        return false
    }

    /**
     * The Juggernaut (`SW_028t6`) equips a random Warrior weapon at turn
     * start. Hearthstone keeps an existing weapon; it must not be modeled as
     * a replacement. Veto only this card's automatic trigger, leaving other
     * turn-start effects untouched.
     */
    override fun shouldSimulateTurnStart(card: Card, war: War): Boolean =
        !(isCard(card, JUGGERNAUT) && war.me.playArea.weapon != null)

    override fun shouldSimulateAction(action: Action, war: War): Boolean =
        isActionLegal(action, war)

    override fun actionOrderPhase(action: Action, war: War): MctsActionOrderPhase? =
        when {
            PirateConditionalDamageSpellPolicy.canKill(action, war) ->
                MctsActionOrderPhase.TACTICAL_SPELL
            // Raid the Docks is a spell/opaque action in the live database,
            // but on turn one it must survive the generic MINION_PLAY phase
            // fence so the hard quest deadline can win before Cannon, weapons,
            // hero power, or EndTurn.
            action is PlayAction &&
                isFirstTurn(war) &&
                action.creator?.let { isCard(it, QUESTLINE) } == true ->
                MctsActionOrderPhase.MINION_PLAY
            action is PlayAction &&
                openingCannonCoinStep(war) == OpeningCannonCoinStep.PLAY_COIN &&
                action.creator?.let(::isCoin) == true ->
                MctsActionOrderPhase.MINION_PLAY
            action is PlayAction &&
                openingCannonCoinStep(war) == OpeningCannonCoinStep.PLAY_QUEST &&
                action.creator?.let { isCard(it, QUESTLINE) } == true ->
                MctsActionOrderPhase.MINION_PLAY
            action is PlayAction &&
                (action.creator?.cardType === CardTypeEnum.MINION ||
                    action.creator?.cardType === CardTypeEnum.LOCATION ||
                    action.creator?.cardType === CardTypeEnum.WEAPON) ->
                MctsActionOrderPhase.MINION_PLAY
            action is PlayAction && action.creator?.cardType === CardTypeEnum.SPELL ->
                MctsActionOrderPhase.SPELL_PLAY
            action is PowerAction && action.creator?.cardType === CardTypeEnum.LOCATION ->
                MctsActionOrderPhase.MINION_PLAY
            action is AttackAction && action.creator?.cardType === CardTypeEnum.MINION &&
                !shouldDeferHookfistAttack(action, war) ->
                MctsActionOrderPhase.MINION_ATTACK
            action is AttackAction && action.creator?.cardType === CardTypeEnum.MINION &&
                shouldDeferHookfistAttack(action, war) ->
                MctsActionOrderPhase.HERO_ATTACK
            action is AttackAction && isFrontlineAxeHeroAttack(action, war) &&
                frontlineAxeTarget(action, war) == FrontlineAxeTarget.MINION ->
                MctsActionOrderPhase.MINION_ATTACK
            action is PowerAction && action.creator?.cardType === CardTypeEnum.HERO_POWER ->
                if (allowsTauntEarlyHeroAction(war)) {
                    MctsActionOrderPhase.EARLY_HERO_ACTION
                } else if (
                    PirateAttackOrderPolicy.shouldUseHeroPowerBeforeWeaponAttack(war) ||
                    PirateAttackOrderPolicy.hasAdrenalineFiend(war)
                ) {
                    MctsActionOrderPhase.HERO_POWER
                } else {
                    MctsActionOrderPhase.EARLY_HERO_ACTION
                }
            action is AttackAction && action.creator?.cardType === CardTypeEnum.HERO ->
                if (allowsTauntEarlyHeroAction(war)) {
                    MctsActionOrderPhase.EARLY_HERO_ACTION
                } else if (
                    PirateAttackOrderPolicy.shouldUseHeroPowerBeforeWeaponAttack(war) ||
                    PirateAttackOrderPolicy.hasAdrenalineFiend(war)
                ) {
                    MctsActionOrderPhase.HERO_ATTACK
                } else {
                    MctsActionOrderPhase.EARLY_HERO_ACTION
                }
            else -> null
        }

    /**
     * Materialize only the deterministic combat buffs for a cloned attack
     * state.  The temporary field makes the matching cleanup explicit, so
     * the base attack is not permanently or doubly buffed across rollouts.
     */
    override fun beforeSimulatedAction(war: War, action: Action): MctsDecisionModel.SimulationResult {
        // CardUtil.simulateAttack currently routes weapon wear through
        // Card.injured(), whose generic canHurt() intentionally excludes
        // WEAPON. Apply the verified Frontline Axe durability transition here
        // until the shared simulator models weapon replacement/wear directly.
        if (isFrontlineAxeHeroAttack(action, war)) {
            war.me.playArea.weapon?.takeIf { !it.isImmune && it.isAlive() }?.let {
                it.damage += 1
            }
        }

        val attacker = (action as? AttackAction)?.creator?.let { war.cardMap[it.entityId] }
            ?: return MctsDecisionModel.SimulationResult()
        if (!isPirate(attacker)) return MctsDecisionModel.SimulationResult()

        val otherCaptains = war.me.playArea.cards.count {
            isCard(it, SOUTHSEA_CAPTAIN) && it.isAlive() && it.entityId != attacker.entityId
        }
        val blastpowderEngineers = activeBlastpowderEngineerCount(war)
        val temporaryBonus = otherCaptains + blastpowderEngineers
        if (temporaryBonus > 0) {
            attacker.atc += temporaryBonus
            attacker.mctsTemporaryAttackBonus += temporaryBonus
        }
        return MctsDecisionModel.SimulationResult()
    }

    override fun afterSimulatedAction(
        before: War,
        after: War,
        action: Action,
    ): MctsDecisionModel.SimulationResult {
        val conditionalSpellExtra =
            PirateConditionalDamageSpellPolicy.applyConditionalDamage(before, after, action)
        val creator = action.creator
        var expectedReward = if (conditionalSpellExtra > 0) 4.0 else 0.0
        if (action is AttackAction && creator != null) {
            after.me.playArea.findByEntityId(creator.entityId)?.let { attacker ->
                if (attacker.mctsTemporaryAttackBonus > 0) {
                    attacker.atc -= attacker.mctsTemporaryAttackBonus
                    attacker.mctsTemporaryAttackBonus = 0
                }
            }
            if (isFrontlineAxeHeroAttack(action, before) && killedRivalMinion(before, after)) {
                // The local DB confirms the Axe draw trigger, but there is no
                // BAR_844 parser here. Reward the verified kill/effect line
                // without inventing a second draw in the simulated hand.
                expectedReward += 18.0
            }
        }
        if (action is PlayAction && creator != null && isCard(creator, HOZEN_ROUGHHOUSER)) {
            // VAC_938 is a one-shot Battlecry: buff only the other Pirate
            // minions already on this board. Excluding by entity ID allows a
            // second Hozen to receive the buff, while the board-only scan
            // excludes hand Pirates and non-Pirates.
            after.me.playArea.cards
                .filter { it.entityId != creator.entityId && isPirate(it) && it.isAlive() }
                .forEach {
                    it.atc += 1
                    it.health += 1
                }
        }
        if (action is PlayAction && creator?.let { isCard(it, APPLAUSE) } == true) {
            val valuation = applauseDrawValuation(action, before)
            traceApplauseValuation(action, before, valuation, "simulated-reward")
            expectedReward += valuation.drawCount * 6.0
        }
        return MctsDecisionModel.SimulationResult(expectedReward = expectedReward)
    }

    /**
     * Reward attack lines using the attack that the live auras are expected
     * to provide. Southsea Captain is represented as an ongoing aura here;
     * Hozen Roughhouser's one-shot Battlecry is materialized in card stats
     * when it is played.
     */
    fun effectivePirateAttack(card: Card, war: War): Int {
        if (!isPirate(card)) return card.atc

        val captains = war.me.playArea.cards.count {
            isCard(it, SOUTHSEA_CAPTAIN) && it.isAlive()
        }
        val captainBonus = (captains - if (isCard(card, SOUTHSEA_CAPTAIN)) 1 else 0)
            .coerceAtLeast(0)
        val engineerBonus = activeBlastpowderEngineerCount(war)

        return PirateDamageAuraPolicy.outgoingDamage(
            card,
            max(0, card.atc) + captainBonus + engineerBonus,
            war,
        )
    }

    private fun activeBlastpowderEngineerCount(war: War): Int =
        war.me.playArea.cards.count {
            isCard(it, BLASTPOWDER_ENGINEER) && it.isAlive()
        }

    override fun scoreAdjustment(war: War): Double {
        val livePirates = war.me.playArea.cards.filter { isPirate(it) && it.isAlive() }
        val attackValue = livePirates
            .filter { it.canAttack() }
            .sumOf { effectivePirateAttack(it, war).toDouble() }
        val otherPirates = livePirates.size
        val cannons = war.me.playArea.cards.count { isCard(it, SHIPS_CANNON) && it.isAlive() }
        val distributors = war.me.playArea.cards.count {
            isCard(it, TREASURE_DISTRIBUTOR) && it.isAlive()
        }
        val engine = war.me.playArea.cards.count {
            isCard(it, BLASTPOWDER_ENGINEER) && it.isAlive()
        }
        val free = freeSlots(war)
        val rivalHero = war.rival.playArea.hero
        val myHero = war.me.playArea.hero
        val incomingAttack = war.rival.playArea.cards
            .filter { it.isAlive() && it.canAttack() }
            .sumOf { max(it.atc, 0) }
        val rivalHeroAttack = war.rival.playArea.hero
            ?.takeIf { it.isAlive() && it.canAttack() }
            ?.let { max(it.atc, 0) }
            ?: 0
        val totalIncomingAttack = incomingAttack + rivalHeroAttack
        // This is a visible-board threat heuristic, not proof of lethal: it
        // deliberately excludes hidden hand, random damage, and unparsed text.
        val defensePenalty = myHero?.let {
            if (!it.isAlive()) {
                0.0
            } else {
                val gap = totalIncomingAttack - it.blood()
                when {
                    gap >= 0 -> 80.0 + gap * 8.0
                    gap >= -4 -> (gap + 5) * 6.0
                    else -> 0.0
                }
            }
        } ?: 0.0
        val hasRivalTaunt = war.rival.playArea.cards.any { it.isAlive() && it.isTaunt }
        val potentialLethalPressure = if (
            rivalHero?.isAlive() == true && !hasRivalTaunt && attackValue >= rivalHero.blood()
        ) {
            40.0
        } else {
            0.0
        }

        return attackValue * 0.65 +
            cannons * (8.0 + otherPirates * 1.5) +
            distributors * (5.0 + otherPirates * 1.0) +
            engine * (3.0 + attackValue * 0.25) +
            potentialLethalPressure -
            defensePenalty +
            if (free == 0 && otherPirates < 4) -3.0 else 0.0
    }

    /**
     * Global-plan objective for Pirate Warrior. The ordinary terminal score
     * values board quality, but by itself it can prefer a short path that
     * leaves usable mana behind. Charge the root plan for mana that is
     * actually reachable by legal card/power actions, while keeping this a
     * soft opportunity cost so a genuinely empty or blocked state is allowed
     * to end the turn.
     */
    override fun turnPlanAdjustment(root: War, terminal: War, path: List<Action>): Double {
        val rootMana = root.me.usableResource.coerceAtLeast(0)
        if (rootMana == 0) return 0.0

        val reachableSpend = maxSpendableMana(root)
        if (reachableSpend == 0) return 0.0

        val actualSpend = (rootMana - terminal.me.usableResource)
            .coerceIn(0, rootMana)
        val missedSpend = (reachableSpend - actualSpend).coerceAtLeast(0)
        return -missedSpend * 4.0
    }

    /** Upper bound on mana that the current Warrior root can legally spend. */
    fun maxSpendableMana(war: War): Int {
        val mana = war.me.usableResource.coerceAtLeast(0)
        if (mana == 0) return 0

        val freeSlots = freeSlots(war)
        val options = mutableListOf<SpendOption>()
        war.me.handArea.cards.forEach { card ->
            if (isPlayableHandCard(card, war, mana, freeSlots)) {
                options += SpendOption(card.cost, if (usesBoardSlot(card)) 1 else 0)
            }
        }

        war.me.playArea.power?.let { power ->
            if (
                power.cost in 1..mana &&
                    !power.isExhausted &&
                    power.canPower() &&
                    runCatching { power.action.generatePowerActions(war, war.me) }
                        .getOrDefault(emptyList())
                        .isNotEmpty()
            ) {
                options += SpendOption(power.cost, 0)
            }
        }

        if (options.isEmpty()) return 0
        val reachable = Array(mana + 1) { BooleanArray(freeSlots + 1) }
        reachable[0][0] = true
        options.forEach { option ->
            for (spentMana in mana downTo option.cost) {
                for (usedSlots in freeSlots downTo option.slot) {
                    if (reachable[spentMana - option.cost][usedSlots - option.slot]) {
                        reachable[spentMana][usedSlots] = true
                    }
                }
            }
        }
        return (mana downTo 0).firstOrNull { spent -> reachable[spent].any { it } } ?: 0
    }

    fun discoverScore(card: Card): Double {
        val pirate = isPirate(card) || card.cardId in setOf(
            PATCHES_THE_PIRATE,
            TREASURE_DISTRIBUTOR,
            SHIPS_CANNON,
        )
        return (if (pirate) 12.0 else 0.0) +
            max(card.atc, 0) * 0.35 +
            if (card.cost <= 2) 2.0 else 0.0
    }

    private fun isHeroPowerAction(action: Action): Boolean =
        action is PowerAction && action.creator?.cardType === CardTypeEnum.HERO_POWER

    private fun isFrontlineAxeHeroAttack(action: Action, war: War): Boolean =
        action is AttackAction &&
            action.creator?.entityId == war.me.playArea.hero?.entityId &&
            war.me.playArea.weapon?.let { isCard(it, FRONTLINE_AXE) && it.isAlive() } == true

    /**
     * AttackAction has no target field in the upstream API. Infer the target
     * only by simulating on a clone and observing the authoritative delta;
     * inability to classify is UNKNOWN and therefore fail-closed.
     */
    private fun frontlineAxeTarget(action: Action, war: War): FrontlineAxeTarget {
        val simulated = simulateOnClone(action, war) ?: return FrontlineAxeTarget.UNKNOWN
        if (killedOrDamagedRivalMinion(war, simulated)) return FrontlineAxeTarget.MINION

        val beforeHero = war.rival.playArea.hero
        val afterHero = simulated.rival.playArea.hero
        return if (beforeHero != null && (afterHero == null || afterHero.blood() < beforeHero.blood())) {
            FrontlineAxeTarget.HERO
        } else {
            FrontlineAxeTarget.UNKNOWN
        }
    }

    private fun frontlineAxeCanKill(action: Action, war: War): Boolean {
        val simulated = simulateOnClone(action, war) ?: return false
        return killedRivalMinion(war, simulated)
    }

    private fun simulateOnClone(action: Action, war: War): War? =
        runCatching {
            war.clone().also { cloned ->
                beforeSimulatedAction(cloned, action)
                action.simulate.accept(cloned)
            }
        }.getOrNull()

    private fun killedRivalMinion(before: War, after: War): Boolean {
        val afterCards = after.rival.playArea.cards.associateBy { it.entityId }
        return before.rival.playArea.cards.any { beforeCard ->
            val afterCard = afterCards[beforeCard.entityId]
            beforeCard.isAlive() && (afterCard == null || !afterCard.isAlive())
        }
    }

    private fun killedOrDamagedRivalMinion(before: War, after: War): Boolean {
        val afterCards = after.rival.playArea.cards.associateBy { it.entityId }
        return before.rival.playArea.cards.any { beforeCard ->
            val afterCard = afterCards[beforeCard.entityId]
            beforeCard.isAlive() && (
                afterCard == null ||
                    !afterCard.isAlive() ||
                    afterCard.damage != beforeCard.damage ||
                    afterCard.isDivineShield != beforeCard.isDivineShield
                )
        }
    }

    /**
     * Hero power is ordered before the hero attack.  Do not count the hero
     * attack itself as a reason to defer the power, otherwise the power can
     * disappear from the current phase and the turn can end after the attack.
     * Hand plays and non-hero board actions still keep the power deferred.
     */
    private fun hasOtherUsefulNonHeroPowerAction(war: War): Boolean {
        val me = war.me
        val handAction = me.handArea.cards.any { card ->
            (!card.isUncertain || MctsCardDiagnostics.braveOpaqueFallbackAllowed(card)) &&
                card.cost <= me.usableResource &&
                (card.cardType !== CardTypeEnum.MINION || freeSlots(war) > 0) &&
                (
                    runCatching { card.action.generatePlayActions(war, me) }
                        .getOrDefault(emptyList())
                        .any { isActionLegal(it, war) } ||
                        canCreateOpaqueAction(card, war)
                )
        }
        val boardAction = me.playArea.cards.any { card ->
            card.cardType !== CardTypeEnum.HERO &&
                card.cardType !== CardTypeEnum.HERO_POWER &&
                card.canAttack() &&
                runCatching { card.action.generateAttackActions(war, me) }
                    .getOrDefault(emptyList())
                    .any { isActionLegal(it, war) }
        }
        return handAction || boardAction
    }

    private fun allowsTauntEarlyHeroAction(war: War): Boolean =
        PirateAttackOrderPolicy.hasAttackableEnemyTaunt(war) &&
            PirateAttackOrderPolicy.hasHeroAttackAction(war) &&
            !PirateHeroAttackTargetPolicy.requiresFriendlySetupAttack(war)

    private fun hasLegalHeroAttack(war: War): Boolean {
        val hero = war.me.playArea.hero ?: return false
        if (!PirateAttackOrderPolicy.hasHeroAttackAction(war)) return false
        return runCatching {
            hero.action.generateAttackActions(war, war.me)
                .any { isActionLegal(it, war) }
        }.getOrDefault(false)
    }

    private fun hasOtherUsefulNonAxeAction(war: War): Boolean {
        val me = war.me
        val handAction = me.handArea.cards.any { card ->
            (!card.isUncertain || MctsCardDiagnostics.braveOpaqueFallbackAllowed(card)) &&
                card.cost <= me.usableResource &&
                (card.cardType !== CardTypeEnum.MINION || freeSlots(war) > 0) &&
                (
                    runCatching { card.action.generatePlayActions(war, me) }
                        .getOrDefault(emptyList())
                        .any { isActionLegal(it, war) } ||
                        canCreateOpaqueAction(card, war)
                    )
        }
        val heroEntityId = me.playArea.hero?.entityId
        val boardAction = me.playArea.cards.any { card ->
            // Do not let the axe attack itself satisfy the "another useful
            // action" check. That self-match was why a lethal axe attack could
            // remain in the same MINION_ATTACK phase and win before a ready
            // friendly minion had attacked.
            if (card.entityId == heroEntityId) return@any false
            val attacks = if (card.canAttack()) runCatching {
                card.action.generateAttackActions(war, me)
            }.getOrDefault(emptyList()) else emptyList()
            val powers = if (card.canPower()) runCatching {
                card.action.generatePowerActions(war, me)
            }.getOrDefault(emptyList()) else emptyList()
            attacks.any { isActionLegal(it, war) } || powers.any { isActionLegal(it, war) }
        }
        return handAction || boardAction
    }

    private fun isFirstTurn(war: War): Boolean = war.me.turn <= 1

    private fun applauseSpellPrior(action: Action, war: War): Double {
        val valuation = applauseDrawValuation(action, war)
        traceApplauseValuation(action, war, valuation, "spell-prior")
        val prior = when {
            valuation.drawCount >= 4 -> 42.0
            valuation.drawCount >= 3 -> 34.0
            valuation.drawCount == 2 -> 14.0
            else -> 4.0
        }
        return if (isVisibleSurvivalEmergency(war)) prior.coerceAtMost(8.0) else prior
    }

    private fun applauseSetupPriorOrNull(action: Action, war: War): Double? {
        val creator = action.creator ?: return null
        if (action !is PlayAction || creator.cardType !== CardTypeEnum.MINION) return null
        if (!isActionLegal(action, war) || freeSlots(war) <= 0) return null
        val applause = war.me.handArea.cards.firstOrNull { isCard(it, APPLAUSE) } ?: return null
        val manaAfter = war.me.usableResource - creator.cost
        if (manaAfter < applause.cost.coerceAtLeast(APPLAUSE_COST)) return null
        val valuation = applauseDrawValuation(action, war)
        if (valuation.projectedTypes.size <= valuation.currentTypes.size) return null
        traceApplauseValuation(action, war, valuation, "minion-first-keeps-two-mana")
        return if (valuation.drawCount >= 4) 38.0 else 30.0
    }

    private fun traceApplauseValuation(
        action: Action,
        war: War,
        valuation: ApplauseDrawValuation,
        reason: String,
    ) {
        log.info {
            "PIRATE_WARRIOR_APPLAUSE_VALUATION cardId=${action.creator?.cardId ?: "NONE"} " +
                "currentTypes=${valuation.currentTypes.map { it.name }.sorted()} " +
                "projectedTypes=${valuation.projectedTypes.map { it.name }.sorted()} " +
                "drawCount=${valuation.drawCount} manaAfter=${valuation.manaAfterAction} " +
                "leavesTwoMana=${valuation.leavesTwoMana} reason=$reason turn=${war.me.turn}"
        }
    }

    private fun isVisibleSurvivalEmergency(war: War): Boolean {
        val hero = war.me.playArea.hero ?: return false
        val incoming = war.rival.playArea.cards
            .filter { it.isAlive() && it.canAttack() }
            .sumOf { max(it.atc, 0) }
        return incoming >= hero.blood()
    }

    private fun freeSlots(war: War): Int =
        (war.me.playArea.maxSize - war.me.playArea.cards.size).coerceAtLeast(0)

    private fun usesBoardSlot(card: Card): Boolean =
        card.cardType === CardTypeEnum.MINION || card.cardType === CardTypeEnum.LOCATION

    private fun isPlayableHandCard(card: Card, war: War, mana: Int, freeSlots: Int): Boolean {
        if ((!card.isUncertain && card.cardId.isBlank()) || card.cost !in 1..mana) return false
        if (usesBoardSlot(card) && freeSlots == 0) return false
        val actions = runCatching { card.action.generatePlayActions(war, war.me) }
            .getOrDefault(emptyList())
        return actions.isNotEmpty() || canCreateOpaqueAction(card, war) ||
            MctsCardDiagnostics.braveOpaqueFallbackAllowed(card)
    }

    private data class SpendOption(val cost: Int, val slot: Int)

    private fun otherPirates(war: War, card: Card): Int =
        war.me.playArea.cards.count { isPirate(it) && it.entityId != card.entityId && it.isAlive() }

    private fun hasWeapon(war: War): Boolean = war.me.playArea.weapon?.isAlive() == true

    private fun hasEquippedWeapon(war: War): Boolean = war.me.playArea.weapon != null

    private fun isWeaponEquipCard(card: Card): Boolean =
        card.cardType === CardTypeEnum.WEAPON || isCard(card, NZOTHS_FIRST_MATE)

    /**
     * Avoid a visible minion sacrifice that neither removes the target nor
     * contributes to a legal combined kill. Explicitly dangerous board
     * signals and Taunt remain valid tactical reasons to trade.
     */
    private fun isNoBenefitMinionAttack(action: Action, war: War): Boolean {
        if (action !is AttackAction || action.creator?.cardType !== CardTypeEnum.MINION) return false
        val attacker = action.creator ?: return false
        val targetId = action.targetEntityId ?: return false
        if (action.targetIsHero || targetId == war.rival.playArea.hero?.entityId) return false
        val target = war.rival.playArea.cards.firstOrNull { it.entityId == targetId }
            ?: return false
        if (target.cardType !== CardTypeEnum.MINION || !target.isAlive() || !target.canBeAttacked()) return false
        if (target.atc <= 0 && !hasTacticalTargetValue(target)) return false
        if (hasTacticalTargetValue(target)) return false

        val requiredDamage = (
            target.bloodLimit() - target.damage + if (target.isDivineShield) 1 else 0
            ).coerceAtLeast(0)
        val attackerDamage = attackDamage(attacker, war)
        if (attackerDamage >= requiredDamage) return false

        val combinedDamage = war.me.playArea.cards
            .asSequence()
            .filter { candidate ->
                candidate.entityId != attacker.entityId &&
                    candidate.cardType === CardTypeEnum.MINION &&
                    candidate.isAlive() &&
                    candidate.canAttack() &&
                    runCatching {
                        candidate.action.generateAttackActions(war, war.me)
                            .any { it.targetEntityId == target.entityId }
                    }.getOrDefault(false)
            }
            .sumOf { attackDamage(it, war) }

        return attackerDamage + combinedDamage < requiredDamage
    }

    private fun attackDamage(attacker: Card, war: War): Int =
        if (isPirate(attacker)) effectivePirateAttack(attacker, war) else attacker.atc.coerceAtLeast(0)

    private fun hasTacticalTargetValue(target: Card): Boolean =
        target.isTaunt ||
            target.isAura ||
            target.isAdjacentBuff ||
            target.isTriggerVisual ||
            target.isWindFury ||
            target.isMegaWindfury ||
            // Preserve the existing fallback for a zero-attack body while
            // treating a visible 3+ attack minion as a real threat.
            target.atc >= 3

    private fun friendlyMinionCount(war: War): Int =
        war.me.playArea.cards.count { it.cardType === CardTypeEnum.MINION && it.isAlive() }

    private fun canPlayWeaponThisTurn(war: War, ignored: Card): Boolean =
        war.me.handArea.cards.any {
            it !== ignored && it.cardType === CardTypeEnum.WEAPON &&
                !it.isUncertain && it.cost + ignored.cost <= war.me.usableResource
        }

    /** True when a real action other than the excluded card is available. */
    private fun hasOtherPlayableAction(war: War, excluded: Card): Boolean {
        val me = war.me
        val handAction = me.handArea.cards.any { card ->
            card.entityId != excluded.entityId &&
                !isCard(card, PARACHUTE_BRIGAND) &&
                (!card.isUncertain || MctsCardDiagnostics.braveOpaqueFallbackAllowed(card)) &&
                card.cost <= me.usableResource &&
                (card.cardType !== CardTypeEnum.MINION || !me.playArea.isFull) &&
                (
                    runCatching { card.action.generatePlayActions(war, me) }
                        .getOrDefault(emptyList())
                        .isNotEmpty() || canCreateOpaqueAction(card, war)
                )
        }
        val boardAction = me.playArea.cards.any { card ->
            (card.canAttack() && runCatching { card.action.generateAttackActions(war, me) }
                .getOrDefault(emptyList()).isNotEmpty()) ||
                (card.canPower() && runCatching { card.action.generatePowerActions(war, me) }
                    .getOrDefault(emptyList()).isNotEmpty())
        }
        val heroAction = me.playArea.hero?.let { hero ->
            hero.canAttack() && runCatching { hero.action.generateAttackActions(war, me) }
                .getOrDefault(emptyList()).isNotEmpty()
        } == true
        val heroPowerAction = me.playArea.power?.let { power ->
            me.usableResource >= power.cost && power.canPower() && runCatching {
                power.action.generatePowerActions(war, me)
            }.getOrDefault(emptyList()).isNotEmpty()
        } == true
        return handAction || boardAction || heroAction || heroPowerAction
    }

    private fun hasOtherPlayableMinion(war: War, ignored: Card): Boolean =
        war.me.handArea.cards.any {
            it !== ignored && it.cardType === CardTypeEnum.MINION &&
                (!it.isUncertain || MctsCardDiagnostics.braveOpaqueFallbackAllowed(it)) &&
                    it.cost <= war.me.usableResource
        }

    private fun isPlayable(card: Card, war: War): Boolean {
        if ((!card.isUncertain && card.cardId.isBlank()) || card.cost > war.me.usableResource) return false
        if (card.cardType === CardTypeEnum.MINION && freeSlots(war) == 0) return false
        return runCatching { card.action.generatePlayActions(war, war.me) }
            .getOrDefault(emptyList())
            .isNotEmpty() || canCreateOpaqueAction(card, war) ||
            MctsCardDiagnostics.braveOpaqueFallbackAllowed(card)
    }

    private fun isCannonPlayable(card: Card, war: War): Boolean {
        if (!isCard(card, SHIPS_CANNON)) return false
        val liveCannon = war.me.playArea.cards.any { isCard(it, SHIPS_CANNON) && it.isAlive() }
        return !liveCannon && isPlayable(card, war)
    }

    private fun isDistributorHookNHeaveAction(action: Action, war: War): Boolean {
        if (action !is PlayAction) {
            return false
        }
        val creator = action.creator ?: return false
        if (!isCard(creator, HOOK_N_HEAVE)) return false
        if (freeSlots(war) < 2 || !isPlayable(creator, war)) return false
        return war.me.playArea.cards.any {
            isCard(it, TREASURE_DISTRIBUTOR) && it.isAlive()
        }
    }
}

class PirateWarriorMctsScoreCalculatorBuilder : WarScoreCalculatorBuilder()
