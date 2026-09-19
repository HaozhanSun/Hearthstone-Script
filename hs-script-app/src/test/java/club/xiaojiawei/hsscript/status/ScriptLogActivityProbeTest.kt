package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptLogActivityProbeTest {

    @Test
    fun `unnumbered active log wins over a newer five megabyte archive`() {
        val directory = java.nio.file.Files.createTempDirectory("hs-script-log-").toFile()
        try {
            val archive = directory.resolve("hs_script-2026-09-15.13.log")
            archive.writeText("x".repeat(5 * 1024 * 1024))
            val active = directory.resolve(ScriptLogActivityProbe.ACTIVE_FILE_NAME)
            active.writeText("heartbeat\n")
            archive.setLastModified(2_000L)
            active.setLastModified(1_000L)

            assertEquals(
                active.absoluteFile.normalize().path,
                ScriptLogActivityProbe.findActiveFile(directory)?.absoluteFile?.normalize()?.path,
            )
            val snapshot = ScriptLogActivityProbe.snapshotDirectory(directory)
            assertEquals(active.absoluteFile.normalize().path, snapshot.active?.path)
            assertEquals(1, snapshot.archives.size)
            assertEquals(archive.absoluteFile.normalize().path, snapshot.archives.single().path)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `active file identity switch and length reset count as rollover progress`() {
        val previous = ScriptActivitySample(
            checkedAtMillis = 10_000L,
            scriptLog = ScriptLogFileSnapshot("log/hs_script.log", "generation-13", 5 * 1024 * 1024L, 9_000L),
            powerLogPath = "Power.log",
            powerLogPosition = 500L,
            lifecycleProgressAtMillis = 9_000L,
            actionProgressAtMillis = 9_000L,
        )
        val current = previous.copy(
            checkedAtMillis = 10_500L,
            scriptLog = ScriptLogFileSnapshot("log/hs_script.log", "generation-14", 8_192L, 10_400L),
        )

        val result = ScriptLogActivityProbe.evaluate(previous, current, stallTimeoutMillis = 30_000L)

        assertTrue(result.activeFileAdvanced)
        assertTrue(result.activeFileRolledOver)
        assertFalse(result.genuinelyStalled)
        assertEquals("active-log-rollover", result.reason)
    }

    @Test
    fun `true over threshold no progress is reported only when every channel is quiet`() {
        val previous = ScriptActivitySample(
            checkedAtMillis = 1_000L,
            scriptLog = ScriptLogFileSnapshot("log/hs_script.log", "same", 4_000L, 900L),
            powerLogPath = "Power.log",
            powerLogPosition = 500L,
            lifecycleProgressAtMillis = 900L,
            actionProgressAtMillis = 900L,
        )
        val current = previous.copy(checkedAtMillis = 31_001L)

        val result = ScriptLogActivityProbe.evaluate(previous, current, stallTimeoutMillis = 30_000L)

        assertTrue(result.genuinelyStalled)
        assertEquals("all-progress-channels-stalled", result.reason)
    }

    @Test
    fun `a lifecycle heartbeat prevents a false stagnation report`() {
        val previous = ScriptActivitySample(
            checkedAtMillis = 1_000L,
            scriptLog = ScriptLogFileSnapshot("log/hs_script.log", "same", 4_000L, 900L),
            powerLogPath = "Power.log",
            powerLogPosition = 500L,
            lifecycleProgressAtMillis = 900L,
            actionProgressAtMillis = 900L,
        )
        val current = previous.copy(
            checkedAtMillis = 31_001L,
            lifecycleProgressAtMillis = 31_000L,
        )

        val result = ScriptLogActivityProbe.evaluate(previous, current, stallTimeoutMillis = 30_000L)

        assertTrue(result.lifecycleAdvanced)
        assertFalse(result.genuinelyStalled)
    }
}
