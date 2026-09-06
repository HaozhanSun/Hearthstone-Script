package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.War
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MctsRoundScreenshotTest {
    @Test
    fun `retains the newest ten screenshots per game in FIFO order`() {
        val root = Files.createTempDirectory("mcts-round-screenshots-").toFile()
        try {
            val war = War(false).apply {
                startTime = 777L
                me = Player(playerId = "me", gameId = "screenshot-game", war = this)
                rival = Player(playerId = "rival", gameId = "rival", war = this)
            }
            val image = BufferedImage(24, 18, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().also { graphics ->
                    graphics.color = Color(20, 40, 80)
                    graphics.fillRect(0, 0, width, height)
                    graphics.dispose()
                }
            }
            repeat(12) { turn ->
                assertNotNull(
                    MctsRoundScreenshot.save(
                        image,
                        war,
                        turn + 1,
                        root,
                        Clock.fixed(
                            Instant.parse("2026-08-26T23:00:00Z").plusSeconds(turn.toLong()),
                            ZoneId.of("UTC"),
                        ),
                    ),
                )
                Thread.sleep(2L)
            }
            val screenshots = root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".png") }
                .toList()
            assertEquals(MctsRoundScreenshot.MAX_ROUND_SCREENSHOTS, screenshots.size)
            assertTrue(screenshots.none { it.name.contains("round-0001-") })
            assertTrue(screenshots.none { it.name.contains("round-0002-") })
            assertTrue(screenshots.any { it.name.contains("round-0003-") })
            assertTrue(screenshots.any { it.name.contains("round-0012-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `action screenshot filename correlates turn step phase and confirmation`() {
        val root = Files.createTempDirectory("mcts-action-screenshots-").toFile()
        try {
            val war = War(false).apply {
                startTime = 778L
                me = Player(playerId = "me", gameId = "action-screenshot-game", war = this)
                rival = Player(playerId = "rival", gameId = "rival", war = this)
                me.turn = 7
            }
            val image = BufferedImage(24, 18, BufferedImage.TYPE_INT_RGB)
            val file = MctsRoundScreenshot.saveAction(
                image,
                war,
                stage = "before",
                step = 3,
                phase = "MINION_ATTACK",
                action = "打出(YOD_032:狂暴邪翼蝠)",
                confirmation = "selected",
                rootDirectory = root,
                clock = Clock.fixed(
                    Instant.parse("2026-08-26T23:00:00Z"),
                    ZoneId.of("UTC"),
                ),
            )

            assertNotNull(file)
            assertTrue(file!!.name.contains("turn-0007-step-03-before-MINION_ATTACK-selected"))
            assertTrue(file!!.parentFile.name == "action-screenshots")
        } finally {
            root.deleteRecursively()
        }
    }
}
