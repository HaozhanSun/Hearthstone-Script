package club.xiaojiawei.hsscript.status.surrender

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PaddleXRankDetectorTest {
    private val engineProperty = "hs.script.rank-ocr-engine"

    @AfterEach
    fun clearProperties() {
        System.clearProperty(engineProperty)
    }

    @Test
    fun `parses concise bridge JSON and accepts rank ten`() {
        val result = PaddleXRankDetector.parsePayload(
            "PaddleX startup message\n{\"schema_version\":1,\"raw_text\":\"10\",\"rank\":10}"
        )
        assertEquals(10, result?.rank)
        assertEquals("10", result?.rawText)
    }

    @Test
    fun `rejects noisy or missing bridge rank`() {
        assertNull(PaddleXRankDetector.parsePayload("{\"raw_text\":\"1|39\",\"rank\":null}")?.rank)
        assertNull(PaddleXRankDetector.parsePayload("not json"))
    }

    @Test
    fun `engine is opt in and does not alter default detector path`() {
        System.setProperty(engineProperty, "tesseract")
        assertEquals(false, PaddleXRankDetector.isEnabled())
        System.setProperty(engineProperty, "paddlex")
        assertEquals(true, PaddleXRankDetector.isEnabled())
    }
}
