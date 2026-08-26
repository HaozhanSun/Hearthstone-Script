package club.xiaojiawei.hsscriptcardsdk.bean.area

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import club.xiaojiawei.hsscriptbase.const.SoftRunMode
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.cardparser.ParsedCardActionFactory
import club.xiaojiawei.hsscriptcardsdk.data.CardInfoData
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptbase.util.RandomUtil

/**
 * 手牌区
 *
 * @author 肖嘉威
 * @date 2022/11/27 15:02
 */
class HandArea(allowLog: Boolean = false, player: Player, var parseCard: Boolean = false) :
    Area(allowLog = allowLog, maxSize = 10, player = player) {

    fun drawCard(): Card? {
        val deckArea = player.deckArea
        if (deckArea.isEmpty) {
            player.playArea.hero?.let { hero ->
                hero.damage += player.incrementFatigue()
            }
        } else {
            deckArea.remove(deckArea.cardSize() - 1)?.let { removeCard ->
                removeCard.apply {
//                     todo 应该从实际套牌中随机获取
                    cardType = CardTypeEnum.SPELL
                    cost = RandomUtil.nextInt(11)
                }
                if (player.handArea.safeAdd(removeCard)) {
                    return removeCard
                }
            }
        }
        return null
    }

    private var nativeTip = false

    override fun addCard(card: Card?, pos: Int) {
        super.addCard(card, pos)
        //        解析卡牌描述
        if (parseCard && card != null) {
            try {
                if (card.cardType === CardTypeEnum.SPELL || card.isBattlecry) {
                    CardInfoData.indexCard(card.cardId)
                    if (BuildInfo.SOFT_RUN_MODE === SoftRunMode.NATIVE) {
                        if (!nativeTip) {
                            nativeTip = true
                            log.warn { "native版软件不支持解析卡牌描述以生成CardAction类" }
                        }
                    } else if (card.action.common) {
                        ParsedCardActionFactory.getOrCreate(card.cardId)?.invoke()?.let {
                            it.belongCard = card
                            card.action = it
                        }
                    }
                }
            } catch (e: Exception) {
                log.error(e) { "解析手中卡牌出错" }
            }
        }
    }
}
