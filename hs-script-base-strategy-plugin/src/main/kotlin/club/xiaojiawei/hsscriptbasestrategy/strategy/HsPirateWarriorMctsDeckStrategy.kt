package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.MctsRootSelectionPolicy
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptstrategysdk.deck.MCTSDeckStrategy

/** Released entry point for the isolated Pirate Warrior MCTS model. */
class HsPirateWarriorMctsDeckStrategy : MCTSDeckStrategy() {
    private var preMulliganHand: List<Card> = emptyList()
    private var openingHandRegistered = false

    override fun reset() {
        super.reset()
        preMulliganHand = emptyList()
        openingHandRegistered = false
    }

    override fun name(): String = PirateMctsStrategyVersion.displayName("海盗战")

    override fun description(): String =
        "海盗战 MCTS ${PirateMctsStrategyVersion.REVISION}：Coin→船载火炮开局例外、船载火炮 P0、掌声雷动起手换掉；未知卡牌可识别、艾瑞达蛮兵硬限制、前锋战斧只打必杀"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.WILD)

    override fun deckCode(): String = ""

    override fun id(): String = "e71234fa-8-pirate-warrior-mcts-9b1f-4d29-8f4f"

    override fun referWeight(): Boolean = true
    override fun referPowerWeight(): Boolean = true
    override fun referChangeWeight(): Boolean = true
    override fun referCardInfo(): Boolean = true

    override fun executeChangeCard(cards: HashSet<Card>) {
        // The actuator removes the Coin before this callback. Preserve the
        // original non-Coin identities so a mulligan replacement or later
        // draw with the same card ID cannot activate the opening exception.
        preMulliganHand = cards.toList()
        val patches = cards.filter { PirateWarriorMctsModel.isCard(it, PirateWarriorMctsModel.PATCHES_THE_PIRATE) }
        val applause = cards.filter { PirateWarriorMctsModel.isCard(it, PirateWarriorMctsModel.APPLAUSE) }
        cards.removeAll((patches + applause).toSet())
        if (patches.isNotEmpty()) {
            log.info { "海盗战 MCTS：起手直接换掉海盗帕奇斯 count=${patches.size}" }
        }
        if (applause.isNotEmpty()) {
            log.info { "海盗战 MCTS：起手直接换掉掌声雷动 count=${applause.size}" }
        }
    }

    override fun executeMCTSOutCard(war: War): List<MCTSArg> {
        if (!openingHandRegistered) {
            PirateWarriorMctsModel.registerOpeningHandSnapshot(war, preMulliganHand)
            openingHandRegistered = true
        }
        DecisionTrace.record(
            war = war,
            event = "PIRATE_WARRIOR_MCTS_START",
            reason = "isolated model search started",
            rule = "COIN>SHIP_CANNON_OPENING_EXCEPTION;QUEST_T1>P0_SHIP_CANNON>TREASURE_DISTRIBUTOR;APPLAUSE_MULLIGAN;PATCHES_BOTTOM",
            priority = 0,
        )
        log.info {
            "海盗战 MCTS：开始搜索 turn=${war.me.turn} mana=${war.me.usableResource} " +
                "hand=${war.me.handArea.cards.joinToString { it.cardId }} " +
                "rules=COIN>SHIP_CANNON_OPENING_EXCEPTION;QUEST_REWARD_WAIT>QUEST_T1>P0_SHIP_CANNON>TREASURE_DISTRIBUTOR;APPLAUSE_MULLIGAN;PATCHES_BOTTOM;DIRECT_MINION_KILL;SAFE_HERO_ATTACK;TAUNT_SAFETY " +
                    "openingStep=${PirateWarriorMctsModel.openingCannonCoinStep(war)} version=${PirateMctsStrategyVersion.REVISION}"
        }
        return listOf(
            MCTSArg(
                endMillisTime = System.currentTimeMillis() + 20_000L,
                turnCount = 1,
                turnFactor = 0.65,
                countPerTurn = 1_500,
                scoreCalculator = PirateWarriorMctsScoreCalculatorBuilder().build(),
                enableMultiThread = false,
                debugName = name(),
                decisionModel = PirateWarriorMctsModel,
                experimentalSearch = true,
                experimentalTurnBudgetMillis = 20_000L,
                experimentalActionBudgetMillis = 1_800L,
                // Use the same complete-turn objective as Pirate DH. The
                // executor still dispatches one action and re-plans from the
                // confirmed live WAR, while the root choice is based on the
                // best discovered resource-efficient turn plan.
                rootSelectionPolicy = MctsRootSelectionPolicy.GLOBAL_TURN_PLAN,
            ),
        )
    }

    override fun executeDiscoverChooseCard(vararg cards: Card): Int =
        cards.indices.maxByOrNull { PirateWarriorMctsModel.discoverScore(cards[it]) } ?: 0
}
