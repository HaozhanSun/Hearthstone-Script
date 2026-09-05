package club.xiaojiawei.hsscript.utils

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Models the temporary run window requested when the user starts outside the
 * configured schedule.  This class is deliberately independent of JavaFX and
 * the game process so the expiry behavior can be tested without launching the
 * application or Hearthstone.
 */
class StartupRunWindow(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val runIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private var forcedUntil: Instant? = null
    private var activeRunId: String? = null

    data class Snapshot(
        val active: Boolean,
        val runId: String?,
        val deadline: Instant?,
    )

    @Synchronized
    fun beginIfOutsideSchedule(
        durationMinutes: Int,
        inSchedule: Boolean,
        now: Instant = clock.instant(),
    ): Boolean {
        if (inSchedule || durationMinutes <= 0) {
            forcedUntil = null
            activeRunId = null
            return false
        }
        if (isActive(now)) return true

        forcedUntil = now.plus(Duration.ofMinutes(durationMinutes.toLong()))
        activeRunId = runIdFactory()
        return true
    }

    @Synchronized
    fun isActive(now: Instant = clock.instant()): Boolean {
        val until = forcedUntil ?: return false
        if (now.isBefore(until)) return true
        forcedUntil = null
        activeRunId = null
        return false
    }

    @Synchronized
    fun snapshot(now: Instant = clock.instant()): Snapshot {
        val until = forcedUntil ?: return Snapshot(false, null, null)
        if (!now.isBefore(until)) {
            forcedUntil = null
            activeRunId = null
            return Snapshot(false, null, null)
        }
        return Snapshot(true, activeRunId, until)
    }

    @Synchronized
    fun shouldWork(inSchedule: Boolean, now: Instant = clock.instant()): Boolean = inSchedule || isActive(now)

    @Synchronized
    fun clear() {
        forcedUntil = null
        activeRunId = null
    }

    @Synchronized
    fun deadline(): Instant? = forcedUntil
}
