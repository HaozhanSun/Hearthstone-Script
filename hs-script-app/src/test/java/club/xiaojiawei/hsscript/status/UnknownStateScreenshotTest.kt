package club.xiaojiawei.hsscript.status

import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import javax.imageio.ImageIO

class UnknownStateScreenshotTest {

    @Test
    fun `dummy e2e saves timestamped annotated screenshot in dated folder`() {
        val configuredOutput = System.getProperty("hs.script.unknown-state.test-output")
        val root = configuredOutput?.let(::File) ?: Files.createTempDirectory("unknown-state-e2e-").toFile()
        val shouldClean = configuredOutput == null
        try {
            val image = BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = Color(40, 60, 90)
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color(220, 220, 220)
            graphics.fillRect(95, 60, 120, 70)
            graphics.dispose()

            val saved = assertNotNull(
                UnknownStateScreenshot.save(
                    image = image,
                    regions = listOf(
                        UnknownStateScreenshot.UnknownRegion(
                            Rectangle(95, 60, 120, 70),
                            "security-check-candidate",
                        ),
                    ),
                    trigger = "dummy-e2e-unknown-screen",
                    state = "expected=HOME observed=UNCLASSIFIED",
                    phase = "dummy-e2e",
                    ocrText = "security check",
                    visual = "sampleHash=deadbeef",
                    rootDirectory = root,
                    clock = Clock.fixed(
                        Instant.parse("2026-08-26T23:59:58.123Z"),
                        ZoneId.of("America/Los_Angeles"),
                    ),
                ),
            )

            assertTrue(saved.file.isFile)
            assertEquals("2026-08-26", saved.dateDirectory.name)
            assertEquals(UnknownStateScreenshot.CATEGORY_SCREEN_RECOVERY_UNRESOLVED, saved.category)
            assertEquals(UnknownStateScreenshot.CATEGORY_SCREEN_RECOVERY_UNRESOLVED, saved.dateDirectory.parentFile.name)
            assertTrue(saved.file.name.startsWith("unknown-state-20260826-165958-123-dummy-e2e-unknown-screen-"))
            assertTrue(saved.file.name.endsWith(".png"))
            assertEquals(1, saved.retainedCount)

            val annotated = assertNotNull(ImageIO.read(saved.file))
            // The red annotation crosses the top-left corner of the supplied
            // unknown region. This proves the dummy E2E wrote the annotated
            // evidence, not merely the unmodified source image.
            val red = Color(annotated.getRGB(95, 60), true)
            assertTrue(red.red > 180 && red.red > red.green * 2 && red.red > red.blue * 2)
        } finally {
            if (shouldClean) root.deleteRecursively()
        }
    }

    @Test
    fun `dummy e2e captures fatal error in its own category`() {
        val configuredOutput = System.getProperty("hs.script.unknown-state.test-output")
        val root = configuredOutput?.let(::File) ?: Files.createTempDirectory("fatal-state-e2e-").toFile()
        val shouldClean = configuredOutput == null
        try {
            val image = BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = Color(25, 25, 25)
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color(210, 70, 45)
            graphics.fillRect(90, 70, 140, 50)
            graphics.dispose()

            val saved = assertNotNull(
                UnknownStateScreenshot.save(
                    image = image,
                    regions = listOf(
                        UnknownStateScreenshot.UnknownRegion(
                            Rectangle(0, 0, image.width, image.height),
                            "fatal-error-before-restart",
                        ),
                    ),
                    category = UnknownStateScreenshot.CATEGORY_FATAL_ERROR,
                    trigger = "dummy-e2e-fatal-error",
                    state = "mode=FATAL_ERROR",
                    phase = "fatal-error-restart",
                    ocrText = "fatal error",
                    rootDirectory = root,
                    clock = Clock.fixed(
                        Instant.parse("2026-08-26T23:59:59.123Z"),
                        ZoneId.of("America/Los_Angeles"),
                    ),
                ),
            )

            assertTrue(saved.file.isFile)
            assertEquals(UnknownStateScreenshot.CATEGORY_FATAL_ERROR, saved.category)
            assertEquals(UnknownStateScreenshot.CATEGORY_FATAL_ERROR, saved.dateDirectory.parentFile.name)
            assertTrue(saved.file.name.contains("dummy-e2e-fatal-error"))
            val annotated = assertNotNull(ImageIO.read(saved.file))
            val hasRedBorderPixel = (0 until 12).any { y ->
                val red = Color(annotated.getRGB(2, y + 40), true)
                red.red > 180 && red.red > red.green * 2 && red.red > red.blue * 2
            }
            assertTrue(hasRedBorderPixel)
        } finally {
            if (shouldClean) root.deleteRecursively()
        }
    }

    @Test
    fun `fifo retention keeps at most one hundred screenshots per date`() {
        val root = Files.createTempDirectory("unknown-state-fifo-").toFile()
        try {
            val dateDirectory = java.io.File(root, "2026-08-26")
            assertTrue(dateDirectory.mkdirs())
            repeat(101) { index ->
                val file = java.io.File(dateDirectory, "unknown-state-${index.toString().padStart(3, '0')}.png")
                assertTrue(file.createNewFile())
                file.setLastModified(index.toLong())
            }
            val retained = UnknownStateScreenshot.prune(dateDirectory, UnknownStateScreenshot.MAX_SCREENSHOTS_PER_DATE)
            assertEquals(100, retained.size)
            assertEquals(100, dateDirectory.listFiles { file -> file.extension == "png" }?.size)
            assertTrue(!java.io.File(dateDirectory, "unknown-state-000.png").exists())
            assertTrue(java.io.File(dateDirectory, "unknown-state-100.png").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
