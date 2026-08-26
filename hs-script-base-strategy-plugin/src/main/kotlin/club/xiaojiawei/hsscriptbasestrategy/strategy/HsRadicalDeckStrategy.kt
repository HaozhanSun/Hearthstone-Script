package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.isValid
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.data.CARD_DATA_TRIE
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import club.xiaojiawei.hsscriptbasestrategy.util.DeckStrategyUtil

/**
 * @author 肖嘉威
 * @date 2024/10/17 17:58
 */
class HsRadicalDeckStrategy : DeckStrategy() {
    private val commonDeckStrategy = HsCommonDeckStrategy()

    override fun name(): String = "激进策略"

    override fun description(): String = "会在基础策略的基础上使用战吼，法术，地标牌（依旧不识别战吼或法术）"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.CASUAL, RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.PRACTICE)

    override fun deckCode(): String = ""

    override fun id(): String = "e71234fa-1-radical-deck-97e9-1f4e126cd33b"

    override fun referWeight(): Boolean = true

    override fun referPowerWeight(): Boolean = true

    override fun referChangeWeight(): Boolean = true

    override fun referCardInfo(): Boolean = true

    override fun executeChangeCard(cards: java.util.HashSet<Card>) {
        commonDeckStrategy.executeChangeCard(cards)
    }

    override fun executeOutCard() {
        log.info { "激进策略：开始计算本回合出牌" }
        val me = WAR.me
        if (me.isValid()) {
            val rival = WAR.rival
            var plays = me.playArea.cards.toList()
            log.info { "激进策略：开始处理地标，场上${plays.size}张" }
            DeckStrategyUtil.activeLocation(plays)
            log.info { "激进策略：地标处理完毕" }
            val hands = me.handArea.cards.toList()
            val myHandCardsCopy = hands.toMutableList()
            myHandCardsCopy.removeAll { card -> card.isCoinCard }
            // 不算硬币牌，计算本回合资源情况下的最优出牌。
            log.info { "激进策略：开始计算最优出牌，手牌${myHandCardsCopy.size}张，水晶${me.usableResource}" }
            val (score, resultCards) = DeckStrategyUtil.calcPowerOrderConvert(myHandCardsCopy, me.usableResource)
            log.info { "激进策略：最优出牌计算完成，候选${resultCards.size}张" }
            // 判断是否存在硬币
            val coinCard = DeckStrategyUtil.findCoin(hands)
            var finalCards = resultCards
            if (coinCard != null) {
                // 使用硬币后资源+1，再计算一次最优出牌。
                log.info { "激进策略：开始计算硬币方案" }
                val (coinScore, coinResultCards) = DeckStrategyUtil.calcPowerOrderConvert(
                    myHandCardsCopy,
                    me.usableResource + 1
                )
                log.info { "激进策略：硬币方案计算完成，候选${coinResultCards.size}张" }
                if (coinScore > score) {
                    // 若使用硬币后得分更高，则先打出硬币
                    coinCard.action.power()
                    Thread.sleep(RandomUtil.getActionInterval(1000).toLong())
                    finalCards = coinResultCards
                }
            }

            if (finalCards.isNotEmpty()) {
                DeckStrategyUtil.updateTextForCard(finalCards)
                val sortCard = DeckStrategyUtil.sortCardByPowerWeight(finalCards)
                log.info { "待出牌:${sortCard.size}张\n${sortCard.joinToString("\n")}" }

                for (simulateWeightCard in sortCard) {
                    val card = simulateWeightCard.card
                    val cardText = simulateWeightCard.text
                    if (me.usableResource >= card.cost) {
                        if (card.cardType === CardTypeEnum.SPELL || card.cardType === CardTypeEnum.HERO) {
                            card.action.autoPower(CARD_DATA_TRIE[card.cardId])
                        } else {
                            if (me.playArea.isFull) break
                            card.action.autoPower(CARD_DATA_TRIE[card.cardId])
                        }
                    }
                }
            }
            plays = me.playArea.cards.toList()
            log.info { "激进策略：开始执行基础策略，场上${plays.size}张" }
            DeckStrategyUtil.activeLocation(plays)
            commonDeckStrategy.executeOutCard()
            log.info { "激进策略：基础策略执行完成" }
        }
        log.info { "激进策略：本回合出牌计算结束" }
    }

    override fun executeDiscoverChooseCard(vararg cards: Card): Int =
        commonDeckStrategy.executeDiscoverChooseCard(*cards)
}
