package club.xiaojiawei.hsscript.status

import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Session-scoped evidence used when the Power.log listener is late to bind. */
internal object PowerLogSessionEvidence {
    private const val MAX_SCAN_BYTES = 8L * 1024L * 1024L
    private const val PROCESS_SESSION_SKEW_MS = 120_000L
    private const val FUTURE_SKEW_MS = 30_000L
    private const val RECENT_ACTIVE_LOG_MAX_AGE_MS = 300_000L
    private val sessionName = Regex("Hearthstone_(\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2})")
    private val sessionFormatter = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss")

    data class Evidence(
        val path: String?,
        val length: Long,
        val currentSession: Boolean,
        val activeGame: Boolean,
        val mulligan: Boolean,
        val latestMarker: String,
        val recentActiveGame: Boolean = false,
    ) {
        val liveMatch: Boolean get() = currentSession && activeGame
        /** Recent unfinished log evidence is enough to forbid a destructive relaunch, not to bind or dispatch. */
        val preserveClient: Boolean get() = activeGame && (currentSession || recentActiveGame)
    }

    fun inspect(file: File?, processStartedAtMs: Long?, nowMs: Long): Evidence {
        if (file == null || !file.isFile) return Evidence(null, 0L, false, false, false, "no-current-power-log")
        val length = runCatching { file.length() }.getOrDefault(0L)
        val recent = runCatching {
            nowMs - file.lastModified() in 0..RECENT_ACTIVE_LOG_MAX_AGE_MS
        }.getOrDefault(false)
        val currentSession = processStartedAtMs != null &&
            isSessionForProcess(file.parentFile?.name.orEmpty(), processStartedAtMs, nowMs)
        if (length <= 0L) {
            return Evidence(file.absolutePath, length, currentSession, false, false, "empty-current-session-log", recent)
        }
        val lines = runCatching { readTail(file).toList() }.getOrElse {
            return Evidence(file.absolutePath, length, currentSession, false, false, "power-log-read-failed", recent)
        }
        val state = classifyActiveGame(lines.asSequence())
        return Evidence(file.absolutePath, length, currentSession, state.active, state.mulligan, state.marker, recent)
    }

    fun isSessionForProcess(directoryName: String, processStartedAtMs: Long, nowMs: Long): Boolean {
        val encoded = sessionName.matchEntire(directoryName)?.groupValues?.getOrNull(1) ?: return false
        val sessionAt = runCatching {
            LocalDateTime.parse(encoded, sessionFormatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull() ?: return false
        return sessionAt >= processStartedAtMs - PROCESS_SESSION_SKEW_MS &&
            sessionAt <= nowMs + FUTURE_SKEW_MS
    }

    internal data class ActiveGameState(val active: Boolean, val mulligan: Boolean, val marker: String)

    internal fun classifyActiveGame(lines: Sequence<String>): ActiveGameState {
        var sawCreate = false
        var terminalAfterCreate = false
        var mulligan = false
        var marker = "no-create-game"
        lines.forEach { line ->
            when {
                line.contains("CREATE_GAME") -> {
                    sawCreate = true
                    terminalAfterCreate = false
                    mulligan = false
                    marker = "CREATE_GAME"
                }
                sawCreate && (line.contains("PLAYSTATE value=WON") || line.contains("PLAYSTATE value=LOST")) -> {
                    terminalAfterCreate = true
                    mulligan = false
                    marker = "terminal-playstate"
                }
                sawCreate && !terminalAfterCreate &&
                    (line.contains("BEGIN_MULLIGAN") || line.contains("MULLIGAN_STATE")) -> {
                    mulligan = true
                    marker = if (line.contains("MULLIGAN_STATE")) "MULLIGAN_STATE" else "BEGIN_MULLIGAN"
                }
                sawCreate && !terminalAfterCreate -> marker = "active-game-progress"
            }
        }
        return ActiveGameState(sawCreate && !terminalAfterCreate, sawCreate && !terminalAfterCreate && mulligan, marker)
    }

    private fun readTail(file: File): Sequence<String> {
        val lines = RandomAccessFile(file, "r").use { input ->
            val start = (input.length() - MAX_SCAN_BYTES).coerceAtLeast(0L)
            input.seek(start)
            if (start > 0L) input.readLine() // discard partial UTF-8/Power.log line
            buildList {
                while (input.filePointer < input.length()) {
                    add(input.readLine() ?: break)
                }
            }
        }
        return lines.asSequence()
    }
}
