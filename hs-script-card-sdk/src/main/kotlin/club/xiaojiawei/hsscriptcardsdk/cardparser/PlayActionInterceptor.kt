package club.xiaojiawei.hsscriptcardsdk.cardparser

import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.util.CardUtil
import net.bytebuddy.implementation.bind.annotation.Argument
import net.bytebuddy.implementation.bind.annotation.This
import kotlin.math.max

/**
 * @author 肖嘉威
 * @date 2026/2/5 14:28
 */
interface PlayActionInterceptor {
    fun generatePlayActions(
        @Argument(0) war: War,
        @Argument(1) player: Player,
        @This cardAction: CardAction
    ): List<PlayAction>
}

internal enum class DirectedTargetType {
    MINION,
    HERO,
    ROLE,
}

internal sealed interface TargetDomain {
    val comment: String
    fun getTargets(war: War): List<Card>
}

internal data object MyMinionTargetDomain : TargetDomain {
    override val comment: String = "随从"
    override fun getTargets(war: War): List<Card> = war.me.playArea.cards.toList()
}

internal data object MyHeroTargetDomain : TargetDomain {
    override val comment: String = "英雄"
    override fun getTargets(war: War): List<Card> = war.me.playArea.hero?.let(::listOf) ?: emptyList()
}

internal data object MyRoleTargetDomain : TargetDomain {
    override val comment: String = "角色"
    override fun getTargets(war: War): List<Card> = war.me.playArea.hero?.let {
        buildList {
            addAll(war.me.playArea.cards)
            add(it)
        }
    } ?: war.me.playArea.cards.toList()
}

internal data object RivalMinionTargetDomain : TargetDomain {
    override val comment: String = "随从"
    override fun getTargets(war: War): List<Card> = war.rival.playArea.cards.toList()
}

internal data object RivalHeroTargetDomain : TargetDomain {
    override val comment: String = "英雄"
    override fun getTargets(war: War): List<Card> = war.rival.playArea.hero?.let(::listOf) ?: emptyList()
}

internal data object RivalRoleTargetDomain : TargetDomain {
    override val comment: String = "角色"
    override fun getTargets(war: War): List<Card> = war.rival.playArea.hero?.let {
        buildList {
            addAll(war.rival.playArea.cards)
            add(it)
        }
    } ?: war.rival.playArea.cards.toList()
}

internal interface TargetDomainAwarePlayActionInterceptor : PlayActionInterceptor {
    val targetDomain: TargetDomain
}

