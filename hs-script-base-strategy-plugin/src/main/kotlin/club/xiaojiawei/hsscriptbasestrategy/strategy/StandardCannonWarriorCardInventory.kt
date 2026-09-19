package club.xiaojiawei.hsscriptbasestrategy.strategy

/** One auditable row from the supplied Standard Warrior screenshot. */
data class StandardCannonWarriorCardSpec(
    val displayName: String,
    val currentCardId: String,
    val role: String,
    val priority: String,
    val prerequisite: String,
    val offlineAssertion: String?,
    val safeFallback: String,
    val idEvidence: String,
)

object StandardCannonWarriorCardInventory {
    const val UNKNOWN_CARD_ID = StandardCannonWarriorMctsModel.UNKNOWN

    /** Exact screenshot-derived order; do not substitute cards from Pirate Warrior. */
    val screenshotCards: List<Pair<String, String>> = listOf(
        "火炮长" to UNKNOWN_CARD_ID,
        "空中悍匪" to StandardCannonWarriorMctsModel.AIRBORNE_RAIDER,
        "赤红深渊" to StandardCannonWarriorMctsModel.SANGUINE_DEPTHS,
        "跟随引线" to UNKNOWN_CARD_ID,
        "戴雾维龙" to UNKNOWN_CARD_ID,
        "影焰晕染" to StandardCannonWarriorMctsModel.SHADOWFLAME_DAZE,
        "怒袭" to StandardCannonWarriorMctsModel.BASH,
        "炸药工程师" to UNKNOWN_CARD_ID,
        "钩手拖曳" to UNKNOWN_CARD_ID,
        "龙巢守护者" to StandardCannonWarriorMctsModel.DRAGON_NEST_GUARDIAN,
        "南海船长" to StandardCannonWarriorMctsModel.SOUTHSEA_CAPTAIN,
        "手持火炮" to UNKNOWN_CARD_ID,
        "次元武器匠" to StandardCannonWarriorMctsModel.DIMENSIONAL_WEAPONSMITH,
        "幻影绿翼龙" to StandardCannonWarriorMctsModel.PHANTOM_GREENWING,
        "眺望陆地" to UNKNOWN_CARD_ID,
        "克罗雷船长" to StandardCannonWarriorMctsModel.CAPTAIN_CROWLEY,
        "乘风浮龙" to StandardCannonWarriorMctsModel.WINDRIDER,
        "破链灾星霍格" to StandardCannonWarriorMctsModel.HOGGER,
    )

    val cards: List<StandardCannonWarriorCardSpec> = listOf(
        spec("火炮长", UNKNOWN_CARD_ID, "cannon support", "parser-only", "card ID not found locally"),
        spec("空中悍匪", StandardCannonWarriorMctsModel.AIRBORNE_RAIDER, "early minion", "P1", "parser-backed play action"),
        spec("赤红深渊", StandardCannonWarriorMctsModel.SANGUINE_DEPTHS, "location/removal", "P0/P1", "legal location target and activation"),
        spec("跟随引线", UNKNOWN_CARD_ID, "unknown tempo", "parser-only", "card ID not found locally"),
        spec("戴雾维龙", UNKNOWN_CARD_ID, "unknown dragon", "parser-only", "card ID not found locally"),
        spec("影焰晕染", StandardCannonWarriorMctsModel.SHADOWFLAME_DAZE, "spell/resource", "P1", "parser-backed spell action"),
        spec("怒袭", StandardCannonWarriorMctsModel.BASH, "removal/armor", "P0/P1", "legal target or armor branch"),
        spec("炸药工程师", UNKNOWN_CARD_ID, "cannon support", "parser-only", "card ID not found locally"),
        spec("钩手拖曳", UNKNOWN_CARD_ID, "weapon/tempo", "parser-only", "card ID not found locally"),
        spec("龙巢守护者", StandardCannonWarriorMctsModel.DRAGON_NEST_GUARDIAN, "dragon development", "P1", "parser-backed minion play"),
        spec("南海船长", StandardCannonWarriorMctsModel.SOUTHSEA_CAPTAIN, "board aura", "P1", "another friendly minion can receive value"),
        spec("手持火炮", UNKNOWN_CARD_ID, "cannon/weapon", "parser-only", "card ID not found locally"),
        spec("次元武器匠", StandardCannonWarriorMctsModel.DIMENSIONAL_WEAPONSMITH, "weapon support", "P1", "parser-backed minion play"),
        spec("幻影绿翼龙", StandardCannonWarriorMctsModel.PHANTOM_GREENWING, "removal payoff", "P1/P2", "opponent board or damage context exists"),
        spec("眺望陆地", UNKNOWN_CARD_ID, "unknown resource", "parser-only", "card ID not found locally"),
        spec("克罗雷船长", StandardCannonWarriorMctsModel.CAPTAIN_CROWLEY, "cannon finisher", "P1/P2", "at least three board slots for the two Cannoneers"),
        spec("乘风浮龙", StandardCannonWarriorMctsModel.WINDRIDER, "late finisher", "P2", "enough mana and legal play action"),
        spec("破链灾星霍格", StandardCannonWarriorMctsModel.HOGGER, "late board control", "P2", "enough mana and legal play action"),
    )

    private fun spec(
        name: String,
        id: String,
        role: String,
        priority: String,
        prerequisite: String,
    ): StandardCannonWarriorCardSpec {
        val confirmed = id != UNKNOWN_CARD_ID
        return StandardCannonWarriorCardSpec(
            displayName = name,
            currentCardId = id,
            role = role,
            priority = priority,
            prerequisite = prerequisite,
            offlineAssertion = if (confirmed) "StandardCannonWarriorMctsModel confirmed ID and priority path" else null,
            safeFallback = if (confirmed) {
                "Use only parser-backed actions; never synthesize an unobserved transition."
            } else {
                "UNKNOWN cardId: parser-backed action only; otherwise exclude and do not fabricate effects."
            },
            idEvidence = if (confirmed) {
                "hs_cards.db exact-name lookup"
            } else {
                "hs_cards.db exact-name lookup returned no unique row"
            },
        )
    }
}


