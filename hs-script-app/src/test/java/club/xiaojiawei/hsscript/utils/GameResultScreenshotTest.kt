package club.xiaojiawei.hsscript.utils

import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GameResultScreenshotTest {

    private val previousDirectory = System.getProperty("hs.script.result-screenshot.dir")

    @AfterTest
    fun restoreProperties() {
        if (previousDirectory == null) {
            System.clearProperty("hs.script.result-screenshot.dir")
        } else {
            System.setProperty("hs.script.result-screenshot.dir", previousDirectory)
        }
    }

    @Test
    fun `result evidence filename preserves the reserved completed game ordinal`() {
        val directory = Files.createTempDirectory("game-result-screenshot-").toFile()
        System.setProperty("hs.script.result-screenshot.dir", directory.absolutePath)

        val file = GameResultScreenshot.save(
            BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB),
            "win",
            133,
        )

        assertNotNull(file)
        assertEquals("game-0133-win-${file.name.substringAfter("game-0133-win-")}", file.name)
        assertTrue(file.exists())
    }
}
