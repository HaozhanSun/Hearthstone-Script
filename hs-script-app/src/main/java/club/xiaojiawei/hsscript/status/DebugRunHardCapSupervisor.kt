package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.config.log
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bridges the in-process Debug Run lease to an independent PowerShell watchdog.
 *
 * The watchdog is intentionally external: a blocked JVM cannot cancel the
 * deadline merely because its in-process scheduler is no longer making progress.
 * Its lease file is also the cancellation/restart ownership token; a stale
 * watchdog must never terminate a later Debug Run in the same runtime folder.
 */
internal object DebugRunHardCapSupervisor {
    private const val WATCHDOG_NAME = "debug-run-hardcap-watchdog.ps1"
    private const val LAUNCHER_NAME = "debug-run-hardcap-launcher.ps1"
    private const val MODULE_NAME = "debug-run-hardcap.psm1"
    private const val LEASE_FILE_NAME = "debug-run-hardcap-lease.json"
    private const val OWNERSHIP_LOCK_NAME = "debug-run-hardcap-ownership.lock"
    private const val WATCHDOG_READY_TIMEOUT_MS = 10_000L
    private const val WATCHDOG_READY_POLL_MS = 50L

    internal data class Plan(
        val runId: String,
        val deploymentId: String,
        val processId: Long,
        val processStartUtc: Instant,
        val deadlineUtc: Instant,
        val runtimeDirectory: Path,
        val guardianStatePath: Path,
    ) {
        val leasePath: Path get() = guardianStatePath.parent.resolve(LEASE_FILE_NAME)
        val ownershipLockPath: Path get() = guardianStatePath.parent.resolve(OWNERSHIP_LOCK_NAME)
        val stopMarkerPath: Path get() = guardianStatePath.parent.resolve("debug-run-hardcap-stop.json")
        val readyPath: Path get() = leasePath.resolveSibling(
            "debug-run-hardcap-${runId.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.ready.json",
        )
        val watchdogPath: Path get() = runtimeDirectory.resolve(WATCHDOG_NAME)
        val launcherPath: Path get() = runtimeDirectory.resolve(LAUNCHER_NAME)
        val modulePath: Path get() = runtimeDirectory.resolve(MODULE_NAME)
    }

    internal data class PersistedLease(
        val state: String,
        val runId: String,
        val deploymentId: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val valid: Boolean,
    )

