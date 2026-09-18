package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class StrategyRefreshCommandWatcherTest {

    @Test
    fun `request path is isolated under the runtime root`() {
        assertEquals(
            Path.of("C:", "beta-runtime", StrategyRefreshCommandWatcher.REQUEST_FILE_NAME),
            StrategyRefreshCommandWatcher.requestPath("C:\\beta-runtime"),
        )
    }

    @Test
    fun `request reason uses the first non-empty line and is bounded`() {
        val reason = StrategyRefreshCommandWatcher.parseReason("\n  strategy-1.1.8\nignored")
        assertEquals("strategy-1.1.8", reason)
        assertTrue(StrategyRefreshCommandWatcher.parseReason("\n").isNotBlank())
        assertTrue(StrategyRefreshCommandWatcher.parseReason("x".repeat(500)).length <= 240)
    }

    @Test
    fun `consuming a request only queues it and does not claim application without a turn boundary`() {
        val file = Files.createTempFile("strategy-refresh-", ".request")
        try {
            Files.writeString(file, "strategy-plugin-1.1.9\nmanual-test\nsha256\n")
            val events = mutableListOf<String>()
            val coordinator = StrategyRefreshCoordinator(events::add)

            val requestId = StrategyRefreshCommandWatcher.consumeRequest(file) { reason ->
                coordinator.request(reason)
            }

            assertEquals(1L, requestId)
            assertFalse(Files.exists(file))
            assertTrue(coordinator.hasPendingRequest(), "startup without a turn boundary must leave refresh pending")
            assertTrue(events.any { it.startsWith("STRATEGY_REFRESH_REQUESTED") })
            assertFalse(events.any { it.startsWith("STRATEGY_REFRESH_APPLIED") })
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
