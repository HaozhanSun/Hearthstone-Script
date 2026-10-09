package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.CardTimingPolicy
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsActionOrderPhase
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsCardDiagnostics
import club.xiaojiawei.hsscriptstrategysdk.deck.DiscoverSelectionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElementalMageMctsStrategyTest {
    @Test
    fun `elemental mage is visible only as a wild strategy`() {
        val strategy = HsElementalMageMctsDeckStrategy()

        assertEquals("元素法 V1.5", strategy.name().substringBefore(" ·"))
        assertTrue(strategy.name().startsWith("元素法 V1.5 · build "))
        assertEquals(listOf(RunModeEnum.WILD), strategy.runModes.toList())
        assertFalse(strategy.runModes.contains(RunModeEnum.STANDARD))
        assertTrue(strategy.id().contains("elemental-mage-mcts-v1-2"))
        val entries = javaClass.classLoader
            .getResourceAsStream("META-INF/services/club.xiaojiawei.hsscriptstrategysdk.DeckStrategy")
            ?.bufferedReader()?.readLines().orEmpty()
        assertTrue(entries.contains("club.xiaojiawei.hsscriptbasestrategy.strategy.HsElementalMageMctsDeckStrategy"))
    }

    @Test
    fun `from turn three a playable elemental is mandatory`() {
        val war = testWar(turn = 3, mana = 3)
        val elemental = testCard("ELEMENTAL_TEST", "火光元素", 2, CardRaceEnum.ELEMENTAL)
        val filler = testCard("FILLER_TEST", "普通随从", 1, CardRaceEnum.UNKNOWN)
        war.addCard(elemental, war.me.handArea)
        war.addCard(filler, war.me.handArea)

        val elementalAction = PlayAction({}, {}, elemental)
        val fillerAction = PlayAction({}, {}, filler)
        assertTrue(ElementalMageMctsModel.mustPlayElementalFirst(war))
        assertTrue(ElementalMageMctsModel.isMandatoryAction(elementalAction, war))
        assertFalse(ElementalMageMctsModel.isMandatoryAction(fillerAction, war))
    }

    @Test
    fun `sunfire is legal only at two or less mana and is late priority`() {
        val war = testWar(turn = 5, mana = 5)
        val sunfire = testCard("SUNFIRE_TEST", "阳炎耀斑", 5, CardRaceEnum.UNKNOWN, CardTypeEnum.SPELL)
        val elemental = testCard("ELEMENTAL_TEST", "火光元素", 2, CardRaceEnum.ELEMENTAL)
        war.addCard(sunfire, war.me.handArea)
        war.addCard(elemental, war.me.handArea)

        assertFalse(ElementalMageMctsModel.isActionLegal(PlayAction({}, {}, sunfire), war))
        sunfire.cost = 2
        assertTrue(ElementalMageMctsModel.isActionLegal(PlayAction({}, {}, sunfire), war))
        assertTrue(ElementalMageMctsModel.actionPrior(PlayAction({}, {}, sunfire), war) <
            ElementalMageMctsModel.actionPrior(PlayAction({}, {}, elemental), war))
    }

    @Test
    fun `TTN095 wins the historical turn-three priority race when legal`() {
        val war = testWar(turn = 3, mana = 2)
        val archiveAdministrator = testCard("TTN_095", "流水档案管理员", 2, CardRaceEnum.ELEMENTAL)
        val chainAlternative = testCard("TTN_479", "元素链备选", 2, CardRaceEnum.ELEMENTAL)
        war.addCard(archiveAdministrator, war.me.handArea)
        war.addCard(chainAlternative, war.me.handArea)

        val archiveAction = PlayAction({}, {}, archiveAdministrator)
        val alternativeAction = PlayAction({}, {}, chainAlternative)

        assertTrue(ElementalMageMctsModel.isArchiveAdministrator(archiveAdministrator))
        assertTrue(ElementalMageMctsModel.isActionLegal(archiveAction, war))
        assertTrue(ElementalMageMctsModel.isMandatoryAction(archiveAction, war))
        assertTrue(ElementalMageMctsModel.actionPrior(archiveAction, war) >
            ElementalMageMctsModel.actionPrior(alternativeAction, war))
    }

    @Test
    fun `TTN095 remains unavailable when actual mana is one`() {
        val war = testWar(turn = 3, mana = 1)
        val archiveAdministrator = testCard("TTN_095", "流水档案管理员", 2, CardRaceEnum.ELEMENTAL)
        war.addCard(archiveAdministrator, war.me.handArea)

        assertFalse(ElementalMageMctsModel.isPlayable(archiveAdministrator, war))
        assertFalse(ElementalMageMctsModel.elementalAvailable(war))
    }

    @Test
    fun `chain counter resets on a missed elemental turn`() {
        val first = ElementalMageMctsModel.updateChain(
            ElementalMageMctsModel.ChainSnapshot(), 3, playedElemental = true,
        )
        val second = ElementalMageMctsModel.updateChain(first, 4, playedElemental = true)
        val reset = ElementalMageMctsModel.updateChain(second, 5, playedElemental = false)

        assertEquals(1, first.consecutiveTurns)
        assertEquals(2, second.consecutiveTurns)
        assertEquals(0, reset.consecutiveTurns)
        assertFalse(reset.lastTurnPlayedElemental)
    }

    @Test
    fun `discover protects elemental chain only when no hand elemental can continue it`() {
        val offered = listOf(
            testCard("BIG_NON_ELEMENTAL", "高费非元素", 9, CardRaceEnum.UNKNOWN),
            testCard("DISCOVER_ELEMENTAL", "发现元素", 3, CardRaceEnum.ELEMENTAL),
        )
        val inHandPlayable = testCard("HAND_ELEMENTAL", "手牌元素", 3, CardRaceEnum.ELEMENTAL)
        val inHandTooExpensive = testCard("HAND_EXPENSIVE_ELEMENTAL", "高费手牌元素", 4, CardRaceEnum.ELEMENTAL)

        val maintained = ElementalMageMctsModel.discoverChainOverride(
            offered = offered,
            hand = listOf(inHandPlayable),
            nextTurnMana = 3,
            nextTurnNumber = 3,
        )
        val broken = ElementalMageMctsModel.discoverChainOverride(
            offered = offered,
            hand = listOf(inHandTooExpensive),
            nextTurnMana = 3,
            nextTurnNumber = 3,
        )

        assertEquals(null, maintained)
        assertEquals(listOf(1), broken?.candidateIndices)
        assertTrue(broken?.reason?.contains("elemental-chain-break") == true)
    }

    @Test
    fun `discover elemental continuation respects next turn threshold and chain start`() {
        val offered = listOf(
            testCard("ELEMENTAL_COST_4", "四费元素", 4, CardRaceEnum.ELEMENTAL),
            testCard("ELEMENTAL_COST_3", "三费元素", 3, CardRaceEnum.ELEMENTAL),
        )
        val belowThreshold = ElementalMageMctsModel.discoverChainOverride(
            offered, emptyList(), nextTurnMana = 3, nextTurnNumber = 2,
        )
        val exactManaThreshold = ElementalMageMctsModel.discoverChainOverride(
            offered, emptyList(), nextTurnMana = 3, nextTurnNumber = 3,
        )

        assertEquals(null, belowThreshold)
        assertEquals(listOf(1), exactManaThreshold?.candidateIndices)
    }

    @Test
    fun `chain Discover uses SDK next-turn crystal forecast and only offers playable elemental minions`() {
        val war = testWar(turn = 2, mana = 2).apply {
            me.usedResources = 2
            me.maxResources = 10
        }
        val handElementalTooExpensiveNextTurn = testCard(
            "HAND_COST_4", "手牌四费元素", 4, CardRaceEnum.ELEMENTAL,
        )
        val offered = listOf(
            testCard("OFFER_COST_3", "三费元素", 3, CardRaceEnum.ELEMENTAL),
            testCard("OFFER_COST_4", "四费元素", 4, CardRaceEnum.ELEMENTAL),
            testCard("OFFER_ELEMENTAL_SPELL", "元素法术", 1, CardRaceEnum.ELEMENTAL, CardTypeEnum.SPELL),
            testCard("OFFER_NON_ELEMENTAL", "海盗随从", 1, CardRaceEnum.UNKNOWN),
        )
        val nextTurnMana = DiscoverSelectionPolicy.nextTurnAvailableMana(war.me.resources, war.me.maxResources)
        val nextTurnNumber = war.me.turn + 1

        assertEquals(0, war.me.usableResource)
        assertEquals(3, nextTurnMana)
        val override = ElementalMageMctsModel.discoverChainOverride(
            offered = offered,
            hand = listOf(handElementalTooExpensiveNextTurn),
            nextTurnMana = nextTurnMana,
            nextTurnNumber = nextTurnNumber,
        )

        assertEquals(3, nextTurnNumber)
        assertEquals(listOf(0), override?.candidateIndices)
        assertTrue(override?.reason?.contains("next-turn-no-playable-hand-elemental") == true)
    }

    @Test
    fun `Discover does not override normal scoring when only non-elementals or elemental spells are offered`() {
        val offered = listOf(
            testCard("NON_ELEMENTAL", "海盗随从", 1, CardRaceEnum.UNKNOWN),
            testCard("ELEMENTAL_SPELL", "元素法术", 1, CardRaceEnum.ELEMENTAL, CardTypeEnum.SPELL),
        )

        val override = ElementalMageMctsModel.discoverChainOverride(
            offered = offered,
            hand = emptyList(),
            nextTurnMana = 3,
            nextTurnNumber = 3,
        )

        assertEquals(null, override)
        val fallback = DiscoverSelectionPolicy.select(
            cards = offered,
            handSize = 4,
            selectedDeckCardIds = emptySet(),
            deckSnapshotStatus = "selected-deck-unavailable",
            override = override,
        ) { candidates -> candidates.indexOfFirst { it.cardId == "ELEMENTAL_SPELL" } }
        assertEquals(1, fallback.index)
        assertTrue(fallback.reason.contains("existing-scorer"))
    }

    @Test
    fun `elemental chain override takes precedence over generic hand and deck preferences`() {
        val strategy = HsElementalMageMctsDeckStrategy()
        val offered = listOf(
            testCard("DECK_NON_ELEMENTAL", "牌组高费随从", 9, CardRaceEnum.UNKNOWN),
            testCard("DISCOVER_ELEMENTAL", "续链元素", 3, CardRaceEnum.ELEMENTAL),
        )
        val override = strategy.discoverCandidateOverride(
            cards = offered,
            hand = emptyList(),
            nextTurnMana = 3,
            nextTurnNumber = 3,
        )
        val decision = DiscoverSelectionPolicy.select(
            strategy = strategy,
            cards = offered,
            handSize = 2,
            selectedDeckCardIds = setOf("DECK_NON_ELEMENTAL"),
            deckSnapshotStatus = "ready",
            override = override,
        )

        assertEquals(1, decision.index)
        assertEquals(listOf(1), decision.candidateIndices)
        assertTrue(decision.reason.contains("elemental-chain-break"))
        assertTrue(decision.reason.contains("hand<=3-highest-cost"))
    }

    @Test
    fun `elemental strategy exposes a current face lethal before trade actions`() {
        val war = testWar(turn = 6, mana = 0)
        val hero = testCard("MAGE_HERO", "英雄", 0, CardRaceEnum.UNKNOWN, CardTypeEnum.HERO).apply {
            atc = 3
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("RIVAL_HERO", "敌方英雄", 0, CardRaceEnum.UNKNOWN, CardTypeEnum.HERO).apply {
            health = 3
        }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)

        val face = AttackAction({}, {}, hero, targetEntityId = rivalHero.entityId, targetIsHero = true)
        assertTrue(ElementalMageMctsModel.isLethalAction(face, war))
    }

    @Test
    fun `ready minion attack remains legal independently of spell phase ordering`() {
        val war = testWar(turn = 7, mana = 1)
        val attacker = testCard("TTN_475", "破链角斗士", 3, CardRaceEnum.UNKNOWN).apply {
            isExhausted = false
        }
        val target = testCard("TAUNT_TEST", "嘲讽目标", 0, CardRaceEnum.UNKNOWN).apply {
            health = 3
        }
        war.addCard(attacker, war.me.playArea)
        war.addCard(target, war.rival.playArea)

        val attack = AttackAction(
            {}, {}, attacker,
            targetEntityId = target.entityId,
            targetIsHero = false,
        )

        assertTrue(CardTimingPolicy.isActionLegal(attack, war))
        assertTrue(ElementalMageMctsModel.isActionLegal(attack, war))
        assertEquals(MctsActionOrderPhase.MINION_ATTACK, ElementalMageMctsModel.actionOrderPhase(attack, war))
        assertEquals(null, ElementalMageMctsModel.actionFilterReason(attack, war))
    }

    @Test
    fun `safe unknown minion gets a low priority opaque play while target choice stays blocked`() {
        val war = testWar(turn = 4, mana = 2)
        val safe = testCard("UNKNOWN_SAFE_MINION", "未知无战吼随从", 2, CardRaceEnum.UNKNOWN).apply {
            isUncertain = true
            isBattlecry = false
        }
        val unresolvedBattlecry = testCard("UNKNOWN_TARGET_BATTLECRY", "未知战吼随从", 2, CardRaceEnum.UNKNOWN).apply {
            isUncertain = true
            isBattlecry = true
        }
        war.addCard(safe, war.me.handArea)
        war.addCard(unresolvedBattlecry, war.me.handArea)

        assertTrue(MctsCardDiagnostics.safeOpaqueMinionFallbackAllowed(safe))
        assertTrue(ElementalMageMctsModel.canCreateOpaqueAction(safe, war))
        assertTrue(MctsCardDiagnostics.safeOpaqueMinionFallbackAllowed(unresolvedBattlecry))
        assertTrue(ElementalMageMctsModel.canCreateOpaqueAction(unresolvedBattlecry, war))
    }

    @Test
    fun `overflowing lava rejects plans that lose more than one expected copy`() {
        assertTrue(ElementalMageMctsModel.overflowingLavaCopyPlan(3, 3).allowed)
        assertEquals(1, ElementalMageMctsModel.overflowingLavaCopyPlan(3, 3).lostCopies)
        assertFalse(ElementalMageMctsModel.overflowingLavaCopyPlan(4, 3).allowed)
        assertEquals(2, ElementalMageMctsModel.overflowingLavaCopyPlan(4, 3).lostCopies)
    }

    private fun testWar(turn: Int, mana: Int): War {
        val war = War()
        val me = Player(playerId = "me", war = war)
        val rival = Player(playerId = "rival", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        war.isMyTurn = true
        me.turn = turn
        me.resources = mana
        return war
    }

    private fun testCard(
        cardId: String,
        name: String,
        cost: Int,
        race: CardRaceEnum,
        type: CardTypeEnum = CardTypeEnum.MINION,
    ): Card = Card(TestCardAction()).apply {
        entityId = "$cardId-entity"
        this.cardId = cardId
        entityName = name
        this.cost = cost
        cardRace = race
        cardType = type
        health = 3
        atc = 2
        action.belongCard = this
    }
}