    /** Read only the durable absolute window needed to survive an app restart. */
    internal fun readPersistedLease(guardianStatePath: Path?): PersistedLease? {
        val guardian = guardianStatePath ?: return null
        val path = guardian.toAbsolutePath().normalize().parent?.resolve(LEASE_FILE_NAME) ?: return null
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
            val state = root["state"]?.jsonPrimitive?.content
            val runId = root["runId"]?.jsonPrimitive?.content
            val deploymentId = root["deploymentId"]?.jsonPrimitive?.content
            val startEpochMillis = root["startEpochMillis"]?.jsonPrimitive?.content?.toLongOrNull()
            val endEpochMillis = root["deadlineEpochMillis"]?.jsonPrimitive?.content?.toLongOrNull()
            PersistedLease(
                state = state.orEmpty(),
                runId = runId.orEmpty(),
                deploymentId = deploymentId.orEmpty(),
                startEpochMillis = startEpochMillis ?: 0L,
                endEpochMillis = endEpochMillis ?: 0L,
                valid = !state.isNullOrBlank() && !runId.isNullOrBlank() && !deploymentId.isNullOrBlank() &&
                    startEpochMillis != null && endEpochMillis != null &&
                    endEpochMillis - startEpochMillis == DebugRunLease.MAX_DURATION_MILLIS,
            )
        }.getOrElse {
            PersistedLease("INVALID", "", "", 0L, 0L, valid = false)
        }
    }

    /** Pure eligibility/ownership boundary, kept testable without spawning a process. */
    internal fun plan(
        snapshot: DebugRunLease.Snapshot,
        runId: String?,
        betaChannel: Boolean,
        neverSurrenderEnabled: Boolean,
        processId: Long,
        processStartUtc: Instant?,
        runtimeDirectory: Path,
        guardianStatePath: Path?,
        deploymentId: String,
    ): Plan? {
        if (!betaChannel || !neverSurrenderEnabled || snapshot.state != DebugRunLease.State.ACTIVE) return null
        if (runId.isNullOrBlank() || deploymentId.isBlank() || deploymentId.equals("unknown", ignoreCase = true) ||
            processId <= 0L || guardianStatePath == null ||
            processStartUtc == null ||
            (processStartUtc.epochSecond == 0L && processStartUtc.nano == 0)
        ) return null
        // GUI Debug Run is deliberately fixed at thirty minutes. Do not turn a
        // shorter testing lease into a production process-stop authorization.
        if (snapshot.endEpochMillis - snapshot.startEpochMillis != DebugRunLease.MAX_DURATION_MILLIS) return null
        return Plan(
            runId = runId,
            deploymentId = deploymentId,
            processId = processId,
            processStartUtc = processStartUtc,
            deadlineUtc = Instant.ofEpochMilli(snapshot.endEpochMillis),
            runtimeDirectory = runtimeDirectory,
            guardianStatePath = guardianStatePath,
        )
    }

    /** Production planning seam: bind the watchdog to the manifest ID supplied by the launcher. */
    internal fun planForCurrentRuntime(
        snapshot: DebugRunLease.Snapshot,
        runId: String?,
        betaChannel: Boolean,
        neverSurrenderEnabled: Boolean,
        processId: Long,
        processStartUtc: Instant?,
        runtimeDirectory: Path,
        guardianStatePath: Path?,
    ): Plan? {
        val deploymentId = System.getProperty("hs.script.deployment.id")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
            ?: ""
        return plan(
            snapshot = snapshot,
            runId = runId,
            betaChannel = betaChannel,
            neverSurrenderEnabled = neverSurrenderEnabled,
            processId = processId,
            processStartUtc = processStartUtc,
            runtimeDirectory = runtimeDirectory,
            guardianStatePath = guardianStatePath,
            deploymentId = deploymentId,
        )
    }

    fun arm(
        snapshot: DebugRunLease.Snapshot,
        runId: String?,
        betaChannel: Boolean,
        neverSurrenderEnabled: Boolean,
        reason: String,
    ): Boolean {
        val runtimeDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val guardianStatePath = System.getProperty("hs.script.guardian-state.path")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
        val plan = planForCurrentRuntime(
            snapshot = snapshot,
            runId = runId,
            betaChannel = betaChannel,
            neverSurrenderEnabled = neverSurrenderEnabled,
            processId = ProcessHandle.current().pid(),
            processStartUtc = ProcessHandle.current().info().startInstant().orElse(null),
            runtimeDirectory = runtimeDirectory,
            guardianStatePath = guardianStatePath,
        )
        if (plan == null) {
            log.info { "DEBUG_RUN_HARD_CAP_NOT_ARMED reason=$reason beta=$betaChannel neverSurrender=$neverSurrenderEnabled state=${snapshot.state}" }
            return false
        }
        if (!Files.isRegularFile(plan.launcherPath) || !Files.isRegularFile(plan.watchdogPath) || !Files.isRegularFile(plan.modulePath)) {
            log.error {
                "DEBUG_RUN_HARD_CAP_NOT_ARMED reason=missing-packaged-watchdog " +
                    "launcher=${plan.launcherPath} watchdog=${plan.watchdogPath} module=${plan.modulePath}"
            }
            return false
        }
        val watchdog = runCatching {
            withGuardianStateLock(plan.guardianStatePath) {
                Files.deleteIfExists(plan.readyPath)
                writeLease(plan, state = "ACTIVE", reason = reason)
                val logPath = plan.leasePath.parent.resolve("debug-run-hardcap.log")
                ProcessBuilder(watchdogCommand(plan))
                    .directory(plan.runtimeDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()))
                    .start()
            }
        }.onFailure { error ->
            log.error(error) { "DEBUG_RUN_HARD_CAP_NOT_ARMED reason=watchdog-launch-failed" }
        }.getOrNull() ?: run {
            runCatching {
                withGuardianStateLock(plan.guardianStatePath) {
                    writeLease(plan, state = "CANCELLED", reason = "watchdog-launch-failed")
                }
            }
            return false
        }
        if (!awaitWatchdogReady(plan, watchdog)) {
            runCatching { watchdog.destroyForcibly() }
            runCatching {
                withGuardianStateLock(plan.guardianStatePath) {
                    writeLease(plan, state = "CANCELLED", reason = "watchdog-ready-handshake-failed")
                }
            }
            log.error {
                "DEBUG_RUN_HARD_CAP_NOT_ARMED reason=watchdog-ready-handshake-failed runId=${plan.runId} " +
                    "watchdogPid=${watchdog.pid()} readyPath=${plan.readyPath} action=LEASE_REFUSED"
            }
            return false
        }
        val elevatedWatchdogPid = runCatching { Files.readString(plan.readyPath).jsonNumber("watchdogPid") }
            .getOrNull() ?: -1L
        log.info {
            "DEBUG_RUN_HARD_CAP_ARMED runId=${plan.runId} pid=${plan.processId} " +
                "processStartUtc=${plan.processStartUtc} deadlineUtc=${plan.deadlineUtc} " +
                "capMillis=${DebugRunLease.MAX_DURATION_MILLIS} bootstrapPid=${watchdog.pid()} " +
                "elevatedWatchdogPid=$elevatedWatchdogPid reason=$reason"
        }
        return true
    }

    fun cancel(reason: String): Boolean {
        val guardianStatePath = System.getProperty("hs.script.guardian-state.path")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
            ?: return false
        return runCatching {
            val path = guardianStatePath.parent.resolve(LEASE_FILE_NAME)
            withGuardianStateLock(guardianStatePath) {
                Files.createDirectories(path.parent)
                writeAtomically(
                    path,
                    """{"schema":1,"state":"CANCELLED","cancelledAtUtc":"${Instant.now()}","reason":"${safe(reason)}"}""",
                )
                log.info { "DEBUG_RUN_HARD_CAP_CANCELLED leasePath=$path reason=$reason" }
            }
            true
        }.onFailure { error ->
            log.warn(error) { "DEBUG_RUN_HARD_CAP_CANCEL_FAILED reason=$reason" }
        }.getOrDefault(false)
    }

    internal fun watchdogCommand(plan: Plan): List<String> = listOf(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-WindowStyle",
        "Hidden",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        plan.launcherPath.toString(),
        "-WatchdogPath",
        plan.watchdogPath.toString(),
        "-RunId",
        plan.runId,
        "-DeploymentId",
        plan.deploymentId,
        "-ProcessId",
        plan.processId.toString(),
        "-ProcessStartUtc",
        plan.processStartUtc.toString(),
        "-DeadlineUtc",
        plan.deadlineUtc.toString(),
        "-LeasePath",
        plan.leasePath.toString(),
        "-GuardianStatePath",
        plan.guardianStatePath.toString(),
        "-StopMarkerPath",
        plan.stopMarkerPath.toString(),
        "-OwnershipLockPath",
        plan.ownershipLockPath.toString(),
        "-ReadyPath",
        plan.readyPath.toString(),
        "-WatchdogLogPath",
        plan.leasePath.parent.resolve("debug-run-hardcap-watchdog.log").toString(),
        "-ModulePath",
        plan.modulePath.toString(),
    )

    private fun writeLease(plan: Plan, state: String, reason: String) {
        Files.createDirectories(plan.leasePath.parent)
        writeAtomically(
            plan.leasePath,
            """{"schema":1,"state":"$state","runId":"${safe(plan.runId)}","deploymentId":"${safe(plan.deploymentId)}","startEpochMillis":${plan.deadlineUtc.toEpochMilli() - DebugRunLease.MAX_DURATION_MILLIS},"deadlineEpochMillis":${plan.deadlineUtc.toEpochMilli()},"processId":${plan.processId},"processStartUtc":"${plan.processStartUtc}","deadlineUtc":"${plan.deadlineUtc}","reason":"${safe(reason)}"}""",
        )
    }

    private fun awaitWatchdogReady(plan: Plan, watchdog: Process): Boolean {
        val deadline = System.nanoTime() + WATCHDOG_READY_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            val ready = runCatching { Files.readString(plan.readyPath) }.getOrNull()
            if (ready != null && readyRecordMatches(plan, ready)) return true
            try {
                Thread.sleep(WATCHDOG_READY_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /**
     * PowerShell's ConvertTo-Json formats records with spaces and newlines.
     * The watchdog READY protocol is structural JSON, not a minified-text
     * protocol, so accept optional JSON whitespace while retaining every
     * ownership field required for an armed hard cap.
     */
    internal fun readyRecordMatches(plan: Plan, ready: String): Boolean =
        ready.matchesJsonNumber("schema", 1L) &&
            ready.matchesJsonString("state", "READY") &&
            ready.matchesJsonString("runId", safe(plan.runId)) &&
            ready.matchesJsonString("deploymentId", safe(plan.deploymentId)) &&
            ready.matchesJsonNumber("processId", plan.processId) &&
            (ready.jsonNumber("watchdogPid") ?: 0L) > 0L &&
            ready.matchesJsonNumber("deadlineEpochMillis", plan.deadlineUtc.toEpochMilli())

    private fun String.matchesJsonString(field: String, value: String): Boolean =
        Regex("\\\"${Regex.escape(field)}\\\"\\s*:\\s*\\\"${Regex.escape(value)}\\\"").containsMatchIn(this)

    private fun String.matchesJsonNumber(field: String, value: Long): Boolean =
        Regex("\\\"${Regex.escape(field)}\\\"\\s*:\\s*${Regex.escape(value.toString())}(?![0-9])").containsMatchIn(this)

    private fun String.jsonNumber(field: String): Long? =
        Regex("\\\"${Regex.escape(field)}\\\"\\s*:\\s*(-?[0-9]+)")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()

    /** Shared OS file lock serializes arm/cancel against the external watchdog's final stop transaction. */
    internal fun <T> withGuardianStateLock(guardianStatePath: Path, action: () -> T): T {
        val parent = guardianStatePath.toAbsolutePath().normalize().parent
            ?: throw IllegalArgumentException("guardian-state path has no parent: $guardianStatePath")
        val lockPath = parent.resolve(OWNERSHIP_LOCK_NAME)
        Files.createDirectories(lockPath.parent)
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock(0L, 1L, false).use { return action() }
        }
    }

    private fun writeAtomically(path: Path, content: String) {
        val temporary = path.resolveSibling("${path.fileName}.${ProcessHandle.current().pid()}.tmp")
        Files.writeString(temporary, content, StandardCharsets.UTF_8)
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9_. -]"), "_")
}

/** Keeps the detached hard stop aligned with live Never Surrender setting changes. */
internal object DebugRunHardCapSettingPolicy {
    enum class Action { ARM, NO_OP }

    fun action(neverSurrenderEnabled: Boolean, leaseActive: Boolean): Action = when {
        leaseActive && neverSurrenderEnabled -> Action.ARM
        else -> Action.NO_OP
    }

    fun mustRefuseUncappedRun(hardCapRequired: Boolean, watchdogArmed: Boolean): Boolean =
        hardCapRequired && !watchdogArmed
}

/** Prevents automatic process restarts from granting a new full debug-run window. */
internal object DebugRunLeaseRestartPolicy {
    enum class Action { START_NEW, RESTORE_EXISTING, REFUSE_AUTO_START }
    data class Decision(val action: Action, val reason: String, val lease: DebugRunHardCapSupervisor.PersistedLease? = null)

    fun decide(
        previous: DebugRunHardCapSupervisor.PersistedLease?,
        currentDeploymentId: String,
        nowEpochMillis: Long,
    ): Decision {
        if (previous == null) return Decision(Action.START_NEW, "no-previous-hard-cap-lease")
        if (!previous.valid) return Decision(Action.REFUSE_AUTO_START, "previous-lease-invalid")
        if (previous.state != "ACTIVE") return Decision(Action.REFUSE_AUTO_START, "previous-lease-not-active")
        if (currentDeploymentId.isBlank() || previous.deploymentId != currentDeploymentId) {
            return Decision(Action.REFUSE_AUTO_START, "deployment-identity-mismatch")
        }
        if (nowEpochMillis >= previous.endEpochMillis) {
            return Decision(Action.REFUSE_AUTO_START, "previous-lease-expired")
        }
        return Decision(Action.RESTORE_EXISTING, "preserve-original-deadline", previous)
    }
}
