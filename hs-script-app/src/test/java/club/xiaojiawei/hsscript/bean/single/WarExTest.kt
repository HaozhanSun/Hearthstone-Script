package club.xiaojiawei.hsscript.bean.single

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarExTest {

    @BeforeTest
    fun setUp() {
        WarEx.reset(print = false)
    }

    @AfterTest
    fun tearDown() {
        WarEx.reset(print = false)
    }

    @Test
    fun `authoritative loss clears stale win even when player identity is unknown`() {
        WarEx.isWin = true

        WarEx.endWar(resultOverride = false)

        assertFalse(WarEx.isWin)
    }

    @Test
    fun `authoritative win is retained`() {
        WarEx.isWin = false

        WarEx.endWar(resultOverride = true)

        assertTrue(WarEx.isWin)
    }
}
