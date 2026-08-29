package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.ScoreCalculator
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.bean.WarScoreCalculatorBuilder
import club.xiaojiawei.hsscriptcardsdk.bean.area.HandArea
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.CardTriggerSimulator
import club.xiaojiawei.hsscriptcardsdk.mcts.CardTimingPolicy
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsDecisionModel
import club.xiaojiawei.hsscriptcardsdk.util.CardUtil
import kotlin.math.max

/**
 * Card identity and timing model for the experimental Pirate Demon Hunter
 * strategy. It deliberately separates deterministic effects from expected
 * random reward: cannon damage never changes a target's simulated health.
 */
object PirateDemonHunterMctsExperimentModel : MctsDecisionModel {
    const val TREASURE_DISTRIBUTOR = "TOY_518"
    const val PATCHES_THE_PIRATE = "CFM_637"
    const val PATCHES_THE_PILOT = "VAC_933"
    const val SIGIL_OF_SKYDIVING = "VAC_925"
    const val BATTLEFIELD = "AV_661"
    const val TERROR_HARVEST = "EDR_840"
    const val ADRENALINE_FIEND = "VAC_927"
    const val PARACHUTE_BRIGAND = "DRG_056"
    const val SHIPS_CANNON = "GVG_075"
    const val PUFFERFIST = "TSC_002"
    const val SOUTHSEA_CAPTAIN = "NEW1_027"
    const val MAGNIFYING_GLAIVE = "REV_509"
    const val INSECT_CLAW = "TLC_833"
    const val WATCHPOST_OBSERVER = "DED_507"
    const val CUSTOMS_ENFORCER = "VAC_440"
    const val HOZEN_ROUGHHOUSER = "VAC_938"
    const val PRINCE_RENATHAL = "REV_018"
    const val DANGEROUS_CLIFFSIDE = "VAC_929"
    /** The live Hearthstone token created by Dangerous Cliffside. */
    const val CLIFFSIDE_PIRATE_TOKEN = "VAC_926t"
    const val RAGEWING = "YOD_032"
    const val BLINDEYE_JUDGE = "MAW_008"
    const val WEAPONS_ATTENDANT = "VAC_924"
    const val PIGGY = "TOY_642"
    const val ZILLIAX_T7 = "TOY_330t7"
    const val ETERNAL_AMALGAM = "WON_143"

    /**
     * Card ids for which this model has an intentional, hand-reviewed rule
     * or simulation hook.  The app uses this set when it compares the live
     * deck code with the cards we actually know how to value.  It is a
     * tuning inventory, not a claim that every card in the deck is always
     * present in every user's current list.
     */
    val knownTunedCardIds: Set<String> = setOf(
        PATCHES_THE_PIRATE,
        "BT_355",
        PUFFERFIST,
        PRINCE_RENATHAL,
        "CORE_BT_187",
        "TOY_330",
        WEAPONS_ATTENDANT,
        PATCHES_THE_PILOT,
        CUSTOMS_ENFORCER,
        INSECT_CLAW,
        "CS2_146",
        SHIPS_CANNON,
        PARACHUTE_BRIGAND,
        RAGEWING,
        WATCHPOST_OBSERVER,
        "CORE_NEW1_027",
        "NEW1_027",
        MAGNIFYING_GLAIVE,
        BLINDEYE_JUDGE,
        TREASURE_DISTRIBUTOR,
        PIGGY,
        SIGIL_OF_SKYDIVING,
        ADRENALINE_FIEND,
        DANGEROUS_CLIFFSIDE,
        HOZEN_ROUGHHOUSER,
        "VAC_430",
        ETERNAL_AMALGAM,
    )

    fun isKnownTunedCardId(cardId: String): Boolean {
        if (cardId.isBlank()) return false
        return knownTunedCardIds.any { known ->
            cardId == known || cardId.startsWith("${known}t") ||
                (known.startsWith("CORE_") && cardId == known.removePrefix("CORE_")) ||
                (cardId.startsWith("CORE_") && cardId.removePrefix("CORE_") == known)
        }
    }

    private val opaqueCards = setOf(
        PATCHES_THE_PILOT,
        SIGIL_OF_SKYDIVING,
        BATTLEFIELD,
        TERROR_HARVEST,
        "ETC_418",
        "VAC_430",
        DANGEROUS_CLIFFSIDE,
        BLINDEYE_JUDGE,
        WEAPONS_ATTENDANT,
        PIGGY,
        ETERNAL_AMALGAM,
    )

