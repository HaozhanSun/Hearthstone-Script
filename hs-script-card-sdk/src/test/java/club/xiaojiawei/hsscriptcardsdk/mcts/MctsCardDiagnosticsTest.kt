package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.InitAction
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MctsCardDiagnosticsTest {

    @Test
    fun `known id with invalid entity snapshot is fail closed even when name is catalogued`() {
        val card = card("TOY_370", CardTypeEnum.INVALID, "UNKNOWN ENTITY [cardType=INVALID]")

        val status = MctsCardDiagnostics.snapshotStatus(card)

        assertEquals(MctsCardSnapshotStatus.INVALID_CARD_TYPE, status)
        assertTrue(MctsCardDiagnostics.isFatalSnapshot(status))
        assertEquals(
            "FAIL_CLOSED_INVALID_SNAPSHOT",
            MctsCardDiagnostics.actionRoute(status, false, true, 1, false, true),
        )
        assertEquals("SKIP_UNRECOGNIZED", MctsCardDiagnostics.safeAction("FAIL_CLOSED_INVALID_SNAPSHOT"))
        assertEquals("三芯诡烛", MctsCardDiagnostics.displayName(card))
    }

    @Test
    fun `unknown display name is nonfatal when id and type are valid`() {
        val card = card("TTN_095", CardTypeEnum.MINION, "UNKNOWN ENTITY [cardType=INVALID]")

        assertEquals(MctsCardSnapshotStatus.UNKNOWN_ENTITY_NAME, MctsCardDiagnostics.snapshotStatus(card))
        assertFalse(MctsCardDiagnostics.isFatalSnapshot(MctsCardDiagnostics.snapshotStatus(card)))
        assertEquals("流水档案管理员", MctsCardDiagnostics.displayName(card))
    }

    @Test
    fun `uncertain minion and weapon may use brave opaque fallback`() {
        val minion = card("NEW_MINION", CardTypeEnum.MINION, "UNKNOWN ENTITY [cardType=MINION]").apply {
            entityId = "entity-minion"
            cost = 2
            atc = 2
            health = 3
            isUncertain = true
        }
        val weapon = card("NEW_WEAPON", CardTypeEnum.WEAPON, "UNKNOWN ENTITY [cardType=WEAPON]").apply {
            entityId = "entity-weapon"
            cost = 2
            isUncertain = true
        }

        assertTrue(MctsCardDiagnostics.braveOpaqueFallbackAllowed(minion))
        assertTrue(MctsCardDiagnostics.braveOpaqueFallbackAllowed(weapon))

        minion.isBattlecry = true
        assertFalse(MctsCardDiagnostics.braveOpaqueFallbackAllowed(minion))
    }

    @Test
    fun `unknown minion with missing or zero stats remains fail closed`() {
        val missing = card("UNKNOWN_MISSING_STATS", CardTypeEnum.MINION, "UNKNOWN ENTITY [cardType=MINION]").apply {
            entityId = "entity-missing-stats"
            cost = 2
            isUncertain = true
        }
        val zeroAttack = missing.clone() as Card
        zeroAttack.entityId = "entity-zero-attack"
        zeroAttack.atc = 0
        zeroAttack.health = 3
        val zeroHealth = missing.clone() as Card
        zeroHealth.entityId = "entity-zero-health"
        zeroHealth.atc = 2
        zeroHealth.health = 0

        assertFalse(MctsCardDiagnostics.hasUsableUnknownMinionStats(missing))
        assertFalse(MctsCardDiagnostics.hasUsableUnknownMinionStats(zeroAttack))
        assertFalse(MctsCardDiagnostics.hasUsableUnknownMinionStats(zeroHealth))
        assertEquals("missing-or-invalid-minion-stats", MctsCardDiagnostics.opaqueFallbackBlockReason(missing))
        assertFalse(MctsCardDiagnostics.braveOpaqueFallbackAllowed(missing))
    }

    @Test
    fun `uncertain discover or battlecry spell remains fail closed`() {
        val discover = card("NEW_DISCOVER", CardTypeEnum.SPELL, "UNKNOWN ENTITY [cardType=SPELL]").apply {
            entityId = "entity-discover"
            cost = 2
            isUncertain = true
            isDiscover = true
        }
        val battlecry = card("NEW_BATTLECRY", CardTypeEnum.SPELL, "UNKNOWN ENTITY [cardType=SPELL]").apply {
            entityId = "entity-battlecry"
            cost = 2
            isUncertain = true
            isBattlecry = true
        }

        assertFalse(MctsCardDiagnostics.braveOpaqueFallbackAllowed(discover))
        assertFalse(MctsCardDiagnostics.braveOpaqueFallbackAllowed(battlecry))
    }

    @Test
    fun `unknown spell without explicit no-target metadata remains unchanged`() {
        val spell = card("UNKNOWN_SPELL", CardTypeEnum.SPELL, "UNKNOWN ENTITY [cardType=SPELL]").apply {
            entityId = "entity-unknown-spell"
            cost = 1
            isUncertain = true
        }

        assertFalse(MctsCardDiagnostics.braveOpaqueFallbackAllowed(spell))
        assertEquals("unknown-effect-metadata", MctsCardDiagnostics.opaqueFallbackBlockReason(spell))
    }

    @Test
    fun `known no-choice spell may use brave opaque fallback`() {
        // AT_016 is present in hs_cards.db and has no directed target.  The
        // policy should allow a brave click only when this positive metadata
        // evidence exists; an unknown ID is covered by the corner-case test.
        val spell = card("AT_016", CardTypeEnum.SPELL, "UNKNOWN ENTITY [cardType=SPELL]").apply {
            entityId = "entity-spell"
            cost = 2
            isUncertain = true
        }

        assertTrue(MctsCardDiagnostics.braveOpaqueFallbackAllowed(spell))
        assertEquals(
            "OPAQUE_FALLBACK",
            MctsCardDiagnostics.actionRoute(
                MctsCardSnapshotStatus.UNKNOWN_ENTITY_NAME,
                requiresDescriptionAction = true,
                actionIsCommon = true,
                parsedActionCount = 0,
                opaqueFallbackAllowed = true,
                decisionModelInstalled = false,
            ),
        )
    }

    @Test
    fun `parser-sensitive common action cannot enter model without explicit opaque permission`() {
        val status = MctsCardSnapshotStatus.VALID

        assertEquals(
            "FAIL_CLOSED_PARSER_UNAVAILABLE",
            MctsCardDiagnostics.actionRoute(status, true, true, 1, false, true),
        )
        assertEquals(
            "OPAQUE_FALLBACK",
            MctsCardDiagnostics.actionRoute(status, true, true, 1, true, true),
        )
        assertEquals(
            "COMMON_GENERIC",
            MctsCardDiagnostics.actionRoute(status, false, true, 1, false, true),
        )
    }

    @Test
    fun `live scan preserves root route and cannot resurrect fail closed parser action`() {
        assertFalse(MctsCardDiagnostics.isLiveActionableRoute("FAIL_CLOSED_PARSER_UNAVAILABLE", true))
        assertFalse(MctsCardDiagnostics.isLiveActionableRoute("FAIL_CLOSED_INVALID_SNAPSHOT", true))
        assertFalse(MctsCardDiagnostics.isLiveActionableRoute("PARSED", false))
        assertTrue(MctsCardDiagnostics.isLiveActionableRoute("PARSED", true))
        assertTrue(MctsCardDiagnostics.isLiveActionableRoute("OPAQUE_FALLBACK", false))
    }

    @Test
    fun `planner skips invalid entity instead of treating it as generic minion`() {
        val war = testWar()
        war.me.resources = 2
        val invalid = card("TOY_370", CardTypeEnum.INVALID, "UNKNOWN ENTITY [cardType=INVALID]")
        invalid.cost = 2
        war.addCard(invalid, war.me.handArea)

        val model = object : MctsDecisionModel {}
        val node = MonteCarloTreeNode(
            war,
            InitAction,
            MCTSArg(
                endMillisTime = Long.MAX_VALUE,
                turnCount = 1,
                turnFactor = 0.5,
                countPerTurn = 1,
                scoreCalculator = { 0.0 },
                enableMultiThread = false,
                decisionModel = model,
                experimentalSearch = true,
            ),
        )

        assertTrue(node.actions.none { it.creator?.cardId == "TOY_370" })
        assertTrue(node.actions.any { it === club.xiaojiawei.hsscriptcardsdk.bean.TurnOverAction })
    }

    @Test
    fun `planner includes uncertain minion as brave generic action`() {
        val war = testWar()
        war.me.resources = 2
        val unknown = card("NEW_MINION", CardTypeEnum.MINION, "UNKNOWN ENTITY [cardType=MINION]").apply {
            entityId = "entity-minion"
            cost = 2
            atc = 2
            health = 3
            isUncertain = true
        }
        war.addCard(unknown, war.me.handArea)

        val node = MonteCarloTreeNode(
            war,
            InitAction,
            MCTSArg(
                endMillisTime = Long.MAX_VALUE,
                turnCount = 1,
                turnFactor = 0.5,
                countPerTurn = 1,
                scoreCalculator = { 0.0 },
                enableMultiThread = false,
                decisionModel = object : MctsDecisionModel {},
                experimentalSearch = true,
            ),
        )

        assertTrue(node.actions.any { it.creator?.cardId == "NEW_MINION" })
    }

    @Test
    fun `planner excludes unknown minion when unaffordable or board is full`() {
        val unaffordableWar = testWar().apply { me.resources = 1 }
        val expensive = card("EXPENSIVE_UNKNOWN_MINION", CardTypeEnum.MINION, "UNKNOWN ENTITY [cardType=MINION]").apply {
            entityId = "entity-expensive"
            cost = 2
            atc = 2
            health = 3
            isUncertain = true
        }
        unaffordableWar.addCard(expensive, unaffordableWar.me.handArea)
        assertTrue(rootNode(unaffordableWar).actions.none { it.creator?.cardId == expensive.cardId })

        val fullWar = testWar().apply { me.resources = 2 }
        repeat(fullWar.me.playArea.maxSize) { index ->
            fullWar.addCard(card("BOARD_$index", CardTypeEnum.MINION, "board-$index").apply {
                entityId = "board-entity-$index"
                atc = 1
                health = 1
            }, fullWar.me.playArea)
        }
        val blocked = card("FULL_BOARD_UNKNOWN_MINION", CardTypeEnum.MINION, "UNKNOWN ENTITY [cardType=MINION]").apply {
            entityId = "entity-full-board"
            cost = 1
            atc = 2
            health = 2
            isUncertain = true
        }
        fullWar.addCard(blocked, fullWar.me.handArea)
        assertTrue(rootNode(fullWar).actions.none { it.creator?.cardId == blocked.cardId })
    }

    private fun rootNode(war: War): MonteCarloTreeNode = MonteCarloTreeNode(
        war,
        InitAction,
        MCTSArg(
            endMillisTime = Long.MAX_VALUE,
            turnCount = 1,
            turnFactor = 0.5,
            countPerTurn = 1,
            scoreCalculator = { 0.0 },
            enableMultiThread = false,
            decisionModel = object : MctsDecisionModel {},
            experimentalSearch = true,
        ),
    )

    private fun card(id: String, type: CardTypeEnum, name: String): Card =
        Card(object : CardAction(createDefaultAction = false, common = true) {
            override fun getCardId(): Array<String> = arrayOf(id)
            override fun execPower(): Boolean = true
            override fun execPower(card: Card): Boolean = true
            override fun execPower(index: Int): Boolean = true
            override fun execAttack(card: Card): Boolean = true
            override fun execAttackHero(): Boolean = true
            override fun execPointTo(card: Card, click: Boolean): Boolean = true
            override fun execPointTo(index: Int, click: Boolean): Boolean = true
            override fun createNewInstance(): CardAction = this
            override fun execLClick(): Boolean = true
            override fun execLaunch(): Boolean = true
            override fun execTrade(): Boolean = true
            override fun execChooseOne(index: Int): Boolean = true
            override fun execForge(): Boolean = true
        }).apply {
            cardId = id
            cardType = type
            entityName = name
        }

    private fun testWar(): War {
        val war = War()
        val me = Player(playerId = "me", gameId = "game", war = war)
        val rival = Player(playerId = "rival", gameId = "rival", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        war.isMyTurn = true
        return war
    }
}
