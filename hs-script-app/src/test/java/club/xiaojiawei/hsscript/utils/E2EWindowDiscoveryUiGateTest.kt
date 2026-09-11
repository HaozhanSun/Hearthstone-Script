package club.xiaojiawei.hsscript.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class E2EWindowDiscoveryUiGateTest {

    @Test
    fun `identical discovery event appears only once in the compact feed`() {
        val gate = E2EWindowDiscoveryUiGate()
        val event = "E2E_WINDOW_DISCOVERY state=FOUND handle=native@0x100 pid=33552 process=Hearthstone.exe"

        assertTrue(gate.shouldShow(event))
        assertFalse(gate.shouldShow(event))
    }

    @Test
    fun `window change and failure recovery remain visible`() {
        val gate = E2EWindowDiscoveryUiGate()

        assertTrue(gate.shouldShow("E2E_WINDOW_DISCOVERY state=FOUND handle=native@0x100 pid=33552"))
        assertTrue(gate.shouldShow("E2E_WINDOW_DISCOVERY state=MISSING handle=null pid=0"))
        assertTrue(gate.shouldShow("E2E_WINDOW_DISCOVERY state=FOUND handle=native@0x200 pid=33552"))
    }
}
