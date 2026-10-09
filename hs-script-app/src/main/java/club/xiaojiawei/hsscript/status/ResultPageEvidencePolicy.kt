package club.xiaojiawei.hsscript.status

import java.util.Locale

/** Conservative text contract for identifying a terminal result screen. */
internal object ResultPageEvidencePolicy {
    private const val RESULT_CONTINUE_GRAY_LIGHT_MIN = 0.025
    private val outcomeTerms = listOf(
        "胜利", "胜出", "失败", "败北", "战败", "本局结果", "对战结束", "游戏结束",
        "victory", "defeat", "game over", "match over", "you win", "you lose",
    )
    private val explicitTerminalTerms = listOf(
        "本局结果", "对战结束", "游戏结束", "victory", "defeat", "game over", "match over", "you win", "you lose",
    )

    /** A fuzzy standalone “continue” OCR hit is common on live boards and is not terminal evidence. */
    fun looksLikeResultText(ocrText: String): Boolean {
        val text = ocrText.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        if (explicitTerminalTerms.any(text::contains)) return true
        val hasOutcome = outcomeTerms.any(text::contains)
        val hasContinue = text.contains("继续") || text.contains("continue")
        return hasOutcome && hasContinue
    }

    /**
     * Shared visual fallback for both recovery pipelines. The live loss frame
     * has a high-saturation defeat plaque. Rank progression is classified by
     * its own intermediate-screen contract below, not as a terminal result.
     */
    fun looksLikeResultVisual(
        continueGrayLightRatio: Double,
        bannerLowSaturationRatio: Double,
        centerDarkRatio: Double,
        bannerWarmRatio: Double,
    ): Boolean {
        if (continueGrayLightRatio < RESULT_CONTINUE_GRAY_LIGHT_MIN) return false
        // The result plaque has a stable amount of warm artwork in both the
        // rank reward and live defeat captures. Menus and the pre-overlay
        // board are substantially warmer; requiring this independent regional
        // signal avoids broadening the center-dark threshold to fit a single frame.
        if (bannerWarmRatio !in 0.35..0.56) return false
        val legacyOutcomePlaqueSignature = bannerLowSaturationRatio in 0.45..0.60 && centerDarkRatio in 0.24..0.40
        if (legacyOutcomePlaqueSignature) return true

        // Deployed 574 captured a distinct defeat plaque whose centered
        // dark ratio was 0.229 (just below the original 0.24 floor). Keep this
        // alternate signature tight across all four independent regions so
        // the Gold-rank continuation page and ordinary menus remain distinct.
        val liveDefeatPlaqueVariant =
            continueGrayLightRatio in 0.025..0.060 &&
                bannerLowSaturationRatio in 0.47..0.55 &&
                centerDarkRatio in 0.21..0.239 &&
                bannerWarmRatio in 0.39..0.44
        // v4.16.576's authorized current-process capture showed the same
        // result plaque with a darker-centered panel: continue=.040,
        // low-saturation=.511, center-dark=.173, warm=.411. Keep this
        // separate, narrow signature away from the rank-progress signature.
        val lowCenterDarkDefeatPlaqueVariant =
            continueGrayLightRatio in 0.025..0.055 &&
                bannerLowSaturationRatio in 0.48..0.54 &&
                centerDarkRatio in 0.15..0.19 &&
                bannerWarmRatio in 0.39..0.44
        // v4.16.579 terminal-proof capture (1920x1080) retained the same warm
        // defeat banner and continue band, but the broad centered artwork made
        // centerDark=.429. Keep this high-dark variant tightly bounded so the
        // rank-progress panel (.19..28) and ordinary screens remain excluded.
        val highCenterDarkDefeatPlaqueVariant =
            continueGrayLightRatio in 0.035..0.045 &&
                bannerLowSaturationRatio in 0.49..0.52 &&
                centerDarkRatio in 0.41..0.45 &&
                bannerWarmRatio in 0.40..0.43
        return liveDefeatPlaqueVariant || lowCenterDarkDefeatPlaqueVariant || highCenterDarkDefeatPlaqueVariant
    }

