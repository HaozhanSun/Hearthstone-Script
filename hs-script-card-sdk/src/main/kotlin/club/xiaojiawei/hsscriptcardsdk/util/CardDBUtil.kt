package club.xiaojiawei.hsscriptcardsdk.util

import club.xiaojiawei.hsscriptcardsdk.bean.DBCard
import club.xiaojiawei.hsscriptcardsdk.config.DBConfig
import club.xiaojiawei.hsscriptbase.config.log
import org.springframework.jdbc.core.BeanPropertyRowMapper

/**
 * @author 肖嘉威
 * @date 2024/11/13 15:56
 */
object CardDBUtil {

    fun queryCardByName(name: String, limit: Int = 100, offset: Int = 0, precise: Boolean = true): List<DBCard> {
        runCatching {
            if (precise) {
                DBConfig.CARD_DB.query(
                    "select * from cards where name = ? limit ? offset ?",
                    BeanPropertyRowMapper(DBCard::class.java),
                    name,
                    limit,
                    offset
                )
            } else {
                DBConfig.CARD_DB.query(
                    "select * from cards where name like ? limit ? offset ?",
                    BeanPropertyRowMapper(DBCard::class.java),
                    name,
                    limit,
                    offset
                )
            }
        }.onSuccess {
            return it
        }.onFailure {
            log.error(it) { "查询卡牌异常" }
            return emptyList()
        }
        return emptyList()
    }

    fun queryCardById(cardId: String, limit: Int = 100, offset: Int = 0, precise: Boolean = true): List<DBCard> {
        runCatching {
            if (precise) {
                DBConfig.CARD_DB.query(
                    "select * from cards where cardId = ? limit ? offset ?",
                    BeanPropertyRowMapper(DBCard::class.java),
                    cardId,
                    limit,
                    offset
                )
            } else {
                DBConfig.CARD_DB.query(
                    "select * from cards where cardId like ? limit ? offset ?",
                    BeanPropertyRowMapper(DBCard::class.java),
                    cardId,
                    limit,
                    offset
                )
            }
        }.onSuccess {
            return it
        }.onFailure {
            log.error(it) { "查询卡牌异常" }
            return emptyList()
        }
        return emptyList()
    }

    fun queryCardByDbfId(dbfId: Int): DBCard? {
        runCatching {
            DBConfig.CARD_DB.query(
                "select * from cards where dbfId = ? limit 1",
                BeanPropertyRowMapper(DBCard::class.java),
                dbfId
            )
        }.onSuccess {
            return it.firstOrNull()
        }.onFailure {
            log.error(it) { "查询卡牌异常" }
            return null
        }
        return null
    }

    fun queryCardsByDbfIds(dbfIds: List<Int>): Map<Int, DBCard> {
        if (dbfIds.isEmpty()) return emptyMap()
        
        val result = mutableMapOf<Int, DBCard>()
        runCatching {
            val placeholders = dbfIds.joinToString(",") { "?" }
            DBConfig.CARD_DB.query(
                "select * from cards where dbfId in ($placeholders)",
                BeanPropertyRowMapper(DBCard::class.java),
                *dbfIds.toTypedArray()
            )
        }.onSuccess { cards ->
            cards.forEach { card ->
                result[card.dbfId] = card
            }
        }.onFailure {
            log.error(it) { "批量查询卡牌异常" }
        }
        return result
    }

}