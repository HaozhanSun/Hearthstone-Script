package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryCascadeGuardTest {
    @Test
    fun terminalPauseIsLatchedUntilResume() {
        val guard = RecoveryCascadeGuard()
        assertTrue(guard.trip("startup-handshake-timeout"))
        assertFalse(guard.trip("same-root"))
        assertTrue(guard.suppressWhilePaused(true))
        assertTrue(guard.suppressWhilePaused(true))
        assertFalse(guard.suppressWhilePaused(false))
        assertTrue(guard.trip("new-root-after-resume"))
    }

    @Test
    fun rootAndFollowOnLogLinesAreClassifiedSeparately() {
        assertTrue(
            RecoveryCascadeGuard.classifyLogLine(
                "GAME_STARTUP_STOPPED reason=handshake-timeout",
            ) == RecoveryCascadeGuard.Companion.LogRole.ROOT_CAUSE,
        )
        assertTrue(
            RecoveryCascadeGuard.classifyLogLine(
                "SCREEN_RECOVERY_UNRESOLVED reason=unknown",
            ) == RecoveryCascadeGuard.Companion.LogRole.FOLLOW_ON,
        )
    }
}