    /**
     * Rank progression follows the first result-page Continue input. Its
     * centered medal panel is brighter than a defeat plaque but dimmer than
     * ordinary gameplay; only the calibrated banner, Continue band, and warm
     * medal signature together identify this intermediate page.
     */
    fun looksLikeRankProgressContinuationVisual(
        continueGrayLightRatio: Double,
        bannerLowSaturationRatio: Double,
        centerDarkRatio: Double,
        bannerWarmRatio: Double,
    ): Boolean {
        val capturedRankProgressSignature = continueGrayLightRatio in 0.025..0.09 &&
            bannerLowSaturationRatio in 0.07..0.16 &&
            centerDarkRatio in 0.19..0.28 &&
            bannerWarmRatio in 0.62..0.72
        val establishedRankProgressSignature = continueGrayLightRatio in 0.025..0.09 &&
            bannerLowSaturationRatio in 0.07..0.16 &&
            centerDarkRatio in 0.19..0.28 &&
            bannerWarmRatio in 0.45..0.57
        // The v4.16.577 live Gold 3 reward page after the result dismissal
        // settled at continue=.050, low-saturation=.096, center-dark=.240,
        // warm=.437. Its ribbon is blue/low-saturation while the blurred
        // board is cool, unlike the warm centered defeat-result plaque.
        val coolBoardGoldRewardSignature = continueGrayLightRatio in 0.035..0.055 &&
            bannerLowSaturationRatio in 0.085..0.14 &&
            centerDarkRatio in 0.22..0.25 &&
            bannerWarmRatio in 0.37..0.45
        // v4.16.580's live Gold 4 continuation frame had continue=.049,
        // low-saturation=.086, center-dark=.318, warm=.503. The ornate large
        // medal darkens more of the center than the earlier Gold 3 crop; keep
        // the new range specific to its continue band and muted gold ribbon.
        val largeGoldMedalRewardSignature = continueGrayLightRatio in 0.045..0.055 &&
            bannerLowSaturationRatio in 0.075..0.10 &&
            centerDarkRatio in 0.30..0.34 &&
            bannerWarmRatio in 0.48..0.53
        // v4.16.596's authorized post-surrender Gold 4 reward capture used a
        // narrower/dimmer medal composition: continue=.046, low-saturation=.091,
        // center-dark=.195, warm=.404. Keep this separate from the defeat
        // plaque range above; the low-saturation and dark-center bounds are
        // deliberately tight, while the continue strip and warm medal remain
        // independent required evidence.
        val compactGoldRewardSignature = continueGrayLightRatio in 0.04..0.06 &&
            bannerLowSaturationRatio in 0.075..0.11 &&
            centerDarkRatio in 0.18..0.21 &&
            bannerWarmRatio in 0.38..0.43
        // The v4.16.597 post-Enter capture still showed the same Gold 4
        // reward, now with a warmer board sample: continue=.046, banner=.096,
        // centerDark=.269, warm=.598. This narrow signature keeps that
        // explicitly observed progression frame out of UNKNOWN without
        // broadening the defeat-result classifier.
        val postEnterGoldRewardSignature = continueGrayLightRatio in 0.04..0.055 &&
            bannerLowSaturationRatio in 0.085..0.11 &&
            centerDarkRatio in 0.25..0.285 &&
            bannerWarmRatio in 0.58..0.62
        // v4.16.601's post-surrender Gold 4 continuation had the same
        // stable lower-center Continue band and blue rank ribbon as the
        // prior Gold captures, but a cooler blurred board: continue=.043,
        // banner=.099, centerDark=.263, warm=.400. Keep this four-signal
        // contract narrow so it remains distinct from the defeat plaque
        // (whose banner saturation is materially higher) and normal board.
        val coolPostSurrenderGoldRewardSignature = continueGrayLightRatio in 0.04..0.05 &&
            bannerLowSaturationRatio in 0.085..0.11 &&
            centerDarkRatio in 0.25..0.28 &&
            bannerWarmRatio in 0.38..0.43
        // v4.16.605's authorized Gold 5 post-surrender screen renders a
        // much larger central medal. That leaves less dark background in the
        // center than the earlier Gold-reward variants, while the blue-ribbon
        // / warm-medal composition remains distinct from ordinary gameplay and
        // defeat plaques. Keep all four independent signals narrow: continue=.0434, banner=.0790,
        // centerDark=.1571, warm=.4181 in the captured client frame.
        val largeGoldFiveRewardSignature = continueGrayLightRatio in 0.035..0.055 &&
            bannerLowSaturationRatio in 0.065..0.10 &&
            centerDarkRatio in 0.13..0.18 &&
            bannerWarmRatio in 0.38..0.46
        // v4.16.606's Gold 4 post-match continuation retained the visible
        // Continue band and muted blue rank ribbon, but the larger rank-four
        // medal/board composition measured continue=.047, banner=.088,
        // center-dark=.349, warm=.581. This is deliberately a separate
        // four-region contract: a rank continuation is actionable only after
        // same-game terminal proof and a fresh capture authorize the bounded
        // Continue control; it must not turn an unknown screen into a generic
        // board or result-page click.
        val largeGoldFourRankProgressSignature = continueGrayLightRatio in 0.035..0.055 &&
            bannerLowSaturationRatio in 0.075..0.115 &&
            centerDarkRatio in 0.33..0.38 &&
            bannerWarmRatio in 0.50..0.62
        // v4.16.607 exposed a second Gold 4 presentation after an authorized
        // surrender. This star/progression arrangement measured continue=.043,
        // banner=.147, center-dark=.159, warm=.468: it is visually distinct
        // from the large/dark Gold 4 composition above. Keep it independent
        // and narrow so only the existing proof- and fresh-capture-gated rank
        // Continue path—not a generic fallback—can act on it.
        val goldFourStarProgressSignature = continueGrayLightRatio in 0.035..0.055 &&
            bannerLowSaturationRatio in 0.13..0.16 &&
            centerDarkRatio in 0.13..0.18 &&
            bannerWarmRatio in 0.43..0.50
        // v4.16.608's authorized Platinum 8 progression page preserves the
        // lower-center Continue control but uses a silver badge with a much
        // less-muted banner than the Gold variants.  Its recorded frame was
        // continue=.040, banner=.281, center-dark=.270, warm=.436.  Keep all
        // four regions bounded: this is only an intermediate post-result
        // continuation, never generic board or matchmaking authority.
        val platinumEightRankProgressSignature = continueGrayLightRatio in 0.035..0.055 &&
            bannerLowSaturationRatio in 0.24..0.30 &&
            centerDarkRatio in 0.24..0.30 &&
            bannerWarmRatio in 0.40..0.48
        return capturedRankProgressSignature || establishedRankProgressSignature ||
            coolBoardGoldRewardSignature || largeGoldMedalRewardSignature || compactGoldRewardSignature ||
            postEnterGoldRewardSignature || coolPostSurrenderGoldRewardSignature || largeGoldFiveRewardSignature ||
            largeGoldFourRankProgressSignature || goldFourStarProgressSignature ||
            platinumEightRankProgressSignature
    }

    /** The saved live trace showed GAME_OVER before its authoritative result proof arrived. */
    fun shouldStopSurrenderRetry(authoritativeTerminalState: Boolean, terminalProofAccepted: Boolean): Boolean =
        authoritativeTerminalState || terminalProofAccepted
}