val PLAY_ACTION_INTERCEPTOR_COMMENT_MAP: Map<Class<out PlayActionInterceptor>, String> = linkedMapOf(
    SpellNoPointPlayActionInterceptor::class.java to "无需指向-法术",
    NonSpellNoPointPlayActionInterceptor::class.java to "无需指向-非法术",
    SpellPointMyDamagePlayActionInterceptor::class.java to "法术单体友方-伤害",
    SpellPointMyHealPlayActionInterceptor::class.java to "法术单体友方-治疗",
    SpellPointMyHealthBuffPlayActionInterceptor::class.java to "法术单体友方-生命增益",
    SpellPointMyAtkBuffPlayActionInterceptor::class.java to "法术单体友方-攻击增益",
    SpellPointMyAttributeBuffPlayActionInterceptor::class.java to "法术单体友方-属性增益",
    SpellPointMyFreezePlayActionInterceptor::class.java to "法术单体友方-冻结",
    SpellPointRivalDamagePlayActionInterceptor::class.java to "法术单体敌方-伤害",
    SpellPointRivalHealPlayActionInterceptor::class.java to "法术单体敌方-治疗",
    SpellPointRivalHealthBuffPlayActionInterceptor::class.java to "法术单体敌方-生命增益",
    SpellPointRivalAtkBuffPlayActionInterceptor::class.java to "法术单体敌方-攻击增益",
    SpellPointRivalAttributeBuffPlayActionInterceptor::class.java to "法术单体敌方-属性增益",
    SpellPointRivalFreezePlayActionInterceptor::class.java to "法术单体敌方-冻结",
    SpellAllRivalDamagePlayActionInterceptor::class.java to "法术群体敌方-伤害",
    SpellAllRivalHealPlayActionInterceptor::class.java to "法术群体敌方-治疗",
    SpellAllRivalHealthBuffPlayActionInterceptor::class.java to "法术群体敌方-生命增益",
    SpellAllRivalAtkBuffPlayActionInterceptor::class.java to "法术群体敌方-攻击增益",
    SpellAllRivalAttributeBuffPlayActionInterceptor::class.java to "法术群体敌方-属性增益",
    SpellAllRivalFreezePlayActionInterceptor::class.java to "法术群体敌方-冻结",
    SpellAllMyDamagePlayActionInterceptor::class.java to "法术群体友方-伤害",
    SpellAllMyHealPlayActionInterceptor::class.java to "法术群体友方-治疗",
    SpellAllMyHealthBuffPlayActionInterceptor::class.java to "法术群体友方-生命增益",
    SpellAllMyAtkBuffPlayActionInterceptor::class.java to "法术群体友方-攻击增益",
    SpellAllMyAttributeBuffPlayActionInterceptor::class.java to "法术群体友方-属性增益",
    SpellAllMyFreezePlayActionInterceptor::class.java to "法术群体友方-冻结",
    MinionPointRivalDamagePlayActionInterceptor::class.java to "随从单体敌方-伤害",
    MinionPointRivalHealPlayActionInterceptor::class.java to "随从单体敌方-治疗",
    MinionPointRivalHealthBuffPlayActionInterceptor::class.java to "随从单体敌方-生命增益",
    MinionPointRivalAtkBuffPlayActionInterceptor::class.java to "随从单体敌方-攻击增益",
    MinionPointRivalAttributeBuffPlayActionInterceptor::class.java to "随从单体敌方-属性增益",
    MinionPointRivalFreezePlayActionInterceptor::class.java to "随从单体敌方-冻结",
    MinionPointMyDamagePlayActionInterceptor::class.java to "随从单体友方-伤害",
    MinionPointMyHealPlayActionInterceptor::class.java to "随从单体友方-治疗",
    MinionPointMyHealthBuffPlayActionInterceptor::class.java to "随从单体友方-生命增益",
    MinionPointMyAtkBuffPlayActionInterceptor::class.java to "随从单体友方-攻击增益",
    MinionPointMyAttributeBuffPlayActionInterceptor::class.java to "随从单体友方-属性增益",
    MinionPointMyFreezePlayActionInterceptor::class.java to "随从单体友方-冻结",
    MinionAllRivalDamagePlayActionInterceptor::class.java to "随从群体敌方-伤害",
    MinionAllRivalHealPlayActionInterceptor::class.java to "随从群体敌方-治疗",
    MinionAllRivalHealthBuffPlayActionInterceptor::class.java to "随从群体敌方-生命增益",
    MinionAllRivalAtkBuffPlayActionInterceptor::class.java to "随从群体敌方-攻击增益",
    MinionAllRivalAttributeBuffPlayActionInterceptor::class.java to "随从群体敌方-属性增益",
    MinionAllRivalFreezePlayActionInterceptor::class.java to "随从群体敌方-冻结",
    MinionAllMyDamagePlayActionInterceptor::class.java to "随从群体友方-伤害",
    MinionAllMyHealPlayActionInterceptor::class.java to "随从群体友方-治疗",
    MinionAllMyHealthBuffPlayActionInterceptor::class.java to "随从群体友方-生命增益",
    MinionAllMyAtkBuffPlayActionInterceptor::class.java to "随从群体友方-攻击增益",
    MinionAllMyAttributeBuffPlayActionInterceptor::class.java to "随从群体友方-属性增益",
    MinionAllMyFreezePlayActionInterceptor::class.java to "随从群体友方-冻结",
)

fun describePlayActionInterceptor(interceptor: PlayActionInterceptor): String {
    val baseComment = PLAY_ACTION_INTERCEPTOR_COMMENT_MAP[interceptor::class.java]
        ?: interceptor::class.simpleName
        ?: interceptor::class.java.simpleName
    if (interceptor !is TargetDomainAwarePlayActionInterceptor) {
        return baseComment
    }
    return "$baseComment(${interceptor.targetDomain.comment})"
}

/*无需指向*/

