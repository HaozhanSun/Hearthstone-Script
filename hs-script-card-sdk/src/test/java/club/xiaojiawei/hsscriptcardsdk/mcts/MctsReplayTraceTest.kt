package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.War
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
