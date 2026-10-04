package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.interfaces.LogFile

/** Reads only the small raw tail after FINAL_GAMEOVER so terminal evidence is not lost at phase handoff. */
internal object PowerLogTerminalTailReader {
    enum class StopReason { COMPLETE, EOF, NEXT_GAME, LINE_LIMIT, TIME_LIMIT }

    data class Result(val stopReason: StopReason, val linesRead: Int)

    fun observeUntilComplete(
        file: LogFile,
        maxLines: Int,
        deadlineNanos: Long,
        observeLine: (String) -> Unit,
    ): Result {
        var linesRead = 0
        while (linesRead < maxLines) {
            if (System.nanoTime() >= deadlineNanos) return Result(StopReason.TIME_LIMIT, linesRead)
            val lineStart = file.getPosition()
            val line = file.readLine() ?: return Result(StopReason.EOF, linesRead)
            if (line.contains("CREATE_GAME")) {
                // Do not consume the next match boundary while waiting for
                // this game's terminal marker.
                file.seek(lineStart)
                return Result(StopReason.NEXT_GAME, linesRead)
            }
            observeLine(line)
            linesRead++
            if (line.contains("tag=STATE value=COMPLETE")) {
                return Result(StopReason.COMPLETE, linesRead)
            }
        }
        return Result(StopReason.LINE_LIMIT, linesRead)
    }
}
