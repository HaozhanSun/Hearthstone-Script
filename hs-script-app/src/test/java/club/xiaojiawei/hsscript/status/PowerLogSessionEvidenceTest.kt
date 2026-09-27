package club.xiaojiawei.hsscript.status

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerLogSessionEvidenceTest {
    @Test
    fun `empty then growing matching Power log upgrades to current live mulligan`() {
        val processStart = LocalDateTime.of(2026, 9, 21, 23, 2, 50)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val root = Files.createTempDirectory("power-log-session-test").toFile()
        try {
            val dir = root.resolve("Hearthstone_2026_09_21_23_03_14").apply { mkdirs() }
            val powerLog = dir.resolve("Power.log")
            powerLog.writeText("")
            val empty = PowerLogSessionEvidence.inspect(powerLog, processStart, processStart + 60_000L)
            assertTrue(empty.currentSession)
            assertFalse(empty.liveMatch)

            powerLog.writeText(
                "CREATE_GAME\nTAG_CHANGE Entity=GameEntity tag=STEP value=BEGIN_MULLIGAN\n" +
                    "TAG_CHANGE Entity=Player tag=MULLIGAN_STATE value=INPUT\n",
            )
            val growing = PowerLogSessionEvidence.inspect(powerLog, processStart, processStart + 60_000L)
            assertTrue(growing.liveMatch)
            assertTrue(growing.mulligan)
            assertTrue(growing.length > empty.length)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `late growing current session log upgrades startup to active Mulligan evidence`() {
        val processStart = LocalDateTime.of(2026, 9, 21, 23, 2, 50)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val sessionDir = "Hearthstone_2026_09_21_23_03_14"
        val now = processStart + 60_000L
        assertTrue(PowerLogSessionEvidence.isSessionForProcess(sessionDir, processStart, now))

        val beforeLog = PowerLogSessionEvidence.classifyActiveGame(emptySequence())
        assertFalse(beforeLog.active)
        val growingLog = PowerLogSessionEvidence.classifyActiveGame(
            sequenceOf("CREATE_GAME", "TAG_CHANGE Entity=GameEntity tag=STEP value=BEGIN_MULLIGAN", "TAG_CHANGE Entity=Player tag=MULLIGAN_STATE value=INPUT"),
        )
        assertTrue(growingLog.active)
        assertTrue(growingLog.mulligan)
        assertTrue(growingLog.marker == "MULLIGAN_STATE")
    }

    @Test
    fun `a previous client session is not evidence for the current process`() {
        val processStart = LocalDateTime.of(2026, 9, 21, 23, 10, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertFalse(
            PowerLogSessionEvidence.isSessionForProcess(
                "Hearthstone_2026_09_21_23_03_14",
                processStart,
                processStart + 5_000L,
            ),
        )
    }

    @Test
    fun `recent unfinished latest log forbids process relaunch when pid lineage is unavailable`() {
        val now = System.currentTimeMillis()
        val root = Files.createTempDirectory("power-log-recent-test").toFile()
        try {
            val dir = root.resolve("Hearthstone_2026_09_21_23_03_14").apply { mkdirs() }
            val powerLog = dir.resolve("Power.log")
            powerLog.writeText("CREATE_GAME\nTAG_CHANGE Entity=GameEntity tag=STEP value=BEGIN_MULLIGAN\n")
            assertTrue(powerLog.setLastModified(now - 1_000L))
            val evidence = PowerLogSessionEvidence.inspect(powerLog, processStartedAtMs = null, nowMs = now)

            assertFalse(evidence.currentSession)
            assertTrue(evidence.activeGame)
            assertTrue(evidence.recentActiveGame)
            assertTrue(evidence.preserveClient)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `terminal state in current session overrides previous mulligan marker`() {
        val state = PowerLogSessionEvidence.classifyActiveGame(
            sequenceOf(
                "CREATE_GAME",
                "TAG_CHANGE Entity=GameEntity tag=STEP value=BEGIN_MULLIGAN",
                "TAG_CHANGE Entity=Player tag=PLAYSTATE value=LOST",
            ),
        )
        assertFalse(state.active)
        assertFalse(state.mulligan)
    }
}