object SpellNoPointPlayActionInterceptor : PlayActionInterceptor {
    override fun generatePlayActions(
        @Argument(0) war: War,
        @Argument(1) player: Player,
        @This cardAction: CardAction
    ): List<PlayAction> {
        return listOf(
            PlayAction({ newWar ->
                cardAction.findSelf(newWar)?.action?.power()
            }, { newWar ->
                cardAction.apply {
                    spendSelfCost(newWar)
                    removeSelf(newWar)
                }
            }, cardAction.belongCard)
        )
    }
}

object NonSpellNoPointPlayActionInterceptor : PlayActionInterceptor {
    override fun generatePlayActions(
        @Argument(0) war: War,
        @Argument(1) player: Player,
        @This cardAction: CardAction
    ): List<PlayAction> {
        return listOf(
            PlayAction({ newWar ->
                cardAction.findSelf(newWar)?.action?.power()
            }, { newWar ->
                cardAction.apply {
                    spendSelfCost(newWar)
                    removeSelf(newWar)?.let { card ->
                        addCardToPlayArea(newWar, card)
                    }
                }
            }, cardAction.belongCard)
        )
    }
}

fun createNoPointPlayActionInterceptor(cardType: CardTypeEnum): PlayActionInterceptor {
    return if (cardType === CardTypeEnum.SPELL) SpellNoPointPlayActionInterceptor else NonSpellNoPointPlayActionInterceptor
}

private fun addCardToPlayArea(newWar: War, card: Card) {
    val me = newWar.me
    if (card.cardType === CardTypeEnum.MINION || card.cardType === CardTypeEnum.LOCATION) {
        if (me.playArea.safeAdd(card)) {
            CardUtil.handleCardExhaustedWhenIntoPlayArea(card)
        }
        return
    }
    me.playArea.add(card)
}

/*法术单体友方*/

internal abstract class AbstractSpellPointMyPlayActionInterceptor(
    override val targetDomain: TargetDomain,
) : TargetDomainAwarePlayActionInterceptor {
    protected abstract fun applyEffect(targetCard: Card, newWar: War)

    protected open fun generateTest(targetCard: Card): Boolean = targetCard.canBeTargetedByMySpells()

    override fun generatePlayActions(
        @Argument(0) war: War,
        @Argument(1) player: Player,
        @This cardAction: CardAction
    ): List<PlayAction> {
        val result = mutableListOf<PlayAction>()
        val myPlayCards = targetDomain.getTargets(war)
        cardAction.apply {
            for (myCard in myPlayCards) {
                if (generateTest(myCard)) {
                    result.add(
                        PlayAction({ newWar ->
                            findSelf(newWar)?.action?.power(myCard.action.findSelf(newWar))
                        }, { newWar ->
                            spendSelfCost(newWar)
                            removeSelf(newWar)?.let {
                                myCard.action.findSelf(newWar)?.let { targetCard ->
                                    applyEffect(targetCard, newWar)
                                }
                            }
                        }, belongCard)
                    )
                }
            }
        }
        return result
    }
}

internal class SpellPointMyDamagePlayActionInterceptor(
    private val damage: Int,
    targetDomain: TargetDomain,
) : AbstractSpellPointMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) = targetCard.injured(damage + newWar.me.getSpellPower())

    override fun generateTest(targetCard: Card): Boolean = targetCard.canHurt() && targetCard.canBeTargetedByMySpells()
}

internal class SpellPointMyHealPlayActionInterceptor(
    private val heal: Int,
    targetDomain: TargetDomain,
) : AbstractSpellPointMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.damage = max(0, targetCard.damage - heal)
    }
}

internal class SpellPointMyHealthBuffPlayActionInterceptor(
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractSpellPointMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.plusHealth(health)
    }

}

internal class SpellPointMyAtkBuffPlayActionInterceptor(
    private val atc: Int,
    targetDomain: TargetDomain,
) : AbstractSpellPointMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.plusAtc(atc)
    }
}

internal class SpellPointMyAttributeBuffPlayActionInterceptor(
    private val atc: Int,
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractSpellPointMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.plusAtc(atc)
        targetCard.plusHealth(health)
    }
}

internal class SpellPointMyFreezePlayActionInterceptor(
    targetDomain: TargetDomain,
) : AbstractSpellPointMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.isFrozen = true
    }
}
/*法术单体敌方*/

