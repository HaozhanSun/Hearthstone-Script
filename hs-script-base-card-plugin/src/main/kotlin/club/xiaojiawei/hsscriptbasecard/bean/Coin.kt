package club.xiaojiawei.hsscriptbasecard.bean

import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.data.COIN_CARD_ID

/**
 * 幸运币
 * @author 肖嘉威
 * @date 2025/1/17 15:22
 */
private val cardIds = arrayOf<String>(
    COIN_CARD_ID
)

class Coin : CardAction.DefaultCardAction() {

    override fun generatePlayActions(war: War, player: Player): List<PlayAction> {
        return listOf(
            PlayAction({ newWar ->
                findSelf(newWar)?.action?.power()
            }, { newWar ->
                spendSelfCost(newWar)
                removeSelf(newWar)?.let {
                    newWar.me.tempResources++
                }
            }, belongCard)
        )
    }

    override fun createNewInstance(): CardAction {
        return Coin()
    }

    override fun getCardId(): Array<String> {
        return cardIds
    }
}