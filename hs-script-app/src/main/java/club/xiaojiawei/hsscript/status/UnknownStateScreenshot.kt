package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.consts.LOG_PATH
import club.xiaojiawei.hsscriptbase.config.log
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Durable evidence for a screen that the state machine could not identify.
 *
 * This is deliberately separate from [DebugScreenshotRing]. The global ring
 * is useful for short-lived diagnosis, while unknown states need a dated,
 * searchable archive that survives a later post-mortem. The retention limit
 * is applied inside each date directory, so an investigation on one day
 * cannot evict evidence from another day.
 */
object UnknownStateScreenshot {

    const val MAX_SCREENSHOTS_PER_DATE = 100

    // Each category gets its own daily FIFO.  Keeping the category in the
    // path makes a post-mortem searchable without parsing filenames first.
    const val CATEGORY_STUCK_STATE = "stuck-state"
    const val CATEGORY_SCREEN_RECOVERY_UNRESOLVED = "screen-recovery-unresolved"
    const val CATEGORY_FATAL_ERROR = "fatal-error"
    const val CATEGORY_ACTION_FAILURE = "action-failure"
    const val CATEGORY_TURN_END_STUCK = "turn-end-stuck"
    const val CATEGORY_POPUP_RECOVERY = "popup-recovery"

    private val timestampFormatter = DateTimeFormatter.ofPattern(
        "yyyyMMdd-HHmmss-SSS",
        Locale.ROOT,
    )
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    data class UnknownRegion(
        val bounds: Rectangle,
        val label: String,
    )

    data class SavedScreenshot(
        val file: File,
        val link: String,
        val category: String,
        val dateDirectory: File,
        val retainedCount: Int,
    )

    /**
     * Capture the known game rectangle, or the whole desktop during startup,
     * and persist it in a problem category.  This method only reads pixels;
     * it never focuses a window or sends input.
     */
    fun capture(
        category: String,
        trigger: String,
        state: String,
        phase: String,
        label: String = "diagnostic-screen",
        ocrText: String = "",
        visual: String = "",
        rootDirectory: File = defaultRootDirectory(),
        clock: Clock = Clock.systemDefaultZone(),
    ): SavedScreenshot? = runCatching {
        if (GraphicsEnvironment.isHeadless()) {
            log.warn { "UNKNOWN_STATE_SCREENSHOT_FAILED category=$category trigger=$trigger reason=headless" }
            return null
        }
        val allScreens = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
            .fold(Rectangle()) { all, next -> all.union(next) }
        if (allScreens.width <= 0 || allScreens.height <= 0) {
            log.warn { "UNKNOWN_STATE_SCREENSHOT_FAILED category=$category trigger=$trigger reason=no-screen-bounds" }
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
            log.warn {
                "UNKNOWN_STATE_SCREENSHOT_FAILED category=$category trigger=$trigger " +
                    "reason=invalid-bounds bounds=$bounds"
            }
            return null
        }
        val image = try {
            Robot().createScreenCapture(bounds)
        } catch (_: Exception) {
            Robot().createScreenCapture(allScreens)
        }
        save(
            image = image,
            regions = listOf(UnknownRegion(Rectangle(0, 0, image.width, image.height), label)),
            category = category,
            trigger = trigger,
            state = state,
            phase = phase,
            ocrText = ocrText,
            visual = visual,
            rootDirectory = rootDirectory,
            clock = clock,
        )
    }.getOrElse { error ->
        log.warn(error) {
            "UNKNOWN_STATE_SCREENSHOT_FAILED category=$category trigger=$trigger reason=capture-exception"
        }
        null
    }

