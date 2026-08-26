package club.xiaojiawei.hsscriptbasecard.bean.warrior

import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.War

/**
 * [斩杀](https://hearthstone.huijiwiki.com/wiki/Card/69535)
 * @author 肖嘉威
 * @date 2025/1/18 8:19
 */

private val cardIds = arrayOf<String>(
    "%CS2_108",
)

class Execute : CardAction.DefaultCardAction() {

    override fun generatePlayActions(war: War, player: Player): List<PlayAction> {
        val result = mutableListOf<PlayAction>()
        war.rival.playArea.cards.forEach { rivalCard ->
            if (rivalCard.cardType === CardTypeEnum.MINION && rivalCard.canBeTargetedByRivalSpells() && rivalCard.isInjured()) {
                result.add(PlayAction({ newWar ->
                    findSelf(newWar)?.action?.power(rivalCard.action.findSelf(newWar))
                }, { newWar ->
                    spendSelfCost(newWar)
                    removeSelf(newWar)?.let {
                        rivalCard.action.findSelf(newWar)?.let { rivalCard->
                            rivalCard.damage = rivalCard.bloodLimit()
                        }
                    }
                }, belongCard))
            }
        }
        return result
    }

    override fun createNewInstance(): CardAction {
        return Execute()
    }

    override fun getCardId(): Array<String> {
        return cardIds
    }

}