    fun isPirate(card: Card): Boolean =
        card.cardRace === CardRaceEnum.PIRATE || card.cardRace === CardRaceEnum.ALL

    fun isCard(card: Card, id: String): Boolean =
        card.cardId == id ||
            card.cardId.startsWith("${id}t") ||
            card.cardId == "CORE_$id" ||
            card.cardId.startsWith("CORE_${id}t") ||
            (id.startsWith("CORE_") && card.cardId == id.removePrefix("CORE_")) ||
            (id.startsWith("CORE_") && card.cardId.startsWith("${id.removePrefix("CORE_")}t"))

    override fun shouldDefer(card: Card, war: War): Boolean {
        // A Cliffside play needs one slot for the location and two more for
        // its immediate pirate summons.  Keep it in the MCTS search, but do
        // not let a low-space play create a misleading candidate that cannot
        // realize the card's actual value.
        if (isCard(card, DANGEROUS_CLIFFSIDE) && card.area is HandArea && freeSlots(war) < 3) {
            return true
        }
        if (CardTimingPolicy.shouldDefer(card, war)) return true

        // The Sigil is a delayed board-development card, but its next-turn
        // two charge Pirates are valuable enough that it must remain in the
        // search whenever it is playable.  Its prior below still lets the
        // state evaluator choose a more urgent answer (for example lethal or
        // a required clear); hiding it here made it look like a dead card.
        return false
    }

    override fun canCreateOpaqueAction(card: Card, war: War): Boolean =
        card.entityId.isNotBlank() &&
            (
                card.cardId in opaqueCards ||
                    CardTimingPolicy.isPatchesThePirate(card) ||
                    CardTimingPolicy.isOpponentDamageReductionCard(card) ||
                    CardTimingPolicy.isZilliaxDeluxe3000(card)
                )

    override fun canCreateOpaquePowerAction(card: Card, war: War): Boolean =
        isCard(card, DANGEROUS_CLIFFSIDE) &&
            card.cardType === CardTypeEnum.LOCATION &&
            card.entityId.isNotBlank() &&
            card.canPower()

    override fun isMandatoryAction(action: Action, war: War): Boolean {
        val cliffside = war.me.playArea.cards.firstOrNull { isCard(it, DANGEROUS_CLIFFSIDE) && it.isAlive() }
        val heroCanAttack = war.me.playArea.hero?.canAttack() == true

        val cannonReady = !war.me.playArea.cards.any { isCard(it, SHIPS_CANNON) && it.isAlive() } &&
            war.me.handArea.cards.any { isCannonPlayableNow(it, war) }
        if (cannonReady) {
            return action is PlayAction && action.creator?.let { isCard(it, SHIPS_CANNON) } == true
        }

        // If the cannon is one mana short, the coin is its mandatory
        // predecessor.  Without this bridge the generic MCTS prior can spend
        // the two available mana on a different card first, making the
        // already-established cannon-first rule ineffective in the common
        // 2-mana + Coin + 3-mana-cannon opening.
        val cannonNeedsCoin = !war.me.playArea.cards.any { isCard(it, SHIPS_CANNON) && it.isAlive() } &&
            war.me.handArea.cards.any { isCannonPlayableWithCoin(it, war) } &&
            war.me.handArea.cards.any { it.isCoinCard && !it.isUncertain }
        if (cannonNeedsCoin) {
            return action is PlayAction && action.creator?.isCoinCard == true
        }

        // After the first location click, the live game marks the location
        // cooldown. While it is cooling down, the only action we want the
        // MCTS branch to expose is the Demon Hunter hero attack. The attack
        // unlocks the location again; the next re-plan then sees PowerAction.
        if (cliffside?.isLocationActionCooldown == true && heroCanAttack && freeSlots(war) >= 2) {
            return action is AttackAction && action.creator?.cardType === CardTypeEnum.HERO
        }

        return action is PowerAction &&
            action.creator?.let { card ->
                isCard(card, DANGEROUS_CLIFFSIDE) &&
                    card.cardType === CardTypeEnum.LOCATION &&
                    card.canPower() &&
                    freeSlots(war) >= 2
            } == true
    }

    private fun isCannonPlayableNow(card: Card, war: War): Boolean =
        isCard(card, SHIPS_CANNON) &&
            !card.isUncertain &&
            card.cost <= war.me.usableResource &&
            (!war.me.playArea.isFull || card.cardType !== CardTypeEnum.MINION)

