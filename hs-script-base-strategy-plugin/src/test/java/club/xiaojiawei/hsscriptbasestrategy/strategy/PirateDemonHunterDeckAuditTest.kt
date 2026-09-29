package club.xiaojiawei.hsscriptbasestrategy.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Offline audit fixture from the authoritative Pirate DH deck profile
 * (deckId=3263144615, 26 unique cards).  The collector also sees cards from
 * other games and generated tokens; those must not silently become Pirate DH
 * tuning rules.
 */
class PirateDemonHunterDeckAuditTest {
    private val confirmedDeckCardIds = setOf(
        "AV_661", "BT_355", "CFM_637", "CORE_BT_187", "CORE_NEW1_027",
        "DED_507", "DRG_056", "GVG_075", "MAW_008", "REV_018", "REV_509",
        "SW_040", "TLC_833", "TOY_330", "TOY_518", "TOY_642", "TSC_002",
        "VAC_430", "VAC_924", "VAC_925", "VAC_927", "VAC_929", "VAC_933",
        "VAC_938", "WON_143", "YOD_032",
    )

    @Test
    fun `all confirmed Pirate DH cards have a reviewed model rule`() {
        val uncovered = confirmedDeckCardIds
            .filterNot(PirateDemonHunterMctsExperimentModel::isKnownTunedCardId)
        assertTrue(uncovered.isEmpty(), "unreviewed current deck cards: $uncovered")
        assertEquals(26, confirmedDeckCardIds.size)
    }

    @Test
    fun `collector-only cards are not current Pirate DH deck members`() {
        val otherDeckOrGeneratedCards = setOf(
            "TOY_370", "DEEP_034", "DMF_100", "TTN_095", "GDB_302",
            "GDB_303", "UNG_018", "ULD_197", "ULD_239", "VAC_933t",
        )
        assertTrue(otherDeckOrGeneratedCards.intersect(confirmedDeckCardIds).isEmpty())
        assertFalse("VAC_933t" in confirmedDeckCardIds)
    }

    @Test
    fun `profile tuning inventory absent entries remain explicit and do not imply new cards`() {
        val expectedAbsentFromThisProfile = setOf("CS2_146", "VAC_440")
        assertTrue(expectedAbsentFromThisProfile.none { it in confirmedDeckCardIds })
        assertTrue(expectedAbsentFromThisProfile.all(PirateDemonHunterMctsExperimentModel::isKnownTunedCardId))
    }
}
