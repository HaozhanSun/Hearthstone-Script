package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.status.ScreenWatchdog
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import javax.imageio.ImageIO

class GameResultScreenshotTest {

    private val previousDirectory = System.getProperty("hs.script.result-screenshot.dir")
    private val previousArtifactRoot = System.getProperty("hs.script.artifact-root")

    @AfterTest
    fun restoreProperties() {
        if (previousDirectory == null) {
            System.clearProperty("hs.script.result-screenshot.dir")
        } else {
            System.setProperty("hs.script.result-screenshot.dir", previousDirectory)
        }
        if (previousArtifactRoot == null) System.clearProperty("hs.script.artifact-root")
        else System.setProperty("hs.script.artifact-root", previousArtifactRoot)
    }

    @Test
    fun `result evidence filename preserves the reserved completed game ordinal`() {
        val directory = Files.createTempDirectory("game-result-screenshot-").toFile()
        System.setProperty("hs.script.result-screenshot.dir", directory.absolutePath)
        System.setProperty("hs.script.artifact-root", directory.absolutePath)

        val file = GameResultScreenshot.save(
            BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB),
            "win",
            133,
        )

        assertNotNull(file)
        assertEquals("game-0133-win-${file.name.substringAfter("game-0133-win-")}", file.name)
        assertTrue(file.exists())
    }

    @Test
    fun `live v794 game result artifact is rejected when delayed capture is gameplay menu`() {
        val screenshot = requireNotNull(
            javaClass.getResourceAsStream(
                "/offline-ocr/screen-recovery/beta-live-game-0002-win-20261122-120412-game-menu.png",
            ),
        ).use(ImageIO::read)
        assertTrue(
            ScreenWatchdog.hasVerifiedGameMenuOverlay(screenshot),
            ScreenWatchdog.settingsOverlayDiagnosticsForTest(screenshot),
        )

        val directory = Files.createTempDirectory("game-result-menu-").toFile()
        System.setProperty("hs.script.result-screenshot.dir", directory.absolutePath)
        System.setProperty("hs.script.artifact-root", directory.absolutePath)

        assertNull(
            GameResultScreenshot.save(screenshot, "win", 2),
            "authoritative terminal outcome must not mislabel a gameplay menu frame as a result screenshot",
        )
        assertTrue(directory.walkTopDown().none { it.isFile && it.name.startsWith("game-0002-") })
    }
}