    /**
     * Save an already captured screen with a red annotation around the area
     * that the detector could not classify.
     *
     * Callers should pass image-local coordinates. When a detector has no
     * narrower region, passing the complete image is intentional and is
     * labelled as an unidentified screen rather than pretending a smaller
     * region is known.
     */
    fun save(
        image: BufferedImage,
        regions: List<UnknownRegion>,
        category: String = CATEGORY_SCREEN_RECOVERY_UNRESOLVED,
        trigger: String,
        state: String,
        phase: String,
        ocrText: String = "",
        visual: String = "",
        rootDirectory: File = defaultRootDirectory(),
        clock: Clock = Clock.systemDefaultZone(),
    ): SavedScreenshot? = runCatching {
        require(image.width > 0 && image.height > 0) { "unknown-state screenshot has invalid dimensions" }

        val now = ZonedDateTime.now(clock)
        val date = dateFormatter.format(now)
        val safeCategory = sanitizePathComponent(category).ifBlank { CATEGORY_SCREEN_RECOVERY_UNRESOLVED }
        val categoryDirectory = File(rootDirectory, safeCategory)
        val dateDirectory = File(categoryDirectory, date)
        if (!dateDirectory.exists() && !dateDirectory.mkdirs()) {
            log.warn {
                "UNKNOWN_STATE_SCREENSHOT_FAILED trigger=$trigger reason=mkdir " +
                    "path=${dateDirectory.absolutePath}"
            }
            return null
        }

        val stamp = timestampFormatter.format(now)
        val safeTrigger = sanitize(trigger).take(80).ifBlank { "unknown" }
        val file = File(
            dateDirectory,
            "unknown-state-$stamp-$safeTrigger-${UUID.randomUUID()}.png",
        )
        val annotated = annotate(image, regions)
        if (!ImageIO.write(annotated, "png", file)) {
            log.warn {
                "UNKNOWN_STATE_SCREENSHOT_FAILED trigger=$trigger reason=png-writer " +
                    "path=${file.absolutePath}"
            }
            return null
        }

        val retained = prune(dateDirectory, MAX_SCREENSHOTS_PER_DATE)
        val link = file.toURI().toString()
        val regionSummary = regions.joinToString(";") {
            "${sanitize(it.label)}@${it.bounds.x},${it.bounds.y},${it.bounds.width},${it.bounds.height}"
        }.ifBlank { "full-screen-fallback" }
        log.warn {
            "UNKNOWN_STATE_SCREENSHOT trigger=$trigger state=${sanitize(state).take(160)} " +
                "phase=${sanitize(phase).take(80)} date=$date " +
                "ocr=${sanitize(ocrText).take(240).ifBlank { "<empty>" }} " +
                "visual=${sanitize(visual).take(240).ifBlank { "<none>" }} " +
                "regions=$regionSummary path=${file.absolutePath} link=$link " +
                "category=$safeCategory retained=${retained.size} max=$MAX_SCREENSHOTS_PER_DATE"
        }
        SavedScreenshot(file, link, safeCategory, dateDirectory, retained.size)
    }.getOrElse { error ->
        log.warn(error) {
            "UNKNOWN_STATE_SCREENSHOT_FAILED trigger=$trigger state=${sanitize(state).take(160)} " +
                "phase=${sanitize(phase).take(80)} reason=save-exception"
        }
        null
    }

    /** Keep the newest PNGs in one date directory and return what remains. */
    internal fun prune(directory: File, maxFiles: Int): List<File> {
        val files = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            ?: emptyList()
        files.drop(maxFiles.coerceAtLeast(0)).forEach { it.delete() }
        return files.take(maxFiles.coerceAtLeast(0))
    }

    private fun defaultRootDirectory(): File = File(
        System.getProperty(
            "hs.script.unknown-state.dir",
            File(LOG_PATH, "unknown-states").path,
        ),
    )

    private fun sanitizePathComponent(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', ' ')
        .take(80)

    private fun annotate(image: BufferedImage, regions: List<UnknownRegion>): BufferedImage {
        val annotated = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val copy = annotated.createGraphics()
        try {
            copy.drawImage(image, 0, 0, null)
            copy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            copy.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val validRegions = regions.mapNotNull { region ->
                val clipped = region.bounds.intersection(Rectangle(0, 0, image.width, image.height))
                if (clipped.width > 0 && clipped.height > 0) region.copy(bounds = clipped) else null
            }.ifEmpty {
                listOf(UnknownRegion(Rectangle(0, 0, image.width, image.height), "unidentified-screen"))
            }
            validRegions.forEach { region -> drawRegion(copy, region) }
        } finally {
            copy.dispose()
        }
        return annotated
    }

    private fun drawRegion(graphics: Graphics2D, region: UnknownRegion) {
        val bounds = region.bounds
        graphics.color = Color(255, 35, 35, 235)
        graphics.stroke = BasicStroke(6f)
        graphics.drawRect(bounds.x, bounds.y, (bounds.width - 1).coerceAtLeast(1), (bounds.height - 1).coerceAtLeast(1))

        val label = region.label.ifBlank { "unidentified" }
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 20)
        val metrics = graphics.fontMetrics
        val textWidth = metrics.stringWidth(label)
        val labelHeight = metrics.height + 8
        val labelX = bounds.x.coerceAtLeast(0)
        val labelY = (bounds.y - labelHeight).coerceAtLeast(0)
        graphics.color = Color(0, 0, 0, 210)
        graphics.fillRect(labelX, labelY, textWidth + 16, labelHeight)
        graphics.color = Color(255, 235, 80)
        graphics.drawString(label, labelX + 8, labelY + metrics.ascent + 4)
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("\\s+"), "_")
        .replace(Regex("[^A-Za-z0-9._:/,@=+\\-\\u4e00-\\u9fff]"), "_")
}