    private fun isCannonPlayableWithCoin(card: Card, war: War): Boolean =
        isCard(card, SHIPS_CANNON) &&
            !card.isUncertain &&
            card.cost > war.me.usableResource &&
            card.cost <= war.me.usableResource + 1 &&
            (!war.me.playArea.isFull || card.cardType !== CardTypeEnum.MINION)

    override fun isDeferredAction(action: Action, war: War): Boolean {
        // Demon Hunter's hero power is a resource sink, not an opening move.
        // Keep it out of the current node while any non-hero-power action is
        // still available. It becomes legal again on the next re-plan after
        // the useful hand/board actions have been exhausted.
        if (isHeroPowerAction(action)) {
            return hasOtherNonHeroPowerAction(war)
        }

        // Blindeye Judge is a last-resort draw card. Remove it from the
        // current node while any useful hand play, board attack, location
        // activation, or hero power remains. This is deliberately separate
        // from the cliffside mandatory-action chain above.
        return action is club.xiaojiawei.hsscriptcardsdk.bean.PlayAction &&
            action.creator?.let { isCard(it, BLINDEYE_JUDGE) } == true &&
            hasOtherPlayableAction(war, action.creator)
    }

    override fun actionPrior(action: Action, war: War): Double {
        val card = action.creator ?: return if (action.javaClass.simpleName == "TurnOverAction") -50.0 else 0.0
        val me = war.me
        val otherPirates = me.playArea.cards.count { isPirate(it) && it.entityId != card.entityId }
        val attackablePirates = me.playArea.cards.count { isPirate(it) && it.canAttack() }
        val friendlyMinions = me.playArea.cards.count { it.cardType === CardTypeEnum.MINION }
        val futurePirates = futurePirateSummons(war)
        if (isHeroPowerAction(action)) {
            // This also protects rollout/expansion ordering if a caller uses
            // the model without the deferred-action filter.
            return if (hasOtherNonHeroPowerAction(war)) -40.0 else -1.0
        }
        return when {
            isCard(card, SHIPS_CANNON) -> if (me.playArea.cards.any { isCard(it, SHIPS_CANNON) }) 0.0 else 12.0
            isCard(card, TREASURE_DISTRIBUTOR) -> 10.0 + futurePirates * 1.5
            isCard(card, SOUTHSEA_CAPTAIN) ->
                if (otherPirates == 0) -24.0 else 7.0 + otherPirates * 2.0
            isCard(card, HOZEN_ROUGHHOUSER) ->
                if (otherPirates == 0) -24.0 else 6.0 + attackablePirates * 1.5
            isCard(card, WEAPONS_ATTENDANT) -> {
                val otherPirateOnBoard = me.playArea.cards.any { isPirate(it) }
                val currentWeapon = me.playArea.weapon
                when {
                    !otherPirateOnBoard -> -22.0
                    currentWeapon == null -> 14.0 + attackablePirates
                    // Replacing a healthy weapon is usually a destructive
                    // random roll.  Allow the MCTS to consider replacement
                    // once the current weapon is nearly spent, but do not
                    // silently force it over a good weapon.
                    currentWeapon.durability <= 1 -> 8.0 + attackablePirates
                    else -> -10.0
                }
            }
            isCard(card, SIGIL_OF_SKYDIVING) ->
                if (freeSlots(war) >= 2) 16.0 + futurePirates * 1.5 else -16.0
            isCard(card, BATTLEFIELD) ->
                if (friendlyMinions == 0) -22.0 else 4.0 + friendlyMinions
            isCard(card, ADRENALINE_FIEND) ->
                // This is a board-development Pirate DH card.  A current
                // attack is valuable, but its absence must not turn the card
                // into a dead action: the body can enable the next Pirate.
                if (attackablePirates == 0 && futurePirates == 0) 1.0 else 7.0 + attackablePirates
            isCard(card, PIGGY) -> {
                val lowHealthTargets = lowHealthEnemyMinions(me, 3)
                val oneHealthTargets = oneHealthEnemyMinions(me)
                // Piggy's battlecry/deathrattle deals a deterministic 3
                // damage.  Prefer it strongly when it can immediately hit a
                // real target, without making it an unconditional top pick.
                16.0 + lowHealthTargets * 5.0 + oneHealthTargets * 3.0
            }
            isCard(card, PUFFERFIST) -> {
                val oneHealthTargets = oneHealthEnemyMinions(me)
                val lowHealthTargets = lowHealthEnemyMinions(me, 3)
                val canFollowWithHeroPower = canFollowWithHeroPower(war, card)
                // A <=3-health enemy is exactly in the Piggy's deterministic
                // 3-damage range.  This is a very strong prior, but remains
                // below mandatory action hooks and can still lose to a
                // direct lethal/clear found by the MCTS rollout.
                16.0 + lowHealthTargets * 5.0 + oneHealthTargets * 3.0 +
                    if (canFollowWithHeroPower || me.playArea.hero?.canAttack() == true) 8.0 else 0.0
            }
            isCard(card, MAGNIFYING_GLAIVE) -> {
                val handAfterPlay = (me.handArea.cards.size - 1).coerceAtLeast(0)
                if (handAfterPlay >= 3 && !hasHighThreatEnemy(me)) -18.0 else 5.0
            }
            isCard(card, BLINDEYE_JUDGE) ->
                if (hasOtherPlayableAction(war, card)) -28.0 else -2.0
            isCard(card, DANGEROUS_CLIFFSIDE) -> when {
                action is PlayAction && freeSlots(war) < 3 -> -30.0
                action is PowerAction && freeSlots(war) >= 2 -> 60.0 + attackablePirates * 2.0
                action is PowerAction -> -30.0
                freeSlots(war) < 3 -> -30.0
                else -> 24.0 + attackablePirates * 2.0
            }
            isZilliax(card) ->
                if (friendlyMinions == 0) -6.0 else 6.0 + attackablePirates
            isCard(card, RAGEWING) -> if (card.cost <= 1) 12.0 else 1.0
            else -> 0.0
        }
    }

