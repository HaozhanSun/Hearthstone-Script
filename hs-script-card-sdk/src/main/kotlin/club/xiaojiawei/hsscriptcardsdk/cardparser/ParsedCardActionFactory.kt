package club.xiaojiawei.hsscriptcardsdk.cardparser

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.DBCard
import club.xiaojiawei.hsscriptcardsdk.util.CardDBUtil
import club.xiaojiawei.hsscriptcardsdk.util.CardIdentityCatalog

/**
 * 基于卡牌文本动态生成 [CardAction] 的兜底工厂。
 * 仅在手写插件未命中时使用。
 */
object ParsedCardActionFactory {

    private val supplierCache = mutableMapOf<String, (() -> CardAction)?>()

    private val classFullNamePrefix = "${this::class.java.packageName}.generated.GeneratedCardAction_"

    fun get(cardId: String): (() -> CardAction)? = supplierCache[cardId]

    @Synchronized
    fun getOrCreate(cardId: String): (() -> CardAction)? {
        if (cardId.isBlank()) {
            return null
        }
        if (supplierCache.containsKey(cardId)) {
            return supplierCache[cardId]
        }
        val dbCard = CardDBUtil.queryCardById(cardId).firstOrNull()
        if (dbCard == null) {
            val identity = CardIdentityCatalog.lookup(cardId)
            log.warn {
                if (identity == null) {
                    "CARD_ACTION_UNRECOGNIZED cardName=未知卡牌($cardId) cardId=$cardId " +
                        "reason=card-db-missing action=FAIL_CLOSED"
                } else {
                    "CARD_ACTION_UNRECOGNIZED cardName=${identity.name} cardId=$cardId " +
                        "reason=description-parser-no-interceptor action=FAIL_CLOSED " +
                        "identitySource=${identity.source.name}"
                }
            }
        }
        val supplier = dbCard?.let(::createSupplier)
        supplierCache[cardId] = supplier
        return supplier
    }

    @Synchronized
    fun getOrCreate(dbCard: DBCard): (() -> CardAction)? {
        if (dbCard.cardId.isBlank()) {
            return null
        }
        if (supplierCache.containsKey(dbCard.cardId)) {
            return supplierCache[dbCard.cardId]
        }
        val supplier = createSupplier(dbCard)
        supplierCache[dbCard.cardId] = supplier
        return supplier
    }

    @Synchronized
    fun clear() {
        supplierCache.clear()
        CardActionGenerator.clear()
    }

    private fun createSupplier(dbCard: DBCard): (() -> CardAction)? {
        val interceptor = CardDescriptionParser.parseAsPlayActionInterceptor(dbCard) ?: let {
            log.warn {
                "CARD_ACTION_UNRECOGNIZED cardName=${dbCard.name} cardId=${dbCard.cardId} " +
                    "reason=description-parser-no-interceptor action=FAIL_CLOSED"
            }
            return null
        }
        val generatedClass = CardActionGenerator.generateCardActionClass(
            className = buildClassName(dbCard.cardId),
            cardIds = arrayOf(dbCard.cardId),
            playActionInterceptor = interceptor,
        )
        log.info {
            """
                行为类-解析卡牌【${dbCard.name}:${dbCard.cardId}】
                描述：${dbCard.text.replace("\n", "")}
                [${describePlayActionInterceptor(interceptor)}:${generatedClass.simpleName}]                
            """.trimIndent()
        }
        val cardAction = generatedClass.getDeclaredConstructor().newInstance()
        return { cardAction.createNewInstance() }
    }

    private fun buildClassName(cardId: String): String {
        val sanitized = cardId.replace(Regex("[^A-Za-z0-9_]"), "_")
        return "${classFullNamePrefix}$sanitized"
    }
}
