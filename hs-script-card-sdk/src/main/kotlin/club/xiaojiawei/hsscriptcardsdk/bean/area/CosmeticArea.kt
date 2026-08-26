package club.xiaojiawei.hsscriptcardsdk.bean.area

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player

/**
 * 装饰区
 * @author 肖嘉威
 * @date 2022/11/30 14:36
 */
class CosmeticArea(allowLog: Boolean = false, player: Player) :
    Area(allowLog = allowLog, maxSize = Int.MAX_VALUE, player = player) {

    override fun addZeroCard(card: Card?) {
        add(card)
    }

}