    override fun beforeSimulatedAction(war: War, action: Action): MctsDecisionModel.SimulationResult {
        val attacker = (action as? AttackAction)?.creator?.let { war.cardMap[it.entityId] }
            ?: return MctsDecisionModel.SimulationResult()
        if (!isPirate(attacker)) return MctsDecisionModel.SimulationResult()

        // Hozen's trigger is evaluated before combat damage. Its own attack
        // does not count as "another" Pirate.
        val hozenCount = war.me.playArea.cards.count {
            isCard(it, HOZEN_ROUGHHOUSER) && it.entityId != attacker.entityId && it.isAlive()
        }
        if (hozenCount > 0) {
            attacker.atc += hozenCount
        }

        // Dynamic auras are applied only for this combat calculation and then
        // removed after the simulated attack; they are never double-counted.
        val aura = war.me.playArea.cards.count { isCard(it, SOUTHSEA_CAPTAIN) && it.isAlive() } +
            confirmedZilliaxAuraCount(war)
        if (aura > 0) {
            attacker.atc += aura
            attacker.mctsTemporaryAttackBonus += aura
        }
        return MctsDecisionModel.SimulationResult()
    }

    override fun afterSimulatedAction(before: War, after: War, action: Action): MctsDecisionModel.SimulationResult {
        val creator = action.creator
        var expectedReward = 0.0
        var stopRollout = false

        if (action is AttackAction && creator != null) {
            val attackerAfter = after.me.playArea.findByEntityId(creator.entityId)
            attackerAfter?.let {
                if (it.mctsTemporaryAttackBonus > 0) {
                    it.atc -= it.mctsTemporaryAttackBonus
                    it.mctsTemporaryAttackBonus = 0
                }
            }
            if (isPirate(creator)) {
                val survivingFiends = after.me.playArea.cards.count { isCard(it, ADRENALINE_FIEND) && it.isAlive() }
                if (survivingFiends > 0) {
                    after.me.playArea.hero?.atc = (after.me.playArea.hero?.atc ?: 0) + survivingFiends
                }
            }
        }

        if (creator != null && isCard(creator, PUFFERFIST) && creator.cardType === CardTypeEnum.MINION) {
            // Handled below on Hero attack; this branch intentionally has no
            // reward, because merely playing the card does not deal damage.
        }

        val isPlayedPirateFromHand = action is club.xiaojiawei.hsscriptcardsdk.bean.PlayAction &&
            creator != null && creator.area is HandArea && isPirate(creator)
        if (isPlayedPirateFromHand) {
            val beforeIds = before.me.playArea.cards.map { it.entityId }.toSet()
            summonPatchesFromDeck(after)
            applyDistributorToNewPirates(beforeIds, after)
            if (!isCard(creator, HOZEN_ROUGHHOUSER)) {
                applyHozenHealthAuraToNewPirates(beforeIds, after)
            }
            val newPirates = after.me.playArea.cards.count { it.entityId !in beforeIds && isPirate(it) }
            val cannons = after.me.playArea.cards.count { isCard(it, SHIPS_CANNON) && it.isAlive() }
            // A cannon's random 2 damage is expected reward only. It never
            // changes a minion's health or the Ragewing deterministic cost.
            expectedReward += newPirates * cannons * 1.1
        }

        if (creator != null && isCard(creator, HOZEN_ROUGHHOUSER) && action is PlayAction) {
            // Hozen's Battlecry immediately gives the other Pirates +1/+1.
            // Attack is represented by effectivePirateAttack so it remains
            // an ongoing aura for future summons; health is materialized in
            // the simulated state because it affects combat survivability.
            after.me.playArea.findByEntityId(creator.entityId)?.let { applyHozenHealthAura(it, after) }
            applyHozenHealthAuraToOtherPirates(after, creator)
        }

        if (creator != null && isCard(creator, DANGEROUS_CLIFFSIDE) && action is PowerAction) {
            after.cardMap[creator.entityId]?.isLocationActionCooldown = true
            summonChargePirates(after, 2)
        }

        if (creator != null && isHeroAttack(action)) {
            val pufferfists = after.me.playArea.cards.count { isCard(it, PUFFERFIST) && it.isAlive() }
            val guaranteedKills = if (pufferfists > 0) {
                before.rival.playArea.cards.count { it.isAlive() && it.blood() <= pufferfists }
            } else {
                0
            }
            repeat(pufferfists) {
                after.rival.playArea.cards.toList().forEach { it.injured(1) }
                after.rival.playArea.hero?.injured(1)
            }
            expectedReward += guaranteedKills * 8.0 + pufferfists * 0.5
            val insectClaws = after.me.playArea.cards.count { isCard(it, INSECT_CLAW) && it.isAlive() }
            if (insectClaws > 0) summonRushInsects(after, insectClaws)

            after.me.playArea.cards.filter { isCard(it, DANGEROUS_CLIFFSIDE) && it.isAlive() }
                .forEach { it.isLocationActionCooldown = false }

            val glaives = after.me.playArea.weapon?.let { if (isCard(it, MAGNIFYING_GLAIVE)  ) 1 else 0 } ?: 0
            if (glaives > 0) {
                expectedReward += ((3 - after.me.handArea.cards.size).coerceAtLeast(0) * 2.0)
                stopRollout = true
            }
        }

        if (creator != null && creator.cardId == SIGIL_OF_SKYDIVING) {
            expectedReward += 2.0 + after.me.playArea.cards.count { isCard(it, TREASURE_DISTRIBUTOR) } * 1.0
            stopRollout = true
        }

        if (creator != null && creator.cardId in opaqueCards) {
            stopRollout = true
        }

        applyDeterministicDynamicCosts(before, after)
        return MctsDecisionModel.SimulationResult(expectedReward, stopRollout)
    }

