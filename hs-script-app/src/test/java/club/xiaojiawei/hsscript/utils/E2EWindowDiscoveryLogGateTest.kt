package club.xiaojiawei.hsscript.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class E2EWindowDiscoveryLogGateTest {

    @Test
    fun `repeated successful discovery is debug after the first info`() {
        val gate = E2EWindowDiscoveryLogGate()

        assertEquals(
            E2EWindowDiscoveryLogGate.Decision.INFO_STATE_CHANGE,
            gate.classify(E2EWindowDiscoveryLogGate.State.FOUND, "native@0x100", 33_552),
        )
        assertEquals(
            E2EWindowDiscoveryLogGate.Decision.DEBUG_DUPLICATE,
            gate.classify(E2EWindowDiscoveryLogGate.State.FOUND, "native@0x100", 33_552),
        )
    }

    @Test
    fun `window handle or process change is retained as an info transition`() {
        val gate = E2EWindowDiscoveryLogGate()
        gate.classify(E2EWindowDiscoveryLogGate.State.FOUND, "native@0x100", 33_552)

        assertEquals(
            E2EWindowDiscoveryLogGate.Decision.INFO_STATE_CHANGE,
            gate.classify(E2EWindowDiscoveryLogGate.State.FOUND, "native@0x200", 33_552),
        )
        assertEquals(
            E2EWindowDiscoveryLogGate.Decision.INFO_STATE_CHANGE,
            gate.classify(E2EWindowDiscoveryLogGate.State.FOUND, "native@0x200", 35_276),
        )
    }

    @Test
    fun `failure and later recovery are both retained`() {
        val gate = E2EWindowDiscoveryLogGate()
        gate.classify(E2EWindowDiscoveryLogGate.State.FOUND, "native@0x100", 33_552)

        assertEquals(
            E2EWindowDiscoveryLogGate.Decision.WARN_FAILURE_CHANGE,
            gate.classify(E2EWindowDiscoveryLogGate.State.MISSING, null, null),
        )
        assertEquals(
            E2EWindowDiscoveryLogGate.Decision.DEBUG_DUPLICATE,
            gate.classify(E2EWindowDiscoveryLogGate.State.MISSING, null, null),
        )
        assertEquals(
            E2EWindowDiscoveryLogGate.Decision.INFO_STATE_CHANGE,
            gate.classify(E2EWindowDiscoveryLogGate.State.FOUND, "native@0x300", 33_552),
        )
    }
}
