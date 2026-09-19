package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
}