internal abstract class AbstractSpellPointRivalPlayActionInterceptor(
    override val targetDomain: TargetDomain,
) : TargetDomainAwarePlayActionInterceptor {
    protected abstract fun applyEffect(targetCard: Card, newWar: War)

    protected open fun generateTest(targetCard: Card): Boolean = targetCard.canBeTargetedByRivalSpells()

    override fun generatePlayActions(
        @Argument(0) war: War,
        @Argument(1) player: Player,
        @This cardAction: CardAction
    ): List<PlayAction> {
        val result = mutableListOf<PlayAction>()
        val rivalPlayCards = targetDomain.getTargets(war)
        cardAction.apply {
            for (rivalCard in rivalPlayCards) {
                if (generateTest(rivalCard)) {
                    result.add(
                        PlayAction({ newWar ->
                            findSelf(newWar)?.action?.power(rivalCard.action.findSelf(newWar))
                        }, { newWar ->
                            spendSelfCost(newWar)
                            removeSelf(newWar)?.let {
                                rivalCard.action.findSelf(newWar)?.let { targetCard ->
                                    applyEffect(targetCard, newWar)
                                }
                            }
                        }, belongCard)
                    )
                }
            }
        }
        return result
    }
}

internal class SpellPointRivalDamagePlayActionInterceptor(
    private val damage: Int,
    targetDomain: TargetDomain,
) : AbstractSpellPointRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) = targetCard.injured(damage + newWar.me.getSpellPower())

    override fun generateTest(targetCard: Card): Boolean =
        targetCard.canHurt() && targetCard.canBeTargetedByRivalSpells()
}

internal class SpellPointRivalHealPlayActionInterceptor(
    private val heal: Int,
    targetDomain: TargetDomain,
) : AbstractSpellPointRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.damage = max(0, targetCard.damage - heal)
    }
}

internal class SpellPointRivalHealthBuffPlayActionInterceptor(
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractSpellPointRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.plusHealth(health)
    }

}

internal class SpellPointRivalAtkBuffPlayActionInterceptor(
    private val atc: Int,
    targetDomain: TargetDomain,
) : AbstractSpellPointRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.plusAtc(atc)
    }
}

internal class SpellPointRivalAttributeBuffPlayActionInterceptor(
    private val atc: Int,
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractSpellPointRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.plusAtc(atc)
        targetCard.plusHealth(health)
    }
}

internal class SpellPointRivalFreezePlayActionInterceptor(
    targetDomain: TargetDomain,
) : AbstractSpellPointRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.isFrozen = true
    }
}

/*法术群体敌方*/

internal abstract class AbstractSpellAllRivalPlayActionInterceptor(
    override val targetDomain: TargetDomain,
) : TargetDomainAwarePlayActionInterceptor {
    protected abstract fun applyEffect(targetCard: Card, newWar: War)


    override fun generatePlayActions(
        @Argument(0) war: War,
        @Argument(1) player: Player,
        @This cardAction: CardAction
    ): List<PlayAction> {
        cardAction.apply {
            return listOf(PlayAction({ newWar ->
                findSelf(newWar)?.action?.power()
            }, { newWar ->
                spendSelfCost(newWar)
                removeSelf(newWar)?.let {
                    val targetCards = targetDomain.getTargets(newWar)
                    for (card in targetCards) {
                        card.action.findSelf(newWar)?.let { targetCard ->
                            applyEffect(targetCard, newWar)
                        }
                    }
                }
            }, belongCard))
        }
    }
}

internal class SpellAllRivalDamagePlayActionInterceptor(
    private val damage: Int,
    targetDomain: TargetDomain,
) : AbstractSpellAllRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) = targetCard.injured(damage + newWar.me.getSpellPower())

}

internal class SpellAllRivalHealPlayActionInterceptor(
    private val heal: Int,
    targetDomain: TargetDomain,
) : AbstractSpellAllRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.damage = max(0, targetCard.damage - heal)
    }
}

internal class SpellAllRivalHealthBuffPlayActionInterceptor(
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractSpellAllRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.plusHealth(health)
    }
}

internal class SpellAllRivalAtkBuffPlayActionInterceptor(
    private val atc: Int,
    targetDomain: TargetDomain,
) : AbstractSpellAllRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.plusAtc(atc)
    }
}

