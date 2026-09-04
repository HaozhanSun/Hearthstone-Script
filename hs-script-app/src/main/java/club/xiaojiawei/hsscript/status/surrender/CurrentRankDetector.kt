package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.bean.TesseractEx
import club.xiaojiawei.hsscript.consts.CHI_SIM_DATA
import club.xiaojiawei.hsscript.consts.TESS_DATA_PATH
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.status.UnknownStateScreenshot
import club.xiaojiawei.hsscriptbase.config.log
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale

/**
 * Reads the player's constructed-game rank from the lower-left Hearthstone
 * HUD. The parser is intentionally separate from screen capture so OCR
 * behavior can be tested without a live game window.
 */
object CurrentRankDetector {

    /** Ordered from below the configured Silver 10 floor to above it. */
    enum class RankTier(val order: Int) {
        UNKNOWN(-1),
        BRONZE(0),
        SILVER(1),
        GOLD(2),
        PLATINUM(3),
        DIAMOND(4),
        LEGEND(5),
    }

    private const val MIN_RANK = 1
    private const val MAX_RANK = 10
    // The inner red frame in the supplied 389x341 diagnostic reference was
    // mapped against the recorded legacy frame (0,885,144,140). At the
    // current 1920x1080 layout that maps to (23,941,57,47). This deliberately
    // contains the numeral only, excluding the shield artwork and username.
    private const val RANK_REGION_LEFT = 0.01198
    private const val RANK_REGION_TOP = 0.87130
    private const val RANK_REGION_WIDTH = 0.02969
    private const val RANK_REGION_HEIGHT = 0.04352
    // Tier/Legendary visual detection needs the complete badge frame, while
    // numeric OCR remains confined to RANK_REGION above. This crop excludes
    // the player name and other HUD numbers but retains the shield artwork.
    private const val RANK_BADGE_VISUAL_LEFT = 0.0
    private const val RANK_BADGE_VISUAL_TOP = 0.82
    // The active 1920x1080 screenshot places the badge inside roughly
    // x=0..100,y=885..977. Keep a small safety margin without carrying the
    // player name or the empty lower background into visual classification.
    private const val RANK_BADGE_VISUAL_WIDTH = 0.055
    private const val RANK_BADGE_VISUAL_HEIGHT = 0.085
    private const val RANK_EXPANDED_LEFT = 0.01198
    private const val RANK_EXPANDED_TOP = 0.87130
    private const val RANK_EXPANDED_WIDTH = 0.02969
    private const val RANK_EXPANDED_HEIGHT = 0.04352
    // The former broad crop contained the portrait, shield ornament, rank
    // numeral, and first pixels of the player name. Feeding it to Tesseract
    // produced values such as 939/51/191.
    // This inner window is centered on the white numeral row in the 1920x1080
    // Hearthstone HUD and deliberately excludes the portrait and name.
    private const val RANK_DIGIT_LEFT = 0.018
    private const val RANK_DIGIT_TOP = 0.880
    private const val RANK_DIGIT_WIDTH = 0.018
    private const val RANK_DIGIT_HEIGHT = 0.027
    // The stylized 10 badge is frequently OCR'd as a lone 1.  This narrow
    // inner window excludes the shield border and the player name, leaving
    // only the numeral row for a conservative two-digit layout check.
    private const val RANK_VISUAL_LEFT = 0.26
    private const val RANK_VISUAL_TOP = 0.32
    private const val RANK_VISUAL_WIDTH = 0.30
    private const val RANK_VISUAL_HEIGHT = 0.25
    private const val RANK_TWO_DIGIT_MIN_SPAN = 24

    data class Detection(
        val rank: Int?,
        val tier: RankTier,
        val ocrText: String,
        val confidence: Double?,
        val captureBounds: Rectangle,
    )

    data class RankCandidate(
        val rank: Int,
        val confidence: Double?,
    )

    /** Parse only valid constructed ranks, preventing unrelated HUD numbers from becoming a decision. */
    internal fun parseRankText(rawText: String): Int? {
        val normalized = rawText.map { char ->
            when (char) {
                in '０'..'９' -> ('0'.code + (char.code - '０'.code)).toChar()
                else -> char
            }
        }.joinToString("")
        // Numeric-only means one complete numeric token, not merely one digit
        // somewhere in arbitrary OCR text. Latin letters are rejected because
        // the rank ROI must never turn a username such as "laz8" into rank 8;
        // Chinese/localized prefixes such as "商8" remain valid.
        if (normalized.any { it in 'a'..'z' || it in 'A'..'Z' }) return null
        val numericRuns = Regex("\\d+")
            .findAll(normalized)
            .map { it.value }
            .toList()
        if (numericRuns.size != 1) return null
        val token = numericRuns.single()
        if (token == "10") return 10
        if (token.length != 1) return null
        return token.toIntOrNull()?.takeIf { it in MIN_RANK until MAX_RANK }
    }

