package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscriptbase.config.log
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Saves the result screen before the normal result-page cleanup clicks begin.
 *
 * This is intentionally owned by the script rather than by an external E2E
 * watcher.  A normal long-running session therefore keeps an evidence trail
 * for every completed game without having to stop or pause after a result.
 */
object GameResultScreenshot {

    private val timestampFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT)

    fun capture(outcome: String, gameNumber: Int): File? {
        return runCatching {
            if (GraphicsEnvironment.isHeadless()) {
                log.warn { "GAME_RESULT_SCREENSHOT_SKIPPED reason=headless" }
                return null
            }

            val screenshotDirectory = File(
                System.getProperty(
                    "hs.script.result-screenshot.dir",
                    File("log", "game-results").path,
                ),
            )
            if (!screenshotDirectory.exists() && !screenshotDirectory.mkdirs()) {
                log.warn { "GAME_RESULT_SCREENSHOT_FAILED reason=mkdir path=${screenshotDirectory.absolutePath}" }
                return null
            }

            val screenBounds = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .screenDevices
                .map { it.defaultConfiguration.bounds }
                .fold(Rectangle()) { all, next -> all.union(next) }

            if (screenBounds.width <= 0 || screenBounds.height <= 0) {
                log.warn { "GAME_RESULT_SCREENSHOT_FAILED reason=invalid-bounds bounds=$screenBounds" }
                return null
            }

            val stamp = synchronized(timestampFormat) {
                timestampFormat.format(Date())
            }
            val safeOutcome = outcome.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(
                screenshotDirectory,
                "game-${gameNumber.toString().padStart(4, '0')}-$safeOutcome-$stamp.png",
            )
            ImageIO.write(Robot().createScreenCapture(screenBounds), "png", file)
            log.info {
                "GAME_RESULT_SCREENSHOT outcome=$outcome game=$gameNumber path=${file.absolutePath}"
            }
            file
        }.getOrElse { error ->
            log.warn(error) { "GAME_RESULT_SCREENSHOT_FAILED outcome=$outcome game=$gameNumber" }
            null
        }
    }
}
