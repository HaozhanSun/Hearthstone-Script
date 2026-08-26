package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscriptbase.config.log
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO

/** Evidence captured while the mulligan UI is visible. */
object MulliganScreenshot {

    private val timestampFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT)

    /**
     * Wait for the actual mulligan cards to be painted and interactive.
     * Power.log reaches REPLACE_CARD before the client finishes its opening
     * hand animation, so a timer-only delay can send a perfectly valid click
     * into the previous screen.  The green mulligan glow is the UI's own
     * readiness signal; require it around every visible card before input.
     */
    fun awaitInteractiveHand(cardCenters: List<Point>, timeoutMillis: Long = 25_000L): Boolean {
        if (cardCenters.isEmpty()) return false
        if (GraphicsEnvironment.isHeadless()) return false
        return runCatching {
            val robot = Robot()
            val bounds = screenBounds()
            val deadline = System.currentTimeMillis() + timeoutMillis
            var samples = 0
            var consecutiveReadySamples = 0
            var readyVisualSamples = 0
            var lastCardsReadyAt = 0L
            var lastConfirmReadyAt = 0L
            // A few green pixels also occur in card artwork and during the
            // three-card-to-four-card opening-hand animation.  Require the
            // neon interactive border around the expected number of cards,
            // and require it to remain visible for several consecutive
            // captures.  This is an image-state check, not a fixed sleep.
            val minimumGreenPixels = (cardCenters.size * 20).coerceAtLeast(80)
            val minimumGreenPixelsPerCard = 10
            val minimumVisibleCardPixelsPerCard = 250
            val minimumConfirmButtonBluePixels = 500
            val minimumConfirmButtonWhitePixels = 300
            while (System.currentTimeMillis() < deadline) {
                samples++
                val screenshot = robot.createScreenCapture(bounds)
                val greenPixelsPerCard = countGreenMulliganPixelsPerCard(screenshot, bounds, cardCenters)
                val visibleCardPixelsPerCard = countVisibleCardPixelsPerCard(screenshot, bounds, cardCenters)
                val confirmButtonBluePixels = countConfirmButtonBluePixels(screenshot, bounds)
                val confirmButtonWhitePixels = countConfirmButtonWhitePixels(screenshot, bounds)
                val greenPixels = greenPixelsPerCard.sum()
                val allCardBordersReady = greenPixelsPerCard.all { it >= minimumGreenPixelsPerCard }
                val allCardsPainted = visibleCardPixelsPerCard.all { it >= minimumVisibleCardPixelsPerCard }
                val confirmButtonReady = confirmButtonBluePixels >= minimumConfirmButtonBluePixels &&
                    confirmButtonWhitePixels >= minimumConfirmButtonWhitePixels
                val cardsReady = (allCardBordersReady && greenPixels >= minimumGreenPixels) || allCardsPainted
                val visualReady = cardsReady && confirmButtonReady
                val now = System.currentTimeMillis()
                if (cardsReady) lastCardsReadyAt = now
                if (confirmButtonReady) lastConfirmReadyAt = now
                // Card borders and the confirm label are animated independently.
                // Accept them when both signals were observed within a short
                // window, even if they did not land on the same Robot frame.
                val signalsCoObserved = lastCardsReadyAt > 0L && lastConfirmReadyAt > 0L &&
                    kotlin.math.abs(lastCardsReadyAt - lastConfirmReadyAt) <= 1_500L
                if (visualReady) {
                    consecutiveReadySamples++
                    readyVisualSamples++
                } else if (signalsCoObserved) {
                    consecutiveReadySamples++
                    readyVisualSamples++
                } else {
                    consecutiveReadySamples = 0
                }
                // Keep this diagnostic explicit: when the client is visibly
                // on the mulligan screen but this function returns false, the
                // next run must tell us which side of the predicate failed.
                if (samples == 1 || samples % 10 == 0 || visualReady) {
                    log.info {
                        "MULLIGAN_UI_PROBE sample=$samples cardsReady=$cardsReady " +
                        "allCardBordersReady=$allCardBordersReady allCardsPainted=$allCardsPainted " +
                        "confirmButtonReady=$confirmButtonReady " +
                        "greenPixels=$greenPixels perCard=$greenPixelsPerCard " +
                        "visiblePixelsPerCard=$visibleCardPixelsPerCard " +
                        "confirmButtonBluePixels=$confirmButtonBluePixels " +
                        "confirmButtonWhitePixels=$confirmButtonWhitePixels " +
                        "consecutiveReadySamples=$consecutiveReadySamples " +
                        "signalsCoObserved=$signalsCoObserved"
                    }
                }
                // Two stable visual samples are enough. Three samples added
                // unnecessary latency and made the check lose the hand when
                // the short confirmation animation changed a frame.
                if (consecutiveReadySamples >= 2) {
                    log.info {
                        "MULLIGAN_UI_READY cards=${cardCenters.size} samples=$samples " +
                        "greenPixels=$greenPixels perCard=$greenPixelsPerCard " +
                        "visiblePixelsPerCard=$visibleCardPixelsPerCard " +
                        "confirmButtonBluePixels=$confirmButtonBluePixels " +
                        "confirmButtonWhitePixels=$confirmButtonWhitePixels " +
                        "minimumGreenPixels=$minimumGreenPixels " +
                        "elapsedMs=${timeoutMillis - (deadline - System.currentTimeMillis())} " +
                        "readyVisualSamples=$readyVisualSamples"
                    }
                    return true
                }
                Thread.sleep(100L)
            }
            log.warn {
                    "MULLIGAN_UI_NOT_READY cards=${cardCenters.size} samples=$samples " +
                    "greenPixelsPerCard=${countGreenMulliganPixelsPerCard(robot.createScreenCapture(bounds), bounds, cardCenters)} " +
                    "visiblePixelsPerCard=${countVisibleCardPixelsPerCard(robot.createScreenCapture(bounds), bounds, cardCenters)} " +
                    "confirmButtonBluePixels=${countConfirmButtonBluePixels(robot.createScreenCapture(bounds), bounds)} " +
                    "confirmButtonWhitePixels=${countConfirmButtonWhitePixels(robot.createScreenCapture(bounds), bounds)} " +
                    "minimumGreenPixels=$minimumGreenPixels " +
                    "minimumGreenPixelsPerCard=$minimumGreenPixelsPerCard " +
                    "minimumVisibleCardPixelsPerCard=$minimumVisibleCardPixelsPerCard " +
                    "minimumConfirmButtonBluePixels=$minimumConfirmButtonBluePixels " +
                    "minimumConfirmButtonWhitePixels=$minimumConfirmButtonWhitePixels timeoutMs=$timeoutMillis"
            }
            false
        }.getOrElse { error ->
            if (error is InterruptedException || Thread.currentThread().isInterrupted) {
                log.info {
                    "MULLIGAN_UI_READY_CANCELLED cards=${cardCenters.size} " +
                        "reason=phase-transition"
                }
            } else {
                log.error(error) { "MULLIGAN_UI_READY_CHECK_FAILED cards=${cardCenters.size}" }
            }
            false
        }
    }

    /** The blue confirm button is absent on the VS/loading transition screen. */
    private fun countConfirmButtonBluePixels(
        image: java.awt.image.BufferedImage,
        bounds: Rectangle,
    ): Int {
        val x0 = (800 - bounds.x).coerceAtLeast(0)
        val x1 = (1120 - bounds.x).coerceAtMost(image.width - 1)
        val y0 = (780 - bounds.y).coerceAtLeast(0)
        val y1 = (930 - bounds.y).coerceAtMost(image.height - 1)
        var bluePixels = 0
        for (y in y0..y1 step 2) {
            for (x in x0..x1 step 2) {
                val rgb = image.getRGB(x, y)
                val red = (rgb ushr 16) and 0xFF
                val green = (rgb ushr 8) and 0xFF
                val blue = rgb and 0xFF
                if (blue >= 140 && blue >= red * 1.15 && blue >= green * 0.90) {
                    bluePixels++
                }
            }
        }
        return bluePixels
    }

    private fun countConfirmButtonWhitePixels(
        image: java.awt.image.BufferedImage,
        bounds: Rectangle,
    ): Int {
        val x0 = (800 - bounds.x).coerceAtLeast(0)
        val x1 = (1120 - bounds.x).coerceAtMost(image.width - 1)
        val y0 = (780 - bounds.y).coerceAtLeast(0)
        val y1 = (930 - bounds.y).coerceAtMost(image.height - 1)
        var whitePixels = 0
        for (y in y0..y1 step 2) {
            for (x in x0..x1 step 2) {
                val rgb = image.getRGB(x, y)
                val red = (rgb ushr 16) and 0xFF
                val green = (rgb ushr 8) and 0xFF
                val blue = rgb and 0xFF
                if (red >= 170 && green >= 170 && blue >= 170) whitePixels++
            }
        }
        return whitePixels
    }

    private fun countGreenMulliganPixelsPerCard(
        image: java.awt.image.BufferedImage,
        bounds: Rectangle,
        cardCenters: List<Point>,
    ): List<Int> = cardCenters.map { center ->
        // Check each card independently.  During the opening animation the
        // aggregate green count can be large even though one of the four card
        // slots has not been painted yet; requiring every slot prevents that
        // false positive while still accepting the lower-contrast glow seen
        // on some Hearthstone boards.
        val x0 = (center.x - 145 - bounds.x).coerceAtLeast(0)
        val x1 = (center.x + 145 - bounds.x).coerceAtMost(image.width - 1)
        val y0 = (center.y - 220 - bounds.y).coerceAtLeast(0)
        val y1 = (center.y + 190 - bounds.y).coerceAtMost(image.height - 1)
        var greenPixels = 0
        for (y in y0..y1 step 2) {
            for (x in x0..x1 step 2) {
                val rgb = image.getRGB(x, y)
                val red = (rgb ushr 16) and 0xFF
                val green = (rgb ushr 8) and 0xFF
                val blue = rgb and 0xFF
                if (green >= 150 && green >= red * 1.35 && green >= blue * 1.15) {
                    greenPixels++
                }
            }
        }
        greenPixels
    }

    private fun countVisibleCardPixelsPerCard(
        image: java.awt.image.BufferedImage,
        bounds: Rectangle,
        cardCenters: List<Point>,
    ): List<Int> = cardCenters.map { center ->
        val x0 = (center.x - 120 - bounds.x).coerceAtLeast(0)
        val x1 = (center.x + 120 - bounds.x).coerceAtMost(image.width - 1)
        val y0 = (center.y - 155 - bounds.y).coerceAtLeast(0)
        val y1 = (center.y + 155 - bounds.y).coerceAtMost(image.height - 1)
        var visiblePixels = 0
        for (y in y0..y1 step 2) {
            for (x in x0..x1 step 2) {
                val rgb = image.getRGB(x, y)
                val red = (rgb ushr 16) and 0xFF
                val green = (rgb ushr 8) and 0xFF
                val blue = rgb and 0xFF
                val maximum = maxOf(red, green, blue)
                val minimum = minOf(red, green, blue)
                if (maximum >= 100 && maximum - minimum >= 35) visiblePixels++
            }
        }
        visiblePixels
    }

    /** Confirm that Hearthstone painted the red replacement X after a click. */
    fun awaitCardSelected(cardCenter: Point, timeoutMillis: Long = 1_200L): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false
        return runCatching {
            val robot = Robot()
            val bounds = screenBounds()
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                val screenshot = robot.createScreenCapture(bounds)
                val x0 = (cardCenter.x - 145 - bounds.x).coerceAtLeast(0)
                val x1 = (cardCenter.x + 145 - bounds.x).coerceAtMost(screenshot.width - 1)
                val y0 = (cardCenter.y - 220 - bounds.y).coerceAtLeast(0)
                val y1 = (cardCenter.y + 190 - bounds.y).coerceAtMost(screenshot.height - 1)
                var redPixels = 0
                for (y in y0..y1 step 2) {
                    for (x in x0..x1 step 2) {
                        val rgb = screenshot.getRGB(x, y)
                        val red = (rgb ushr 16) and 0xFF
                        val green = (rgb ushr 8) and 0xFF
                        val blue = rgb and 0xFF
                        if (red >= 160 && red >= green * 1.4 && red >= blue * 1.4) redPixels++
                    }
                }
                if (redPixels >= 250) return true
                Thread.sleep(100L)
            }
            false
        }.getOrElse { error ->
            if (error is InterruptedException || Thread.currentThread().isInterrupted) {
                log.info { "MULLIGAN_SELECTION_CHECK_CANCELLED center=$cardCenter reason=phase-transition" }
            } else {
                log.error(error) { "MULLIGAN_SELECTION_CHECK_FAILED center=$cardCenter" }
            }
            false
        }
    }

    fun capture(stage: String, gameNumber: Int): File? {
        if (System.getProperty("hs.script.mulligan-screenshot", "true") != "true") return null
        return runCatching {
            if (GraphicsEnvironment.isHeadless()) {
                log.warn { "MULLIGAN_SCREENSHOT_SKIPPED stage=$stage reason=headless" }
                return null
            }
            val directory = File(
                System.getProperty(
                    "hs.script.mulligan-screenshot.dir",
                    File("log", "mulligan").path,
                ),
            )
            if (!directory.exists() && !directory.mkdirs()) {
                log.warn {
                    "MULLIGAN_SCREENSHOT_FAILED stage=$stage reason=mkdir path=${directory.absolutePath}"
                }
                return null
            }
            val bounds = screenBounds()
            if (bounds.width <= 0 || bounds.height <= 0) {
                log.warn { "MULLIGAN_SCREENSHOT_FAILED stage=$stage reason=invalid-bounds bounds=$bounds" }
                return null
            }
            val stamp = synchronized(timestampFormat) { timestampFormat.format(Date()) }
            val safeStage = stage.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(
                directory,
                "game-${gameNumber.toString().padStart(4, '0')}-$safeStage-$stamp.png",
            )
            ImageIO.write(Robot().createScreenCapture(bounds), "png", file)
            log.info {
                "MULLIGAN_SCREENSHOT stage=$stage game=$gameNumber path=${file.absolutePath}"
            }
            file
        }.getOrElse { error ->
            log.warn(error) { "MULLIGAN_SCREENSHOT_FAILED stage=$stage game=$gameNumber" }
            null
        }
    }

    private fun screenBounds(): Rectangle = GraphicsEnvironment
        .getLocalGraphicsEnvironment()
        .screenDevices
        .map { it.defaultConfiguration.bounds }
        .fold(Rectangle()) { all, next -> all.union(next) }
}