    override fun scoreAdjustment(war: War): Double {
        val me = war.me
        val pirates = me.playArea.cards.count(::isPirate)
        val otherPirates = me.playArea.cards.count(::isPirate) - 1
        val attackablePirates = me.playArea.cards.count { isPirate(it) && it.canAttack() }
        val effectivePirateAttack = me.playArea.cards
            .filter { isPirate(it) && it.canAttack() }
            .sumOf { effectivePirateAttack(it, war).toDouble() }
        val futurePirates = futurePirateSummons(war)
        val minions = me.playArea.cards.count { it.cardType === CardTypeEnum.MINION }
        val slots = freeSlots(war)
        var score = 0.0

        score += me.playArea.cards.count { isCard(it, SHIPS_CANNON) && it.isAlive() } * (5.0 + futurePirates * 1.3)
        score += me.playArea.cards.count { isCard(it, TREASURE_DISTRIBUTOR) && it.isAlive() } * (4.0 + futurePirates * 1.4)
        score += me.playArea.cards.count { isCard(it, ADRENALINE_FIEND) && it.isAlive() } *
            (2.0 + attackablePirates * 1.5)
        score += me.playArea.cards.count { isCard(it, HOZEN_ROUGHHOUSER) && it.isAlive() } *
            if (otherPirates <= 0) -6.0 else otherPirates * 1.5
        // Keep the Hozen trigger visible to the state evaluator as well as
        // the combat simulation.  Otherwise a board with a ready Pirate and
        // a Hozen looks one attack point weaker than the live game.
        score += effectivePirateAttack * 0.35
        score += me.playArea.cards.count { isCard(it, SOUTHSEA_CAPTAIN) && it.isAlive() } *
            if (otherPirates <= 0) -7.0 else otherPirates * 2.0

        val battlefieldInHand = me.handArea.cards.count { isCard(it, BATTLEFIELD) }
        if (battlefieldInHand > 0) score += if (minions == 0) -6.0 else minions * 1.2

        val glaiveInHand = me.handArea.cards.count { isCard(it, MAGNIFYING_GLAIVE) }
        if (glaiveInHand > 0) {
            val handAfter = (me.handArea.cards.size - glaiveInHand).coerceAtLeast(0)
            score += if (handAfter >= 3 && !hasHighThreatEnemy(me)) -6.0 else 3.0
        }

        if (me.handArea.cards.any { isCard(it, BLINDEYE_JUDGE) } && hasOtherPlayableAction(war, null)) {
            score -= 8.0
        }
        if (slots <= 1 && futurePirates >= 2) score -= 5.0
        if (me.handArea.cards.size >= 8) score -= (me.handArea.cards.size - 7) * 1.5
        return score
    }

