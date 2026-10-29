package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafeIdleUnblockScreenPolicyTest {
    @Test
    fun `only structurally confirmed hub chooser and exact event overlay enter idle ladder`() {
        assertTrue(SafeIdleUnblockScreenPolicy.matches(
            screenKind = "TOURNAMENT", screenMode = "HUB",
            hubModeChooserVisual = true, eventQuestOverlayVisual = false,
        ))
        assertFalse(SafeIdleUnblockScreenPolicy.matches(
            screenKind = "TOURNAMENT", screenMode = "TOURNAMENT",
            hubModeChooserVisual = true, eventQuestOverlayVisual = false,
        ))
        assertFalse(SafeIdleUnblockScreenPolicy.matches(
            screenKind = "TOURNAMENT", screenMode = "HUB",
            hubModeChooserVisual = false, eventQuestOverlayVisual = false,
        ))
        assertTrue(SafeIdleUnblockScreenPolicy.matches(
            screenKind = "EVENT_QUEST_OVERLAY", screenMode = "TOURNAMENT",
            hubModeChooserVisual = false, eventQuestOverlayVisual = true,
        ))
        assertFalse(SafeIdleUnblockScreenPolicy.matches(
            screenKind = "DECK_SELECTION", screenMode = "TOURNAMENT",
            hubModeChooserVisual = false, eventQuestOverlayVisual = false,
        ), "deck format mismatch stays on the dedicated mode-navigation path")
        assertFalse(SafeIdleUnblockScreenPolicy.matches(
            screenKind = "MATCHMAKING", screenMode = "TOURNAMENT",
            hubModeChooserVisual = false, eventQuestOverlayVisual = false,
        ))
    }
}
