package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResultPageEvidencePolicyTest {
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
}
