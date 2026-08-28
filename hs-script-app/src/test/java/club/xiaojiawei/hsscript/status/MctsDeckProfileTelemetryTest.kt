package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals

class MctsDeckProfileTelemetryTest {

    @Test
    fun `classifier flags only ids outside the hand reviewed Pirate DH inventory`() {
        assertEquals(
            listOf("NEW_CARD_999"),
            MctsDeckProfileTelemetry.classifyUnknownCardIds(
                listOf("VAC_925", "CORE_NEW1_027", "NEW_CARD_999", "NEW_CARD_999"),
            ),
        )
    }
}