internal class SpellAllRivalAttributeBuffPlayActionInterceptor(
    private val atc: Int,
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractSpellAllRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.plusAtc(atc)
        targetCard.plusHealth(health)
    }
}

internal class SpellAllRivalFreezePlayActionInterceptor(
    override val targetDomain: TargetDomain,
) : TargetDomainAwarePlayActionInterceptor {
    override fun generatePlayActions(
        @Argument(0) war: War,
        @Argument(1) player: Player,
        @This cardAction: CardAction
    ): List<PlayAction> {
        return listOf(
            PlayAction({ newWar ->
                cardAction.findSelf(newWar)?.action?.power()
            }, { newWar ->
                cardAction.spendSelfCost(newWar)
                cardAction.removeSelf(newWar)?.let {
                    targetDomain.getTargets(newWar).forEach { targetCard ->
                        targetCard.isFrozen = true
                    }
                }
            }, cardAction.belongCard)
        )
    }
}

/*法术群体我方*/

internal abstract class AbstractSpellAllMyPlayActionInterceptor(
    override val targetDomain: TargetDomain,
) : TargetDomainAwarePlayActionInterceptor {
    protected abstract fun applyEffect(targetCard: Card, newWar: War)

    override fun generatePlayActions(
        @Argument(0) war: War,
        @Argument(1) player: Player,
        @This cardAction: CardAction
    ): List<PlayAction> {
        cardAction.apply {
            return listOf(PlayAction({ newWar ->
                findSelf(newWar)?.action?.power()
            }, { newWar ->
                spendSelfCost(newWar)
                removeSelf(newWar)?.let {
                    val targetCards = targetDomain.getTargets(newWar)
                    for (card in targetCards) {
                        card.action.findSelf(newWar)?.let { targetCard ->
                            applyEffect(targetCard, newWar)
                        }
                    }
                }
            }, belongCard))
        }
    }
}

internal class SpellAllMyDamagePlayActionInterceptor(
    private val damage: Int,
    targetDomain: TargetDomain,
) : AbstractSpellAllMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) = targetCard.injured(damage + newWar.me.getSpellPower())

}

internal class SpellAllMyHealPlayActionInterceptor(
    private val heal: Int,
    targetDomain: TargetDomain,
) : AbstractSpellAllMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.damage = max(0, targetCard.damage - heal)
    }
}

internal class SpellAllMyHealthBuffPlayActionInterceptor(
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractSpellAllMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.plusHealth(health)
    }
}

internal class SpellAllMyAtkBuffPlayActionInterceptor(
    private val atc: Int,
    targetDomain: TargetDomain,
) : AbstractSpellAllMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.plusAtc(atc)
    }
}

internal class SpellAllMyAttributeBuffPlayActionInterceptor(
    private val atc: Int,
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractSpellAllMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.plusAtc(atc)
        targetCard.plusHealth(health)
    }
}

internal class SpellAllMyFreezePlayActionInterceptor(
    targetDomain: TargetDomain,
) : AbstractSpellAllMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card, newWar: War) {
        targetCard.isFrozen = true
    }
}

/*随从单体敌方*/

internal abstract class AbstractMinionPointRivalPlayActionInterceptor(
    override val targetDomain: TargetDomain,
) : TargetDomainAwarePlayActionInterceptor {
    protected abstract fun applyEffect(targetCard: Card)

    protected open fun generateTest(targetCard: Card): Boolean = targetCard.canBeTargetedByRival()

    override fun generatePlayActions(
        @Argument(0) war: War,
        @Argument(1) player: Player,
        @This cardAction: CardAction
    ): List<PlayAction> {
        val result = mutableListOf<PlayAction>()
        val rivalPlayCards = targetDomain.getTargets(war)
        cardAction.apply {
            for (rivalCard in rivalPlayCards) {
                if (generateTest(rivalCard)) {
                    result.add(
                        PlayAction({ newWar ->
                            findSelf(newWar)?.action?.power(false)?.pointTo(rivalCard.action.findSelf(newWar))
                        }, { newWar ->
                            spendSelfCost(newWar)
                            val me = newWar.me
                            removeSelf(newWar)?.let { card ->
                                if (me.playArea.safeAdd(card)) {
                                    CardUtil.handleCardExhaustedWhenIntoPlayArea(card)
                                    rivalCard.action.findSelf(newWar)?.let { targetCard ->
                                        applyEffect(targetCard)
                                    }
                                }
                            }
                        }, belongCard)
                    )
                }
            }
        }
        return result
    }
}

