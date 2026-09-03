package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.consts.LOG_PATH
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
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
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

/**
 * Shared diagnostic screenshot storage. It intentionally does not send input
 * to Hearthstone: it only reads the desktop and writes evidence files.
 */
object DebugScreenshotRing {

    const val MAX_RETAINED_SCREENSHOTS = 60
    const val DEFAULT_MAX_BYTES = 268_435_456L
    const val DEFAULT_COOLDOWN_MS = 1_500L
    private val timestampFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
    private val writeLock = Any()
    private val lastCaptureAt = AtomicLong(0L)

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
        save(Robot().createScreenCapture(bounds), event, reason, enforceCooldown = true)
    }.getOrElse { error ->
        log.warn(error) { "DEBUG_SCREENSHOT_FAILED event=$event reason=capture-exception" }
        null
    }

    /** Save an already-captured image into the same global FIFO ring. */
    fun save(
        image: BufferedImage,
        event: String,
        reason: String,
        enforceCooldown: Boolean = false,
    ): SavedScreenshot? = runCatching {
        if (enforceCooldown && !tryAcquireCapture(event)) return null
        synchronized(writeLock) {
            val directory = File(LOG_PATH, "debug-screenshots")
            if (!directory.exists() && !directory.mkdirs()) {
                log.warn { "DEBUG_SCREENSHOT_FAILED event=$event reason=mkdir path=${directory.absolutePath}" }
                return null
            }
            val stamp = synchronized(timestampFormat) { timestampFormat.format(Date()) }
            val safeEvent = event.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val file = File(directory, "debug-$stamp-$safeEvent-${UUID.randomUUID()}.png")
            if (!ImageIO.write(image, "png", file)) {
                log.warn { "DEBUG_SCREENSHOT_FAILED event=$event reason=png-writer path=${file.absolutePath}" }
                return null
            }
            val maxFiles = configuredMaxFiles()
            val maxBytes = configuredMaxBytes()
            val retained = prune(directory, maxFiles, maxBytes)
            val link = file.toURI().toString()
            log.warn {
                "DEBUG_SCREENSHOT event=$event reason=$reason path=${file.absolutePath} " +
                    "link=$link retained=${retained.size} maxFiles=$maxFiles " +
                    "maxBytes=$maxBytes totalBytes=${retained.sumOf { it.length() }}"
            }
            SavedScreenshot(file, link, retained.size)
        }
    }.getOrElse { error ->
        log.warn(error) { "DEBUG_SCREENSHOT_FAILED event=$event reason=save-exception" }
        null
    }

    /** Keep newest files only; separated for a deterministic regression test. */
    internal fun prune(directory: File, maxFiles: Int): List<File> {
        return prune(directory, maxFiles, Long.MAX_VALUE)
    }

    /**
     * Keep newest files within both limits. This only operates on PNGs in the
     * caller-provided debug directory; user logs, data, plugins, ledgers and
     * final-evidence directories are outside this retention boundary.
     */
    internal fun prune(directory: File, maxFiles: Int, maxBytes: Long): List<File> {
        val files = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            ?: emptyList()
        val retained = mutableListOf<File>()
        var totalBytes = 0L
        val countLimit = maxFiles.coerceAtLeast(0)
        val byteLimit = maxBytes.coerceAtLeast(1L)
        files.forEach { file ->
            val protected = isProtectedEvidence(file)
            val fitsCount = retained.size < countLimit
            val fitsBytes = totalBytes + file.length() <= byteLimit
            if (protected || (fitsCount && (fitsBytes || retained.isEmpty()))) {
                retained += file
                totalBytes += file.length()
            } else {
                val fileSize = file.length()
                if (file.delete()) {
                    log.info {
                        "DEBUG_SCREENSHOT_EVICTED size=$fileSize limitBytes=$byteLimit " +
                            "limitFiles=$countLimit path=${file.absolutePath}"
                    }
                }
            }
        }
        return retained
    }

    internal fun resetForTest() = lastCaptureAt.set(0L)

    private fun tryAcquireCapture(event: String): Boolean {
        val cooldownMs = ConfigUtil.getLong(ConfigEnum.SCREEN_RECOVERY_SCREENSHOT_COOLDOWN_MS)
            .coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        while (true) {
            val previous = lastCaptureAt.get()
            if (previous > 0L && now - previous < cooldownMs) {
                log.info {
                    "DEBUG_SCREENSHOT_THROTTLED event=$event cooldownMs=$cooldownMs " +
                        "remainingMs=${cooldownMs - (now - previous)}"
                }
                return false
            }
            if (lastCaptureAt.compareAndSet(previous, now)) return true
        }
    }

    private fun configuredMaxFiles(): Int = ConfigUtil.getInt(ConfigEnum.SCREEN_RECOVERY_SCREENSHOT_MAX_FILES)
        .takeIf { it > 0 } ?: MAX_RETAINED_SCREENSHOTS

    private fun configuredMaxBytes(): Long = ConfigUtil.getLong(ConfigEnum.SCREEN_RECOVERY_SCREENSHOT_MAX_BYTES)
        .takeIf { it > 0 } ?: DEFAULT_MAX_BYTES

    private fun isProtectedEvidence(file: File): Boolean {
        val name = file.name.lowercase(Locale.ROOT)
        return name.contains("ledger") || name.contains("final-victory") || name.contains("victory-evidence")
    }
}
