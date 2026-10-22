package club.xiaojiawei.hsscript.utils

import kotlin.math.roundToInt

/** Timing policy for normal gameplay cursor movement paths. */
internal object GameplayMouseMovementTiming {
    const val GAMEPLAY_SPEED_FACTOR = 1.5
    const val MIN_STEP_DELAY_MS = 1
    const val MAX_BASE_STEP_DELAY_MS = 18
    const val MAX_SCALED_STEP_DELAY_MS = 12

    /** Scale one sampled-point delay while keeping it in the supported range. */
    fun scaleStepDelayMs(baseDelayMs: Int): Int =
        (baseDelayMs.coerceIn(MIN_STEP_DELAY_MS, MAX_BASE_STEP_DELAY_MS) / GAMEPLAY_SPEED_FACTOR)
            .roundToInt()
            .coerceIn(MIN_STEP_DELAY_MS, MAX_SCALED_STEP_DELAY_MS)
}