    private fun futurePirateSummons(war: War): Int {
        val hand = war.me.handArea.cards
        val directPirates = hand.count { isPirate(it) }
        val brigands = hand.count { isCard(it, PARACHUTE_BRIGAND) }
        val sigils = hand.count { isCard(it, SIGIL_OF_SKYDIVING) } * 2
        val cliffside = hand.count { isCard(it, DANGEROUS_CLIFFSIDE) } * 2
        return directPirates + brigands + sigils + cliffside +
            war.me.deckArea.cards.count { isCard(it, PATCHES_THE_PIRATE) }
    }

    private fun freeSlots(war: War): Int =
        (war.me.playArea.maxSize - war.me.playArea.cards.size).coerceAtLeast(0)

    private fun hasOtherPlayableAction(war: War, excluded: Card?): Boolean {
        val me = war.me
        val handAction = me.handArea.cards.any { card ->
            card.entityId != excluded?.entityId &&
                !isCard(card, BLINDEYE_JUDGE) &&
                !card.isUncertain &&
                card.cost <= me.usableResource &&
                (card.cardType !== CardTypeEnum.MINION || !me.playArea.isFull) &&
                (
                    card.action.generatePlayActions(war, me).isNotEmpty() ||
                        canCreateOpaqueAction(card, war)
                )
        }
        val boardAction = me.playArea.cards.any { hasGeneratedBoardAction(it, war) }
        val heroAttack = me.playArea.hero?.let { hero ->
            hero.canAttack() && runCatching {
                hero.action.generateAttackActions(war, me).isNotEmpty()
            }.getOrDefault(false)
        } == true
        val heroPower = me.playArea.power?.let { power ->
            me.usableResource >= power.cost && power.canPower() && runCatching {
                power.action.generatePowerActions(war, me)
                    .any { !isDeferredAction(it, war) }
            }.getOrDefault(false)
        } == true
        return handAction || boardAction || heroAttack || heroPower
    }

    /** Whether the current state has useful work other than hero power. */
    private fun hasOtherNonHeroPowerAction(war: War): Boolean {
        val me = war.me
        val handAction = me.handArea.cards.any { card ->
            !isCard(card, BLINDEYE_JUDGE) &&
                !card.isUncertain &&
                !shouldDefer(card, war) &&
                card.cost <= me.usableResource &&
                (card.cardType !== CardTypeEnum.MINION || !me.playArea.isFull) &&
                (
                    card.action.generatePlayActions(war, me).isNotEmpty() ||
                        canCreateOpaqueAction(card, war)
                )
        }
        val boardAction = me.playArea.cards.any { hasGeneratedBoardAction(it, war) }
        val heroAttack = me.playArea.hero?.let { hero ->
            hero.canAttack() && runCatching {
                hero.action.generateAttackActions(war, me).isNotEmpty()
            }.getOrDefault(false)
        } == true
        // Treat Coin as a planning bridge when it unlocks a non-power card.
        // At the current mana total that card is not yet a legal hand action,
        // but using the hero power first would still consume the resource that
        // the bridge needs.  This keeps the power at the end of the useful
        // sequence without hard-coding a card or a fixed play order.
        val coinBridge = hasCoinUnlockingNonHeroPowerCard(war)
        return handAction || boardAction || heroAttack || coinBridge
    }

