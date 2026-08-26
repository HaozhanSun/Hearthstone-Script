package club.xiaojiawei.hsscript.listener.log

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenLogListenerTest {

    @Test
    fun `active Power log game keeps gameplay mode during historical screen log attach`() {
        assertTrue(ScreenLogListener.shouldPreserveActiveGameMode(inWar = true))
    }

    @Test
    fun `no active Power log game still allows historical screen reset`() {
        assertFalse(ScreenLogListener.shouldPreserveActiveGameMode(inWar = false))
    }
}