    /** Parse an explicit localized/English league name when it is visible. */
    internal fun parseTierText(rawText: String): RankTier {
        val text = rawText.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return when {
            text.contains("白银") || text.contains("白銀") || text.contains("silver") -> RankTier.SILVER
            text.contains("黄金") || text.contains("黃金") || text.contains("gold") -> RankTier.GOLD
            text.contains("青铜") || text.contains("青銅") || text.contains("bronze") -> RankTier.BRONZE
            text.contains("白金") || text.contains("platinum") -> RankTier.PLATINUM
            text.contains("钻石") || text.contains("鑽石") || text.contains("diamond") -> RankTier.DIAMOND
            text.contains("传说") || text.contains("傳說") || text.contains("legend") -> RankTier.LEGEND
            else -> RankTier.UNKNOWN
        }
    }

    /**
     * Select the strongest numeric candidate.  PaddleX may return one text
     * result while legacy OCR returns several passes; both paths use the same
     * deterministic best guess instead of silently becoming "continue".
     * PaddleX's native recognition score is used when supplied; legacy OCR
     * has no native score, so repeated candidates only select the most common
     * number and never become a fabricated confidence value.
     */
    internal fun resolveRankCandidate(
        candidates: List<String>,
        visualTenHint: Boolean = false,
        nativeConfidence: Double? = null,
    ): RankCandidate? {
        val parsed = candidates.mapNotNull(::parseRankText)
        // On the real rank-10 badge, all OCR passes can be contaminated by
        // the shield artwork and return only invalid multi-digit noise (for
        // example 939|51|191|91).  If the independent visual check confirms
        // a two-digit badge and OCR did see numeric pixels, that is still
        // stronger evidence for rank 10 than accepting an arbitrary lower
        // rank.  Keep the numeric-evidence requirement so an empty/blank
        // screen cannot become rank 10 from the visual hint alone.
        if (visualTenHint && parsed.isEmpty() && candidates.any { it.any(Char::isDigit) }) {
            return RankCandidate(rank = 10, confidence = nativeConfidence)
        }
        val counts = parsed.groupingBy { it }.eachCount()
        val best = counts.entries
            .filter { (rank, _) -> rank in MIN_RANK..MAX_RANK }
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { if (it.key == 10) 1 else 0 })
            ?: return null
        return RankCandidate(
            rank = best.key,
            confidence = nativeConfidence,
        )
    }

    internal fun resolveRankCandidates(candidates: List<String>, visualTenHint: Boolean = false): Int? =
        resolveRankCandidate(candidates, visualTenHint)?.rank

    /** Capture and OCR the rank badge without touching the Hearthstone input path. */
    fun detect(
        trigger: String = "current-rank-paddlex-badge",
        phase: String = "pre-mulligan-rank-check",
    ): Detection? = runCatching {
        if (GraphicsEnvironment.isHeadless()) return null
        val allScreens = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
            .fold(Rectangle()) { all, next -> all.union(next) }
        if (allScreens.width <= 0 || allScreens.height <= 0) return null

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
        if (bounds.width < 400 || bounds.height < 300) return null

        val screen = Robot().createScreenCapture(bounds)
        return@runCatching detectCapturedImage(
            screen,
            bounds,
            saveEvidence = true,
            evidenceTrigger = trigger,
            evidencePhase = phase,
        )
    }.getOrElse { error ->
        val provider = if (OcrRuntime.isLegacySelected()) "LEGACY" else "PADDLEX"
        log.warn(error) {
            "RANK_OCR_FAILED provider=$provider trigger=$trigger phase=$phase " +
                "unknownReason=${error.javaClass.simpleName}:${error.message ?: "no-message"}"
        }
        null
    }

    /**
     * Run the same OCR pipeline against a supplied screenshot.  Keeping this
     * separate from Robot capture makes the real saved rank screenshots
     * usable as deterministic regression fixtures.
     */
    internal fun detectCapturedImage(
        screen: BufferedImage,
        bounds: Rectangle = Rectangle(0, 0, screen.width, screen.height),
        saveEvidence: Boolean = false,
        evidenceTrigger: String = "current-rank-paddlex-badge",
        evidencePhase: String = "pre-mulligan-rank-check",
    ): Detection? = runCatching {
        val rankRegion = cropRankRegion(screen)
        val rankRegionBounds = rankBadgeBoundsForTest(screen.width, screen.height)
        val tessData = File(TESS_DATA_PATH)
        val chiSim = File(tessData, "$CHI_SIM_DATA.traineddata")
        if (OcrRuntime.isLegacySelected() && !chiSim.isFile) {
            log.info {
                "RANK_OCR_SKIPPED provider=LEGACY " +
                    "RANK_OCR_ROI x=${rankRegionBounds.x} y=${rankRegionBounds.y} " +
                    "width=${rankRegionBounds.width} height=${rankRegionBounds.height} " +
                    "reason=missing-tessdata path=${chiSim.absolutePath}"
            }
            return null
        }
        // The badge contains Arabic numerals.  Tesseract's English model is
        // substantially more reliable for the outlined 10 than the Chinese
        // model, while the Chinese model remains a compatible fallback for
        // installations that have not downloaded eng.traineddata yet.
        val rankLanguage = if (File(tessData, "eng.traineddata").isFile) "eng" else CHI_SIM_DATA

        val expandedRegion = crop(
            screen,
            RANK_EXPANDED_LEFT,
            RANK_EXPANDED_TOP,
            RANK_EXPANDED_WIDTH,
            RANK_EXPANDED_HEIGHT,
        )
        val badgeVisualRegion = crop(
            screen,
            RANK_BADGE_VISUAL_LEFT,
            RANK_BADGE_VISUAL_TOP,
            RANK_BADGE_VISUAL_WIDTH,
            RANK_BADGE_VISUAL_HEIGHT,
        )
        val digitRegion = crop(
            screen,
            RANK_DIGIT_LEFT,
            RANK_DIGIT_TOP,
            RANK_DIGIT_WIDTH,
            RANK_DIGIT_HEIGHT,
        )
        if (!OcrRuntime.isLegacySelected()) {
            val recognition = OcrRuntime.recognizeResult(
                rankRegion,
                evidenceTrigger,
                legacyOcr = { "" },
                allowEmptyProbeResult = true,
            )
            val rawOcrTexts = listOf(recognition.text)
            val ocrTexts = rawOcrTexts.map(::normalizeOcrText)
            val ocrText = ocrTexts.firstOrNull { it.isNotBlank() }.orEmpty()
            val visualTenHint = !java.lang.Boolean.getBoolean("rank.disable.visual.hint") &&
                looksLikeTwoDigitRank(rankRegion)
            val rankCandidate = resolveRankCandidate(ocrTexts, visualTenHint, recognition.confidence)
            val rank = rankCandidate?.rank
            val confidence = rankCandidate?.confidence
            // PaddleX numeric text stays on the narrow contract above, while
            // the independent visual classifier consumes the complete badge
            // crop so a non-numeric Legendary rating remains observable.
            val tier = detectTierVisual(badgeVisualRegion)
            val unknownReason = unknownReason(rank, tier, ocrTexts)
            log.info {
                "RANK_OCR provider=PADDLEX trigger=$evidenceTrigger phase=$evidencePhase " +
                    "passes=${ocrTexts.size} bounds=$bounds " +
                    "roi=x${rankRegionBounds.x},y${rankRegionBounds.y},w${rankRegionBounds.width},h${rankRegionBounds.height} " +
                    "raw=${rawOcrTexts.joinToString("|") { it.ifBlank { "<empty>" } }} " +
                    "normalized=${ocrText.ifBlank { "<empty>" }} confidence=${formatConfidence(confidence)} " +
                    "candidates=${ocrTexts.joinToString("|") { it.ifBlank { "<empty>" } }} " +
                    "tierCandidates=<visual-only> visualTenHint=$visualTenHint " +
                    "tier=${tier.name} rank=${rank ?: "UNKNOWN"} unknownReason=$unknownReason"
            }
            if (saveEvidence) {
                saveRankEvidence(
                    screen = screen,
                    roiBounds = rankRegionBounds,
                    provider = "PADDLEX",
                    rawOcrTexts = rawOcrTexts,
                    normalizedOcrText = ocrText,
                    numericRank = rank,
                    rank = rank,
                    confidence = confidence,
                    tier = tier,
                    unknownReason = unknownReason,
                    trigger = evidenceTrigger,
                    phase = evidencePhase,
                )
            }
            return Detection(rank, tier, ocrText, confidence, bounds)
        }
        val ocrInputs = listOf(
            // Rank OCR must only see the small numeral window.  Running OCR
            // on the full badge was the source of 939/51/191/91 noise from
            // the portrait and shield artwork.
            digitRegion to 6,
            digitRegion to 7,
            digitRegion to 8,
            digitRegion to 10,
            digitRegion to 11,
            digitRegion to 13,
        )
        val rawOcrTexts = ocrInputs.map { (image, pageSegMode) ->
            ocrRank(image, tessData, pageSegMode, rankLanguage)
        }
        val ocrTexts = rawOcrTexts.map(::normalizeOcrText)
        val ocrText = ocrTexts.firstOrNull { it.isNotBlank() }.orEmpty()
        val visualTenHint = !java.lang.Boolean.getBoolean("rank.disable.visual.hint") &&
            looksLikeTwoDigitRank(rankRegion)
        val rankCandidate = resolveRankCandidate(ocrTexts, visualTenHint)
        val rank = rankCandidate?.rank
        val confidence = rankCandidate?.confidence
        val tierOcrTexts = listOf(rankRegion, expandedRegion).map { image ->
            ocrTier(image, tessData)
        }
        val tierFromOcr = tierOcrTexts
            .asSequence()
            .map(::parseTierText)
            .firstOrNull { it !== RankTier.UNKNOWN }
            ?: RankTier.UNKNOWN
        val tier = if (tierFromOcr !== RankTier.UNKNOWN) {
            tierFromOcr
        } else {
            detectTierVisual(badgeVisualRegion)
        }
        val digitRegionBounds = rankDigitBoundsForTest(screen.width, screen.height)
        val unknownReason = unknownReason(rank, tier, ocrTexts)
        log.info {
            "RANK_OCR provider=LEGACY trigger=$evidenceTrigger phase=$evidencePhase bounds=$bounds " +
                "roi=x${digitRegionBounds.x},y${digitRegionBounds.y},w${digitRegionBounds.width},h${digitRegionBounds.height} " +
                "raw=${rawOcrTexts.joinToString("|") { it.ifBlank { "<empty>" } }} " +
                "normalized=${ocrText.ifBlank { "<empty>" }} confidence=${formatConfidence(confidence)} " +
                "candidates=${ocrTexts.joinToString("|") { it.ifBlank { "<empty>" } }} " +
                "tierCandidates=${tierOcrTexts.joinToString("|") { it.ifBlank { "<empty>" } }} " +
                "visualTenHint=$visualTenHint tier=${tier.name} rank=${rank ?: "UNKNOWN"} unknownReason=$unknownReason"
        }
        if (saveEvidence) {
            saveRankEvidence(
                screen = screen,
                roiBounds = digitRegionBounds,
                provider = "LEGACY",
                rawOcrTexts = rawOcrTexts,
                normalizedOcrText = ocrText,
                numericRank = rank,
                rank = rank,
                confidence = confidence,
                tier = tier,
                unknownReason = unknownReason,
                trigger = evidenceTrigger,
                phase = evidencePhase,
            )
        }
        Detection(rank, tier, ocrText, confidence, bounds)
    }.getOrElse { error ->
        val provider = if (OcrRuntime.isLegacySelected()) "LEGACY" else "PADDLEX"
        log.warn(error) {
            "RANK_OCR_FAILED provider=$provider trigger=$evidenceTrigger phase=$evidencePhase confidence=unavailable " +
                "unknownReason=${error.javaClass.simpleName}:${error.message ?: "no-message"}"
        }
        null
    }

    private fun saveRankEvidence(
        screen: BufferedImage,
        roiBounds: Rectangle,
        provider: String,
        rawOcrTexts: List<String>,
        normalizedOcrText: String,
        numericRank: Int?,
        rank: Int?,
        confidence: Double?,
        tier: RankTier,
        unknownReason: String,
        trigger: String,
        phase: String,
    ) {
        val extractedRank = numericRank?.toString() ?: "UNKNOWN"
        val resolvedRank = rank?.toString() ?: "UNKNOWN"
        val finalDecision = if (rank == null) "UNKNOWN_FAIL_CLOSED" else "RANK_RESOLVED"
        val raw = rawOcrTexts.joinToString("|") { it.ifBlank { "<empty>" } }
        val normalized = normalizedOcrText.ifBlank { "<empty>" }
        val runId = System.getProperty("hs.script.e2e.run-id", "normal")
        val annotationLines = listOf(
            "stage=$phase trigger=$trigger runId=$runId",
            "provider=$provider",
            "roi=x=${roiBounds.x} y=${roiBounds.y} w=${roiBounds.width} h=${roiBounds.height}",
            "rawOCR=$raw",
            "normalizedOCR=$normalized",
            "numericRank=$extractedRank",
            "resolvedRank=$resolvedRank",
            "confidence=${formatConfidence(confidence)}",
            "tier=${tier.name}",
            "unknownReason=$unknownReason",
            "finalDecision=$finalDecision",
        )
        val evidence = UnknownStateScreenshot.save(
            image = screen,
            regions = listOf(
                UnknownStateScreenshot.UnknownRegion(
                    rankBadgeBoundsForTest(screen.width, screen.height),
                    "rank-badge-${finalDecision.lowercase(Locale.ROOT)}",
                ),
            ),
            category = "rank-detection",
            trigger = "$trigger-$finalDecision",
            state = "rank=$resolvedRank|numericRank=$extractedRank|tier=${tier.name}",
            phase = phase,
            ocrText = raw,
            annotationLines = annotationLines,
            logWarning = rank == null,
        )
        val evidencePath = evidence?.file?.absolutePath ?: "not-saved"
        val evidenceMessage = {
            "RANK_OCR_EVIDENCE provider=$provider trigger=$trigger phase=$phase " +
                "numericRank=$extractedRank rank=$resolvedRank tier=${tier.name} " +
                "confidence=${formatConfidence(confidence)} unknownReason=$unknownReason finalDecision=$finalDecision " +
                "path=$evidencePath link=${evidence?.link ?: "none"}"
        }
        if (rank == null) log.warn(evidenceMessage) else log.info(evidenceMessage)
        val roiMessage = {
            "RANK_OCR_ROI provider=$provider trigger=$trigger phase=$phase x=${roiBounds.x} y=${roiBounds.y} " +
                "width=${roiBounds.width} height=${roiBounds.height} raw=$raw " +
                "normalized=$normalized confidence=${formatConfidence(confidence)} unknownReason=$unknownReason " +
                "screenshot=$evidencePath"
        }
        if (rank == null) log.warn(roiMessage) else log.info(roiMessage)
    }

    private fun formatConfidence(confidence: Double?): String =
        confidence?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "unavailable"

    private fun cropRankRegion(image: BufferedImage): BufferedImage {
        return crop(image, RANK_REGION_LEFT, RANK_REGION_TOP, RANK_REGION_WIDTH, RANK_REGION_HEIGHT)
    }

    internal fun rankBadgeBoundsForTest(imageWidth: Int, imageHeight: Int): Rectangle =
        normalizedBounds(
            imageWidth,
            imageHeight,
            RANK_REGION_LEFT,
            RANK_REGION_TOP,
            RANK_REGION_WIDTH,
            RANK_REGION_HEIGHT,
        )

    internal fun rankExpandedBoundsForTest(imageWidth: Int, imageHeight: Int): Rectangle =
        normalizedBounds(
            imageWidth,
            imageHeight,
            RANK_EXPANDED_LEFT,
            RANK_EXPANDED_TOP,
            RANK_EXPANDED_WIDTH,
            RANK_EXPANDED_HEIGHT,
        )

    internal fun rankBadgeVisualBoundsForTest(imageWidth: Int, imageHeight: Int): Rectangle =
        normalizedBounds(
            imageWidth,
            imageHeight,
            RANK_BADGE_VISUAL_LEFT,
            RANK_BADGE_VISUAL_TOP,
            RANK_BADGE_VISUAL_WIDTH,
            RANK_BADGE_VISUAL_HEIGHT,
        )

    internal fun rankDigitBoundsForTest(imageWidth: Int, imageHeight: Int): Rectangle =
        normalizedBounds(
            imageWidth,
            imageHeight,
            RANK_DIGIT_LEFT,
            RANK_DIGIT_TOP,
            RANK_DIGIT_WIDTH,
            RANK_DIGIT_HEIGHT,
        )

    private fun normalizeOcrText(text: String): String = text.replace(Regex("\\s+"), "")

    private fun unknownReason(rank: Int?, tier: RankTier, ocrTexts: List<String>): String = when {
        rank == null && tier === RankTier.UNKNOWN && ocrTexts.all(String::isBlank) -> "empty-text-and-tier"
        rank == null && tier === RankTier.UNKNOWN -> "rank-and-tier-unresolved"
        rank == null -> "rank-unresolved"
        tier === RankTier.UNKNOWN -> "tier-unresolved"
        else -> "none"
    }

    /**
     * Detect the two-digit width of the rank-10 numeral when OCR drops the
     * right-hand zero.  This is deliberately only a ten hint: it never turns
     * an arbitrary one-digit OCR result into a lower rank.
     */
    internal fun looksLikeTwoDigitRank(image: BufferedImage): Boolean {
        val left = (image.width * RANK_VISUAL_LEFT).toInt().coerceIn(0, image.width - 1)
        val top = (image.height * RANK_VISUAL_TOP).toInt().coerceIn(0, image.height - 1)
        val right = (image.width * (RANK_VISUAL_LEFT + RANK_VISUAL_WIDTH))
            .toInt().coerceAtMost(image.width)
        val bottom = (image.height * (RANK_VISUAL_TOP + RANK_VISUAL_HEIGHT))
            .toInt().coerceAtMost(image.height)
        val occupiedColumns = (left until right).filter { x ->
            var foregroundPixels = 0
            for (y in top until bottom) {
                val color = image.getRGB(x, y)
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                val brightest = maxOf(red, green, blue)
                val darkest = minOf(red, green, blue)
                if (brightest >= 180 && brightest - darkest <= 70) {
                    foregroundPixels++
                }
            }
            foregroundPixels >= 2
        }
        if (occupiedColumns.isEmpty()) return false
        val span = occupiedColumns.last() - occupiedColumns.first() + 1
        return span >= RANK_TWO_DIGIT_MIN_SPAN && occupiedColumns.size >= RANK_TWO_DIGIT_MIN_SPAN / 2
    }

    /**
     * The in-game HUD normally shows only the numeric badge, not the league
     * word. Gold uses a warm yellow metal frame while Silver is near-neutral;
     * sample the badge perimeter and ignore the portrait/number interior.
     */
    internal fun detectTierVisual(image: BufferedImage): RankTier {
        if (detectLegendaryBadgeVisual(image)) return RankTier.LEGEND
        val right = (image.width * 0.72).toInt().coerceAtMost(image.width)
        val bottom = (image.height * 0.82).toInt().coerceAtMost(image.height)
        val insetX = (image.width * 0.18).toInt()
        val insetY = (image.height * 0.18).toInt()
        var gold = 0
        var silver = 0
        var metal = 0
        for (y in 0 until bottom) {
            for (x in 0 until right) {
                if (x in insetX until right - insetX && y in insetY until bottom - insetY) continue
                val rgb = image.getRGB(x, y)
                val red = rgb shr 16 and 0xff
                val green = rgb shr 8 and 0xff
                val blue = rgb and 0xff
                val maximum = maxOf(red, green, blue)
                val minimum = minOf(red, green, blue)
                if (maximum < 130) continue
                if (red >= 135 && green >= 95 && blue <= 105 && red > blue * 1.35 && green > blue * 1.15) {
                    gold++
                    metal++
                } else if (maximum - minimum <= 32) {
                    silver++
                    metal++
                }
            }
        }
        if (metal < 12) return RankTier.UNKNOWN
        if (gold >= 12 && gold >= silver * 1.25) return RankTier.GOLD
        if (silver >= 12 && silver >= gold * 1.25) return RankTier.SILVER
        return RankTier.UNKNOWN
    }

    /**
     * Detect the constructed-game Legendary badge when its rating is not a
     * 1..10 rank.  The probe is deliberately geometric and badge-local:
     * Legendary has a saturated red/orange field around a warm-metal center,
     * while a red UI overlay has no metal evidence and a normal gold badge
     * has no broad red outer field.  HSB keeps the test stable across modest
     * brightness changes without accepting arbitrary red pixels.
     */
    internal data class LegendaryVisualMetrics(
        val warmPixels: Int,
        val scannedPixels: Int,
        val warmBounds: Rectangle?,
        val activeColumnCount: Int,
        val digitSegments: Int,
        val digitSpan: Int,
        val confirmed: Boolean,
    )

    internal fun detectLegendaryBadgeVisual(image: BufferedImage): Boolean =
        legendaryVisualMetrics(image).confirmed

    internal fun legendaryVisualMetrics(image: BufferedImage): LegendaryVisualMetrics {
        if (image.width < 40 || image.height < 40) {
            return LegendaryVisualMetrics(0, 0, null, 0, 0, 0, false)
        }
        val scanRight = (image.width * 0.80).toInt().coerceAtMost(image.width)
        val scanBottom = (image.height * 0.82).toInt().coerceAtMost(image.height)
        var warm = 0
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until scanBottom) {
            for (x in 0 until scanRight) {
                val rgb = image.getRGB(x, y)
                val red = rgb ushr 16 and 0xff
                val green = rgb ushr 8 and 0xff
                val blue = rgb and 0xff
                val hsb = Color.RGBtoHSB(red, green, blue, null)
                if (hsb[0] * 360f in 22f..68f && hsb[1] >= 0.28f && hsb[2] >= 0.25f) {
                    warm++
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        val scannedPixels = scanRight * scanBottom
        if (maxX < minX || maxY < minY) {
            return LegendaryVisualMetrics(warm, scannedPixels, null, 0, 0, 0, false)
        }
        val warmBounds = Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1)
        if (warmBounds.width < 55 || warmBounds.height < 45 ||
            warm.toDouble() / scannedPixels < 0.12
        ) {
            return LegendaryVisualMetrics(warm, scannedPixels, warmBounds, 0, 0, 0, false)
        }

        // The badge frame is saturated gold, while the rating glyphs are
        // pale/near-neutral. Count bright columns rather than requiring
        // artificial gaps between adjacent glyphs after client scaling.
        val digitLeft = warmBounds.x + (warmBounds.width * 0.08).toInt()
        val digitTop = warmBounds.y + (warmBounds.height * 0.16).toInt()
        val digitBottom = (warmBounds.y + warmBounds.height * 0.78).toInt().coerceAtMost(scanBottom)
        val activeColumns = mutableListOf<Int>()
        val digitRight = minOf(warmBounds.x + warmBounds.width, scanRight)
        for (x in digitLeft until digitRight) {
            var bright = 0
            for (y in digitTop until digitBottom) {
                val rgb = image.getRGB(x, y)
                val red = rgb ushr 16 and 0xff
                val green = rgb ushr 8 and 0xff
                val blue = rgb and 0xff
                val hsb = Color.RGBtoHSB(red, green, blue, null)
                if (hsb[2] >= 0.70f && hsb[1] <= 0.55f && red + green + blue > 420) bright++
            }
            if (bright >= 3) activeColumns += x
        }
        val strongColorSignature = legacyColorSignature(image)
        if (activeColumns.isEmpty()) {
            return LegendaryVisualMetrics(warm, scannedPixels, warmBounds, 0, 0, 0, strongColorSignature)
        }
        val segments = mutableListOf<Int>()
        var start = activeColumns.first()
        var previous = start
        for (column in activeColumns.drop(1)) {
            if (column > previous + 3) {
                segments += previous - start + 1
                start = column
            }
            previous = column
        }
        segments += previous - start + 1
        val digitSegments = segments.count { it in 10..35 }
        val digitSpan = activeColumns.last() - activeColumns.first() + 1
        val confirmedByGlyphs = activeColumns.size >= 50 && digitSpan >= 60
        val confirmed = strongColorSignature || confirmedByGlyphs
        return LegendaryVisualMetrics(
            warmPixels = warm,
            scannedPixels = scannedPixels,
            warmBounds = warmBounds,
            activeColumnCount = activeColumns.size,
            digitSegments = digitSegments,
            digitSpan = digitSpan,
            confirmed = confirmed,
        )
    }

    private fun legacyColorSignature(image: BufferedImage): Boolean {
        val centerLeft = (image.width * 0.22).toInt()
        val centerTop = (image.height * 0.18).toInt()
        val centerRight = (image.width * 0.78).toInt().coerceAtMost(image.width)
        val centerBottom = (image.height * 0.82).toInt().coerceAtMost(image.height)
        var centerPixels = 0
        var outerPixels = 0
        var warmMetal = 0
        var warmCenter = 0
        var warmOuter = 0
        var redOuter = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val center = x in centerLeft until centerRight && y in centerTop until centerBottom
                val rgb = image.getRGB(x, y)
                val hsb = Color.RGBtoHSB(rgb ushr 16 and 0xff, rgb ushr 8 and 0xff, rgb and 0xff, null)
                val hue = hsb[0] * 360f
                val warm = hue in 22f..68f && hsb[1] >= 0.28f && hsb[2] >= 0.25f
                val saturatedRed = (hue <= 18f || hue >= 345f) && hsb[1] >= 0.38f && hsb[2] >= 0.20f
                if (center) {
                    centerPixels++
                    if (warm) warmCenter++
                } else {
                    outerPixels++
                    if (warm) warmOuter++
                    if (saturatedRed) redOuter++
                }
                if (warm) warmMetal++
            }
        }
        if (centerPixels == 0 || outerPixels == 0) return false
        val warmRatio = warmMetal.toDouble() / (centerPixels + outerPixels)
        val warmCenterRatio = warmCenter.toDouble() / centerPixels
        val warmOuterRatio = warmOuter.toDouble() / outerPixels
        val redOuterRatio = redOuter.toDouble() / outerPixels
        return warmRatio >= 0.12 &&
            warmCenterRatio >= 0.12 &&
            redOuterRatio >= 0.22 &&
            warmCenterRatio >= warmOuterRatio * 0.55
    }

    private fun crop(
        image: BufferedImage,
        left: Double,
        top: Double,
        widthRatio: Double,
        heightRatio: Double,
    ): BufferedImage {
        val bounds = normalizedBounds(image.width, image.height, left, top, widthRatio, heightRatio)
        return image.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height)
    }

    private fun normalizedBounds(
        imageWidth: Int,
        imageHeight: Int,
        left: Double,
        top: Double,
        widthRatio: Double,
        heightRatio: Double,
    ): Rectangle {
        require(imageWidth > 0 && imageHeight > 0) { "image dimensions must be positive" }
        val x = (imageWidth * left).toInt().coerceIn(0, imageWidth - 1)
        val y = (imageHeight * top).toInt().coerceIn(0, imageHeight - 1)
        val right = (imageWidth * (left + widthRatio)).toInt().coerceAtMost(imageWidth)
        val bottom = (imageHeight * (top + heightRatio)).toInt().coerceAtMost(imageHeight)
        return Rectangle(
            x,
            y,
            (right - x).coerceAtLeast(1),
            (bottom - y).coerceAtLeast(1),
        )
    }

    private fun ocrRank(image: BufferedImage, tessData: File, pageSegMode: Int, language: String): String {
        fun run(
            input: BufferedImage,
            suffix: String,
            whitelist: String = "0123456789",
        ): String = TesseractEx().apply {
            setDatapath(tessData.absolutePath)
            setLanguage(language)
            setPageSegMode(pageSegMode)
            setVariable("tessedit_char_whitelist", whitelist)
            setVariable("user_defined_dpi", "300")
        }.doOCR(
            input,
            "current-rank-psm$pageSegMode-$suffix",
            allowEmptyProbeResult = true,
        )

        if (pageSegMode == 10) {
            // The badge's outlined 1 and 0 touch visually after scaling. OCR
            // each half of the same tight box as one character, then join
            // only a clean 1+0 pair. This is still OCR-only recognition and
            // avoids any template/image matching.
            // The numeral 1 is narrower than the 0.  Splitting the same
            // tight OCR input gives Tesseract a single-character view when
            // the full two-character badge is difficult to segment.
            val split = (image.width * 0.45).toInt().coerceIn(1, image.width - 1)
            val overlap = 2
            val left = image.getSubimage(0, 0, (split + overlap).coerceAtMost(image.width), image.height)
            val rightStart = (split - overlap).coerceAtLeast(0)
            val right = image.getSubimage(rightStart, 0, image.width - rightStart, image.height)
            val leftTexts = listOf(
                run(prepareRankOcrImage(left), "left-one-mask", whitelist = "1"),
                run(scaleForOcr(left, 4), "left-one-raw", whitelist = "1"),
            )
            val rightTexts = listOf(
                run(prepareRankOcrImage(right), "right-zero-mask", whitelist = "0"),
                run(scaleForOcr(right, 4), "right-zero-raw", whitelist = "0"),
            )
            val leftIsOne = leftTexts.any { text -> text.filter(Char::isDigit) == "1" }
            val rightIsZero = rightTexts.any { text -> text.filter(Char::isDigit) == "0" }
            if (leftIsOne && rightIsZero) return "10"
        }

        // Keep both views inside the same tight digit box.  The mask removes
        // the colored badge background, while the raw view preserves the
        // antialiased outline when the glyph is too thin for thresholding.
        return listOf(
            run(prepareRankOcrImage(image), "mask"),
            run(scaleForOcr(image, 4), "raw"),
        ).firstOrNull(String::isNotBlank).orEmpty()
    }

    private fun ocrTier(image: BufferedImage, tessData: File): String =
        TesseractEx().apply {
            setDatapath(tessData.absolutePath)
            setLanguage(CHI_SIM_DATA)
            setPageSegMode(11)
            setVariable("user_defined_dpi", "200")
        }.doOCR(
            scaleForOcr(image),
            "current-tier",
            allowEmptyProbeResult = true,
        )
            .replace(Regex("\\s+"), "")

    /**
     * Keep the OCR input limited to the numeral box, but make the white
     * outlined glyph readable against the colored badge background.  This is
     * deliberately plain BufferedImage processing; it does not use a visual
     * template or OpenCV.
     */
    private fun prepareRankOcrImage(image: BufferedImage): BufferedImage {
        val scale = 4
        val padding = 20
        val scaled = BufferedImage(
            image.width * scale + padding * 2,
            image.height * scale + padding * 2,
            BufferedImage.TYPE_BYTE_GRAY,
        )
        val graphics = scaled.createGraphics()
        graphics.color = java.awt.Color.WHITE
        graphics.fillRect(0, 0, scaled.width, scaled.height)
        graphics.dispose()
        for (y in padding until scaled.height - padding) {
            for (x in padding until scaled.width - padding) {
                val source = image.getRGB((x - padding) / scale, (y - padding) / scale)
                val red = source shr 16 and 0xff
                val green = source shr 8 and 0xff
                val blue = source and 0xff
                val brightest = maxOf(red, green, blue)
                val darkest = minOf(red, green, blue)
                // The numeral has a bright, nearly neutral face.  Darken
                // those pixels and leave the colored/dark badge as white so
                // Tesseract sees a clean black-on-white digit mask.
                val foreground = brightest >= 155 && brightest - darkest <= 105
                val value = if (foreground) 0 else 255
                val gray = (value shl 16) or (value shl 8) or value
                scaled.setRGB(x, y, gray)
            }
        }
        return scaled
    }

    private fun scaleForOcr(image: BufferedImage, scale: Int = 2): BufferedImage {
        val width = (image.width * scale).coerceAtLeast(1)
        val height = (image.height * scale).coerceAtLeast(1)
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = scaled.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC,
            )
            graphics.drawImage(image, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return scaled
    }
}
