package club.xiaojiawei.hsscriptcardsdk.cardparser

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.DBCard
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.util.CardUtil
import club.xiaojiawei.hsscriptcardsdk.util.CardDBUtil
import club.xiaojiawei.hsscriptcardsdk.util.CardIdentityCatalog
import club.xiaojiawei.hsscriptcardsdk.diagnostics.UnknownCardCollector
import club.xiaojiawei.hsscriptcardsdk.diagnostics.UnknownCardSourceZone

/**
 * 基于卡牌文本动态生成 [CardAction] 的兜底工厂。
 * 仅在手写插件未命中时使用。
 */
object ParsedCardActionFactory {

    /**
     * CAP_105 (钩手拖曳/钩手拖拽) is present in the live card stream, but is
     * absent from some local card databases.  It is a no-target spell: the
     * game handles the Discover choice and the two summoned pirates after the
     * card click, so a generic play action is the correct executable contract.
     */
    private const val HOOK_N_HEAVE_ID = "CAP_105"
    private const val QUEST_REWARD_ID = "SW_028t5"
    private const val RAID_THE_DOCKS_ID = "SW_028"
    private const val APPLAUSE_THUNDER_ID = "ETC_372"
    private const val HOOKFIST_ID = "CORE_NX2_028"
    private const val BATTLEFIELD_ID = "AV_661"
    private const val DEEP_SEA_FUSION_ID = "TSC_069"
    private const val OVERFLOWING_LAVA_ID = "WW_424"
    private const val INITIAL_FIRE_ID = "CORE_SW_108"
    private const val INHERITED_FIRE_ID = "SW_108t"
    private const val INITIAL_FIRE_DAMAGE = 2

    /**
     * These cards are present in the Pirate Warrior deck or are its verified
     * generated cards. Their battlecry/quest resolution is handled by the
     * game after the click; the MCTS simulator only needs a safe play
     * transition so the card is not silently discarded as unrecognized.
     */
    private val opaqueMinionFallbackNames = mapOf(
        HOOKFIST_ID to "勾拳-3000型",
        "CAP_104" to "炸药工程师",
        "CAP_106" to "克罗雷船长",
        "CAP_107" to "火炮长",
        "CAP_107t" to "火炮手",
        "DRG_024" to "空中悍匪",
        "OG_312" to "恩佐斯的副官",
        "SW_029" to "港口匪徒",
        "VAC_430" to "血帆征兵员",
        "VAC_924" to "武器寄存员",
        "VAC_933" to "飞行员帕奇斯",
        "VAC_937" to "帆船舰长",
        "MAW_008" to "盲眼法官",
        "TOY_642" to "球霸野猪人",
        "CS3_008" to "血帆桨手",
        "DRG_082" to "黏指狗头人",
        "TTN_475" to "破链角斗士",
    )

    private val supplierCache = mutableMapOf<String, (() -> CardAction)?>()
    private val unrecognizedCardIds = mutableSetOf<String>()

    private val classFullNamePrefix = "${this::class.java.packageName}.generated.GeneratedCardAction_"

    fun get(cardId: String): (() -> CardAction)? = supplierCache[cardId]

    @Synchronized
    fun getOrCreate(cardId: String): (() -> CardAction)? {
        return getOrCreate(cardId, null)
    }

    /**
     * Resolves a dynamic action and records a user-visible diagnostic when the
     * card cannot be understood.  The display name comes from Power.log when
     * the local card database does not contain the card.
     */
    @Synchronized
    fun getOrCreate(cardId: String, displayName: String?): (() -> CardAction)? {
        return getOrCreate(cardId, displayName, UnknownCardSourceZone.UNKNOWN)
    }

