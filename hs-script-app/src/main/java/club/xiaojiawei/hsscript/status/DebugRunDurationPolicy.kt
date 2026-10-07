package club.xiaojiawei.hsscript.status

/** Validation and conversion for the user-selected, bounded Debug Run duration. */
object DebugRunDurationPolicy {
    const val MINUTES_MIN = 1
    const val MINUTES_DEFAULT = 30
    const val MINUTES_MAX = 45

    fun parseMinutes(value: String?): Int? = value
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { it in MINUTES_MIN..MINUTES_MAX }

    fun normalizeMinutes(value: Int): Int = value.takeIf { it in MINUTES_MIN..MINUTES_MAX }
        ?: MINUTES_DEFAULT

    fun toMillis(minutes: Int): Long? =
        minutes.takeIf { it in MINUTES_MIN..MINUTES_MAX }?.toLong()?.times(60_000L)
}
