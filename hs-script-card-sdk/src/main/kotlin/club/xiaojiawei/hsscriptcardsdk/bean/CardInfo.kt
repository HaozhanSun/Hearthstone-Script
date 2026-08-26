package club.xiaojiawei.hsscriptcardsdk.bean

import club.xiaojiawei.hsscriptcardsdk.enums.CardActionEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardEffectTypeEnum

/**
 * 合并的卡牌数据类，包含卡牌信息和权重
 * @author 肖嘉威
 * @date 2026/2/2
 */
class CardInfo(
    /**
     * 效果类型
     */
    var effectType: CardEffectTypeEnum = CardEffectTypeEnum.UNKNOWN,

    /**
     * 打出行为
     */
    var playActions: List<CardActionEnum> = listOf(CardActionEnum.NO_POINT),

    /**
     * 使用行为
     */
    var powerActions: List<CardActionEnum> = listOf(),

    /**
     * 权重：衡量卡牌的价值，影响本回合要出哪些牌及优先解哪个怪
     */
    var weight: Double = 1.0,

    /**
     * 使用权重：衡量卡牌出牌顺序
     */
    var powerWeight: Double = 1.0,

    /**
     * 换牌权重
     */
    var changeWeight: Double = 0.0
){
    override fun toString(): String {
        return "playActions:${playActions},powerActions:${powerActions}"
    }
}