    @Synchronized
    fun getOrCreate(
        cardId: String,
        displayName: String?,
        sourceZone: UnknownCardSourceZone,
    ): (() -> CardAction)? {
        if (cardId.isBlank()) {
            return null
        }
        if (supplierCache.containsKey(cardId)) {
            if (supplierCache[cardId] == null) {
                UnknownCardCollector.record(
                    cardId = cardId,
                    cardName = displayName,
                    reason = "cached-unresolved",
                    action = "FAIL_CLOSED",
                    sourceZone = sourceZone,
                    phase = "hand-action-resolution",
                    route = "FAIL_CLOSED_PARSER_UNAVAILABLE",
                    safeAction = "SKIP_UNRECOGNIZED",
                )
            }
            return supplierCache[cardId]
        }
        builtInSupplier(cardId, displayName)?.let { supplier ->
            supplierCache[cardId] = supplier
            return supplier
        }
        val dbCard = CardDBUtil.queryCardById(cardId).firstOrNull()
        val supplier = if (dbCard == null) {
            val identity = CardIdentityCatalog.lookup(cardId)
            if (identity == null) {
                logUnrecognizedCard(
                    cardId = cardId,
                    cardName = displayName,
                    reason = "card-db-missing",
                    sourceZone = sourceZone,
                )
            } else {
                logUnrecognizedCard(
                    cardId = cardId,
                    cardName = identity.name,
                    reason = "description-parser-no-interceptor",
                    sourceZone = sourceZone,
                    identitySource = identity.source.name,
                )
            }
            null
        } else {
            createSupplier(dbCard, sourceZone)
        }
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
        builtInSupplier(dbCard.cardId, dbCard.name)?.let { supplier ->
            supplierCache[dbCard.cardId] = supplier
            return supplier
        }
        val supplier = createSupplier(dbCard, UnknownCardSourceZone.UNKNOWN)
        supplierCache[dbCard.cardId] = supplier
        return supplier
    }

    @Synchronized
    fun clear() {
        supplierCache.clear()
        unrecognizedCardIds.clear()
        CardActionGenerator.clear()
    }

