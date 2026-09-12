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
        "CAP_104" to "炸药工程师",
        "CAP_105" to "钩手拖曳",
        "CAP_106" to "克罗雷船长",
        "CAP_107" to "火炮长",
        "CAP_107t" to "火炮手",
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
