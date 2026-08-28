package club.xiaojiawei.hsscript.utils

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Models the temporary run window requested when the user starts outside the
 * configured schedule.  This class is deliberately independent of JavaFX and
 * the game process so the expiry behavior can be tested without launching the
 * application or Hearthstone.
 */
class StartupRunWindow(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private var forcedUntil: Instant? = null

    @Synchronized
    fun beginIfOutsideSchedule(
        durationMinutes: Int,
        inSchedule: Boolean,
        now: Instant = clock.instant(),
    ): Boolean {
        if (inSchedule || durationMinutes <= 0) {
            forcedUntil = null
            return false
        }
        if (isActive(now)) return true

        forcedUntil = now.plus(Duration.ofMinutes(durationMinutes.toLong()))
        return true
    }

    @Synchronized
    fun isActive(now: Instant = clock.instant()): Boolean {
        val until = forcedUntil ?: return false
        if (now.isBefore(until)) return true
        forcedUntil = null
        return false
    }

    @Synchronized
    fun shouldWork(inSchedule: Boolean, now: Instant = clock.instant()): Boolean = inSchedule || isActive(now)

    @Synchronized
    fun clear() {
        forcedUntil = null
    }

    @Synchronized
    fun deadline(): Instant? = forcedUntil
}
