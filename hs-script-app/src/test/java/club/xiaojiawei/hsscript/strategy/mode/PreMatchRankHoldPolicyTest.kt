package club.xiaojiawei.hsscript.strategy.mode

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreMatchRankHoldPolicyTest {
    @Test
    fun `rank denial requests automatic hold only while the runtime can progress`() {
        assertTrue(
            PreMatchRankHoldPolicy.shouldEnterHold(
                rankAuthorized = false,
                working = true,
                paused = false,
                mandatoryRankSurrenderPending = false,
            ),
            "fresh ineligible or unknown rank must stop the unchanged pre-match recovery loop",
        )
        assertFalse(PreMatchRankHoldPolicy.shouldEnterHold(true, true, false, false))
        assertFalse(PreMatchRankHoldPolicy.shouldEnterHold(false, false, false, false))
        assertFalse(PreMatchRankHoldPolicy.shouldEnterHold(false, true, true, false))
        assertFalse(
            PreMatchRankHoldPolicy.shouldEnterHold(false, true, false, true),
            "the mandatory-surrender workflow owns its own continuation semantics",
        )
    }
}
