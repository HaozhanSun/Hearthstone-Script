package club.xiaojiawei.hsscript.e2e

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the Windows E2E harness against two regressions that make a run
 * impossible to audit: launching an old hard-coded JAR and mixing multiple
 * runs in one console log.
 */
class DebugRunScriptContractTest {
    @Test
    fun `debug runner selects deployed jar and isolates run evidence`() {
        val script = Path.of("src", "main", "resources", "bat", "run-debug.ps1")
        assertTrue(Files.isRegularFile(script), "run-debug.ps1 must remain checked in")
        val text = Files.readString(script)

        assertTrue(text.contains("deployment-manifest.json"))
        assertTrue(text.contains("Get-ChildItem -LiteralPath \$scriptDirectory -Filter \"hs-script_*.jar\""))
        assertTrue(text.contains("\$runDirectory = Join-Path (Join-Path \$logDirectory \"e2e-runs\") \$runId"))
        assertTrue(text.contains("\$ledgerPath = Join-Path \$runDirectory \"run-ledger.jsonl\""))
        assertTrue(text.contains("Write-LedgerEvent \"run-start\""))
        assertTrue(text.contains("Write-LedgerEvent \"attempt-start\""))
        assertTrue(text.contains("Write-LedgerEvent \"attempt-exit\""))
        assertTrue(text.contains("Write-LedgerEvent \"run-complete\""))
        assertTrue(text.contains("Write-LedgerEvent \"run-exhausted\""))

        // The shared script log is application-owned, but the watchdog's
        // console trace must never be reset for a new run.
        assertFalse(text.contains("\$consoleLog = Join-Path \$logDirectory \"java-console-debug.log\""))
        assertTrue(text.contains("\$consoleLog = Join-Path \$runDirectory \"java-console-debug.log\""))
    }
}
