package club.xiaojiawei.hsscriptcardsdk.util

import club.xiaojiawei.hsscriptcardsdk.bean.DBCard

enum class CardIdentitySource {
    DATABASE,
    VERIFIED_CATALOG,
}

data class CardIdentity(
    val cardId: String,
    val name: String,
    val source: CardIdentitySource,
)

/**
 * Stable identity-only fallback for cards missing from a local hs_cards.db.
 *
 * This catalog deliberately contains no card text, stats, or transitions.
 * It can make telemetry and deck reports readable, but it cannot make a card
 * executable.  ParsedCardActionFactory must therefore continue to fail
 * closed for catalog-only entries.
 */
object CardIdentityCatalog {

    private val verifiedNames = mapOf(
        "SW_028t5" to "船长洛卡拉",
        "CAP_104" to "炸药工程师",
        "CAP_105" to "钩手拖曳",
        "CAP_106" to "克罗雷船长",
        "CAP_107" to "火炮长",
        "CAP_107t" to "火炮手",
        // Elemental Mage cards observed in the Beta action stream.  These are
        // identity-only fallbacks for telemetry when a local DB is stale;
        // they intentionally do not grant an executable action.
        "TTN_095" to "流水档案管理员",
        "DEEP_034" to "页岩蛛",
        "TOY_370" to "三芯诡烛",
        "DMF_100" to "甜点飓风",
        "CORE_UNG_809" to "火羽精灵",
        "GDB_302" to "吸积炽焰",
        "GDB_303" to "爆炎流星",
        "SW_439t" to "橡果",
        "ULD_239" to "火焰结界",
        "TOY_000" to "焦油泥浆怪",
        "WW_424" to "溢流熔岩",
        "EX1_015" to "工程师学徒",
    )

    fun lookup(cardId: String): CardIdentity? =
        verifiedNames[cardId]?.let { CardIdentity(cardId, it, CardIdentitySource.VERIFIED_CATALOG) }

    fun resolve(cardId: String, databaseCard: DBCard?): CardIdentity? =
        databaseCard?.let {
            CardIdentity(
                cardId = it.cardId.ifBlank { cardId },
                name = it.name.ifBlank { cardId },
                source = CardIdentitySource.DATABASE,
            )
        } ?: lookup(cardId)
}
