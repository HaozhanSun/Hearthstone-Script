package club.xiaojiawei.hsscriptcardsdk.bean.area

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player

/**
 * 墓地
 * @author 肖嘉威
 * @date 2022/11/27 15:02
 */
class GraveyardArea(allowLog: Boolean = false, player: Player) :
    Area(allowLog = allowLog, maxSize = Int.MAX_VALUE, player = player) {

    override fun addZeroCard(card: Card?) {
        add(card)
    }

    override fun cardSize(): Int {
        return zeroCardSize()
    }
}
