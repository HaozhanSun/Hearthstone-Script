package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Offline replay of the 2026-09-10 17:14 focus incident.  The screenshot
 * capture must be deferred while another app owns foreground, then resume
 * when a user brings a Hearthstone-owned window forward.
 */
class GameWindowReadinessReplayTest {
    @Test
    fun `old handle and another foreground window are not game ready`() {
        assertFalse(
            GameWindowReadiness.sameVisibleGameProcess(
                targetVisible = true,
                foregroundVisible = true,
                targetPid = 35_276,
                foregroundPid = 18_104,
            ),
        )
    }

    @Test
    fun `user click can restore a different Hearthstone owned foreground window`() {
        assertTrue(
            GameWindowReadiness.sameVisibleGameProcess(
                targetVisible = true,
                foregroundVisible = true,
                targetPid = 35_276,
                foregroundPid = 35_276,
            ),
        )
    }

    @Test
    fun `invalid or hidden game window remains unready`() {
        assertFalse(GameWindowReadiness.sameVisibleGameProcess(false, true, 35_276, 35_276))
        assertFalse(GameWindowReadiness.sameVisibleGameProcess(true, false, 35_276, 35_276))
        assertFalse(GameWindowReadiness.sameVisibleGameProcess(true, true, 0, 35_276))
    }
}
