package club.xiaojiawei.hsscript.status

import java.nio.file.Path
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
        ))
        val ready = """
            {
              "schema": 1,
              "state": "READY",
              "runId": "gui-run",
              "processId": 42,
              "deadlineEpochMillis": ${plan.deadlineUtc.toEpochMilli()},
              "readyAtUtc": "2026-11-02T16:00:01Z"
            }
        """.trimIndent()

        assertTrue(DebugRunHardCapSupervisor.readyRecordMatches(plan, ready))
        assertFalse(DebugRunHardCapSupervisor.readyRecordMatches(plan, ready.replace("\"schema\": 1", "\"schema\": 2")))
        assertFalse(DebugRunHardCapSupervisor.readyRecordMatches(plan, ready.replace("\"state\": \"READY\"", "\"state\": \"STALE\"")))
        assertFalse(DebugRunHardCapSupervisor.readyRecordMatches(plan, ready.replace("\"runId\": \"gui-run\"", "\"runId\": \"other-run\"")))
        assertFalse(DebugRunHardCapSupervisor.readyRecordMatches(plan, ready.replace("\"processId\": 42", "\"processId\": 43")))
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
