package club.xiaojiawei.hsscript.listener.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import club.xiaojiawei.hsscript.status.E2EReadinessGate
import java.nio.file.Files

class PowerLogListenerTest {

    @Test
    fun `default startup readiness wait covers slow real-client recovery but stays bounded`() {
        assertEquals(180_000L, E2EReadinessGate.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun `only first-game navigation contexts bypass readiness while waiting`() {
        assertTrue(PowerLogListener.isPreGameDispatchContext("hub-after-enter"))
        assertTrue(PowerLogListener.isPreGameDispatchContext("tournament-entry"))
        assertTrue(PowerLogListener.isPreGameDispatchContext("start-matching"))
        assertFalse(PowerLogListener.isPreGameDispatchContext("gameplay-turn"))
    }

    @Test
    fun `empty fresh log blocks until a current CREATE_GAME transition`() {
        val gate = E2EReadinessGate(timeoutMs = 5_000)
        gate.begin("run-a", 101L, "fresh/Power.log", 0L, 1_000L)

        assertEquals(
            E2EReadinessGate.State.WAITING_FOR_CREATE_GAME,
            gate.evaluate("run-a", 101L, 4_000L).state,
        )
        assertEquals(
            E2EReadinessGate.State.BLOCKED,
            gate.evaluate("run-a", 101L, 6_000L).state,
        )
    }

    @Test
    fun `CREATE_GAME after fresh rotate is the only readiness transition`() {
        val gate = E2EReadinessGate(timeoutMs = 5_000)
        gate.begin("run-b", 102L, "rotated/Power.log", 0L, 1_000L)

        assertFalse(gate.observeLine("run-b", 102L, 0L, "CREATE_GAME"))
        assertTrue(gate.observeLine("run-b", 102L, 42L, "CREATE_GAME"))
        assertEquals(
            E2EReadinessGate.State.READY,
            gate.evaluate("run-b", 102L, 2_000L).state,
        )
    }

    @Test
    fun `stale existing gameplay tail cannot satisfy readiness`() {
        val gate = E2EReadinessGate(timeoutMs = 5_000)
        gate.begin("run-c", 103L, "old/Power.log", 900L, 1_000L)

        assertFalse(gate.observeLine("run-c", 103L, 900L, "CREATE_GAME"))
        assertFalse(gate.observeLine("run-c", 103L, 899L, "CREATE_GAME"))
        assertEquals(
            "existing-log-tail-rejected-awaiting-fresh-create-game",
            gate.evaluate("run-c", 103L, 2_000L).reason,
        )
    }

    @Test
    fun `old run and pid lineage cannot satisfy a new session`() {
        val gate = E2EReadinessGate(timeoutMs = 5_000)
        gate.begin("run-old", 104L, "old/Power.log", 0L, 1_000L)
        gate.begin("run-new", 105L, "new/Power.log", 0L, 2_000L)

        assertFalse(gate.observeLine("run-old", 104L, 10L, "CREATE_GAME"))
        assertFalse(gate.observeLine("run-new", 104L, 10L, "CREATE_GAME"))
        assertTrue(gate.observeLine("run-new", 105L, 10L, "CREATE_GAME"))
    }

    @Test
    fun `active create game without terminal playstate is replayable`() {
        assertTrue(
            PowerLogListener.hasUnfinishedGame(
                sequenceOf(
                    "CREATE_GAME",
                    "TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION",
                ),
            ),
        )
    }

    @Test
    fun `completed game is not replayed`() {
        assertFalse(
            PowerLogListener.hasUnfinishedGame(
                sequenceOf(
                    "CREATE_GAME",
                    "TAG_CHANGE Entity=1 tag=PLAYSTATE value=PLAYING",
                    "TAG_CHANGE Entity=1 tag=PLAYSTATE value=WON",
                ),
            ),
        )
    }

    @Test
    fun `a later create game reopens replay after an earlier completed game`() {
        assertTrue(
            PowerLogListener.hasUnfinishedGame(
                sequenceOf(
                    "CREATE_GAME",
                    "TAG_CHANGE Entity=1 tag=PLAYSTATE value=WON",
                    "CREATE_GAME",
                    "TAG_CHANGE Entity=1 tag=PLAYSTATE value=PLAYING",
                ),
            ),
        )
    }

    @Test
    fun `newest timestamped Power log path is an actual listener switch boundary`() {
        val root = Files.createTempDirectory("hs-powerlog-switch")
        val oldPath = root.resolve("Hearthstone_2026_09_01_09_16_50/Power.log")
        val newPath = root.resolve("Hearthstone_2026_09_01_09_25_25/Power.log")
        Files.createDirectories(newPath.parent)
        Files.writeString(
            newPath,
            "D 09:26:41 GameState.DebugPrintPower() - CREATE_GAME\r\n" +
                "D 09:26:41 GameState.DebugPrintPower() - TAG_CHANGE Entity=laz#12793 tag=PLAYSTATE value=PLAYING\r\n",
        )

        assertTrue(
            PowerLogListener.shouldSwitchPowerLog(
                currentPath = oldPath.toString(),
                latestPath = newPath.toString(),
                latestExists = Files.isRegularFile(newPath),
            ),
        )

        val gate = E2EReadinessGate(timeoutMs = 5_000L)
        gate.begin("run-path-switch", 9001L, newPath.toString(), 0L, 1_000L)
        val createGame = Files.readAllLines(newPath).first()
        assertTrue(gate.observeLine("run-path-switch", 9001L, createGame.toByteArray().size + 2L, createGame))
        assertEquals(E2EReadinessGate.State.READY, gate.evaluate("run-path-switch", 9001L, 2_000L).state)
    }

    @Test
    fun `missing newest Power log cannot trigger a listener switch`() {
        assertFalse(
            PowerLogListener.shouldSwitchPowerLog(
                currentPath = "old/Power.log",
                latestPath = "new/Power.log",
                latestExists = false,
            ),
        )
    }
}