internal class MinionPointRivalDamagePlayActionInterceptor(
    private val damage: Int,
    targetDomain: TargetDomain,
) : AbstractMinionPointRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) = targetCard.injured(damage)

    override fun generateTest(targetCard: Card): Boolean = targetCard.canHurt() && targetCard.canBeTargetedByRival()
}

internal class MinionPointRivalHealPlayActionInterceptor(
    private val heal: Int,
    targetDomain: TargetDomain,
) : AbstractMinionPointRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.damage = max(0, targetCard.damage - heal)
    }
}

internal class MinionPointRivalHealthBuffPlayActionInterceptor(
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractMinionPointRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.plusHealth(health)
    }
}

internal class MinionPointRivalAtkBuffPlayActionInterceptor(
    private val atc: Int,
    targetDomain: TargetDomain,
) : AbstractMinionPointRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.plusAtc(atc)
    }
}

internal class MinionPointRivalAttributeBuffPlayActionInterceptor(
    private val atc: Int,
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractMinionPointRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.plusAtc(atc)
        targetCard.plusHealth(health)
    }
}

internal class MinionPointRivalFreezePlayActionInterceptor(
    targetDomain: TargetDomain,
) : AbstractMinionPointRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.isFrozen = true
    }
}

/*随从单体我方*/

internal abstract class AbstractMinionPointMyPlayActionInterceptor(
    override val targetDomain: TargetDomain,
) : TargetDomainAwarePlayActionInterceptor {
    protected abstract fun applyEffect(targetCard: Card)

    protected open fun generateTest(targetCard: Card): Boolean = targetCard.canBeTargetedByMe()

    override fun generatePlayActions(
        @Argument(0) war: War,
        @Argument(1) player: Player,
        @This cardAction: CardAction
    ): List<PlayAction> {
        val result = mutableListOf<PlayAction>()
        val myPlayCards = targetDomain.getTargets(war)
        cardAction.apply {
            for (myCard in myPlayCards) {
                if (generateTest(myCard)) {
                    result.add(
                        PlayAction({ newWar ->
                            findSelf(newWar)?.action?.power(false)?.pointTo(myCard.action.findSelf(newWar))
                        }, { newWar ->
                            spendSelfCost(newWar)
                            val me = newWar.me
                            removeSelf(newWar)?.let { card ->
                                if (me.playArea.safeAdd(card)) {
                                    CardUtil.handleCardExhaustedWhenIntoPlayArea(card)
                                    myCard.action.findSelf(newWar)?.let { targetCard ->
                                        applyEffect(targetCard)
                                    }
                                }
                            }
                        }, belongCard)
                    )
                }
            }
        }
        return result
    }
}

internal class MinionPointMyDamagePlayActionInterceptor(
    private val damage: Int,
    targetDomain: TargetDomain,
) : AbstractMinionPointMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) = targetCard.injured(damage)

    override fun generateTest(targetCard: Card): Boolean = targetCard.canHurt() && targetCard.canBeTargetedByMe()
}

internal class MinionPointMyHealPlayActionInterceptor(
    private val heal: Int,
    targetDomain: TargetDomain,
) : AbstractMinionPointMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.damage = max(0, targetCard.damage - heal)
    }
}

internal class MinionPointMyHealthBuffPlayActionInterceptor(
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractMinionPointMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.plusHealth(health)
    }
}

internal class MinionPointMyAtkBuffPlayActionInterceptor(
    private val atc: Int,
    targetDomain: TargetDomain,
) : AbstractMinionPointMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.plusAtc(atc)
    }
}

internal class MinionPointMyAttributeBuffPlayActionInterceptor(
    private val atc: Int,
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractMinionPointMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.plusAtc(atc)
        targetCard.plusHealth(health)
    }
}

internal class MinionPointMyFreezePlayActionInterceptor(
    targetDomain: TargetDomain,
) : AbstractMinionPointMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.isFrozen = true
    }
}

/*随从群体敌方*/

