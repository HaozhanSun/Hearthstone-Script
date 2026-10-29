package club.xiaojiawei.hsscript.status

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TournamentModeEntryDispatchGuardTest {
    @Test
    fun `unknown startup proof suppresses repeated mode inputs and notices until cooldown`() {
        val guard = TournamentModeEntryDispatchGuard()
        var dispatches = 0
        var notices = 0
        repeat(8) { index ->
            val decision = guard.decide(screenConfirmed = false, nowMs = 10_000L + index * 500L)
            if (decision.allowed) dispatches++
            if (decision.emitNotice) notices++
            assertFalse(decision.allowed)
        }
        assertEquals(0, dispatches, "no mode click or matchmaking start may be attempted without screen proof")
        assertEquals(1, notices, "rapid repeated entry callbacks produce one operator notice")

        val cooldown = guard.decide(
            screenConfirmed = false,
            nowMs = 10_000L + TournamentModeEntryDispatchGuard.DEFAULT_NOTICE_COOLDOWN_MS,
        )
        assertFalse(cooldown.allowed)
        assertTrue(cooldown.emitNotice)
        assertEquals("startup-screen-not-confirmed", cooldown.reason)

        val recovered = guard.decide(screenConfirmed = true, nowMs = 80_001L)
        assertTrue(recovered.allowed)
        assertFalse(recovered.emitNotice)
    }

    @Test
    fun `tournament entry checks shared startup gate before mode selector inputs`() {
        assertFalse(StartupInputAuthorizationPolicy.allows(
            action = "mouse.left",
            currentSessionReady = false,
            freshOnlineMenuReady = false,
        ))
        val moduleRoot = listOf(Path.of("."), Path.of("hs-script-app")).first {
            Files.isRegularFile(it.resolve(Path.of(
                "src", "main", "java", "club", "xiaojiawei", "hsscript", "strategy", "mode", "TournamentModeStrategy.kt",
            )))
        }
        val source = Files.readString(moduleRoot.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "strategy", "mode", "TournamentModeStrategy.kt",
        )))
        val gateCheck = source.indexOf("isStartupScreenConfirmedForAction(\"mouse.left\")")
        val firstModeInput = source.indexOf("clickModeChangeButton()")
        assertTrue(gateCheck >= 0)
        assertTrue(firstModeInput > gateCheck, "mode selector clicks must follow the same proof used by final dispatch")
        assertTrue(source.contains("TOURNAMENT_MODE_ENTRY_BLOCKED reason="))
        assertTrue(source.contains("action=NO_MODE_INPUT_NO_START"))
    }
}