    private fun hasCoinUnlockingNonHeroPowerCard(war: War): Boolean {
        val me = war.me
        val coinAvailable = me.handArea.cards.any { it.isCoinCard && !it.isUncertain }
        if (!coinAvailable) return false

        val afterCoinMana = me.usableResource + 1
        return me.handArea.cards.any { card ->
            !card.isCoinCard &&
                !isCard(card, BLINDEYE_JUDGE) &&
                !card.isUncertain &&
                !shouldDefer(card, war) &&
                card.cost > me.usableResource &&
                card.cost <= afterCoinMana &&
                (card.cardType !== CardTypeEnum.MINION || !me.playArea.isFull) &&
                (
                    card.action.generatePlayActions(war, me).isNotEmpty() ||
                        canCreateOpaqueAction(card, war)
                )
        }
    }

    private fun isHeroPowerAction(action: Action): Boolean =
        action is PowerAction && action.creator?.cardType === CardTypeEnum.HERO_POWER

    /**
     * `canPower()` is only a capability flag.  Some parser entities expose it
     * while their action generator still returns no action.  Treating that
     * flag as real work makes hero-power deferral self-perpetuating: MCTS
     * hides the power, the phantom board action keeps it hidden, and the
     * timing card that should be the last fallback is never reached.
     */
    private fun hasGeneratedBoardAction(card: Card, war: War): Boolean {
        val attackActions = if (card.canAttack()) runCatching {
            card.action.generateAttackActions(war, war.me)
        }.getOrDefault(emptyList()) else emptyList()
        if (attackActions.any { !isDeferredAction(it, war) }) return true

        val powerActions = if (card.canPower()) runCatching {
            card.action.generatePowerActions(war, war.me)
        }.getOrDefault(emptyList()) else emptyList()
        return powerActions.any { !isDeferredAction(it, war) } ||
            canCreateOpaquePowerAction(card, war)
    }

    private fun oneHealthEnemyMinions(me: Player): Int =
        me.war.rival.playArea.cards.count { it.cardType === CardTypeEnum.MINION && it.isAlive() && it.blood() <= 1 }

    private fun lowHealthEnemyMinions(me: Player, maxHealth: Int): Int =
        me.war.rival.playArea.cards.count {
            it.cardType === CardTypeEnum.MINION && it.isAlive() && it.blood() <= maxHealth
        }

    /** Effective attack for a Pirate combat calculation in the current state. */
    fun effectivePirateAttack(card: Card, war: War): Int {
        if (!isPirate(card)) return card.atc
        val hozenCount = war.me.playArea.cards.count {
            isCard(it, HOZEN_ROUGHHOUSER) && it.isAlive() && it.entityId != card.entityId
        }
        return card.atc + hozenCount
    }

    private fun canFollowWithHeroPower(war: War, card: Card): Boolean {
        val power = war.me.playArea.power ?: return false
        val remainingMana = war.me.usableResource - card.cost
        return remainingMana >= power.cost && power.canPower()
    }

    /**
     * Discover is outside the turn tree, so use the same compact Pirate DH
     * knowledge when the live client asks the strategy to choose one option.
     */
    fun discoverScore(card: Card): Double {
        var score = 0.0
        if (isPirate(card)) score += 6.0
        if (card.cost <= 2) score += 3.0
        if (card.isCharge || card.isRush) score += 4.0
        if (card.cardId in setOf(
                TREASURE_DISTRIBUTOR,
                SHIPS_CANNON,
                PARACHUTE_BRIGAND,
                ADRENALINE_FIEND,
                PUFFERFIST,
                DANGEROUS_CLIFFSIDE,
            )
        ) {
            score += 7.0
        }
        if (card.cardType === CardTypeEnum.SPELL && card.cost >= 4) score -= 1.0
        if (card.isUncertain) score -= 4.0
        return score
    }

    private fun hasHighThreatEnemy(me: Player): Boolean =
        me.war.rival.playArea.cards.any { it.isTaunt || it.atc >= 5 || it.health >= 6 }

    private fun confirmedZilliaxAuraCount(war: War): Int =
        war.me.playArea.cards.count { isZilliax(it) && it.isAlive() }

    private fun summonPatchesFromDeck(war: War) {
        if (war.me.playArea.isFull) return
        val index = war.me.deckArea.cards.indexOfFirst { isCard(it, PATCHES_THE_PIRATE) }
        if (index < 0) return
        val patches = war.me.deckArea.remove(index) ?: return
        patches.isExhausted = true
        war.addCard(patches, war.me.playArea)
    }