internal abstract class AbstractMinionAllRivalPlayActionInterceptor(
    override val targetDomain: TargetDomain,
) : TargetDomainAwarePlayActionInterceptor {
    protected abstract fun applyEffect(targetCard: Card)

    override fun generatePlayActions(
        @Argument(0) war: War,
        @Argument(1) player: Player,
        @This cardAction: CardAction
    ): List<PlayAction> {
        cardAction.apply {
            return listOf(PlayAction({ newWar ->
                findSelf(newWar)?.action?.power()
            }, { newWar ->
                spendSelfCost(newWar)
                removeSelf(newWar)?.let { card ->
                    CardUtil.handleCardExhaustedWhenIntoPlayArea(card)
                    val targetCards = targetDomain.getTargets(newWar)
                    for (card in targetCards) {
                        card.action.findSelf(newWar)?.let { targetCard ->
                            applyEffect(targetCard)
                        }
                    }
                }
            }, belongCard))
        }
    }
}

internal class MinionAllRivalDamagePlayActionInterceptor(
    private val damage: Int,
    targetDomain: TargetDomain,
) : AbstractMinionAllRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) = targetCard.injured(damage)

}

internal class MinionAllRivalHealPlayActionInterceptor(
    private val heal: Int,
    targetDomain: TargetDomain,
) : AbstractMinionAllRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.damage = max(0, targetCard.damage - heal)
    }
}

internal class MinionAllRivalHealthBuffPlayActionInterceptor(
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractMinionAllRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.plusHealth(health)
    }
}

internal class MinionAllRivalAtkBuffPlayActionInterceptor(
    private val atc: Int,
    targetDomain: TargetDomain,
) : AbstractMinionAllRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.plusAtc(atc)
    }
}

internal class MinionAllRivalAttributeBuffPlayActionInterceptor(
    private val atc: Int,
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractMinionAllRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.plusAtc(atc)
        targetCard.plusHealth(health)
    }
}

internal class MinionAllRivalFreezePlayActionInterceptor(
    targetDomain: TargetDomain,
) : AbstractMinionAllRivalPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.isFrozen = true
    }
}

/*随从群体我方*/

internal abstract class AbstractMinionAllMyPlayActionInterceptor(
    override val targetDomain: TargetDomain,
) : TargetDomainAwarePlayActionInterceptor {
    protected abstract fun applyEffect(targetCard: Card)

    override fun generatePlayActions(
        @Argument(0) war: War,
        @Argument(1) player: Player,
        @This cardAction: CardAction
    ): List<PlayAction> {
        cardAction.apply {
            return listOf(PlayAction({ newWar ->
                findSelf(newWar)?.action?.power()
            }, { newWar ->
                spendSelfCost(newWar)
                removeSelf(newWar)?.let { card ->
                    val targetCards = targetDomain.getTargets(newWar)
                    CardUtil.handleCardExhaustedWhenIntoPlayArea(card)
                    for (card in targetCards) {
                        card.action.findSelf(newWar)?.let { targetCard ->
                            applyEffect(targetCard)
                        }
                    }
                }
            }, belongCard))
        }
    }
}

internal class MinionAllMyDamagePlayActionInterceptor(
    private val damage: Int,
    targetDomain: TargetDomain,
) : AbstractMinionAllMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) = targetCard.injured(damage)

}

internal class MinionAllMyHealPlayActionInterceptor(
    private val heal: Int,
    targetDomain: TargetDomain,
) : AbstractMinionAllMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.damage = max(0, targetCard.damage - heal)
    }
}

internal class MinionAllMyHealthBuffPlayActionInterceptor(
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractMinionAllMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.plusHealth(health)
    }
}

internal class MinionAllMyAtkBuffPlayActionInterceptor(
    private val atc: Int,
    targetDomain: TargetDomain,
) : AbstractMinionAllMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.plusAtc(atc)
    }
}

internal class MinionAllMyAttributeBuffPlayActionInterceptor(
    private val atc: Int,
    private val health: Int,
    targetDomain: TargetDomain,
) : AbstractMinionAllMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.plusAtc(atc)
        targetCard.plusHealth(health)
    }
}

internal class MinionAllMyFreezePlayActionInterceptor(
    targetDomain: TargetDomain,
) : AbstractMinionAllMyPlayActionInterceptor(targetDomain) {
    override fun applyEffect(targetCard: Card) {
        targetCard.isFrozen = true
    }
}
