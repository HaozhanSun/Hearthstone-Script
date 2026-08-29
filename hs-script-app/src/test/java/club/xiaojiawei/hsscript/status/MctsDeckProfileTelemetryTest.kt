package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.Deck
import kotlin.test.Test
import kotlin.test.assertEquals

class MctsDeckProfileTelemetryTest {

    @Test
    fun `current matchmaking selection wins over cached pirate deck list`() {
        val selected = Deck("海盗瞎", "9268485339", "selected-code")
        val cached = listOf(
            Deck("海盗DK", "9268709466", "cached-death-knight-code"),
            selected,
        )

        assertEquals(selected, MctsDeckProfileTelemetry.selectCurrentDeck(selected, cached))
    }

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
