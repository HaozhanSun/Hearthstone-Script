package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.LocalTime
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RcTerminalCleanupIncidentReplayTest {
    @Test
    fun `v4 16 682 screenshot and timeline reproduce blocked result-page recovery`() {
        val screenshotBytes = requireNotNull(javaClass.getResourceAsStream(SCREENSHOT)).use { it.readBytes() }
        assertEquals(SCREENSHOT_SHA256, screenshotBytes.sha256())
        val image: BufferedImage = requireNotNull(ImageIO.read(ByteArrayInputStream(screenshotBytes)))
        assertEquals(1919, image.width)
        assertEquals(1079, image.height)
        assertFalse(HubModeChooserVisualPolicy.isVisible(image), "result art has no four separated chooser button faces")
        assertEquals(null, ScreenStateRecovery.classifyImageForResultFixture(image), "the exact captured frame is the UNKNOWN OCR case")
        assertEquals(null, UpstreamScreenStateRecovery.classifyImageForResultFixture(image), "the frame remains UNKNOWN in the upstream classifier")

        val incident = requireNotNull(javaClass.getResourceAsStream(TIMELINE))
            .bufferedReader().use { it.readText() }
        assertTrue(incident.contains("terminalResult=23:41:31.598 own=CONCEDED opponent=WON complete=true"))
        assertTrue(incident.contains("screen=defeat-result continue-prompt-visible"))
        assertFalse(incident.contains("dispatch=true"), "the captured recovery actions were rejected, not accepted")
        assertTrue(incident.contains("repeatedSuppressed=15"))
        assertTrue(incident.contains("manualPause=00:08:14.222 F2 accepted"))

        val terminal = LocalTime.parse("23:41:31")
        val screenshotTime = LocalTime.parse("00:08:04")
        val latestBlocked = LocalTime.parse("00:12:21")
        val screenshotDelay = Duration.between(terminal, screenshotTime).let {
            if (it.isNegative) it.plusDays(1) else it
        }
        val latestDelay = Duration.between(terminal, latestBlocked).let {
            if (it.isNegative) it.plusDays(1) else it
        }
        assertEquals(Duration.ofMinutes(26).plusSeconds(33), screenshotDelay)
        assertEquals(Duration.ofMinutes(30).plusSeconds(50), latestDelay)
    }

    private fun ByteArray.sha256(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02X".format(it) }

    private companion object {
        const val SCREENSHOT = "/offline-ocr/result-flow/rc-v4.16.682-terminal-stall.png"
        const val TIMELINE = "/offline-ocr/result-flow/rc-v4.16.682-terminal-stall.txt"
        const val SCREENSHOT_SHA256 = "719E8055BD5A7EC99A3FE32341C77E2216A360C5FE7112FB897212ADD4CF080A"
    }
}
