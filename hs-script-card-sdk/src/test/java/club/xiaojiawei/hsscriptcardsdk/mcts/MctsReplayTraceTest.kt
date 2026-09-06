package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MctsReplayTraceTest {
    @Test
    fun `records readable state reason and details`() {
        val root = Files.createTempDirectory("mcts-replay-trace-").toFile()
        try {
            val war = war("game-a", 101L)
            val file = MctsReplayTrace.record(
                war,
                "turn_end_candidate",
                "playable cards remain but search returned no non-end action",
                mapOf("mana" to 8, "legalActions" to listOf("打出(TEST)")),
                root,
            )
            assertTrue(file?.toFile()?.isFile == true)
            val text = file!!.toFile().readText()
            assertTrue(text.contains("turn_end_candidate"))
            assertTrue(text.contains("playable cards remain"))
            assertTrue(text.contains("\"myMana\":0"))
            assertTrue(text.contains("\"legalActions\":[\"打出(TEST)\"]"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `replay marks a fresh cycle after full live rescan`() {
        val root = Files.createTempDirectory("mcts-replay-cycle-").toFile()
        try {
            val war = war("cycle-game", 102L)
            val file = MctsReplayTrace.record(
                war,
                "turn_cycle_boundary",
                "full live rescan found newly actionable work",
                mapOf(
                    "completedCycle" to 1,
                    "nextCycle" to 2,
                    "fullRescan" to true,
                    "liveActionableCreatorIds" to listOf("YOD_032-entity"),
                ),
                root,
            )
            val text = file!!.toFile().readText()
            assertTrue(text.contains("turn_cycle_boundary"))
            assertTrue(text.contains("\"completedCycle\":1"))
            assertTrue(text.contains("\"nextCycle\":2"))
            assertTrue(text.contains("YOD_032-entity"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `replay stream preserves location power refresh and screenshot evidence`() {
        val root = Files.createTempDirectory("mcts-replay-location-").toFile()
        try {
            val war = war("location-game", 103L)
            val location = Card(TestCardAction()).apply {
                entityId = "location-1"
                cardId = "LOCATION_TEST"
                entityName = "Test Location"
                cardType = CardTypeEnum.LOCATION
                health = 3
            }
            war.addCard(location, war.me.playArea)

            val scan = MctsReplayTrace.record(
                war,
                "live_actionability_scan",
                "fresh live scan exposes a clickable board PowerAction",
                mapOf(
                    "actionableCreatorIds" to listOf("location-1"),
                    "clickableLocations" to listOf(
                        mapOf(
                            "entityId" to "location-1",
                            "canPower" to true,
                            "rawPowerActions" to 0,
                            "opaquePowerFallback" to true,
                            "actionable" to true,
                        ),
                    ),
                ),
                root,
            )!!.toFile()
            MctsReplayTrace.record(
                war,
                "action_dispatched",
                "location activation dispatched",
                mapOf("action" to "使用技能/效果(LOCATION_TEST)", "screenshotBefore" to "before-location.png"),
                root,
            )
            MctsReplayTrace.record(
                war,
                "action_confirmed",
                "location cooldown and board state refreshed",
                mapOf("screenshotAfterConfirmed" to "after-location.png"),
                root,
            )
            val endScan = MctsReplayTrace.record(
                war,
                "turn_end_full_rescan",
                "end-turn guard sees the refreshed location state",
                mapOf("playableBoardPowers" to 1, "fullRescan" to true),
                root,
            )!!.toFile()

            val text = endScan.readText()
            assertTrue(text.contains("LOCATION_TEST"))
            assertTrue(text.contains("playableBoardPowers"))
            assertTrue(text.contains("opaquePowerFallback"))
            assertTrue(text.contains("\"rawPowerActions\":0"))
            assertTrue(text.contains("after-location.png"))
            assertTrue(text.contains("clickableLocations"))
            assertTrue(scan.readText().contains("location-1"))
            assertTrue(text.indexOf("action_confirmed") < text.indexOf("turn_end_full_rescan"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `retains only the newest fifty game directories`() {
        val root = Files.createTempDirectory("mcts-replay-retention-").toFile()
        try {
            repeat(52) { index ->
                MctsReplayTrace.record(
                    war("game-$index", index.toLong() + 1L),
                    "search_started",
                    "test",
                    rootDirectory = root,
                )
                Thread.sleep(2L)
            }
            val games = root.listFiles { file -> file.isDirectory && file.name.startsWith("game-") }.orEmpty()
            assertEquals(MctsReplayTrace.MAX_RETAINED_GAMES, games.size)
            assertTrue(games.none { it.name.contains("game-0-") || it.name.contains("game-1-") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun war(gameId: String, startTime: Long): War = War(false).apply {
        this.startTime = startTime
        me = Player(playerId = "me", gameId = gameId, war = this)
        rival = Player(playerId = "rival", gameId = "rival", war = this)
    }
}
