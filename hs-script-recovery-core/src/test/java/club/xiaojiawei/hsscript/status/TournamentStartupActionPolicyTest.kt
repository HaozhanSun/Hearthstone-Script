package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TournamentStartupActionPolicyTest {
    @Test
    fun `startup inputs require fresh trusted tournament screen on current pid`() {
        assertTrue(TournamentStartupActionPolicy.mayStartModeSelection("TOURNAMENT", 95, 100L, 100L, true, false))
        assertTrue(TournamentStartupActionPolicy.mayStartModeSelection("DECK_SELECTION", 95, 100L, 100L, true, false))
        assertFalse(TournamentStartupActionPolicy.mayStartModeSelection("LOADING", 95, 100L, 100L, true, false))
        assertFalse(TournamentStartupActionPolicy.mayStartModeSelection("HOME_TASK_OVERLAY", 95, 100L, 100L, true, false))
        assertFalse(TournamentStartupActionPolicy.mayStartModeSelection("UNKNOWN", 0, 100L, 100L, true, false))
        assertFalse(TournamentStartupActionPolicy.mayStartModeSelection("TOURNAMENT", 84, 100L, 100L, true, false))
        assertFalse(TournamentStartupActionPolicy.mayStartModeSelection("TOURNAMENT", 95, 100L, 101L, true, false))
        assertFalse(TournamentStartupActionPolicy.mayStartModeSelection("TOURNAMENT", 95, 100L, 100L, false, false))
        assertFalse(TournamentStartupActionPolicy.mayStartModeSelection("TOURNAMENT", 95, 100L, 100L, true, true))
    }
}
