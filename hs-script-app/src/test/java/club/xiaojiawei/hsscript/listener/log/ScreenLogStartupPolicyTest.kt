package club.xiaojiawei.hsscript.listener.log

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenLogStartupPolicyTest {

    @Test
    fun historicalModeRestoreIsOffByDefault() {
        assertFalse(ScreenLogStartupPolicy.shouldRestoreHistoricalState(null))
        assertFalse(ScreenLogStartupPolicy.shouldRestoreHistoricalState("false"))
        assertFalse(ScreenLogStartupPolicy.shouldRestoreHistoricalState("1"))
    }

    @Test
    fun historicalModeRestoreRequiresExplicitOptIn() {
        assertTrue(ScreenLogStartupPolicy.shouldRestoreHistoricalState("true"))
        assertTrue(ScreenLogStartupPolicy.shouldRestoreHistoricalState("TRUE"))
    }
}