    private fun createSupplier(
        dbCard: DBCard,
        sourceZone: UnknownCardSourceZone = UnknownCardSourceZone.UNKNOWN,
    ): (() -> CardAction)? {
        val interceptor = CardDescriptionParser.parseAsPlayActionInterceptor(dbCard) ?: let {
            logUnrecognizedCard(
                cardId = dbCard.cardId,
                cardName = dbCard.name,
                reason = "description-parser-no-interceptor",
                sourceZone = sourceZone,
                identitySource = "DATABASE",
            )
            log.debug {
                "行为类-解析卡牌【${dbCard.name}:${dbCard.cardId}】失败 描述：${dbCard.text.replace("\n", "")}"
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

    private fun builtInSupplier(cardId: String, displayName: String?): (() -> CardAction)? {
        val readableDisplayName = displayName?.takeUnless { it.isBlank() }
        if (cardId == QUEST_REWARD_ID) {
            log.info {
                "CARD_ACTION_KNOWN_FALLBACK cardName=${readableDisplayName ?: "船长洛卡拉"} " +
                    "cardId=$QUEST_REWARD_ID effect=opaque-minion-play"
            }
            return { OpaqueMinionPlayCardAction(QUEST_REWARD_ID, "船长洛卡拉") }
        }
        if (cardId == RAID_THE_DOCKS_ID) {
            log.info {
                "CARD_ACTION_KNOWN_FALLBACK cardName=${readableDisplayName ?: "开进码头"} " +
                    "cardId=$RAID_THE_DOCKS_ID effect=opaque-quest-play"
            }
            return { OpaqueSpellPlayCardAction(RAID_THE_DOCKS_ID, "开进码头") }
        }
        if (cardId == APPLAUSE_THUNDER_ID) {
            log.info {
                "CARD_ACTION_KNOWN_FALLBACK cardName=${readableDisplayName ?: "掌声雷动"} " +
                    "cardId=$APPLAUSE_THUNDER_ID effect=draw-and-repeat-by-card-type"
            }
            return { OpaqueSpellPlayCardAction(APPLAUSE_THUNDER_ID, "掌声雷动") }
        }
        if (cardId == BATTLEFIELD_ID) {
            log.info {
                "CARD_ACTION_KNOWN_FALLBACK cardName=${readableDisplayName ?: "征战平原"} " +
                    "cardId=$BATTLEFIELD_ID effect=opaque-delayed-friendly-minion-buff"
            }
            return { OpaqueSpellPlayCardAction(BATTLEFIELD_ID, "征战平原") }
        }
        if (cardId == DEEP_SEA_FUSION_ID) {
            log.info {
                "CARD_ACTION_KNOWN_FALLBACK cardName=${readableDisplayName ?: "深海融合怪"} " +
                    "cardId=$DEEP_SEA_FUSION_ID effect=discover-same-minion-type-target-friendly-minion"
            }
            return { DeepSeaFusionMinionCardAction() }
        }
        if (cardId == INITIAL_FIRE_ID || cardId == INHERITED_FIRE_ID) {
            log.info {
                "CARD_ACTION_KNOWN_FALLBACK cardName=${readableDisplayName ?: if (cardId == INITIAL_FIRE_ID) "初始之火" else "传承之火"} " +
                    "cardId=$cardId effect=two-damage-enemy-minion-target-prioritize-kill"
            }
            return { InitialFireDamageCardAction(cardId, if (cardId == INITIAL_FIRE_ID) "初始之火" else "传承之火") }
        }
        if (cardId == OVERFLOWING_LAVA_ID) {
            log.info {
                "CARD_ACTION_KNOWN_FALLBACK cardName=${readableDisplayName ?: "溢流熔岩"} " +
                    "cardId=$OVERFLOWING_LAVA_ID effect=elemental-chain-copy"
            }
            return { OpaqueMinionPlayCardAction(OVERFLOWING_LAVA_ID, "溢流熔岩") }
        }
        opaqueMinionFallbackNames[cardId]?.let { name ->
            log.info {
                "CARD_ACTION_KNOWN_FALLBACK cardName=${readableDisplayName ?: name} " +
                    "cardId=$cardId effect=opaque-minion-play"
            }
            return { OpaqueMinionPlayCardAction(cardId, name) }
        }
        if (cardId != HOOK_N_HEAVE_ID) return null
        log.info {
            "CARD_ACTION_KNOWN_FALLBACK cardName=${readableDisplayName ?: "钩手拖曳"} " +
            "cardId=$HOOK_N_HEAVE_ID effect=discover-pirate-and-summon-two-1-1-pirates"
        }
        return { HookNHeaveCardAction() }
    }

    private class HookNHeaveCardAction : CardAction.DefaultCardAction() {
        override fun generatePlayActions(war: War, player: club.xiaojiawei.hsscriptcardsdk.bean.Player): List<PlayAction> {
            val card = belongCard ?: return emptyList()
            if (card.isUncertain || card.cost > player.usableResource) return emptyList()
            return listOf(
                PlayAction(
                    { newWar ->
                        logPlay()
                        findSelf(newWar)?.action?.power()
                    },
                    { newWar ->
                        spendSelfCost(newWar)
                        removeSelf(newWar)?.let { exhaustedCard ->
                            CardUtil.handleCardExhaustedWhenIntoPlayArea(exhaustedCard)
                            newWar.me.playArea.safeAdd(exhaustedCard)
                        }
                    },
                    card,
                ),
            )
        }

        override fun createNewInstance(): CardAction = HookNHeaveCardAction()

        override fun getCardId(): Array<String> = arrayOf(HOOK_N_HEAVE_ID)

        override fun name(): String = "钩手拖曳"
    }

    /**
     * A verified quest reward whose battlecry is resolved by Hearthstone after
     * the card is placed.  The generic minion play transition is safe here;
     * the fallback deliberately does not invent a battlecry or target.
     */
    private class OpaqueMinionPlayCardAction(
        private val opaqueCardId: String,
        private val displayName: String,
    ) : CardAction.DefaultCardAction() {
        override fun createNewInstance(): CardAction =
            OpaqueMinionPlayCardAction(opaqueCardId, displayName)

        override fun getCardId(): Array<String> = arrayOf(opaqueCardId)

        override fun name(): String = displayName
    }

    /**
     * No-target spell fallback.  Hearthstone resolves the quest/draw effect
     * after the click; this action only models spending/removing the spell so
     * the planner can continue and re-scan the live board.
     */
    private class OpaqueSpellPlayCardAction(
        private val opaqueCardId: String,
        private val displayName: String,
    ) : CardAction.DefaultCardAction() {
        override fun generatePlayActions(
            war: War,
            player: club.xiaojiawei.hsscriptcardsdk.bean.Player,
        ): List<PlayAction> {
            val card = belongCard ?: return emptyList()
            if (card.isUncertain || card.cost > player.usableResource) return emptyList()
            return listOf(
                PlayAction(
                    { newWar ->
                        logPlay()
                        findSelf(newWar)?.action?.power()
                    },
                    { newWar ->
                        spendSelfCost(newWar)
                        removeSelf(newWar)?.let { exhaustedCard ->
                            CardUtil.handleCardExhaustedWhenIntoPlayArea(exhaustedCard)
                            newWar.me.playArea.safeAdd(exhaustedCard)
                        }
                    },
                    card,
                ),
            )
        }

        override fun createNewInstance(): CardAction =
            OpaqueSpellPlayCardAction(opaqueCardId, displayName)

        override fun getCardId(): Array<String> = arrayOf(opaqueCardId)

        override fun name(): String = displayName
    }

    /**
     * TSC_069 (深海融合怪) is a targeted battlecry.  The game does not
     * resolve the battlecry from the card click alone: it first asks for a
     * friendly minion whose type is copied by Discover.  Returning a plain
     * opaque minion action here used to dispatch the card without a target,
     * leaving it in hand after the no-op confirmation path.
     *
     * The action is legal only when a living friendly minion exposes a known
     * minion type.  Each legal target becomes a separate MCTS action, so the
     * executor performs the normal card-drag followed by a click on that
     * friendly minion and the simulator never fabricates a no-target play.
     */
    private class DeepSeaFusionMinionCardAction : CardAction.DefaultCardAction() {
        override fun generatePlayActions(
            war: War,
            player: club.xiaojiawei.hsscriptcardsdk.bean.Player,
        ): List<PlayAction> {
            val card = belongCard ?: return emptyList()
            if (card.isUncertain || card.cost > player.usableResource) return emptyList()

            val targets = war.me.playArea.cards.filter { target ->
                target.cardType === club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum.MINION &&
                    target.isAlive() &&
                    target.cardRace !== club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum.UNKNOWN
            }
            if (targets.isEmpty()) return emptyList()

            return targets.map { target ->
                PlayAction(
                    { newWar ->
                        val source = findSelf(newWar)
                        val selectedTarget = newWar.me.playArea.cards.firstOrNull {
                            it.entityId == target.entityId
                        }
                        if (source != null && selectedTarget != null) {
                            log.info {
                                "DEEP_SEA_FUSION_EXECUTION source=${card.entityId}:${card.cardId} " +
                                    "target=${selectedTarget.entityId}:${selectedTarget.cardId} " +
                                    "sequence=play-minion-then-select-friendly-minion"
                            }
                            // This battlecry target is selected after the minion
                            // enters the board.  Dragging the hand card directly
                            // to the target is interpreted as an invalid play by
                            // Hearthstone and leaves the card in hand.
                            source.action.power(false)?.pointTo(selectedTarget)
                        }
                    },
                    { newWar ->
                        spendSelfCost(newWar)
                        removeSelf(newWar)?.let { exhaustedCard ->
                            CardUtil.handleCardExhaustedWhenIntoPlayArea(exhaustedCard)
                            newWar.me.playArea.safeAdd(exhaustedCard)
                        }
                    },
                    card,
                )
            }
        }

        override fun createNewInstance(): CardAction = DeepSeaFusionMinionCardAction()

        override fun getCardId(): Array<String> = arrayOf(DEEP_SEA_FUSION_ID)

        override fun name(): String = "深海融合怪"
    }

    /**
     * 初始之火 and its generated token 传承之火 have the same executable
     * contract: deal two damage to one minion.  The generic parser exposes an
     * action for every legal enemy target, which lets MCTS select a disposable
     * target even when another enemy can be killed.  Keep one deterministic
     * action instead: killable enemy minions first, then the highest-threat
     * enemy minion when no kill is available.  Friendly minions are never a
     * legal fallback for these cards.
     */
    private class InitialFireDamageCardAction(
        private val cardId: String,
        private val displayName: String,
    ) : CardAction.DefaultCardAction() {
        override fun generatePlayActions(
            war: War,
            player: club.xiaojiawei.hsscriptcardsdk.bean.Player,
        ): List<PlayAction> {
            val card = belongCard ?: return emptyList()
            if (card.isUncertain || card.cost > player.usableResource) return emptyList()

            val damage = INITIAL_FIRE_DAMAGE + war.me.getSpellPower()
            val target = war.rival.playArea.cards
                .filter { it.cardType === CardTypeEnum.MINION && it.canBeTargetedByRivalSpells() }
                .maxWithOrNull(
                    compareBy<Card>(
                        { if (it.blood() <= damage) 1 else 0 },
                        { it.atc },
                        { it.blood() },
                    ),
                ) ?: return emptyList()
            val killable = target.blood() <= damage
            log.info {
                "INITIAL_FIRE_TARGET card=${card.entityId}:${card.cardId} damage=$damage " +
                    "target=${target.entityId}:${target.cardId} targetHealth=${target.blood()} " +
                    "targetAttack=${target.atc} killable=$killable policy=enemy-minion-only"
            }

            return listOf(
                PlayAction(
                    { newWar ->
                        val source = findSelf(newWar)
                        val selectedTarget = newWar.rival.playArea.cards.firstOrNull {
                            it.entityId == target.entityId
                        }
                        if (source != null && selectedTarget != null) {
                            logPowerPoint(selectedTarget)
                            source.action.power(selectedTarget)
                        }
                    },
                    { newWar ->
                        val currentTarget = newWar.rival.playArea.cards.firstOrNull {
                            it.entityId == target.entityId
                        }
                        spendSelfCost(newWar)
                        removeSelf(newWar)?.let { exhaustedCard ->
                            currentTarget?.injured(INITIAL_FIRE_DAMAGE + newWar.me.getSpellPower())
                        }
                    },
                    card,
                ),
            )
        }

        override fun createNewInstance(): CardAction =
            InitialFireDamageCardAction(cardId, displayName)

        override fun getCardId(): Array<String> = arrayOf(cardId)

        override fun name(): String = displayName
    }

    private fun logUnrecognizedCard(
        cardId: String,
        cardName: String?,
        reason: String,
        sourceZone: UnknownCardSourceZone = UnknownCardSourceZone.UNKNOWN,
        identitySource: String? = null,
    ) {
        if (!unrecognizedCardIds.add(cardId)) {
            return
        }
        val readableName = cardName
            ?.takeUnless { it.isBlank() || it.startsWith("UNKNOWN ENTITY") }
            ?: "未知卡牌($cardId)"
        log.warn {
            "CARD_ACTION_UNRECOGNIZED cardName=$readableName cardId=$cardId " +
                "reason=$reason sourceZone=${sourceZone.name} " +
                "identitySource=${identitySource ?: "UNKNOWN"} " +
                "route=FAIL_CLOSED_PARSER_UNAVAILABLE safeAction=SKIP_UNRECOGNIZED"
        }
        UnknownCardCollector.record(
            cardId = cardId,
            cardName = readableName,
            reason = reason,
            action = "FAIL_CLOSED",
            sourceZone = sourceZone,
            phase = "hand-action-resolution",
            identitySource = identitySource,
            route = "FAIL_CLOSED_PARSER_UNAVAILABLE",
            safeAction = "SKIP_UNRECOGNIZED",
        )
    }

    private fun buildClassName(cardId: String): String {
        val sanitized = cardId.replace(Regex("[^A-Za-z0-9_]"), "_")
        return "${classFullNamePrefix}$sanitized"
    }
}
