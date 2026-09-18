package club.xiaojiawei.hsscriptcardsdk.util

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.cardparser.CardDescriptionParser
import club.xiaojiawei.hsscriptcardsdk.cardparser.ParsedCardActionFactory
import club.xiaojiawei.hsscriptcardsdk.diagnostics.UnknownCardCollector
import club.xiaojiawei.hsscriptcardsdk.diagnostics.UnknownCardSourceZone
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Evidence test for the cards observed in the current Elemental Mage run. */
class ElementalMageCardIdentityTest {
    @Test
    fun `current elemental cards are database-known and factory-action covered`() {
        val expected = listOf(
            "TTN_095" to "流水档案管理员",
            "DEEP_034" to "页岩蛛",
            "DMF_100" to "甜点飓风",
            "CORE_UNG_809" to "火羽精灵",
            "GDB_302" to "吸积炽焰",
            "GDB_303" to "爆炎流星",
            "SW_439t" to "橡果",
            "ULD_239" to "火焰结界",
        ).map { (cardId, name) -> CardIdentityRef(cardId, name) }

        ParsedCardActionFactory.clear()
        val result = CardIdentityCoverage.inspect(
            expected,
            resolver = { cardId -> CardDBUtil.queryCardById(cardId).firstOrNull() },
            actionResolver = { cardId ->
                ParsedCardActionFactory.getOrCreate(cardId, CardIdentityCatalog.lookup(cardId)?.name)
            },
        )

        assertTrue(result.missing.isEmpty(), result.diagnosticMessage())
        assertTrue(result.actionUnavailable.isEmpty(), result.diagnosticMessage())
        assertEquals(expected.size, result.resolved.size)
        assertEquals(expected.size, result.actionResolved.size)
        expected.forEach { ref ->
            val databaseCard = CardDBUtil.queryCardById(ref.cardId).firstOrNull()
            assertEquals(ref.expectedName, databaseCard?.name, ref.cardId)
            assertEquals(CardIdentitySource.DATABASE, CardIdentityCatalog.resolve(ref.cardId, databaseCard)?.source)
            assertEquals(ref.expectedName, CardIdentityCatalog.lookup(ref.cardId)?.name, ref.cardId)
        }
    }

    @Test
    fun `each observed card uses parser fallback with legal hand action contract`() {
        ParsedCardActionFactory.clear()

        OBSERVED_CARDS.forEach { expected ->
            val databaseCard = requireNotNull(CardDBUtil.queryCardById(expected.cardId).firstOrNull())
            assertEquals(expected.name, databaseCard.name, expected.cardId)
            assertNull(
                CardDescriptionParser.parseAsPlayActionInterceptor(databaseCard),
                "${expected.cardId} must stay outside the generic parser",
            )

            val action = requireNotNull(
                ParsedCardActionFactory.getOrCreate(
                    expected.cardId,
                    expected.name,
                    UnknownCardSourceZone.HAND,
                )
            )()
            assertEquals(expected.cardId, action.getCardId().single())
            assertEquals(expected.name, action.name())

            val card = Card(action).apply {
                entityId = "${expected.cardId}-identity-test"
                cardId = expected.cardId
                cardType = expected.type
                cost = expected.cost
            }
            action.belongCard = card
            val war = createWar()
            war.me.resources = expected.cost
            war.addCard(card, war.me.handArea)

            val playActions = action.generatePlayActions(war, war.me)
            if (expected.cardId == "SW_439t") {
                assertTrue(playActions.isEmpty(), "橡果只能抽到时施放，不能伪造手动施放")
            } else {
                assertEquals(1, playActions.size, expected.cardId)
                playActions.single().simulate.accept(war)
                assertTrue(war.me.handArea.cards.none { it.entityId == card.entityId }, expected.cardId)
                assertTrue(war.me.playArea.cards.any { it.entityId == card.entityId }, expected.cardId)
                assertEquals(expected.cost, war.me.usedResources, expected.cardId)
            }
        }
    }

    @Test
    fun `known coverage is silent while truly unknown cards retain hand and board attribution`() {
        val file = Files.createTempFile("elemental-card-coverage-", ".jsonl")
        try {
            UnknownCardCollector.configureForTests(file)
            ParsedCardActionFactory.clear()

            OBSERVED_CARDS.forEach { expected ->
                assertNotNull(
                    ParsedCardActionFactory.getOrCreate(
                        expected.cardId,
                        expected.name,
                        UnknownCardSourceZone.HAND,
                    ),
                    expected.cardId,
                )
            }
            assertTrue(Files.readAllLines(file).isEmpty(), "known Elemental Mage cards must not emit unknown-card events")

            assertNull(
                ParsedCardActionFactory.getOrCreate(
                    "TEST_UNKNOWN_ELEMENTAL",
                    "测试未知卡",
                    UnknownCardSourceZone.HAND,
                )
            )
            assertNull(
                ParsedCardActionFactory.getOrCreate(
                    "TEST_UNKNOWN_ELEMENTAL",
                    "测试未知卡",
                    UnknownCardSourceZone.BOARD,
                )
            )
            val lines = Files.readAllLines(file)
            assertTrue(lines.any { it.contains("\"sourceZone\":\"HAND\"") })
            assertTrue(lines.any { it.contains("\"sourceZone\":\"BOARD\"") })
            assertEquals(UnknownCardSourceZone.HAND, UnknownCardCollector.sourceZone(createWar().me.handArea))
            assertEquals(UnknownCardSourceZone.BOARD, UnknownCardCollector.sourceZone(createWar().me.playArea))
        } finally {
            UnknownCardCollector.resetConfiguration()
            Files.deleteIfExists(file)
            ParsedCardActionFactory.clear()
        }
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

    private data class ObservedCard(
        val cardId: String,
        val name: String,
        val type: CardTypeEnum,
        val cost: Int,
    )

    private companion object {
        val OBSERVED_CARDS = listOf(
            ObservedCard("CORE_UNG_809", "火羽精灵", CardTypeEnum.MINION, 1),
            ObservedCard("DMF_100", "甜点飓风", CardTypeEnum.MINION, 2),
            ObservedCard("DEEP_034", "页岩蛛", CardTypeEnum.MINION, 2),
            ObservedCard("GDB_302", "吸积炽焰", CardTypeEnum.MINION, 3),
            ObservedCard("GDB_303", "爆炎流星", CardTypeEnum.MINION, 3),
            ObservedCard("SW_439t", "橡果", CardTypeEnum.SPELL, 1),
            ObservedCard("TTN_095", "流水档案管理员", CardTypeEnum.MINION, 2),
            ObservedCard("ULD_239", "火焰结界", CardTypeEnum.SPELL, 3),
        )
    }
}
