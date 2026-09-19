package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.bean.WarScoreCalculatorBuilder
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsActionOrderPhase
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsDecisionModel

/**
 * Decision model for the exact Standard screenshot inventory.
 * Unknown screenshot IDs are never converted into opaque actions.
 */
object StandardCannonWarriorMctsModel : MctsDecisionModel {
    const val UNKNOWN = "UNKNOWN"
    const val CANNONMASTER = UNKNOWN
    const val AIRBORNE_RAIDER = "DRG_024"
    const val SANGUINE_DEPTHS = "REV_990"
    const val FOLLOW_THE_LINE = UNKNOWN
    const val MISTWYRML = UNKNOWN
    const val SHADOWFLAME_DAZE = "FIR_939"
    const val BASH = "AT_064"
    const val BLASTPOWDER_ENGINEER = UNKNOWN
    const val HOOKFIST = UNKNOWN
    const val DRAGON_NEST_GUARDIAN = "EDR_457"
    const val SOUTHSEA_CAPTAIN = "NEW1_027"
    const val HANDHELD_CANNON = UNKNOWN
    const val DIMENSIONAL_WEAPONSMITH = "END_021"
    const val PHANTOM_GREENWING = "EDR_260"
    const val SCOUT_THE_LAND = UNKNOWN
    const val CAPTAIN_CROWLEY = "CAP_106"
    const val WINDRIDER = "TLC_600"
    const val HOGGER = "JAIL_384"

    val confirmedCardIds: Set<String> = setOf(
        AIRBORNE_RAIDER, SANGUINE_DEPTHS, SHADOWFLAME_DAZE, BASH,
        DRAGON_NEST_GUARDIAN, SOUTHSEA_CAPTAIN, DIMENSIONAL_WEAPONSMITH,
        PHANTOM_GREENWING, WINDRIDER, HOGGER,
    )

    override fun canCreateOpaqueAction(card: Card, war: War): Boolean = false

    override fun isActionLegal(action: Action, war: War): Boolean =
        action !is PlayAction || action.creator?.cardId != UNKNOWN

    override fun actionOrderPhase(action: Action, war: War): MctsActionOrderPhase? =
        when {
            action is PlayAction && action.creator?.cardType === CardTypeEnum.SPELL -> MctsActionOrderPhase.SPELL_PLAY
            action is PlayAction -> MctsActionOrderPhase.MINION_PLAY
            action is AttackAction && action.creator?.cardType === CardTypeEnum.MINION -> MctsActionOrderPhase.MINION_ATTACK
            action is PowerAction && action.creator?.cardType === CardTypeEnum.HERO_POWER -> MctsActionOrderPhase.HERO_POWER
            action is AttackAction && action.creator?.cardType === CardTypeEnum.HERO -> MctsActionOrderPhase.HERO_ATTACK
            else -> null
        }

    override fun actionPrior(action: Action, war: War): Double {
        val card = action.creator ?: return 0.0
        return when {
            isCard(card, SANGUINE_DEPTHS) -> 28.0
            isCard(card, BASH) -> if (war.rival.playArea.cards.any { it.isAlive() }) 26.0 else 10.0
            isCard(card, AIRBORNE_RAIDER) -> 24.0
            isCard(card, DRAGON_NEST_GUARDIAN) -> 22.0
            isCard(card, DIMENSIONAL_WEAPONSMITH) -> 20.0
            isCard(card, SOUTHSEA_CAPTAIN) -> if (friendlyMinions(war) > 0) 20.0 else -12.0
            isCard(card, PHANTOM_GREENWING) -> if (war.rival.playArea.cards.any { it.isAlive() }) 18.0 else 8.0
            isCard(card, WINDRIDER) -> 12.0
            isCard(card, HOGGER) -> 10.0
            else -> 0.0
        }
    }

    override fun isLethalAction(action: Action, war: War): Boolean =
        action is AttackAction && action.targetIsHero &&
            action.creator?.atc?.let { attack -> war.rival.playArea.hero?.let { it.health - it.damage <= attack } } == true

    override fun scoreAdjustment(war: War): Double {
        val knownBoard = war.me.playArea.cards.filter { it.cardId in confirmedCardIds && it.isAlive() }
        val knownHand = war.me.handArea.cards.filter { it.cardId in confirmedCardIds }
        val removalTarget = war.rival.playArea.cards.count { it.isAlive() }
        return knownBoard.sumOf { (it.atc.coerceAtLeast(0) + it.blood().coerceAtLeast(0)).toDouble() } * 0.7 +
            knownHand.sumOf { it.cost.toDouble() } * 0.2 +
            if (removalTarget > 0 && knownHand.any { isCard(it, BASH) }) 3.0 else 0.0
    }

    override fun turnPlanAdjustment(root: War, terminal: War, path: List<Action>): Double {
        val rootMana = root.me.usableResource.coerceAtLeast(0)
        if (rootMana == 0 || !hasReachableSpend(root)) return 0.0
        val spent = (rootMana - terminal.me.usableResource).coerceIn(0, rootMana)
        return -(rootMana - spent) * 2.0
    }

    fun discoverScore(card: Card): Double =
        card.atc.coerceAtLeast(0) * 0.5 + card.blood().coerceAtLeast(0) * 0.35 +
            if (card.cardId in confirmedCardIds) 4.0 else 0.0

    fun isCard(card: Card, id: String): Boolean =
        id != UNKNOWN && (card.cardId == id || card.cardId == "CORE_$id")

    private fun friendlyMinions(war: War): Int =
        war.me.playArea.cards.count { it.cardType === CardTypeEnum.MINION && it.isAlive() }

    private fun hasReachableSpend(war: War): Boolean =
        war.me.handArea.cards.any { card ->
            !card.isUncertain && card.cost in 1..war.me.usableResource &&
                (card.cardId in confirmedCardIds ||
                    runCatching { card.action.generatePlayActions(war, war.me) }
                        .getOrDefault(emptyList()).isNotEmpty())
        } || war.me.playArea.power?.let { power ->
            power.cost in 1..war.me.usableResource && !power.isExhausted && power.canPower() &&
                runCatching { power.action.generatePowerActions(war, war.me) }
                    .getOrDefault(emptyList()).isNotEmpty()
        } == true
}

class StandardCannonWarriorScoreCalculatorBuilder : WarScoreCalculatorBuilder()


