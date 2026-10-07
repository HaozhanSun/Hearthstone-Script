package club.xiaojiawei.hsscript.status

import java.io.File
import java.io.RandomAccessFile

/** Bounded, read-only guard for startup when the current-session listener is not bound yet. */
object PowerLogActiveMatchProbe {
    const val MAX_SCAN_BYTES = 4L * 1024L * 1024L
    private const val PROCESS_START_CLOCK_SKEW_TOLERANCE_MS = 10_000L
    enum class State { NO_MATCH, ACTIVE_MATCH, TERMINAL, UNREADABLE }
    data class Evidence(val state: State, val reason: String, val fileLength: Long = 0L)

    fun inspect(file: File?, currentProcessStartedAtMs: Long? = null): Evidence {
        if (file == null || !file.exists()) return Evidence(State.NO_MATCH, "power-log-not-created")
        if (!file.isFile || !file.canRead()) return Evidence(State.UNREADABLE, "power-log-not-readable")
        if (currentProcessStartedAtMs == null ||
            file.lastModified() < currentProcessStartedAtMs - PROCESS_START_CLOCK_SKEW_TOLERANCE_MS
        ) {
            return Evidence(State.UNREADABLE, "power-log-not-proven-current-process-session", file.length())
        }
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                val end = input.length()
                if (end == 0L) return@use Evidence(State.NO_MATCH, "power-log-empty", 0L)
                val start = (end - MAX_SCAN_BYTES).coerceAtLeast(0L)
                input.seek(start)
                if (start > 0L) input.readLine()
                val lines = sequence { while (input.filePointer < end) yield(input.readLine() ?: break) }
                assess(lines, end, truncatedAtBeginning = start > 0L)
            }
        }.getOrElse { Evidence(State.UNREADABLE, "power-log-read-failed") }
    }

    fun assess(
        lines: Sequence<String>,
        fileLength: Long = 0L,
        truncatedAtBeginning: Boolean = false,
    ): Evidence {
        var gameSeen = false
        var active = false
        var terminal = false
        lines.forEach { line ->
            when {
                line.contains("CREATE_GAME") -> { gameSeen = true; active = false; terminal = false }
                gameSeen && ACTIVE_MARKERS.any(line::contains) -> active = true
                gameSeen && TERMINAL_MARKERS.any(line::contains) -> terminal = true
            }
        }
        return when {
            gameSeen && terminal -> Evidence(State.TERMINAL, "latest-game-terminal", fileLength)
            gameSeen && active -> Evidence(State.ACTIVE_MATCH, "latest-game-active-phase", fileLength)
            gameSeen -> Evidence(State.UNREADABLE, "latest-game-phase-incomplete", fileLength)
            truncatedAtBeginning -> Evidence(State.UNREADABLE, "scan-window-misses-game-boundary", fileLength)
            else -> Evidence(State.NO_MATCH, "no-game-segment", fileLength)
        }
    }

    private val ACTIVE_MARKERS = listOf(
        "tag=MULLIGAN_STATE value=INPUT", "tag=MULLIGAN_STATE value=DEALING",
        "tag=MULLIGAN_STATE value=WAITING", "tag=MULLIGAN_STATE value=DONE",
        "tag=STEP value=MAIN_ACTION", "tag=NEXT_STEP value=MAIN_ACTION",
    )
    private val TERMINAL_MARKERS = listOf(
        "tag=STEP value=FINAL_GAMEOVER", "tag=PLAYSTATE value=WON",
        "tag=PLAYSTATE value=LOST", "tag=PLAYSTATE value=CONCEDED",
    )
}
