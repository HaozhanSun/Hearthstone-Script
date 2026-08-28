package club.xiaojiawei.hsscript.statistics

import kotlin.test.Test
import kotlin.test.assertEquals

class SurrenderClassifierTest {

    @Test
    fun `no concession is a played round`() {
        assertEquals(false, SurrenderClassifier.classify("", "Me#1", "Opponent#2", false))
    }

    @Test
    fun `our playstate concession is surrendered`() {
        assertEquals(true, SurrenderClassifier.classify(" Me#1 ", "Me#1", "Opponent#2", false))
    }

    @Test
    fun `opponent concession remains a played win`() {
        assertEquals(false, SurrenderClassifier.classify("Opponent#2", "Me#1", "Opponent#2", false))
    }

    @Test
    fun `local surrender request wins when identity is not populated yet`() {
        assertEquals(true, SurrenderClassifier.classify("", "", "Opponent#2", true))
    }

    @Test
    fun `unresolved concession stays unknown`() {
        assertEquals(null, SurrenderClassifier.classify("Unknown#9", "Me#1", "Opponent#2", false))
    }
}
