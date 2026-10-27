package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit

class DiagnosticRetentionTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `strict cutoff keeps file exactly thirty days old and newer`() {
        val now = Instant.parse("2026-10-27T12:00:00Z")
        val cutoff = DiagnosticRetentionPolicy.cutoff(now)
        assertEquals(Instant.parse("2026-09-27T12:00:00Z"), cutoff)
        assertFalse(DiagnosticRetentionPolicy.isExpired(cutoff, cutoff))
        assertFalse(DiagnosticRetentionPolicy.isExpired(cutoff.plusMillis(1), cutoff))
        assertTrue(DiagnosticRetentionPolicy.isExpired(cutoff.minusMillis(1), cutoff))
    }

    @Test
    fun `fresh files are retained without probing locks and cleanup repeats within six hours`() {
        val logRoot = temp.resolve("fresh-log")
        val fresh = logRoot.resolve("hs_script-fresh.png")
        Files.createDirectories(logRoot)
        Files.write(fresh, byteArrayOf(1))
        Files.setLastModifiedTime(fresh, FileTime.from(NOW.minus(1, ChronoUnit.DAYS)))
        val root = DiagnosticRetentionRoot("beta", logRoot, DiagnosticRootScope.RUNTIME_LOG_TREE)
        val cleaner = DiagnosticRetentionCleaner(isOpenOrLocked = { error("fresh files must not be lock-probed") })

        val report = cleaner.clean("beta", listOf(root), NOW)

        assertEquals(6L, DiagnosticRetentionPolicy.PERIODIC_INTERVAL_HOURS)
        assertEquals(1, report.roots.single().retainedFresh)
        assertEquals(0, report.roots.single().skippedActiveOrLocked)
        assertTrue(Files.exists(fresh))
    }

    @Test
    fun `planner uses isolated roots for beta release candidate and stable`() {
        val runtime = temp.resolve("runtime")
        val appLogs = temp.resolve("external-beta-logs").resolve("key").resolve("app")
        val artifacts = temp.resolve("external-beta-artifacts").resolve("key")
        val relocatedBetaLog = temp.resolve("external-beta-logs").resolve("key").resolve("runtime-log")

        val betaRoots = DiagnosticRetentionPolicy.rootsForChannel("beta", runtime, appLogs, artifacts, relocatedBetaLog)
        assertTrue(betaRoots.any { it.path == relocatedBetaLog && it.scope == DiagnosticRootScope.RUNTIME_LOG_TREE })
        assertTrue(betaRoots.any { it.path == runtime.resolve("log") }, "pre-migration C: diagnostics remain covered")
        assertTrue(betaRoots.any { it.path == appLogs.parent && it.scope == DiagnosticRootScope.EXTERNAL_BETA_LOG_TREE })
        assertTrue(betaRoots.any { it.path == artifacts.resolve("unknown-states") && it.scope == DiagnosticRootScope.ARTIFACT_CATEGORY })
        assertTrue(betaRoots.none { it.path == runtime.resolve("config") || it.path == runtime.resolve("data") })

        listOf("release-candidate", "stable").forEach { channel ->
            val roots = DiagnosticRetentionPolicy.rootsForChannel(channel, runtime, appLogs, artifacts)
            assertEquals(4, roots.size)
            assertTrue(roots.any { it.path == runtime.resolve("log") && it.scope == DiagnosticRootScope.RUNTIME_LOG_TREE })
            assertTrue(roots.any { it.path == runtime.resolve("evidence") && it.scope == DiagnosticRootScope.RUNTIME_EVIDENCE_TREE })
            assertTrue(roots.any { it.path == runtime.resolve("ocr_res") && it.scope == DiagnosticRootScope.RUNTIME_OCR_TREE })
            assertTrue(roots.any { it.path == runtime.resolve("debug/mouse") && it.scope == DiagnosticRootScope.RUNTIME_DEBUG_MOUSE_TREE })
        }
        assertTrue(DiagnosticRetentionPolicy.rootsForChannel("unknown-channel", runtime, appLogs, artifacts).isEmpty())
        assertEquals("release-candidate", DiagnosticRetentionPolicy.channelToken("Release Candidate"))
        assertEquals("release-candidate", DiagnosticRetentionPolicy.channelToken("release_candidate"))
    }

    @Test
    fun `cleaner expires diagnostics only inside explicit roots`() {
        val runtime = temp.resolve("stable-runtime")
        val logRoot = runtime.resolve("log")
        val oldLog = logRoot.resolve("hs_script-2026-09-01.0.log")
        val oldScreenshot = logRoot.resolve("debug-screenshots").resolve("old.png")
        val outsideConfig = runtime.resolve("config").resolve("settings.json")
        val outsideStatistics = runtime.resolve("data").resolve("statistics.db")
        val protectedLogData = logRoot.resolve("config").resolve("config.json")
        val oldJpeg = logRoot.resolve("game-results").resolve("old.jpeg")
        listOf(oldLog, oldScreenshot, outsideConfig, outsideStatistics, protectedLogData, oldJpeg).forEach(::writeOld)
        val root = DiagnosticRetentionPolicy.rootsForChannel("stable", runtime, logRoot, null)
            .single { it.path == logRoot && it.scope == DiagnosticRootScope.RUNTIME_LOG_TREE }

        val report = DiagnosticRetentionCleaner(isOpenOrLocked = { false })
            .clean("stable", listOf(root), NOW)

        assertEquals(3, report.deleted)
        assertFalse(Files.exists(oldLog))
        assertFalse(Files.exists(oldScreenshot))
        assertFalse(Files.exists(oldJpeg), "JPEG screenshots in approved evidence roots expire too")
        assertTrue(Files.exists(outsideConfig))
        assertTrue(Files.exists(outsideStatistics))
        assertTrue(Files.exists(protectedLogData))
    }

    @Test
    fun `outside-log diagnostic roots are explicit and keep neighboring runtime folders out of scope`() {
        val runtime = temp.resolve("stable-runtime")
        val evidenceRoot = runtime.resolve("evidence")
        val ocrRoot = runtime.resolve("ocr_res")
        val mouseRoot = runtime.resolve("debug").resolve("mouse")
        val evidenceFile = evidenceRoot.resolve("e2e").resolve("old.png").also(::writeOld)
        val ocrFile = ocrRoot.resolve("old.png").also(::writeOld)
        val forbiddenOcrJson = ocrRoot.resolve("state.json").also(::writeOld)
        val mouseTrace = mouseRoot.resolve("old.csv").also(::writeOld)
        val neighboringDebug = runtime.resolve("debug").resolve("other").resolve("old.png").also(::writeOld)
        val resourceImage = runtime.resolve("resources").resolve("old.png").also(::writeOld)
        val roots = DiagnosticRetentionPolicy.rootsForChannel("stable", runtime, runtime.resolve("log"), null)
            .filter { it.scope != DiagnosticRootScope.RUNTIME_LOG_TREE }

        val report = DiagnosticRetentionCleaner(isOpenOrLocked = { false }).clean("stable", roots, NOW)

        assertEquals(3, report.deleted)
        assertFalse(Files.exists(evidenceFile))
        assertFalse(Files.exists(ocrFile))
        assertFalse(Files.exists(mouseTrace))
        assertTrue(Files.exists(forbiddenOcrJson))
        assertTrue(Files.exists(neighboringDebug))
        assertTrue(Files.exists(resourceImage))
    }

    @Test
    fun `beta cleaner uses relocated D destination and refuses the C compatibility junction`() {
        val runtime = temp.resolve("C").resolve("runtime")
        val cJunction = runtime.resolve("log")
        val cLegacyEvidence = cJunction.resolve("unknown-states").resolve("old.png")
        val dLog = temp.resolve("D").resolve("HearthstoneScriptLogs").resolve("Beta").resolve("key").resolve("runtime-log")
        val dEvidence = dLog.resolve("unknown-states").resolve("old.png")
        writeOld(cLegacyEvidence)
        writeOld(dEvidence)
        val roots = DiagnosticRetentionPolicy.rootsForChannel(
            "beta", runtime, temp.resolve("D/logs/key/app"), temp.resolve("D/artifacts/key"), dLog,
        )
        assertTrue(roots.any { it.path == cJunction })
        assertTrue(roots.any { it.path == dLog })

        val cleaner = DiagnosticRetentionCleaner(isReparsePoint = { it == cJunction }, isOpenOrLocked = { false })
        val dReport = cleaner.clean("beta", roots, NOW)
        val cReport = cleaner.clean(
            "beta",
            listOf(DiagnosticRetentionRoot("beta", cJunction, DiagnosticRootScope.RUNTIME_LOG_TREE)),
            NOW,
        )

        assertEquals(1, dReport.deleted)
        assertFalse(Files.exists(dEvidence))
        assertEquals(0, cReport.deleted)
        assertEquals(1, cReport.roots.single().skippedReparse)
        assertTrue(Files.exists(cLegacyEvidence), "the compatibility junction target is never traversed")
    }

    @Test
    fun `cleaner retains exact cutoff timestamp and deletes one millisecond older`() {
        val logRoot = temp.resolve("boundary-log")
        val exact = logRoot.resolve("hs_script-exact.log")
        val expired = logRoot.resolve("hs_script-expired.log")
        Files.createDirectories(logRoot)
        Files.write(exact, byteArrayOf(1))
        Files.write(expired, byteArrayOf(2))
        val cutoff = DiagnosticRetentionPolicy.cutoff(NOW)
        Files.setLastModifiedTime(exact, FileTime.from(cutoff))
        Files.setLastModifiedTime(expired, FileTime.from(cutoff.minusMillis(1)))

        val report = DiagnosticRetentionCleaner(isOpenOrLocked = { false }).clean(
            "stable",
            listOf(DiagnosticRetentionRoot("stable", logRoot, DiagnosticRootScope.RUNTIME_LOG_TREE)),
            NOW,
        )

        assertEquals(1, report.deleted)
        assertTrue(Files.exists(exact))
        assertFalse(Files.exists(expired))
    }

    @Test
    fun `active paths and files with an open exclusive lock are skipped`() {
        val runtime = temp.resolve("beta-runtime")
        val logRoot = runtime.resolve("log")
        val active = logRoot.resolve("hs_script.log").also(::writeOld)
        val locked = logRoot.resolve("hs_script-locked.log").also(::writeOld)
        val rotated = logRoot.resolve("hs_script-rotated.log").also(::writeOld)
        val root = DiagnosticRetentionRoot("beta", logRoot, DiagnosticRootScope.RUNTIME_LOG_TREE)
        FileChannel.open(locked, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val report = DiagnosticRetentionCleaner().clean("beta", listOf(root), NOW, setOf(active))
                assertEquals(1, report.deleted)
                assertEquals(2, report.roots.single().skippedActiveOrLocked)
            }
        }
        assertTrue(Files.exists(active))
        assertTrue(Files.exists(locked))
        assertFalse(Files.exists(rotated))
    }

    @Test
    fun `reparse directories are skipped without entering their children`() {
        val logRoot = temp.resolve("runtime-log")
        val reparse = logRoot.resolve("junction")
        val child = reparse.resolve("old.png")
        writeOld(child)
        val root = DiagnosticRetentionRoot("stable", logRoot, DiagnosticRootScope.RUNTIME_LOG_TREE)
        val cleaner = DiagnosticRetentionCleaner(
            isReparsePoint = { it == reparse },
            isOpenOrLocked = { false },
        )

        val report = cleaner.clean("stable", listOf(root), NOW)

        assertEquals(0, report.deleted)
        assertEquals(1, report.roots.single().skippedReparse)
        assertTrue(Files.exists(child), "children behind a junction/reparse point are never visited")
    }

    @Test
    fun `missing and inaccessible roots are reported without failure`() {
        val missing = DiagnosticRetentionRoot("stable", temp.resolve("missing-log"), DiagnosticRootScope.RUNTIME_LOG_TREE)
        val inaccessiblePath = temp.resolve("inaccessible-log").also(Files::createDirectories)
        val inaccessible = DiagnosticRetentionRoot("stable", inaccessiblePath, DiagnosticRootScope.RUNTIME_LOG_TREE)
        val report = DiagnosticRetentionCleaner(canRead = { false })
            .clean("stable", listOf(missing, inaccessible), NOW)

        assertTrue(report.roots[0].missing)
        assertEquals(1, report.roots[1].errors)
    }

    @Test
    fun `failed deletion is counted and retried successfully on next run`() {
        val logRoot = temp.resolve("retry-log")
        val target = logRoot.resolve("hs_script-old.log").also(::writeOld)
        val root = DiagnosticRetentionRoot("release-candidate", logRoot, DiagnosticRootScope.RUNTIME_LOG_TREE)
        var firstAttempt = true
        val cleaner = DiagnosticRetentionCleaner(
            isOpenOrLocked = { false },
            deleteFile = { file ->
                if (firstAttempt) {
                    firstAttempt = false
                    throw java.io.IOException("simulated sharing violation")
                }
                Files.deleteIfExists(file)
            },
        )

        val failed = cleaner.clean("release-candidate", listOf(root), NOW)
        assertEquals(1, failed.errors)
        assertTrue(Files.exists(target))

        val retried = cleaner.clean("release-candidate", listOf(root), NOW)
        assertEquals(1, retried.deleted)
        assertFalse(Files.exists(target))
    }

    @Test
    fun `file allowlist excludes binaries and unrelated nested data`() {
        val root = DiagnosticRetentionRoot("beta", temp.resolve("beta-log"), DiagnosticRootScope.RUNTIME_LOG_TREE)
        assertTrue(DiagnosticRetentionPolicy.allowsFile(root, root.path.resolve("decision-trace/trace.jsonl")))
        assertTrue(DiagnosticRetentionPolicy.allowsFile(root, root.path.resolve("e2e-evidence/screenshot.png")))
        assertFalse(DiagnosticRetentionPolicy.allowsFile(root, root.path.resolve("plugin/plugin.jar")))
        assertFalse(DiagnosticRetentionPolicy.allowsFile(root, root.path.resolve("data/statistics.db")))
        assertFalse(DiagnosticRetentionPolicy.allowsFile(root, root.path.resolve("config/config.json")))
    }

    @Test
    fun `shared Hearthstone logs are cross-channel log-only and locked-safe`() {
        val shared = temp.resolve("Hearthstone").resolve("Logs")
        val sibling = temp.resolve("Hearthstone").resolve("LogsBackup")
        val expired = shared.resolve("Power.log").also(::writeOld)
        val fresh = shared.resolve("fresh.log").also { Files.createDirectories(it.parent) }
        Files.writeString(fresh, "fresh")
        Files.setLastModifiedTime(fresh, FileTime.from(NOW.minus(1, ChronoUnit.DAYS)))
        val locked = shared.resolve("LoadingScreen.log").also(::writeOld)
        val forbidden = shared.resolve("Settings.json").also(::writeOld)
        val outside = sibling.resolve("outside.log").also(::writeOld)
        val gameRoot = DiagnosticRetentionRoot("stable", shared, DiagnosticRootScope.SHARED_GAME_LOG_TREE)
        assertFalse(DiagnosticRetentionPolicy.allowsFile(gameRoot, sibling.resolve("outside.log")))
        assertFalse(DiagnosticRetentionPolicy.allowsFile(gameRoot, shared.resolve("Settings.json")))
        listOf("beta", "release-candidate", "stable").forEach { channel ->
            val roots = DiagnosticRetentionPolicy.rootsForChannel(
                channel, temp.resolve("runtime-$channel"), temp.resolve("runtime-$channel/log"), null,
                sharedGameLogRoot = shared,
            )
            assertTrue(roots.any { it.path == shared && it.scope == DiagnosticRootScope.SHARED_GAME_LOG_TREE })
        }
        FileChannel.open(locked, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val report = DiagnosticRetentionCleaner().clean("stable", listOf(gameRoot), NOW)
                assertEquals(1, report.deleted)
                assertEquals(1, report.roots.single().retainedFresh)
                assertEquals(1, report.roots.single().skippedActiveOrLocked)
            }
        }
        assertFalse(Files.exists(expired))
        assertTrue(Files.exists(fresh))
        assertTrue(Files.exists(locked))
        assertTrue(Files.exists(forbidden))
        assertTrue(Files.exists(outside))
    }

    private fun writeOld(path: Path): Path {
        Files.createDirectories(path.parent)
        Files.write(path, byteArrayOf(1, 2, 3))
        Files.setLastModifiedTime(path, FileTime.from(NOW.minus(31, ChronoUnit.DAYS)))
        return path
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-27T12:00:00Z")
    }
}
