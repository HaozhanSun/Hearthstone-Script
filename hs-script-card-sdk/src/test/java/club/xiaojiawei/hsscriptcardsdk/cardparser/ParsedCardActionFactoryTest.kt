package club.xiaojiawei.hsscriptcardsdk.cardparser

import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.DBCard
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TEST_CARD_ACTION
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParsedCardActionFactoryTest {

    @Test
    fun testParseAsPlayActionInterceptor() {
        val fireball = ParsedCardActionFactory.getOrCreate("CORE_CS2_029")
        val consecration = ParsedCardActionFactory.getOrCreate("CORE_CS2_093")
        val story = ParsedCardActionFactory.getOrCreate("TLC_444")

        val fireballSupplier = requireNotNull(fireball) { "火球术应该能生成动态 CardAction" }
        val consecrationSupplier = requireNotNull(consecration) { "奉献应该能生成动态 CardAction" }
        assertNull(story, "嘉沃顿的故事属于随机额外效果，不应被当前解析器误识别")

        assertTrue(fireballSupplier().javaClass.name.contains("GeneratedCardAction_CORE_CS2_029"))
        assertTrue(consecrationSupplier().javaClass.name.contains("GeneratedCardAction_CORE_CS2_093"))
    }

    @Test
    fun testFactoryCache() {
        ParsedCardActionFactory.clear()

        val first = ParsedCardActionFactory.getOrCreate("CORE_CS2_029")
        val second = ParsedCardActionFactory.getOrCreate("CORE_CS2_029")

        val firstSupplier = requireNotNull(first)
        val secondSupplier = requireNotNull(second)
        assertSame(firstSupplier, secondSupplier)
        assertEquals("CORE_CS2_029", firstSupplier().getCardId().single())
    }

    @Test
    fun testHookNHeaveHasBuiltInActionWhenCardDbIsMissing() {
        ParsedCardActionFactory.clear()

        val action = requireNotNull(
            ParsedCardActionFactory.getOrCreate("CAP_105", "钩手拖拽")
        )()
        val card = Card(action).apply {
            entityId = "hook_n_heave_test"
            cardId = "CAP_105"
            cardType = CardTypeEnum.SPELL
            cost = 2
        }
        action.belongCard = card
        val war = createWar()
        war.me.resources = 2
        war.addCard(card, war.me.handArea)

        val playActions = action.generatePlayActions(war, war.me)
        assertEquals(1, playActions.size)
        playActions.single().simulate.accept(war)
        assertTrue(war.me.handArea.cards.none { it.cardId == "CAP_105" })
        assertEquals(2, war.me.usedResources)
    }

    @Test
    fun testQuestRewardHasNamedOpaqueMinionPlayFallback() {
        ParsedCardActionFactory.clear()

        val action = requireNotNull(
            ParsedCardActionFactory.getOrCreate("SW_028t5", "船长洛卡拉")
        )()
        val card = Card(action).apply {
            entityId = "quest_reward_test"
            cardId = "SW_028t5"
            cardType = CardTypeEnum.MINION
            cost = 5
        }
        action.belongCard = card
        val war = createWar()
        war.me.resources = 5
        war.addCard(card, war.me.handArea)

        val playActions = action.generatePlayActions(war, war.me)
        assertEquals(1, playActions.size)
        assertEquals("船长洛卡拉", action.name())
        playActions.single().simulate.accept(war)
        assertTrue(war.me.handArea.cards.none { it.cardId == "SW_028t5" })
        assertEquals(5, war.me.usedResources)
    }

    @Test
    fun testApplauseThunderHasVerifiedNoTargetSpellFallback() {
        ParsedCardActionFactory.clear()

        val action = requireNotNull(
            ParsedCardActionFactory.getOrCreate("ETC_372", "掌声雷动")
        )()
        val card = Card(action).apply {
            entityId = "applause_thunder_test"
            cardId = "ETC_372"
            cardType = CardTypeEnum.SPELL
            cost = 2
        }
        action.belongCard = card
        val war = createWar()
        war.me.resources = 2
        war.addCard(card, war.me.handArea)

        val playActions = action.generatePlayActions(war, war.me)
        assertEquals(1, playActions.size)
        assertEquals("掌声雷动", action.name())
        playActions.single().simulate.accept(war)
        assertTrue(war.me.handArea.cards.none { it.cardId == "ETC_372" })
        assertEquals(2, war.me.usedResources)
    }

    @Test
    fun testDeepSeaFusionRequiresKnownFriendlyMinionTypeAndGeneratesTargetedActions() {
        ParsedCardActionFactory.clear()

        val action = requireNotNull(
            ParsedCardActionFactory.getOrCreate("TSC_069", "深海融合怪")
        )()
        val card = Card(action).apply {
            entityId = "deep-sea-fusion-test"
            cardId = "TSC_069"
            cardType = CardTypeEnum.MINION
            cost = 2
        }
        action.belongCard = card
        val war = createWar()
        war.me.resources = 2
        war.addCard(card, war.me.handArea)

        assertTrue(
            action.generatePlayActions(war, war.me).isEmpty(),
            "没有已知种族的友方随从时不能生成无目标动作",
        )

        val elemental = createPlayCard(war.me, war, "friendly-elemental", CardTypeEnum.MINION).apply {
            cardRace = CardRaceEnum.ELEMENTAL
            health = 3
        }
        val playActions = action.generatePlayActions(war, war.me)
        assertEquals(1, playActions.size)

        playActions.single().simulate.accept(war)
        assertTrue(war.me.handArea.cards.none { it.cardId == "TSC_069" })
        assertTrue(war.me.playArea.cards.any { it.cardId == "TSC_069" })
        assertTrue(war.me.playArea.cards.any { it.entityId == elemental.entityId })
        assertEquals(2, war.me.usedResources)
    }

    @Test
    fun testDeepSeaFusionKeepsFriendlyMinionTargetAndInitialFireUsesKillableEnemy() {
        ParsedCardActionFactory.clear()

        val deepSeaAction = requireNotNull(
            ParsedCardActionFactory.getOrCreate("TSC_069", "深海融合怪")
        )()
        val deepSea = Card(deepSeaAction).apply {
            entityId = "deep-sea-target-test"
            cardId = "TSC_069"
            cardType = CardTypeEnum.MINION
            cost = 2
        }
        deepSeaAction.belongCard = deepSea

        val war = createWar()
        war.me.resources = 2
        war.addCard(deepSea, war.me.handArea)
        val friendlyElemental = createPlayCard(war.me, war, "friendly-elemental-target", CardTypeEnum.MINION).apply {
            cardRace = CardRaceEnum.ELEMENTAL
            health = 3
        }
        val deepSeaPlayActions = deepSeaAction.generatePlayActions(war, war.me)
        assertEquals(1, deepSeaPlayActions.size)
        deepSeaPlayActions.single().simulate.accept(war)
        assertTrue(war.me.playArea.cards.any { it.entityId == friendlyElemental.entityId })

        val initialFireAction = requireNotNull(
            ParsedCardActionFactory.getOrCreate("CORE_SW_108", "初始之火")
        )()
        val initialFire = Card(initialFireAction).apply {
            entityId = "initial-fire-target-test"
            cardId = "CORE_SW_108"
            cardType = CardTypeEnum.SPELL
            cost = 1
        }
        initialFireAction.belongCard = initialFire
        war.me.resources = 1
        war.me.usedResources = 0
        war.addCard(initialFire, war.me.handArea)

        val highThreat = createPlayCard(war.rival, war, "enemy-high-threat", CardTypeEnum.MINION).apply {
            atc = 8
            health = 4
        }
        val killable = createPlayCard(war.rival, war, "enemy-killable", CardTypeEnum.MINION).apply {
            atc = 3
            health = 2
        }
        val firePlayActions = initialFireAction.generatePlayActions(war, war.me)
        assertEquals(1, firePlayActions.size, "初始之火必须只暴露一个确定的敌方目标")
        firePlayActions.single().simulate.accept(war)
        assertEquals(2, killable.damage, "可击杀敌方随从应优先成为初始之火目标")
        assertEquals(0, highThreat.damage, "不应把初始之火浪费在不可击杀的高威胁随从上")
        assertEquals(0, friendlyElemental.damage, "初始之火及其衍生牌不得选择友方随从")
    }

    @Test
    fun testPirateWarriorAuditCardsHaveNamedFallbacks() {
        ParsedCardActionFactory.clear()

        listOf(
            "CORE_NX2_028" to "勾拳-3000型",
            "AV_661" to "征战平原",
            "WW_424" to "溢流熔岩",
            "TTN_475" to "破链角斗士",
        ).forEach { (cardId, expectedName) ->
            val action = requireNotNull(
                ParsedCardActionFactory.getOrCreate(cardId, expectedName)
            )()
            val card = Card(action).apply {
                entityId = "$cardId-test"
                this.cardId = cardId
                cardType = if (cardId == "AV_661") CardTypeEnum.SPELL else CardTypeEnum.MINION
                cost = if (cardId == "WW_424") 4 else 3
            }
            action.belongCard = card
            val war = createWar()
            war.me.resources = 3
            war.addCard(card, war.me.handArea)

            assertEquals(expectedName, action.name())
            assertEquals(1, action.generatePlayActions(war, war.me).size)
        }
    }

    @Test
    fun testFreezeInterceptorShouldSetFrozen() {
        val action = generateAction(
            DBCard(
                cardId = "TEST_FREEZE_SPELL",
                name = "测试冻结法术",
                text = "冻结所有敌方随从。",
                type = CardTypeEnum.SPELL.name,
            ),
            "club.xiaojiawei.hsscriptcardsdk.generated.TestFreezeAction",
            CardTypeEnum.SPELL,
        )
        val war = createWar()
        val sourceCard = createSourceCard(war, action, "source_freeze", CardTypeEnum.SPELL)
        createPlayCard(war.rival, war, "target_freeze_1", CardTypeEnum.MINION)
        createPlayCard(war.rival, war, "target_freeze_2", CardTypeEnum.MINION)

        action.belongCard = sourceCard
        action.generatePlayActions(war, war.me).single().simulate.accept(war)

        assertTrue(war.rival.playArea.cards.all { it.isFrozen })
    }

    @Test
    fun testDamageInterceptorShouldUsePositiveAmount() {
        val action = generateAction(
            DBCard(
                cardId = "TEST_DAMAGE_SPELL",
                name = "测试伤害法术",
                text = "对一个随从造成\$3点伤害。",
                type = CardTypeEnum.SPELL.name,
            ),
            "club.xiaojiawei.hsscriptcardsdk.generated.TestDamageAction",
            CardTypeEnum.SPELL,
        )
        val war = createWar()
        val sourceCard = createSourceCard(war, action, "source_damage", CardTypeEnum.SPELL)
        val targetCard = createPlayCard(war.rival, war, "target_damage", CardTypeEnum.MINION).apply {
            health = 5
        }

        action.belongCard = sourceCard
        val playAction = action.generatePlayActions(war, war.me).single()
        playAction.simulate.accept(war)

        assertEquals(3, targetCard.damage)
    }

    @Test
    fun testBuffInterceptorShouldApplyAttackAndHealth() {
        val attackAction = generateAction(
            DBCard(
                cardId = "TEST_ATK_BUFF",
                name = "测试攻击增益",
                text = "使一个友方随从获得+2攻击力。",
                type = CardTypeEnum.SPELL.name,
            ),
            "club.xiaojiawei.hsscriptcardsdk.generated.TestAttackBuffAction",
            CardTypeEnum.SPELL,
        )
        val healthAction = generateAction(
            DBCard(
                cardId = "TEST_HEALTH_BUFF",
                name = "测试生命增益",
                text = "使一个友方随从获得\$3生命值。",
                type = CardTypeEnum.SPELL.name,
            ),
            "club.xiaojiawei.hsscriptcardsdk.generated.TestHealthBuffAction",
            CardTypeEnum.SPELL,
        )
        val war = createWar()
        val attackSourceCard = createSourceCard(war, attackAction, "source_attack", CardTypeEnum.SPELL)
        val healthSourceCard = createSourceCard(war, healthAction, "source_health", CardTypeEnum.SPELL)
        val targetCard = createPlayCard(war.me, war, "target_buff", CardTypeEnum.MINION).apply {
            atc = 1
            health = 2
        }

        attackAction.belongCard = attackSourceCard
        attackAction.generatePlayActions(war, war.me).single().simulate.accept(war)
        assertEquals(3, targetCard.atc)

        healthAction.belongCard = healthSourceCard
        healthAction.generatePlayActions(war, war.me).single().simulate.accept(war)
        assertEquals(5, targetCard.health)
    }

    @Test
    fun testAttributeBuffShouldApplyAttackAndHealthTogether() {
        val interceptor = CardDescriptionParser.parseAsPlayActionInterceptor(
            DBCard(
                cardId = "TEST_ATTRIBUTE_BUFF",
                name = "测试属性增益",
                text = "使一个随从获得+2/+3。",
                type = CardTypeEnum.SPELL.name,
            )
        )
        assertIs<SpellPointMyAttributeBuffPlayActionInterceptor>(interceptor)

        val action = generateAction(
            DBCard(
                cardId = "TEST_ATTRIBUTE_BUFF",
                name = "测试属性增益",
                text = "使一个随从获得+2/+3。",
                type = CardTypeEnum.SPELL.name,
            ),
            "club.xiaojiawei.hsscriptcardsdk.generated.TestAttributeBuffAction",
            CardTypeEnum.SPELL,
        )
        val war = createWar()
        val sourceCard = createSourceCard(war, action, "source_attribute", CardTypeEnum.SPELL)
        val targetCard = createPlayCard(war.me, war, "target_attribute", CardTypeEnum.MINION).apply {
            atc = 1
            health = 2
        }

        action.belongCard = sourceCard
        action.generatePlayActions(war, war.me).single().simulate.accept(war)

        assertEquals(3, targetCard.atc)
        assertEquals(5, targetCard.health)
    }

    @Test
    fun testParserShouldRejectUnsupportedDescriptions() {
        val unsupportedCards = listOf(
            DBCard(
                cardId = "TIME_006",
                name = "镜像维度",
                text = "召唤一个0/4并具有嘲讽的随从。如果你的手牌中有龙牌，再召唤一个。",
                type = CardTypeEnum.SPELL.name,
            ),
            DBCard(
                cardId = "CS2_022",
                name = "变形术",
                text = "使一个随从变形成为1/1的绵羊。",
                type = CardTypeEnum.SPELL.name,
            ),
            DBCard(
                cardId = "CS2_017o",
                name = "额外攻击力",
                text = "在本回合中，你的英雄拥有额外攻击力。",
                type = CardTypeEnum.ENCHANTMENT.name,
            ),
            DBCard(
                cardId = "TLC_462",
                name = "出土神器",
                text = "随机召唤一个法力值消耗为（2）的随从。如果你在本回合中发现过，改为随机召唤一个法力值消耗为（4）的随从。",
                type = CardTypeEnum.SPELL.name,
            ),
        )

        unsupportedCards.forEach {
            assertNull(CardDescriptionParser.parseAsPlayActionInterceptor(it), "${it.cardId} 不应被错误识别")
        }
    }

    @Test
    fun testParserShouldReturnExpectedInterceptorTypes() {
        assertIs<SpellPointRivalDamagePlayActionInterceptor>(
            CardDescriptionParser.parseAsPlayActionInterceptor(
                DBCard(
                    cardId = "EDR_941",
                    name = "星涌术",
                    text = "对一个随从造成\$1点伤害。（每有一个在本局对战中死亡的友方随从都会提升。）",
                    type = CardTypeEnum.SPELL.name,
                )
            )
        )
        assertIs<SpellNoPointPlayActionInterceptor>(
            CardDescriptionParser.parseAsPlayActionInterceptor(
                DBCard(
                    cardId = "EX1_277",
                    name = "奥术飞弹",
                    text = "造成\$3点伤害，随机分配到所有敌人身上。",
                    type = CardTypeEnum.SPELL.name,
                )
            )
        )
        assertIs<SpellPointMyHealPlayActionInterceptor>(
            CardDescriptionParser.parseAsPlayActionInterceptor(
                DBCard(
                    cardId = "AT_055",
                    name = "快速治疗",
                    text = "恢复#5点生命值。",
                    type = CardTypeEnum.SPELL.name,
                )
            )
        )
        assertIs<MinionPointMyHealthBuffPlayActionInterceptor>(
            CardDescriptionParser.parseAsPlayActionInterceptor(
                DBCard(
                    cardId = "AT_040",
                    name = "荒野行者",
                    text = "<b>战吼：</b>使一个友方野兽获得+3生命值。",
                    type = CardTypeEnum.MINION.name,
                )
            )
        )
        assertIs<SpellPointMyAttributeBuffPlayActionInterceptor>(
            CardDescriptionParser.parseAsPlayActionInterceptor(
                DBCard(
                    cardId = "TEST_ATTRIBUTE_BUFF_2",
                    name = "测试属性增益",
                    text = "使一个随从获得+2/+3。",
                    type = CardTypeEnum.SPELL.name,
                )
            )
        )
        assertIs<SpellAllRivalFreezePlayActionInterceptor>(
            CardDescriptionParser.parseAsPlayActionInterceptor(
                DBCard(
                    cardId = "CS2_026",
                    name = "冰霜新星",
                    text = "<b>冻结</b>所有敌方随从。",
                    type = CardTypeEnum.SPELL.name,
                )
            )
        )
    }

    @Test
    fun testDescribePlayActionInterceptorShouldContainTargetDomain() {
        val rivalHeroDamage = requireNotNull(
            CardDescriptionParser.parseAsPlayActionInterceptor(
                DBCard(
                    cardId = "TEST_RIVAL_HERO_DAMAGE",
                    name = "测试敌方英雄伤害",
                    text = "对敌方英雄造成\$3点伤害。",
                    type = CardTypeEnum.SPELL.name,
                )
            )
        )
        val myMinionBuff = requireNotNull(
            CardDescriptionParser.parseAsPlayActionInterceptor(
                DBCard(
                    cardId = "TEST_MY_MINION_BUFF",
                    name = "测试友方随从增益",
                    text = "使一个友方随从获得+2攻击力。",
                    type = CardTypeEnum.SPELL.name,
                )
            )
        )

        assertEquals("法术单体敌方-伤害(英雄)", describePlayActionInterceptor(rivalHeroDamage))
        assertEquals("法术单体友方-攻击增益(随从)", describePlayActionInterceptor(myMinionBuff))
    }

    @Test
    fun testParserShouldHandleTypicalDescriptions() {
        val damage = CardDescriptionParser.parseAsPlayActionInterceptor(
            DBCard(
                cardId = "EDR_941",
                name = "星涌术",
                text = "对一个随从造成\$1点伤害。（每有一个在本局对战中死亡的友方随从都会提升。）",
                type = CardTypeEnum.SPELL.name,
            )
        )
        val randomDistributedDamage = CardDescriptionParser.parseAsPlayActionInterceptor(
            DBCard(
                cardId = "EX1_277",
                name = "奥术飞弹",
                text = "造成\$3点伤害，随机分配到所有敌人身上。",
                type = CardTypeEnum.SPELL.name,
            )
        )
        val randomDistributedMinionDamage = CardDescriptionParser.parseAsPlayActionInterceptor(
            DBCard(
                cardId = "VAC_520",
                name = "银樽海韵",
                text = "造成\$2点伤害，随机分配到所有敌方随从身上。",
                type = CardTypeEnum.SPELL.name,
            )
        )
        val heal = CardDescriptionParser.parseAsPlayActionInterceptor(
            DBCard(
                cardId = "AT_055",
                name = "快速治疗",
                text = "恢复#5点生命值。",
                type = CardTypeEnum.SPELL.name,
            )
        )
        val healthBuff = CardDescriptionParser.parseAsPlayActionInterceptor(
            DBCard(
                cardId = "AT_040",
                name = "荒野行者",
                text = "<b>战吼：</b>使一个友方野兽获得+3生命值。",
                type = CardTypeEnum.MINION.name,
            )
        )

        assertIs<SpellPointRivalDamagePlayActionInterceptor>(damage)
        assertIs<SpellNoPointPlayActionInterceptor>(randomDistributedDamage)
        assertIs<SpellNoPointPlayActionInterceptor>(randomDistributedMinionDamage)
        assertIs<SpellPointMyHealPlayActionInterceptor>(heal)
        assertIs<MinionPointMyHealthBuffPlayActionInterceptor>(healthBuff)
    }

    private fun generateAction(dbCard: DBCard, className: String, cardType: CardTypeEnum): CardAction {
        val interceptor = CardDescriptionParser.parseAsPlayActionInterceptor(dbCard)
        assertNotNull(interceptor)
        return CardActionGenerator.generateCardActionClass(
            className = "$className${System.nanoTime()}",
            cardIds = arrayOf(dbCard.cardId),
            playActionInterceptor = interceptor,
        ).getDeclaredConstructor().newInstance()
    }

    private fun createWar(): War {
        val war = War(false)
        val me = Player(playerId = "1", war = war)
        val rival = Player(playerId = "2", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        return war
    }

    private fun createSourceCard(war: War, action: CardAction, entityId: String, cardType: CardTypeEnum): Card {
        val card = Card(action).apply {
            this.entityId = entityId
            this.cardId = entityId
            this.cardType = cardType
            this.cost = 0
        }
        action.belongCard = card
        war.addCard(card, war.me.handArea)
        return card
    }

    private fun createPlayCard(player: Player, war: War, entityId: String, cardType: CardTypeEnum): Card {
        val action = TEST_CARD_ACTION.createNewInstance()
        val card = Card(action).apply {
            this.entityId = entityId
            this.cardId = entityId
            this.cardType = cardType
        }
        action.belongCard = card
        war.addCard(card, player.playArea)
        return card
    }
}
