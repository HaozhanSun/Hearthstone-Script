package club.xiaojiawei.hsscript.status

/** Coalesces repeated input denials while the post-result screen is being confirmed. */
internal class TerminalCleanupBlockLogThrottle(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val monotonicTimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Entry(var lastLoggedAtMillis: Long, var suppressed: Int)

    private val entries = mutableMapOf<String, Entry>()

    init {
        require(intervalMillis > 0)
    }

    /** Returns the number of suppressed repeats when a first/summary log is due. */
    @Synchronized
    fun onBlocked(action: String): Int? {
        val now = monotonicTimeMillis()
        val entry = entries[action]
        if (entry == null) {
            entries[action] = Entry(now, 0)
            return 0
        }
        entry.suppressed += 1
        if (now - entry.lastLoggedAtMillis < intervalMillis) return null
        val suppressed = entry.suppressed
        entry.lastLoggedAtMillis = now
        entry.suppressed = 0
        return suppressed
    }

    @Synchronized
    fun onAllowed(action: String) {
        entries.remove(action)
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 15_000L
    }
}
