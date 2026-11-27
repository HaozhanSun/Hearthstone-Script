package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MissingGameRestartDispatchTest {
    @Test
    fun `completed serialized restart rearms only from its completion callback`() {
        var completion: (() -> Unit)? = null
        var rearmed = 0
        val result = MissingGameRestartDispatch.dispatch(
            channel = "beta",
            stillAuthorized = { true },
            gameAlive = { false },
            requestRestart = { callback ->
                completion = callback
                true
            },
            onRestartCompleted = { rearmed++ },
        )

        assertEquals(MissingGameRestartDispatch.Result.RESTART_REQUESTED, result)
        assertEquals(0, rearmed, "a restart request is not a new generation until close completes")
        completion!!.invoke()
        assertEquals(1, rearmed)
    }

    @Test
    fun `denied or failed restart cannot rearm a missing-client generation`() {
        var rearmed = 0
        val denied = MissingGameRestartDispatch.dispatch(
            channel = "beta",
            stillAuthorized = { false },
            gameAlive = { false },
            requestRestart = { error("must not request a denied restart") },
            onRestartCompleted = { rearmed++ },
        )
        assertEquals(MissingGameRestartDispatch.Result.BLOCKED, denied)

        val liveGame = MissingGameRestartDispatch.dispatch(
            channel = "beta",
            stillAuthorized = { true },
            gameAlive = { true },
            requestRestart = { error("must not restart a live game") },
            onRestartCompleted = { rearmed++ },
        )
        assertEquals(MissingGameRestartDispatch.Result.BLOCKED, liveGame)

        val failed = MissingGameRestartDispatch.dispatch(
            channel = "beta",
            stillAuthorized = { true },
            gameAlive = { false },
            requestRestart = { false },
            onRestartCompleted = { rearmed++ },
        )
        assertEquals(MissingGameRestartDispatch.Result.BLOCKED, failed)
        assertEquals(0, rearmed)
    }

    @Test
    fun `release candidate cannot invoke the Beta forced restart`() {
        var restartRequests = 0
        var rearmed = 0
        val result = MissingGameRestartDispatch.dispatch(
            channel = "release-candidate",
            stillAuthorized = { true },
            gameAlive = { false },
            requestRestart = {
                restartRequests++
                true
            },
            onRestartCompleted = { rearmed++ },
        )

        assertEquals(MissingGameRestartDispatch.Result.BLOCKED, result)
        assertEquals(0, restartRequests, "RC must not invoke the Beta Core restart seam")
        assertEquals(0, rearmed, "RC must not re-arm the Beta missing-client generation")
    }

    @Test
    fun `release candidate keeps the shared recovery executor`() {
        assertEquals(
            false,
            BetaScreenRecoveryService.missingGameRecoveryDispatchUsesDedicatedExecutorForTest("release-candidate"),
            "RC must retain the prior shared-pool dispatch behavior",
        )
    }
}
