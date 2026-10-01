package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NoProgressRecoveryDispatchTest {

    @Test
    fun `restart recovers mode then invokes configured starter chain`() {
        val events = mutableListOf<String>()

        NoProgressRecoveryDispatch.restartToStartup(
            recoverModeToStartup = { events += "mode-startup" },
            startConfiguredStarterChain = { events += "starter-chain-start" },
        )

        assertEquals(listOf("mode-startup", "starter-chain-start"), events)
    }
}
