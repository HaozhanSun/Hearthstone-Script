package club.xiaojiawei.hsscriptcardsdk.data

import club.xiaojiawei.hsscriptbase.bean.LikeTrie
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.CardInfo
import club.xiaojiawei.hsscriptcardsdk.bean.DBCard
import club.xiaojiawei.hsscriptcardsdk.cardparser.CardDescriptionParser
import club.xiaojiawei.hsscriptcardsdk.enums.CardActionEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.util.CardDBUtil

/**
 * 合并的卡牌数据存储，包含卡牌信息和权重
 * key: [club.xiaojiawei.hsscriptcardsdk.bean.Card.cardId], value: [CardInfo]
 * @author 肖嘉威
 * @date 2026/2/2
 */
val CARD_DATA_TRIE = LikeTrie(CardInfo())

object CardInfoData {

    fun parsePowerAction(dbCard: DBCard): List<CardActionEnum> {
        // 根据卡牌类型设置默认使用行为
        if (dbCard.type == CardTypeEnum.MINION.name || dbCard.type == CardTypeEnum.HERO.name || dbCard.type == CardTypeEnum.WEAPON.name) {
            return listOf(CardActionEnum.POINT_RIVAL)
        } else if (dbCard.type == CardTypeEnum.SPELL.name) {
            return emptyList()
        }
        return listOf(CardActionEnum.NO_POINT)
    }

    fun parsePlayAction(cardId: String): List<CardActionEnum> {
        if (cardId.isBlank()) return emptyList()
        CardDBUtil.queryCardById(cardId).firstOrNull()?.let {
            return parsePlayAction(it)
        }
        return emptyList()
    }

    fun parsePlayAction(dbCard: DBCard): List<CardActionEnum> {
        val cardActions = CardDescriptionParser.parseAsCardActionEnum(dbCard)
        printParseInfo(dbCard, cardActions)
        return cardActions
    }

    fun indexCard(cardId: String, likeTrie: LikeTrie<CardInfo> = CARD_DATA_TRIE) {
        if (cardId.isBlank()) return
        likeTrie.getNoDefault(cardId) ?: let {
            CardDBUtil.queryCardById(cardId).firstOrNull()?.let {
                val cardActions = CardDescriptionParser.parseAsCardActionEnum(it)
                printParseInfo(it, cardActions)
                likeTrie[cardId] = CardInfo(playActions = cardActions, powerActions = parsePowerAction(it))
            }
        }
    }

    private fun printParseInfo(dbCard: DBCard, cardActions: List<CardActionEnum>){
        log.info {
            """
                行为-解析卡牌【${dbCard.name}:${dbCard.cardId}】
                描述：${dbCard.text.replace("\n", "")}
                $cardActions
            """.trimIndent()
        }
    }
}
