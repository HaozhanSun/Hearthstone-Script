package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcrInFlightGateTest {
    @Test
    fun `duplicate screen recovery is rejected until the first OCR releases`() {
        val gate = OcrInFlightGate()

        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())

        gate.release()

        assertTrue(gate.tryAcquire())
        gate.release()
    }
}
