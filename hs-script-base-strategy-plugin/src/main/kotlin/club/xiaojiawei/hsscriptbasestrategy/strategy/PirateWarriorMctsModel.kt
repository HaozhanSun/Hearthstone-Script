package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.bean.WarScoreCalculatorBuilder
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsDecisionModel
import kotlin.math.max

/**
 * Isolated card model for the screenshot Pirate Warrior list.
 *
 * The first three timing rules are intentionally explicit and ordered:
 * playable Ship's Cannon, the first-turn quest deadline, then Treasure
 * Distributor.  The rest of the model remains a soft prior so combat and
 * generated-card choices can still be decided by MCTS.
 */
object PirateWarriorMctsModel : MctsDecisionModel {
    const val TREASURE_DISTRIBUTOR = "TOY_518"
    const val QUESTLINE = "SW_028"
    const val PATCHES_THE_PIRATE = "CFM_637"
    const val SHIPS_CANNON = "GVG_075"
    const val SOUTHSEA_CAPTAIN = "NEW1_027"
    const val HOZEN_ROUGHHOUSER = "VAC_938"
    const val RAGEWING = "YOD_032"
    const val HOOKFIST = "CORE_NX2_028"
    const val ANCHOR = "DRG_025"
    const val FRONTLINE_AXE = "BAR_844"
    const val BLASTPOWDER_ENGINEER = "CAP_104"
    const val CANNONMASTER = "CAP_107"
    const val HOOK_N_HEAVE = "CAP_105"
    const val CAPTAIN_CROWLEY = "CAP_106"

    /**
     * Safe, known-meaning fallback cards. New CAP cards are intentionally not
     * included: known text is not the same as a verified local action/state
     * transition, so an unknown parser result must fail closed.
     */
    private val opaqueKnownCards = setOf(
        SHIPS_CANNON,
        QUESTLINE,
        TREASURE_DISTRIBUTOR,
    )

    fun isCard(card: Card, id: String): Boolean =
        card.cardId == id ||
            card.cardId == "CORE_$id" ||
            (id.startsWith("CORE_") && card.cardId == id.removePrefix("CORE_")) ||
            card.cardId.startsWith("${id}t") ||
            card.cardId.startsWith("CORE_${id}t")

    fun isPirate(card: Card): Boolean =
        card.cardRace === CardRaceEnum.PIRATE || card.cardRace === CardRaceEnum.ALL

    override fun canCreateOpaqueAction(card: Card, war: War): Boolean =
        card.entityId.isNotBlank() && !card.isUncertain &&
            opaqueKnownCards.any { isCard(card, it) }

    /** Keep Patches available only as a last-resort action; mulligan removes it. */
    override fun actionPrior(action: Action, war: War): Double {
        val card = action.creator ?: return 0.0
        if (isCard(card, PATCHES_THE_PIRATE)) return -1_000.0

        val otherPirates = otherPirates(war, card)
        val attackablePirates = war.me.playArea.cards.count { isPirate(it) && it.canAttack() }
        val freeSlots = freeSlots(war)

        return when {
            isCard(card, SHIPS_CANNON) -> 100.0
            isCard(card, QUESTLINE) && isFirstTurn(war) -> 95.0
            isCard(card, TREASURE_DISTRIBUTOR) -> 90.0 + otherPirates * 2.0
            isCard(card, CANNONMASTER) -> if (freeSlots > 0) 36.0 else -36.0
            isCard(card, BLASTPOWDER_ENGINEER) ->
                if (otherPirates > 0 || attackablePirates > 0) 28.0 else 8.0
            isCard(card, SOUTHSEA_CAPTAIN) ->
                if (otherPirates > 0) 30.0 + attackablePirates * 3.0 else -18.0
            isCard(card, HOZEN_ROUGHHOUSER) ->
                if (otherPirates > 0) 28.0 + attackablePirates * 3.0 else -18.0
            isCard(card, HOOK_N_HEAVE) -> if (freeSlots >= 2) 24.0 else -30.0
            isCard(card, CAPTAIN_CROWLEY) -> if (freeSlots >= 2) 26.0 else -30.0
            isCard(card, RAGEWING) -> if (card.cost <= 1) 24.0 else 5.0
            isCard(card, HOOKFIST) -> when {
                hasWeapon(war) || canPlayWeaponThisTurn(war, card) -> 18.0
                !hasOtherPlayableMinion(war, card) -> 4.0
                else -> -24.0
            }
            isCard(card, ANCHOR) || isCard(card, FRONTLINE_AXE) ->
                if (hasWeapon(war)) 4.0 else 16.0
            action is AttackAction && isPirate(card) ->
                effectivePirateAttack(card, war) * 0.45
            else -> 0.0
        }
    }

