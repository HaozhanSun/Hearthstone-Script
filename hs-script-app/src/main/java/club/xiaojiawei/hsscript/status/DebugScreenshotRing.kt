package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.consts.LOG_PATH
import club.xiaojiawei.hsscriptbase.config.log
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Shared diagnostic screenshot storage. It intentionally does not send input
 * to Hearthstone: it only reads the desktop and writes evidence files.
 */
object DebugScreenshotRing {

    const val MAX_RETAINED_SCREENSHOTS = 60
    private val timestampFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT)

    data class SavedScreenshot(
        val file: File,
        val link: String,
        val retainedCount: Int,
    )

    /** Capture the game window when known, otherwise the complete desktop. */
    fun capture(event: String, reason: String): SavedScreenshot? = runCatching {
        if (GraphicsEnvironment.isHeadless()) {
            log.warn { "DEBUG_SCREENSHOT_FAILED event=$event reason=headless" }
            return null
        }
        val allScreens = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
            .fold(Rectangle()) { all, next -> all.union(next) }
        if (allScreens.width <= 0 || allScreens.height <= 0) {
            log.warn { "DEBUG_SCREENSHOT_FAILED event=$event reason=no-screen-bounds" }
            return null
        }

        val gameRect = ScriptStatus.GAME_RECT
        val candidate = if (gameRect.right - gameRect.left >= 400 &&
            gameRect.bottom - gameRect.top >= 300
        ) {
            Rectangle(
                gameRect.left,
                gameRect.top,
                gameRect.right - gameRect.left,
                gameRect.bottom - gameRect.top,
            )
        } else {
            allScreens
        }
        val bounds = candidate.intersection(allScreens)
        if (bounds.width < 400 || bounds.height < 300) {
            log.warn { "DEBUG_SCREENSHOT_FAILED event=$event reason=invalid-bounds bounds=$bounds" }
            return null
        }
        save(Robot().createScreenCapture(bounds), event, reason)
    }.getOrElse { error ->
        log.warn(error) { "DEBUG_SCREENSHOT_FAILED event=$event reason=capture-exception" }
        null
    }

    /** Save an already-captured image into the same global FIFO ring. */
    fun save(image: BufferedImage, event: String, reason: String): SavedScreenshot? = runCatching {
        val directory = File(LOG_PATH, "debug-screenshots")
        if (!directory.exists() && !directory.mkdirs()) {
            log.warn { "DEBUG_SCREENSHOT_FAILED event=$event reason=mkdir path=${directory.absolutePath}" }
            return null
        }
        val stamp = synchronized(timestampFormat) { timestampFormat.format(Date()) }
        val safeEvent = event.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val file = File(directory, "debug-$stamp-$safeEvent-${UUID.randomUUID()}.png")
        ImageIO.write(image, "png", file)
        val retained = prune(directory, MAX_RETAINED_SCREENSHOTS)
        val link = file.toURI().toString()
        log.warn {
            "DEBUG_SCREENSHOT event=$event reason=$reason path=${file.absolutePath} " +
                "link=$link retained=${retained.size} max=$MAX_RETAINED_SCREENSHOTS"
        }
        SavedScreenshot(file, link, retained.size)
    }.getOrElse { error ->
        log.warn(error) { "DEBUG_SCREENSHOT_FAILED event=$event reason=save-exception" }
        null
    }

    /** Keep newest files only; separated for a deterministic regression test. */
    internal fun prune(directory: File, maxFiles: Int): List<File> {
        val files = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            ?: emptyList()
        files.drop(maxFiles.coerceAtLeast(0)).forEach { it.delete() }
        return files.take(maxFiles.coerceAtLeast(0))
    }
}
