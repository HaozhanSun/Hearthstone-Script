package club.xiaojiawei.hsscript.utils

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameEndTaskRegistryTest {
    private val executor = ScheduledThreadPoolExecutor(1)

    @AfterEach
    fun shutdownExecutor() {
        executor.shutdownNow()
    }

    @Test
    fun `in-flight surrender recovery request is not terminal-state evidence`() {
        val registry = GameEndTaskRegistry()
        val surrenderRetry = executor.schedule({}, 1, TimeUnit.DAYS)
        registry.add(surrenderRetry, GameEndTaskRegistry.Kind.SURRENDER_RECOVERY)

        assertTrue(registry.isNotEmpty())
        assertTrue(registry.hasSurrenderRecoveryTask())
        assertFalse(registry.hasTerminalPageTask())

        val terminalPage = executor.schedule({}, 1, TimeUnit.DAYS)
        registry.add(terminalPage, GameEndTaskRegistry.Kind.TERMINAL_PAGE)
        assertTrue(registry.hasTerminalPageTask())

        registry.cancel(terminalPage)
        assertFalse(registry.hasTerminalPageTask())
        assertTrue(registry.hasSurrenderRecoveryTask())

        registry.cancel(surrenderRetry)
        assertFalse(registry.hasSurrenderRecoveryTask())
        assertFalse(registry.isNotEmpty())
    }
}
