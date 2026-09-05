package club.xiaojiawei.hsscript.status

import java.time.Instant

/**
 * Runtime metadata for a temporary schedule bypass.
 *
 * The metadata is intentionally not persisted.  A restart must create a new
 * run and must never resurrect an old live deadline.
 */
data class ScheduleOverrideInfo(
    val source: String,
    val runId: String,
    val deadline: Instant,
    val mode: String,
    val provider: String,
)

/**
 * Prevents an active override from turning a 30-second schedule poll into a
 * stream of misleading outside-hours messages.  A new run id is allowed to
 * emit one fresh audit event.
 */
class ScheduleOverrideLogGate {
    private val lock = Any()
    private var lastSuppressionKey: String? = null

    fun consume(info: ScheduleOverrideInfo): Boolean = synchronized(lock) {
        val key = "${info.source}:${info.runId}"
        if (key == lastSuppressionKey) {
            false
        } else {
            lastSuppressionKey = key
            true
        }
    }

    fun reset() = synchronized(lock) {
        lastSuppressionKey = null
    }
}
