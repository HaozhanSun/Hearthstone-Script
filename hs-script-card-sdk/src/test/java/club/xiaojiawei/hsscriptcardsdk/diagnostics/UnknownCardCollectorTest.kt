package club.xiaojiawei.hsscriptcardsdk.diagnostics

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnknownCardCollectorTest {

    private val file = Files.createTempFile("unknown-card-collector-", ".jsonl")

    @AfterTest
    fun restoreCollectorPath() {
        UnknownCardCollector.resetConfiguration()
        Files.deleteIfExists(file)
    }

    @Test
    fun recordsHandBoardAndDiscoveryWithActionableFields() {
        UnknownCardCollector.configureForTests(file)

        UnknownCardCollector.record(
            cardId = "CAP_999",
            cardName = "测试卡",
            reason = "description-parser-no-interceptor",
            action = "FAIL_CLOSED",
            sourceZone = UnknownCardSourceZone.HAND,
            phase = "hand-action-resolution",
            route = "FAIL_CLOSED_PARSER_UNAVAILABLE",
            safeAction = "SKIP_UNRECOGNIZED",
        )
        UnknownCardCollector.record(
            cardId = "BOARD_99",
            cardName = "场面未知随从",
            reason = "opaque-board-snapshot",
            action = "SKIP_UNRECOGNIZED",
            sourceZone = UnknownCardSourceZone.BOARD,
            phase = "board-action-resolution",
            route = "FAIL_CLOSED_PARSER_UNAVAILABLE",
            safeAction = "SKIP_UNRECOGNIZED",
        )
        UnknownCardCollector.record(
            cardId = "HERO_99",
            cardName = "对手英雄",
            reason = "plugin-action-missing",
            action = "DEFAULT",
            sourceZone = UnknownCardSourceZone.ENTITY_DISCOVERY,
            phase = "entity-discovery",
        )

        val lines = Files.readAllLines(file)
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("\"cardId\":\"CAP_999\""))
        assertTrue(lines[0].contains("\"sourceZone\":\"HAND\""))
        assertTrue(lines[0].contains("\"phase\":\"hand-action-resolution\""))
        assertTrue(lines[0].contains("\"route\":\"FAIL_CLOSED_PARSER_UNAVAILABLE\""))
        assertTrue(lines[0].contains("\"safeAction\":\"SKIP_UNRECOGNIZED\""))
        assertTrue(lines[1].contains("\"cardId\":\"BOARD_99\""))
        assertTrue(lines[1].contains("\"sourceZone\":\"BOARD\""))
        assertTrue(lines[1].contains("\"route\":\"FAIL_CLOSED_PARSER_UNAVAILABLE\""))
        assertTrue(lines[2].contains("\"sourceZone\":\"ENTITY_DISCOVERY\""))
    }
}