    private fun applyDistributorToNewPirates(beforeIds: Set<String>, war: War) {
        val distributors = war.me.playArea.cards.count { isCard(it, TREASURE_DISTRIBUTOR) && it.isAlive() }
        if (distributors == 0) return
        war.me.playArea.cards.filter { it.entityId !in beforeIds && isPirate(it) }
            .forEach { it.atc += distributors }
    }

    private fun summonChargePirates(war: War, count: Int) {
        repeat(count) {
            if (war.me.playArea.isFull) return
            val template = (war.me.handArea.cards + war.me.deckArea.cards + war.me.playArea.cards)
                .firstOrNull(::isPirate)
                ?: return
            val token = template.clone().apply {
                entityId = war.incrementMaxEntityId()
                cardId = CLIFFSIDE_PIRATE_TOKEN
                entityName = "惊险悬崖冲锋海盗"
                cardType = CardTypeEnum.MINION
                cardRace = CardRaceEnum.PIRATE
                atc = 1
                health = 1
                damage = 0
                isCharge = true
                isRush = false
                isAttackableByRush = false
                isExhausted = false
            }
            war.addCard(token, war.me.playArea)
            applyHozenHealthAura(token, war)
        }
    }

    private fun summonRushInsects(war: War, count: Int) {
        repeat(count) {
            if (war.me.playArea.isFull) return
            val template = war.me.playArea.cards.firstOrNull() ?: return
            val token = template.clone().apply {
                entityId = war.incrementMaxEntityId()
                cardId = "TLC_833t"
                entityName = "昆虫利爪突袭虫"
                cardType = CardTypeEnum.MINION
                cardRace = CardRaceEnum.UNKNOWN
                atc = 2
                health = 1
                damage = 0
                isCharge = false
                isRush = true
                isAttackableByRush = true
                isExhausted = false
            }
            war.addCard(token, war.me.playArea)
        }
    }

    private fun isHeroAttack(action: Action): Boolean =
        action is AttackAction && action.creator?.cardType === CardTypeEnum.HERO

    private fun applyHozenHealthAuraToOtherPirates(war: War, hozen: Card) {
        war.me.playArea.cards
            .filter { it.entityId != hozen.entityId && isPirate(it) && it.isAlive() }
            .forEach { pirate -> applyHozenHealthAura(pirate, war) }
    }

    private fun applyHozenHealthAuraToNewPirates(beforeIds: Set<String>, war: War) {
        war.me.playArea.cards
            .filter { it.entityId !in beforeIds && isPirate(it) && it.isAlive() }
            .forEach { pirate -> applyHozenHealthAura(pirate, war) }
    }

    private fun applyHozenHealthAura(pirate: Card, war: War) {
        if (!isPirate(pirate)) return
        val hozenCount = war.me.playArea.cards.count {
            isCard(it, HOZEN_ROUGHHOUSER) && it.entityId != pirate.entityId && it.isAlive()
        }
        if (hozenCount > 0) pirate.health += hozenCount
    }

    private fun applyDeterministicDynamicCosts(before: War, after: War) {
        val damageDelta = ((after.rival.playArea.hero?.damage ?: 0) - (before.rival.playArea.hero?.damage ?: 0))
            .coerceAtLeast(0)
        if (damageDelta > 0) {
            after.me.handArea.cards.filter { isCard(it, RAGEWING) }
                .forEach { it.cost = (it.cost - damageDelta).coerceAtLeast(0) }
        }
        val minionCount = after.me.playArea.cards.count { it.cardType === CardTypeEnum.MINION }
        after.me.handArea.cards.filter(::isZilliax)
            .forEach { it.cost = (7 - minionCount).coerceAtLeast(0) }
    }

    private fun isZilliax(card: Card): Boolean =
        CardTimingPolicy.isZilliaxDeluxe3000(card)
}

/** Generic score plus Pirate DH-specific engine, timing and dead-card penalties. */
class PirateDemonHunterMctsTrialScoreCalculatorBuilder : WarScoreCalculatorBuilder() {
    fun buildTrial(): ScoreCalculator = ScoreCalculator { war ->
        // The MCTS State applies decisionModel.scoreAdjustment exactly once.
        // Keep the calculator itself generic so this model is not double-counted.
        calcPlayerScore(war.me, true) - calcPlayerScore(war.rival, false)
    }
}
