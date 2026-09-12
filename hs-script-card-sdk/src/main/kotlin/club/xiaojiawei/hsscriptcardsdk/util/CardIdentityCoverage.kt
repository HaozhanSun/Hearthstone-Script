package club.xiaojiawei.hsscriptcardsdk.util

/**
 * A small, dependency-free audit for deck card identity coverage.
 *
 * Card recognition has two separate failure points: a card may be absent from
 * the local database, or it may be present but have no description parser
 * action.  This helper intentionally audits only the first point.  It does
 * not manufacture an action for an unknown card; callers must keep the
 * fail-closed behavior of the action factory.
 */
data class CardIdentityRef(
    val cardId: String,
    val expectedName: String,
)

data class CardIdentityCoverageResult(
    val resolved: List<CardIdentityRef>,
    val missing: List<CardIdentityRef>,
    val actionResolved: List<CardIdentityRef> = emptyList(),
    val actionUnavailable: List<CardIdentityRef> = emptyList(),
) {
    val isComplete: Boolean
        get() = missing.isEmpty() && actionUnavailable.isEmpty()

    /** Human-readable evidence suitable for a diagnostic log or test failure. */
    fun diagnosticMessage(): String = if (isComplete) {
        "牌库卡牌身份覆盖完整：${resolved.size} 张"
    } else {
        buildList {
            if (missing.isNotEmpty()) {
                add("牌库缺少卡牌记录：" + missing.joinToString("、") {
                    "${it.expectedName}(${it.cardId})"
                })
            }
            if (actionUnavailable.isNotEmpty()) {
                add("描述解析器无可执行拦截器：" + actionUnavailable.joinToString("、") {
                    "${it.expectedName}(${it.cardId})"
                })
            }
        }.joinToString("；")
    }
}

object CardIdentityCoverage {
    /**
     * Preserve the supplied deck order so the resulting diagnostic can be
     * compared with a deck profile and read directly by a human.
     */
    fun inspect(
        cards: List<CardIdentityRef>,
        resolver: (String) -> Any?,
        actionResolver: ((String) -> Any?)? = null,
    ): CardIdentityCoverageResult {
        val resolved = mutableListOf<CardIdentityRef>()
        val missing = mutableListOf<CardIdentityRef>()
        val actionResolved = mutableListOf<CardIdentityRef>()
        val actionUnavailable = mutableListOf<CardIdentityRef>()
        cards.forEach { card ->
            if (card.cardId.isBlank() || resolver(card.cardId) == null) {
                missing += card
            } else if (actionResolver == null || actionResolver(card.cardId) != null) {
                resolved += card
                if (actionResolver != null) actionResolved += card
            } else {
                resolved += card
                actionUnavailable += card
            }
        }
        return CardIdentityCoverageResult(resolved, missing, actionResolved, actionUnavailable)
    }

    /** Fail with names and IDs instead of an opaque list of unresolved IDs. */
    fun requireComplete(result: CardIdentityCoverageResult): CardIdentityCoverageResult {
        check(result.isComplete) { result.diagnosticMessage() }
        return result
    }
}
