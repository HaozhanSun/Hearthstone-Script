package club.xiaojiawei.hsscript.status

import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultPageEvidencePolicyTest {
    @Test
    fun `live Beta Platinum 9 reward fixture outranks Event Quest classifier`() {
        val image = requireNotNull(ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/beta-live-platinum9-rank-reward-20261121.png",
        ))))
        assertTrue(
            ResultPageEvidencePolicy.looksLikeRankProgressContinuationVisual(
                continueGrayLightRatio = 0.043,
                bannerLowSaturationRatio = 0.254,
                centerDarkRatio = 0.164,
                bannerWarmRatio = 0.394,
            ),
            "the captured Platinum 9 Continue/ribbon/panel signature must be admitted before generic modal detection",
        )
        assertEquals(
            "RANK_PROGRESS_CONTINUATION",
            UpstreamScreenStateRecovery.classifyImageForResultFixture(image),
            "the actual live reward screenshot must not fall through to EVENT_QUEST_OVERLAY",
        )
        assertTrue(
            StartupQuestOverlayVisualClassifier.isQuestOverlay(image),
            "the regression must prove ordering: this frame still matches the broad modal geometry, but rank evidence wins first",
        )
    }

    @Test
    fun `v4 16 679 defeat screenshot signature is recognized before OCR`() {
        val observation = requireNotNull(
            ResultPageEvidencePolicy.fastVisualObservation(
                continueGrayLightRatio = 0.040,
                bannerLowSaturationRatio = 0.492,
                centerDarkRatio = 0.277,
                bannerWarmRatio = 0.402,
            ),
        )

        assertTrue(observation.resultVisible == true)
        assertTrue(observation.continueControlVisible)
        assertFalse(observation.rankProgressVisible)
    }

    @Test
    fun `generic continue OCR on gameplay is not terminal evidence`() {
        assertFalse(ResultPageEvidencePolicy.looksLikeResultText(":字XX示盾>|||||-二于7.扣击继续"))
        assertFalse(ResultPageEvidencePolicy.looksLikeResultText("点击继续"))
        assertFalse(ResultPageEvidencePolicy.looksLikeResultText("Continue"))
    }

    @Test
    fun `explicit result outcome with continue remains terminal evidence`() {
        assertTrue(ResultPageEvidencePolicy.looksLikeResultText("败北 点击继续"))
        assertTrue(ResultPageEvidencePolicy.looksLikeResultText("Victory - Continue"))
        assertTrue(ResultPageEvidencePolicy.looksLikeResultText("对战结束"))
    }

    @Test
    fun `authoritative terminal phase blocks surrender retries before result proof settles`() {
        assertTrue(ResultPageEvidencePolicy.shouldStopSurrenderRetry(authoritativeTerminalState = true, terminalProofAccepted = false))
        assertTrue(ResultPageEvidencePolicy.shouldStopSurrenderRetry(authoritativeTerminalState = false, terminalProofAccepted = true))
        assertFalse(ResultPageEvidencePolicy.shouldStopSurrenderRetry(authoritativeTerminalState = false, terminalProofAccepted = false))
    }

    @Test
    fun `visual-only terminal result contract excludes rank progression menus and pre-overlay frame`() {
        assertTrue(ResultPageEvidencePolicy.looksLikeResultVisual(0.040, 0.486, 0.356, 0.427))
        assertTrue(ResultPageEvidencePolicy.looksLikeResultVisual(0.040, 0.511, 0.173, 0.411))
        assertFalse(ResultPageEvidencePolicy.looksLikeResultVisual(0.040, 0.511, 0.149, 0.411))
        assertFalse(ResultPageEvidencePolicy.looksLikeResultVisual(0.040, 0.511, 0.191, 0.411))
        assertFalse(ResultPageEvidencePolicy.looksLikeResultVisual(0.049, 0.087, 0.210, 0.497))
        assertTrue(ResultPageEvidencePolicy.looksLikeRankProgressContinuationVisual(0.049, 0.087, 0.210, 0.497))
        assertTrue(ResultPageEvidencePolicy.looksLikeRankProgressContinuationVisual(0.043, 0.106, 0.233, 0.522))
        assertTrue(ResultPageEvidencePolicy.looksLikeRankProgressContinuationVisual(0.047, 0.090, 0.205, 0.649))
        assertFalse(ResultPageEvidencePolicy.looksLikeRankProgressContinuationVisual(0.047, 0.090, 0.205, 0.76))
        assertTrue(ResultPageEvidencePolicy.looksLikeRankProgressContinuationVisual(0.050, 0.096, 0.240, 0.437))
        assertFalse(ResultPageEvidencePolicy.looksLikeRankProgressContinuationVisual(0.050, 0.096, 0.219, 0.437))
        assertFalse(ResultPageEvidencePolicy.looksLikeRankProgressContinuationVisual(0.050, 0.141, 0.240, 0.437))
        assertFalse(ResultPageEvidencePolicy.looksLikeResultVisual(0.050, 0.096, 0.240, 0.437))
        assertFalse(ResultPageEvidencePolicy.looksLikeResultVisual(0.012, 0.276, 0.259, 0.732))
        assertFalse(ResultPageEvidencePolicy.looksLikeResultVisual(0.010, 0.486, 0.261, 0.427))
        assertFalse(ResultPageEvidencePolicy.looksLikeResultVisual(0.040, 0.486, 0.401, 0.427))
    }

    @Test
    fun `fast authorized continue observation skips OCR and preserves rank visual classification`() {
        val unknownDefeat = requireNotNull(ResultPageEvidencePolicy.fastVisualObservation(
            continueGrayLightRatio = 0.045,
            bannerLowSaturationRatio = 0.179,
            centerDarkRatio = 0.132,
            bannerWarmRatio = 0.313,
        ))
        assertTrue(unknownDefeat.continueControlVisible)
        assertTrue(unknownDefeat.resultVisible == null, "the known Continue band permits the bounded unknown-page fallback")
        assertFalse(unknownDefeat.rankProgressVisible)

        val rankProgress = requireNotNull(ResultPageEvidencePolicy.fastVisualObservation(
            continueGrayLightRatio = 0.050,
            bannerLowSaturationRatio = 0.096,
            centerDarkRatio = 0.240,
            bannerWarmRatio = 0.437,
        ))
        assertTrue(rankProgress.rankProgressVisible, "rank continuation remains distinct from defeat")
        assertTrue(rankProgress.continueControlVisible)
        assertTrue(
            ResultPageEvidencePolicy.fastVisualObservation(0.010, 0.20, 0.20, 0.20) == null,
            "a frame without the calibrated band must use the slower destination-classification path",
        )
    }

    @Test
    fun `v4 16 765 platinum nine and ten loss rank pages are recognized despite unknown OCR`() {
        listOf(
            // Platinum 10 screenshot: unknown-state-20261120-221302-677.
            doubleArrayOf(0.04183, 0.28479, 0.21924, 0.34319),
            // Platinum 9 screenshot: debug-20261120-225202-308.
            doubleArrayOf(0.04563, 0.26138, 0.20212, 0.36981),
        ).forEach { (continueBand, banner, centerDark, bannerWarm) ->
            val observation = requireNotNull(ResultPageEvidencePolicy.fastVisualObservation(
                continueGrayLightRatio = continueBand,
                bannerLowSaturationRatio = banner,
                centerDarkRatio = centerDark,
                bannerWarmRatio = bannerWarm,
            ))

            assertTrue(observation.rankProgressVisible)
            assertTrue(observation.resultVisible == true)
            assertTrue(observation.continueControlVisible)
        }
    }

    @Test
    fun `v705 gold victory metrics stay unknown but preserve the visible continue control`() {
        val fixture = requireNotNull(
            javaClass.getResourceAsStream("/offline-ocr/result-flow/rc-v705-terminal-recovery/incident-evidence.txt"),
        ).bufferedReader().use { it.readLines().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap() }
        val observation = requireNotNull(ResultPageEvidencePolicy.fastVisualObservation(
            continueGrayLightRatio = fixture.getValue("victory.continueGrayLightRatio").toDouble(),
            bannerLowSaturationRatio = fixture.getValue("victory.bannerLowSaturationRatio").toDouble(),
            centerDarkRatio = fixture.getValue("victory.centerDarkRatio").toDouble(),
            bannerWarmRatio = fixture.getValue("victory.bannerWarmRatio").toDouble(),
        ))
        assertNull(observation.resultVisible, "the gold Victory banner is not one of the calibrated defeat/rank signatures")
        assertFalse(observation.rankProgressVisible)
        assertTrue(observation.continueControlVisible, "fresh authorized capture still permits a bounded Continue retry")
    }
}
