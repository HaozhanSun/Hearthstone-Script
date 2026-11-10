package club.xiaojiawei.hsscript.status

import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebugRunHardCapSupervisorTest {
    private val activeThirtyMinuteLease = DebugRunLease.Snapshot(
        state = DebugRunLease.State.ACTIVE,
        startEpochMillis = 1_000L,
        endEpochMillis = 1_000L + DebugRunLease.MAX_DURATION_MILLIS,
        remainingMillis = DebugRunLease.MAX_DURATION_MILLIS,
    )

    @Test
    fun `beta GUI never surrender lease produces a process-owned thirty minute watchdog plan`() {
        val plan = DebugRunHardCapSupervisor.plan(
            snapshot = activeThirtyMinuteLease,
            runId = "gui-run",
            betaChannel = true,
            neverSurrenderEnabled = true,
            processId = 42L,
            processStartUtc = Instant.parse("2026-11-02T16:00:00Z"),
            runtimeDirectory = Path.of("C:/beta-runtime"),
            guardianStatePath = Path.of("C:/beta-runtime/log/guardian-state.json"),
            deploymentId = "test-deployment",
        ) ?: error("expected Beta Never Surrender Debug Run to arm")

        assertEquals(Instant.ofEpochMilli(activeThirtyMinuteLease.endEpochMillis), plan.deadlineUtc)
        assertEquals(DebugRunLease.MAX_DURATION_MILLIS, plan.deadlineUtc.toEpochMilli() - activeThirtyMinuteLease.startEpochMillis)
        assertEquals("C:/beta-runtime/log/debug-run-hardcap-lease.json", plan.leasePath.toString().replace('\\', '/'))
        assertEquals("C:/beta-runtime/log/debug-run-hardcap-ownership.lock", plan.ownershipLockPath.toString().replace('\\', '/'))
        assertEquals("debug-run-hardcap-watchdog.ps1", plan.watchdogPath.fileName.toString())
        assertEquals("debug-run-hardcap-launcher.ps1", plan.launcherPath.fileName.toString())
        assertEquals("debug-run-hardcap.psm1", plan.modulePath.fileName.toString())
        assertTrue(DebugRunHardCapSupervisor.watchdogCommand(plan).contains("-StopMarkerPath"))
        assertTrue(DebugRunHardCapSupervisor.watchdogCommand(plan).contains(plan.stopMarkerPath.toString()))
        assertTrue(DebugRunHardCapSupervisor.watchdogCommand(plan).contains("-OwnershipLockPath"))
        assertTrue(DebugRunHardCapSupervisor.watchdogCommand(plan).contains(plan.ownershipLockPath.toString()))
        assertTrue(DebugRunHardCapSupervisor.watchdogCommand(plan).contains("-WindowStyle"))
        assertTrue(DebugRunHardCapSupervisor.watchdogCommand(plan).contains("Hidden"))
        assertTrue(DebugRunHardCapSupervisor.watchdogCommand(plan).contains(plan.launcherPath.toString()))
        assertTrue(DebugRunHardCapSupervisor.watchdogCommand(plan).contains("-WatchdogPath"))
        assertTrue(DebugRunHardCapSupervisor.watchdogCommand(plan).contains("-DeploymentId"))
        assertTrue(DebugRunHardCapSupervisor.watchdogCommand(plan).contains(plan.deploymentId))
        assertTrue(DebugRunHardCapSupervisor.watchdogCommand(plan).contains("-WatchdogLogPath"))
    }

    @Test
    fun `PowerShell formatted READY record arms the configured beta debug-run lease`() {
        val plan = requireNotNull(DebugRunHardCapSupervisor.plan(
            snapshot = activeThirtyMinuteLease,
            runId = "gui-run",
            betaChannel = true,
            neverSurrenderEnabled = true,
            processId = 42L,
            processStartUtc = Instant.parse("2026-11-02T16:00:00Z"),
            runtimeDirectory = Path.of("C:/beta-runtime"),
            guardianStatePath = Path.of("C:/beta-runtime/log/guardian-state.json"),
            deploymentId = "test-deployment",
        ))
        val ready = """
            {
              "schema": 1,
              "state": "READY",
              "runId": "gui-run",
              "deploymentId": "test-deployment",
              "processId": 42,
              "watchdogPid": 99,
              "deadlineEpochMillis": ${plan.deadlineUtc.toEpochMilli()},
              "readyAtUtc": "2026-11-02T16:00:01Z"
            }
        """.trimIndent()

        assertTrue(DebugRunHardCapSupervisor.readyRecordMatches(plan, ready))
        assertFalse(
            DebugRunHardCapSettingPolicy.mustRefuseUncappedRun(
                hardCapRequired = true,
                watchdogArmed = DebugRunHardCapSupervisor.readyRecordMatches(plan, ready),
            ),
            "only an exact accepted READY record permits the capped run",
        )
        assertFalse(DebugRunHardCapSupervisor.readyRecordMatches(plan, ready.replace("\"schema\": 1", "\"schema\": 2")))
        assertFalse(DebugRunHardCapSupervisor.readyRecordMatches(plan, ready.replace("\"state\": \"READY\"", "\"state\": \"STALE\"")))
        assertFalse(DebugRunHardCapSupervisor.readyRecordMatches(plan, ready.replace("\"runId\": \"gui-run\"", "\"runId\": \"other-run\"")))
        assertFalse(DebugRunHardCapSupervisor.readyRecordMatches(plan, ready.replace("\"deploymentId\": \"test-deployment\"", "\"deploymentId\": \"other-deployment\"")))
        assertFalse(DebugRunHardCapSupervisor.readyRecordMatches(plan, ready.replace("\"processId\": 42", "\"processId\": 43")))
        assertFalse(DebugRunHardCapSupervisor.readyRecordMatches(plan, ready.replace("\"watchdogPid\": 99", "\"watchdogPid\": 0")))
        assertFalse(DebugRunHardCapSupervisor.readyRecordMatches(plan, ready.replace("\"watchdogPid\": 99,\n", "")))
        val acceptedLease = DebugRunHardCapSupervisor.PersistedLease(
            state = "ACTIVE",
            runId = plan.runId,
            deploymentId = plan.deploymentId,
            startEpochMillis = plan.deadlineUtc.toEpochMilli() - DebugRunLease.MAX_DURATION_MILLIS,
            endEpochMillis = plan.deadlineUtc.toEpochMilli(),
            valid = true,
            processId = plan.processId,
            processStartUtc = plan.processStartUtc,
        )
        assertTrue(DebugRunHardCapSupervisor.activeLeaseMatchesPlan(plan, acceptedLease))
        assertFalse(
            DebugRunHardCapSupervisor.activeLeaseMatchesPlan(plan, acceptedLease.copy(state = "CANCELLED")),
            "READY must not arm a lease cancelled before Java observes it",
        )
        assertFalse(
            DebugRunHardCapSupervisor.activeLeaseMatchesPlan(plan, acceptedLease.copy(runId = "replacement-run")),
            "READY must not arm a replaced lease",
        )
        assertFalse(
            DebugRunHardCapSupervisor.activeLeaseMatchesPlan(plan, acceptedLease.copy(processId = plan.processId + 1)),
            "READY must not arm a lease for a different process ID",
        )
        assertFalse(
            DebugRunHardCapSupervisor.activeLeaseMatchesPlan(plan, acceptedLease.copy(processStartUtc = plan.processStartUtc.plusSeconds(1))),
            "READY must not arm a lease for a reused process ID with another start time",
        )
        val mismatchedReady = ready.replace("\"deploymentId\": \"test-deployment\"", "\"deploymentId\": \"other-deployment\"")
        assertTrue(
            DebugRunHardCapSettingPolicy.mustRefuseUncappedRun(
                hardCapRequired = true,
                watchdogArmed = DebugRunHardCapSupervisor.readyRecordMatches(plan, mismatchedReady),
            ),
            "launch acceptance without the matching READY authority must refuse the run",
        )
        assertFalse(
            DebugRunHardCapSupervisor.readyRecordMatches(
                plan,
                ready.replace(
                    "\"deadlineEpochMillis\": ${plan.deadlineUtc.toEpochMilli()}",
                    "\"deadlineEpochMillis\": ${plan.deadlineUtc.toEpochMilli() + 1}",
                ),
            ),
        )
    }

    @Test
    fun `production planning seam binds current manifest deployment and rejects missing identity`() {
        val property = "hs.script.deployment.id"
        val previous = System.getProperty(property)
        fun currentRuntimePlan() = DebugRunHardCapSupervisor.planForCurrentRuntime(
            snapshot = activeThirtyMinuteLease,
            runId = "gui-run",
            betaChannel = true,
            neverSurrenderEnabled = true,
            processId = 42L,
            processStartUtc = Instant.parse("2026-11-02T16:00:00Z"),
            runtimeDirectory = Path.of("C:/beta-runtime"),
            guardianStatePath = Path.of("C:/beta-runtime/log/guardian-state.json"),
        )

        try {
            System.setProperty(property, " beta-deployment-726 ")
            val plan = requireNotNull(currentRuntimePlan())
            assertEquals("beta-deployment-726", plan.deploymentId)
            assertTrue(DebugRunHardCapSupervisor.watchdogCommand(plan).contains("beta-deployment-726"))

            System.clearProperty(property)
            assertNull(currentRuntimePlan(), "Missing deployment identity must not arm a production watchdog")
            System.setProperty(property, "unknown")
            assertNull(currentRuntimePlan(), "Unknown deployment identity must not arm a production watchdog")
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test
    fun `restart policy preserves an unexpired same deployment window and refuses expiry or identity changes`() {
        val previous = DebugRunHardCapSupervisor.PersistedLease(
            state = "ACTIVE",
            runId = "same-run",
            deploymentId = "beta-deployment-704",
            startEpochMillis = 1_000L,
            endEpochMillis = 1_000L + DebugRunLease.MAX_DURATION_MILLIS,
            valid = true,
        )

        val resume = DebugRunLeaseRestartPolicy.decide(previous, "beta-deployment-704", 1_000L + 25L * 60_000L)
        assertEquals(DebugRunLeaseRestartPolicy.Action.RESTORE_EXISTING, resume.action)
        assertEquals(previous.endEpochMillis, resume.lease?.endEpochMillis)
        assertEquals("same-run", resume.lease?.runId)

        assertEquals(
            DebugRunLeaseRestartPolicy.Action.REFUSE_AUTO_START,
            DebugRunLeaseRestartPolicy.decide(previous, "beta-deployment-704", previous.endEpochMillis).action,
        )
        assertEquals(
            "deployment-identity-mismatch",
            DebugRunLeaseRestartPolicy.decide(previous, "beta-deployment-705", 1_001L).reason,
        )
        assertEquals(
            "previous-lease-invalid",
            DebugRunLeaseRestartPolicy.decide(previous.copy(valid = false), "beta-deployment-704", 1_001L).reason,
        )
        assertEquals(
            DebugRunLeaseRestartPolicy.Action.START_NEW,
            DebugRunLeaseRestartPolicy.decide(null, "beta-deployment-704", 1_001L).action,
        )
    }

    @Test
    fun `persisted restart lease reader retains deployment id and original absolute window`() {
        val directory = Files.createTempDirectory("debug-run-hardcap-restart-test")
        try {
            val guardian = directory.resolve("guardian-state.json")
            val lease = directory.resolve("debug-run-hardcap-lease.json")
            Files.writeString(
                lease,
                """{"schema":1,"state":"ACTIVE","runId":"same-run","deploymentId":"beta-deployment-704","startEpochMillis":1000,"deadlineEpochMillis":${1_000L + DebugRunLease.MAX_DURATION_MILLIS},"processId":42,"processStartUtc":"2026-11-02T16:00:00Z"}""",
            )
            val persisted = requireNotNull(DebugRunHardCapSupervisor.readPersistedLease(guardian))
            assertTrue(persisted.valid)
            assertEquals("beta-deployment-704", persisted.deploymentId)
            assertEquals(1_000L, persisted.startEpochMillis)
            assertEquals(42L, persisted.processId)
            assertEquals(Instant.parse("2026-11-02T16:00:00Z"), persisted.processStartUtc)
            assertEquals(1_000L + DebugRunLease.MAX_DURATION_MILLIS, persisted.endEpochMillis)
        } finally {
            Files.deleteIfExists(directory.resolve("debug-run-hardcap-lease.json"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `watchdog launcher explicitly elevates and relies on ready handshake`() {
        val launcher = requireNotNull(javaClass.getResourceAsStream("/bat/debug-run-hardcap-launcher.ps1")) {
            "packaged watchdog launcher resource is missing from the test classpath"
        }.bufferedReader().use { it.readText() }
        assertTrue(launcher.contains("-Verb RunAs"))
        assertTrue(launcher.contains("-PassThru"))
        assertFalse(launcher.contains("-RedirectStandardOutput \$WatchdogLogPath"))
        assertFalse(launcher.contains("-RedirectStandardError (\$WatchdogLogPath"))
        assertTrue(launcher.contains("ELEVATED_WATCHDOG_PROCESS_ACCEPTED"))
        assertTrue(launcher.contains("ELEVATED_WATCHDOG_LAUNCH_FAILED"))
        assertTrue(launcher.contains("action=LEASE_REFUSED"))
        assertTrue(launcher.contains("parent JVM confirms readiness from the exact READY file"))
        assertTrue(launcher.contains("'-WatchdogLogPath', \$WatchdogLogPath"))
        assertTrue(launcher.contains("|&;<>^()"))

        val watchdog = requireNotNull(javaClass.getResourceAsStream("/bat/debug-run-hardcap-watchdog.ps1")) {
            "packaged watchdog resource is missing from the test classpath"
        }.bufferedReader().use { it.readText() }
        assertTrue(watchdog.contains("[Parameter(Mandatory)][string]\$WatchdogLogPath"))
        assertTrue(watchdog.contains("WATCHDOG_READY"))
        assertTrue(watchdog.contains("WATCHDOG_FAILED"))
        assertTrue(watchdog.contains("watchdogPid = \$PID"))

        val launcherHarness = requireNotNull(javaClass.getResourceAsStream("/bat/DebugRunHardCapLauncher.Tests.ps1")) {
            "launcher acceptance/refusal regression harness is missing from the test classpath"
        }.bufferedReader().use { it.readText() }
        assertTrue(launcherHarness.contains("event=ELEVATED_WATCHDOG_PROCESS_ACCEPTED"))
        assertTrue(launcherHarness.contains("event=ELEVATED_WATCHDOG_LAUNCH_FAILED"))
        assertTrue(launcherHarness.contains("readiness binds deployment identity"))
    }

    @Test
    fun `hard cap eligibility requires beta active switch and exact thirty minute lease`() {
        fun plan(
            beta: Boolean = true,
            neverSurrender: Boolean = true,
            snapshot: DebugRunLease.Snapshot = activeThirtyMinuteLease,
        ) = DebugRunHardCapSupervisor.plan(
            snapshot = snapshot,
            runId = "gui-run",
            betaChannel = beta,
            neverSurrenderEnabled = neverSurrender,
            processId = 42L,
            processStartUtc = Instant.parse("2026-11-02T16:00:00Z"),
            runtimeDirectory = Path.of("C:/beta-runtime"),
            guardianStatePath = Path.of("C:/beta-runtime/log/guardian-state.json"),
            deploymentId = "test-deployment",
        )

        assertNull(plan(beta = false))
        assertNull(plan(neverSurrender = false))
        assertNull(plan(snapshot = activeThirtyMinuteLease.copy(endEpochMillis = activeThirtyMinuteLease.endEpochMillis - 1L)))
        assertNull(plan(snapshot = activeThirtyMinuteLease.copy(state = DebugRunLease.State.DISABLED)))
        assertNull(DebugRunHardCapSupervisor.plan(
            snapshot = activeThirtyMinuteLease,
            runId = "gui-run",
            betaChannel = true,
            neverSurrenderEnabled = true,
            processId = 42L,
            processStartUtc = Instant.EPOCH,
            runtimeDirectory = Path.of("C:/beta-runtime"),
            guardianStatePath = Path.of("C:/beta-runtime/log/guardian-state.json"),
            deploymentId = "test-deployment",
        ))
        assertNull(DebugRunHardCapSupervisor.plan(
            snapshot = activeThirtyMinuteLease,
            runId = "gui-run",
            betaChannel = true,
            neverSurrenderEnabled = true,
            processId = 42L,
            processStartUtc = null,
            runtimeDirectory = Path.of("C:/beta-runtime"),
            guardianStatePath = Path.of("C:/beta-runtime/log/guardian-state.json"),
            deploymentId = "test-deployment",
        ))
        assertNull(DebugRunHardCapSupervisor.plan(
            snapshot = activeThirtyMinuteLease,
            runId = "gui-run",
            betaChannel = true,
            neverSurrenderEnabled = true,
            processId = 0L,
            processStartUtc = Instant.parse("2026-11-02T16:00:00Z"),
            runtimeDirectory = Path.of("C:/beta-runtime"),
            guardianStatePath = Path.of("C:/beta-runtime/log/guardian-state.json"),
            deploymentId = "test-deployment",
        ))
    }

    @Test
    fun `never surrender setting can arm an active lease but never disarms its hard cap`() {
        assertEquals(DebugRunHardCapSettingPolicy.Action.ARM, DebugRunHardCapSettingPolicy.action(true, true))
        assertEquals(DebugRunHardCapSettingPolicy.Action.NO_OP, DebugRunHardCapSettingPolicy.action(false, true))
        assertEquals(DebugRunHardCapSettingPolicy.Action.NO_OP, DebugRunHardCapSettingPolicy.action(true, false))
        assertEquals(DebugRunHardCapSettingPolicy.Action.NO_OP, DebugRunHardCapSettingPolicy.action(false, false))
        assertTrue(DebugRunHardCapSettingPolicy.mustRefuseUncappedRun(hardCapRequired = true, watchdogArmed = false))
        assertFalse(DebugRunHardCapSettingPolicy.mustRefuseUncappedRun(hardCapRequired = true, watchdogArmed = true))
        assertFalse(DebugRunHardCapSettingPolicy.mustRefuseUncappedRun(hardCapRequired = false, watchdogArmed = false))
    }
}
