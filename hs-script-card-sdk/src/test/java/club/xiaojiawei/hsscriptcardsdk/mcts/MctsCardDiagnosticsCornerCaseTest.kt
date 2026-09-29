package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.diagnostics.UnknownCardCollector
import club.xiaojiawei.hsscriptcardsdk.diagnostics.UnknownCardSourceZone
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MctsCardDiagnosticsCornerCaseTest {

    @Test
    fun `hand-zone collector preserves route and safe action for later replay`() {
        val file = Files.createTempFile("unknown-card-hand-", ".jsonl")
        try {
            UnknownCardCollector.configureForTests(file)
            UnknownCardCollector.record(
                cardId = "CORNER_HAND",
                cardName = "未知手牌",
                reason = "parser-unavailable",
                action = "SKIP_UNRECOGNIZED",
                sourceZone = UnknownCardSourceZone.HAND,
                phase = "mcts-action-scan",
                route = "FAIL_CLOSED_PARSER_UNAVAILABLE",
                safeAction = "SKIP_UNRECOGNIZED",
            )

            val line = Files.readString(file)
            assertTrue(line.contains("\"cardId\":\"CORNER_HAND\""))
            assertTrue(line.contains("\"sourceZone\":\"HAND\""))
            assertTrue(line.contains("\"route\":\"FAIL_CLOSED_PARSER_UNAVAILABLE\""))
            assertTrue(line.contains("\"safeAction\":\"SKIP_UNRECOGNIZED\""))
        } finally {
            UnknownCardCollector.resetConfiguration()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `known attack health and damage values do not make a valid uncertain minion fatal`() {
        val card = card("KNOWN_STATS", CardTypeEnum.MINION).apply {
            entityName = "UNKNOWN ENTITY [cardType=MINION]"
            isUncertain = true
            atc = 5
            health = 7
            damage = 3
            cost = 4
        }

        assertEquals(MctsCardSnapshotStatus.UNKNOWN_ENTITY_NAME, MctsCardDiagnostics.snapshotStatus(card))
        assertFalse(MctsCardDiagnostics.isFatalSnapshot(MctsCardDiagnostics.snapshotStatus(card)))
        assertTrue(MctsCardDiagnostics.braveOpaqueFallbackAllowed(card))
        assertEquals(5, card.atc)
        assertEquals(7, card.health)
        assertEquals(3, card.damage)
    }

    @Test
    fun `targeted unknown spell is not treated as a no-target opaque action`() {
        val card = card("CORNER_TARGETED_SPELL", CardTypeEnum.SPELL).apply {
            entityName = "对一个敌方随从造成伤害"
            isUncertain = true
        }

        assertFalse(MctsCardDiagnostics.braveOpaqueFallbackAllowed(card))
        assertFalse(MctsCardDiagnostics.isLiveActionableRoute("FAIL_CLOSED_PARSER_UNAVAILABLE", true))
    }

    @Test
    fun `metadata-missing unknown spell uses auditable brave fallback route`() {
        val card = card("CORNER_METADATA_MISSING_SPELL", CardTypeEnum.SPELL).apply {
            isUncertain = true
        }

        assertTrue(MctsCardDiagnostics.braveOpaqueFallbackAllowed(card))
        assertEquals(null, MctsCardDiagnostics.opaqueFallbackBlockReason(card))
        val route = MctsCardDiagnostics.actionRoute(
            snapshotStatus = MctsCardSnapshotStatus.UNKNOWN_ENTITY_NAME,
            requiresDescriptionAction = true,
            actionIsCommon = true,
            parsedActionCount = 0,
            opaqueFallbackAllowed = true,
            decisionModelInstalled = true,
            opaqueFallbackBlockReason = null,
        )
        assertEquals("OPAQUE_FALLBACK", route)
        assertEquals("EXECUTE_GENERIC_WITH_REPLAN", MctsCardDiagnostics.safeAction(route))
        assertTrue(MctsCardDiagnostics.isLiveActionableRoute(route, false))
    }

    @Test
    fun `known directed spell text remains fail closed even when entity name is missing`() {
        val card = card("CS2_024", CardTypeEnum.SPELL).apply {
            isUncertain = true
        }

        assertFalse(MctsCardDiagnostics.braveOpaqueFallbackAllowed(card))
        assertEquals("unsafe-target-or-choice", MctsCardDiagnostics.opaqueFallbackBlockReason(card))
    }

    @Test
    fun `discover and choice flags remain fail closed even with a generic type`() {
        val discover = card("CORNER_DISCOVER", CardTypeEnum.SPELL).apply {
            isUncertain = true
            isDiscover = true
        }
        val choiceMinion = card("CORNER_CHOICE_MINION", CardTypeEnum.MINION).apply {
            isUncertain = true
            isChooseOne = true
        }

        assertFalse(MctsCardDiagnostics.braveOpaqueFallbackAllowed(discover))
        assertFalse(MctsCardDiagnostics.braveOpaqueFallbackAllowed(choiceMinion))
    }

    @Test
    fun `missing and invalid snapshots never become actionable routes`() {
        val missingId = card("", CardTypeEnum.MINION).apply { entityId = "missing-id" }
        val unknownType = card("CORNER_UNKNOWN_TYPE", CardTypeEnum.UNKNOWN)
        val invalidType = card("CORNER_INVALID_TYPE", CardTypeEnum.INVALID)

        listOf(missingId, unknownType, invalidType).forEach { card ->
            val status = MctsCardDiagnostics.snapshotStatus(card)
            assertTrue(MctsCardDiagnostics.isFatalSnapshot(status), "expected fatal status for ${card.cardId}")
            assertFalse(
                MctsCardDiagnostics.isLiveActionableRoute(
                    "FAIL_CLOSED_INVALID_SNAPSHOT",
                    hasLegalParsedAction = true,
                ),
            )
        }
    }

    private fun card(id: String, type: CardTypeEnum): Card = Card(TestCardAction()).apply {
        cardId = id
        entityId = if (id.isBlank()) "entity-$id" else "entity-$id"
        cardType = type
        cost = 1
        health = 3
        atc = 2
    }
}
