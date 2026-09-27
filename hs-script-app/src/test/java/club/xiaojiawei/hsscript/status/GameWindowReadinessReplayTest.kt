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

    @Test
    fun `capture is rejected when foreground changes to another app`() {
        assertFalse(
            GameWindowReadiness.captureRemainsOnGame(
                targetVisible = true,
                foregroundVisibleBefore = true,
                foregroundVisibleAfter = true,
                targetPid = 35_276,
                foregroundPidBefore = 35_276,
                foregroundPidAfter = 18_104,
            ),
        )
    }

    @Test
    fun `capture is accepted only when both edges remain game foreground`() {
        assertTrue(
            GameWindowReadiness.captureRemainsOnGame(
                targetVisible = true,
                foregroundVisibleBefore = true,
                foregroundVisibleAfter = true,
                targetPid = 35_276,
                foregroundPidBefore = 35_276,
                foregroundPidAfter = 35_276,
            ),
        )
    }

    @Test
    fun `same process is insufficient when a different Hearthstone window owns foreground`() {
        assertFalse(
            GameWindowReadiness.exactVisibleForeground(
                targetVisible = true,
                foregroundVisible = true,
                targetHandle = 0x100L,
                foregroundHandle = 0x200L,
            ),
        )
    }

    @Test
    fun `exact target handle must remain visible foreground`() {
        assertTrue(
            GameWindowReadiness.exactVisibleForeground(
                targetVisible = true,
                foregroundVisible = true,
                targetHandle = 0x100L,
                foregroundHandle = 0x100L,
            ),
        )
        assertFalse(GameWindowReadiness.exactVisibleForeground(true, false, 0x100L, 0x100L))
    }
}