    /**
     * Hard sequencing, not a prior: if a legal priority action exists, the
     * current search node is restricted to that action. The first-turn quest
     * deadline is checked after Cannon because Cannon is the user's P0 rule.
     */
    override fun isMandatoryAction(action: Action, war: War): Boolean {
        val cannon = war.me.handArea.cards.firstOrNull { isCannonPlayable(it, war) }
        if (cannon != null) {
            return action is PlayAction && action.creator?.let { isCard(it, SHIPS_CANNON) } == true
        }

        val quest = war.me.handArea.cards.firstOrNull {
            isCard(it, QUESTLINE) && isPlayable(it, war)
        }
        if (isFirstTurn(war) && quest != null) {
            return action is PlayAction && action.creator?.let { isCard(it, QUESTLINE) } == true
        }

        val distributor = war.me.handArea.cards.firstOrNull {
            isCard(it, TREASURE_DISTRIBUTOR) && isPlayable(it, war)
        }
        if (distributor != null) {
            return action is PlayAction && action.creator?.let { isCard(it, TREASURE_DISTRIBUTOR) } == true
        }
        return false
    }

    /** Do not hide Patches when it is literally the only legal action. */
    override fun shouldDefer(card: Card, war: War): Boolean = false

    /**
     * Materialize only the deterministic combat buffs for a cloned attack
     * state.  The temporary field makes the matching cleanup explicit, so
     * the base attack is not permanently or doubly buffed across rollouts.
     */
    override fun beforeSimulatedAction(war: War, action: Action): MctsDecisionModel.SimulationResult {
        val attacker = (action as? AttackAction)?.creator?.let { war.cardMap[it.entityId] }
            ?: return MctsDecisionModel.SimulationResult()
        if (!isPirate(attacker)) return MctsDecisionModel.SimulationResult()

        val otherCaptains = war.me.playArea.cards.count {
            isCard(it, SOUTHSEA_CAPTAIN) && it.isAlive() && it.entityId != attacker.entityId
        }
        val otherHozens = war.me.playArea.cards.count {
            isCard(it, HOZEN_ROUGHHOUSER) && it.isAlive() && it.entityId != attacker.entityId
        }
        val temporaryBonus = otherCaptains + otherHozens
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
        val creator = action.creator
        if (action is AttackAction && creator != null) {
            after.me.playArea.findByEntityId(creator.entityId)?.let { attacker ->
                if (attacker.mctsTemporaryAttackBonus > 0) {
                    attacker.atc -= attacker.mctsTemporaryAttackBonus
                    attacker.mctsTemporaryAttackBonus = 0
                }
            }
        }
        return MctsDecisionModel.SimulationResult()
    }

    /**
     * Reward attack lines using the attack that the live auras are expected
     * to provide. Southsea Captain buffs other Pirates statically; each other
     * Hozen Roughhouser buffs an attacking Pirate at the attack event.
     */
    fun effectivePirateAttack(card: Card, war: War): Int {
        if (!isPirate(card)) return card.atc

        val captains = war.me.playArea.cards.count {
            isCard(it, SOUTHSEA_CAPTAIN) && it.isAlive()
        }
        val captainBonus = (captains - if (isCard(card, SOUTHSEA_CAPTAIN)) 1 else 0)
            .coerceAtLeast(0)

        val otherPirates = otherPirates(war, card)
        val hozenCount = war.me.playArea.cards.count {
            isCard(it, HOZEN_ROUGHHOUSER) && it.isAlive()
        }
        val hozenBonus = if (otherPirates > 0) {
            (hozenCount - if (isCard(card, HOZEN_ROUGHHOUSER)) 1 else 0)
                .coerceAtLeast(0)
        } else {
            0
        }
        return max(0, card.atc) + captainBonus + hozenBonus
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

    private fun isFirstTurn(war: War): Boolean = war.me.turn <= 1

    private fun freeSlots(war: War): Int =
        (war.me.playArea.maxSize - war.me.playArea.cards.size).coerceAtLeast(0)

    private fun otherPirates(war: War, card: Card): Int =
        war.me.playArea.cards.count { isPirate(it) && it.entityId != card.entityId && it.isAlive() }

    private fun hasWeapon(war: War): Boolean = war.me.playArea.weapon?.isAlive() == true

    private fun canPlayWeaponThisTurn(war: War, ignored: Card): Boolean =
        war.me.handArea.cards.any {
            it !== ignored && it.cardType === CardTypeEnum.WEAPON &&
                !it.isUncertain && it.cost + ignored.cost <= war.me.usableResource
        }

    private fun hasOtherPlayableMinion(war: War, ignored: Card): Boolean =
        war.me.handArea.cards.any {
            it !== ignored && it.cardType === CardTypeEnum.MINION &&
                !it.isUncertain && it.cost <= war.me.usableResource
        }

    private fun isPlayable(card: Card, war: War): Boolean {
        if (card.isUncertain || card.cost > war.me.usableResource) return false
        if (card.cardType === CardTypeEnum.MINION && freeSlots(war) == 0) return false
        return runCatching { card.action.generatePlayActions(war, war.me) }
            .getOrDefault(emptyList())
            .isNotEmpty() || canCreateOpaqueAction(card, war)
    }

    private fun isCannonPlayable(card: Card, war: War): Boolean {
        if (!isCard(card, SHIPS_CANNON)) return false
        val liveCannon = war.me.playArea.cards.any { isCard(it, SHIPS_CANNON) && it.isAlive() }
        return !liveCannon && isPlayable(card, war)
    }
}

class PirateWarriorMctsScoreCalculatorBuilder : WarScoreCalculatorBuilder()
