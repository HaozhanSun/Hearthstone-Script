package club.xiaojiawei.hsscript.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameResultOutcomeDetectorTest {
    @Test
    fun `result banner text recognizes Chinese win and loss`() {
        assertEquals("win", GameResultOutcomeDetector.classifyText("胜利属于你。"))
        assertEquals("loss", GameResultOutcomeDetector.classifyText("结束吧，你输了。"))
    }

    @Test
    fun `unresolved result text remains unknown`() {
        assertNull(GameResultOutcomeDetector.classifyText("点击继续"))
    }
}
