package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.config.log
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Opt-in, privacy-preserving process metadata audit.
 *
 * The project logger is intentionally narrower than ETW/Sysmon/Procmon: it
 * records only this JVM and allowlisted Blizzard/Hearthstone image names. It
 * never reads file contents, enumerates arbitrary handles, inspects registry
 * values, captures packets, or monitors unrelated applications.
 */
object PrivacyProcessAudit {
    private val allowedImageNames = setOf("hearthstone.exe", "battle.net.exe", "agent.exe")

    fun emit(reason: String): String? {
        if (!ConfigUtil.getBoolean(ConfigEnum.PROCESS_PRIVACY_AUDIT)) return null
        val correlationId = UUID.randomUUID().toString()
        val runId = System.getProperty("hs.script.e2e.run-id", "normal")
        val deploymentId = System.getProperty("hs.script.deployment.id", "unmarked")
        val manifest = System.getProperty("hs.script.deployment.manifest", "unmarked")
        val processes = observedProcesses()
        val processFields = processes.joinToString("|") { metadata(it) }
        log.info {
            "PRIVACY_PROCESS_AUDIT event=PROCESS_SNAPSHOT " +
                "scope=self-and-blizzard-allowlist source=java-user-mode " +
                "reason=${safeToken(reason)} runId=${safeToken(runId)} " +
                "correlationId=$correlationId timestampUtc=${Instant.now()} " +
                "monotonicNanos=${System.nanoTime()} deploymentId=${safeToken(deploymentId)} " +
                "manifestHash=${digest(manifest)} processCount=${processes.size} " +
                "processes=$processFields coverage=process-tree-command-metadata-only " +
                "fileIo=external-etw-required registry=external-etw-required " +
                "network=external-wfp-or-etw-required handles=external-tool-required " +
                "injection=heuristic-external-telemetry-required"
        }
        return correlationId
    }

    internal fun isAllowedImageNameForTest(imageName: String): Boolean =
        imageName.lowercase() in allowedImageNames

    internal fun safeTokenForTest(value: String): String = safeToken(value)

    internal fun digestForTest(value: String): String = digest(value)

    private fun observedProcesses(): List<ProcessHandle> = runCatching {
        val currentPid = ProcessHandle.current().pid()
        ProcessHandle.allProcesses().use { stream ->
            val observed = mutableListOf<ProcessHandle>()
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val process = iterator.next()
                if (process.pid() == currentPid || isAllowedImageName(process)) observed += process
            }
            observed
        }
    }.getOrDefault(listOf(ProcessHandle.current()))

    private fun isAllowedImageName(process: ProcessHandle): Boolean =
        process.info().command()
            .map { command -> commandName(command) }
            .map { imageName -> imageName.lowercase() in allowedImageNames }
            .orElse(false)

    private fun metadata(process: ProcessHandle): String {
        val info = process.info()
        val command = info.command().orElse("")
        val imageName = if (command.isBlank()) "unknown" else commandName(command)
        val args = info.arguments().orElse(null)?.toList() ?: emptyList()
        val parentPid = process.parent().map(ProcessHandle::pid).orElse(0L)
        val childCount = runCatching {
            process.children().use { children ->
                var count = 0
                val iterator = children.iterator()
                while (iterator.hasNext()) {
                    iterator.next()
                    count++
                }
                count
            }
        }.getOrDefault(-1)
        val start = info.startInstant().map(Instant::toString).orElse("unknown")
        val flags = args.filter { it.startsWith("-") }
            .map { safeToken(it.substringBefore('=')) }
            .joinToString(",")
            .ifBlank { "none" }
        return "pid=${process.pid()},parentPid=$parentPid,image=$imageName," +
            "imagePathHash=${digest(command)},startUtc=$start,argsHash=${digest(args.joinToString("\u0000"))}," +
            "argCount=${args.size},argFlags=$flags,childCount=$childCount"
    }

    private fun commandName(command: String): String = runCatching {
        Path.of(command).fileName?.toString() ?: "unknown"
    }.getOrDefault("unknown")

    private fun safeToken(value: String): String = value
        .replace(Regex("(?i)(password|passwd|token|secret|authorization|cookie|email|account)"), "redacted")
        .replace(Regex("[^A-Za-z0-9_.:/=-]"), "_")
        .take(160)
        .ifBlank { "blank" }

    private fun digest(value: String): String = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }.getOrDefault("unavailable")
}
