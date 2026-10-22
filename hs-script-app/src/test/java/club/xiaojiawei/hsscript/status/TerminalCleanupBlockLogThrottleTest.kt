package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TerminalCleanupBlockLogThrottleTest {
    @Test
    fun `repeated terminal cleanup denials emit periodic summaries and stop after an allowed action`() {
        var now = 100L
        val throttle = TerminalCleanupBlockLogThrottle(
            intervalMillis = 15_000L,
            monotonicTimeMillis = { now },
        )

        assertEquals(0, throttle.onBlocked("mouse.left"), "log the first denial")
        now += 900
        assertNull(throttle.onBlocked("mouse.left"), "coalesce repeated denials inside the window")
        now += 13_900
        assertNull(throttle.onBlocked("mouse.left"))
        now += 200
        assertEquals(3, throttle.onBlocked("mouse.left"), "periodically summarize suppressed repeats")
        assertEquals(0, throttle.onBlocked("mouse.right"), "track independent action paths separately")

        throttle.onAllowed("mouse.left")
        now += 1
        assertEquals(0, throttle.onBlocked("mouse.left"), "a later cleanup episode starts with a fresh log")
    }
}
