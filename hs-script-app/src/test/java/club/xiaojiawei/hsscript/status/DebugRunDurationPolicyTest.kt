package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DebugRunDurationPolicyTest {
    @Test
    fun `accepts useful durations including approved forty five minutes`() {
        assertEquals(10, DebugRunDurationPolicy.parseMinutes("10"))
        assertEquals(30, DebugRunDurationPolicy.parseMinutes(" 30 "))
        assertEquals(45, DebugRunDurationPolicy.parseMinutes("45"))
        assertEquals(2_700_000L, DebugRunDurationPolicy.toMillis(45))
    }

    @Test
    fun `rejects blank malformed zero negative and values above the hard bound`() {
        listOf(null, "", "  ", "abc", "0", "-1", "46", "90").forEach {
            assertNull(DebugRunDurationPolicy.parseMinutes(it), "input=$it")
        }
        assertEquals(30, DebugRunDurationPolicy.normalizeMinutes(0))
        assertEquals(30, DebugRunDurationPolicy.normalizeMinutes(46))
        assertNull(DebugRunDurationPolicy.toMillis(46))
    }
}
