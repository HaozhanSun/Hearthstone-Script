package club.xiaojiawei.hsscriptbase.util

import java.security.SecureRandom
import java.util.Random
import kotlin.math.max

/**
 * 随机数生成工具
 * @author 肖嘉威
 * @date 2022/11/24 19:41
 */
object RandomUtil {

    /**
     * One random stream shared by the app, card SDK, and strategy plugins.
     * The stream is re-seeded at the beginning of each matchmaking/game run.
     */
    val RANDOM = Random()

    private val SEED_SOURCE = SecureRandom()

    @Volatile
    var currentSeed: Long = 0L
        private set

    init {
        rerollSeed()
    }

    /**
     * Start a new random sequence for the next run. Keeping this in one place
     * makes all random delays and random selections share the same per-run seed.
     */
    @Synchronized
    fun rerollSeed(): Long {
        currentSeed = SEED_SOURCE.nextLong()
        RANDOM.setSeed(currentSeed)
        return currentSeed
    }

    @Synchronized
    fun nextInt(bound: Int): Int = RANDOM.nextInt(bound)

    @Synchronized
    fun nextBoolean(): Boolean = RANDOM.nextBoolean()

    fun getRandom(min: Int, max: Int): Int {
        if (min == max) return min
        val low = minOf(min, max)
        val high = maxOf(min, max)
        return synchronized(RANDOM) {
            (RANDOM.nextDouble() * (high - low + 1) + low).toInt()
        }
    }

    fun getRandom(min: Double, max: Double): Double {
        if (min == max) return min
        val low = minOf(min, max)
        val high = maxOf(min, max)
        return synchronized(RANDOM) {
            RANDOM.nextDouble(low, high)
        }
    }

    fun getRandomAround(value: Int, variance: Int): Int {
        val safeVariance = max(0, variance)
        return getRandom(value - safeVariance, value + safeVariance)
    }

    /** Randomized equivalent of the configured card/attack action interval. */
    fun getActionInterval(configuredInterval: Int): Int {
        val safeInterval = max(1, configuredInterval)
        return getRandomAround(safeInterval, max(100, safeInterval / 10))
    }

    /** Mulligan startup wait: 20 seconds +/- 1 second, plus distortion time. */
    fun getMulliganDelay(distortionEnabled: Boolean): Int {
        val baseDelay = getRandomAround(20_000, 1_000)
        val distortionDelay = if (distortionEnabled) getRandomAround(4_500, 500) else 0
        return baseDelay + distortionDelay
    }

    /** Beginning-of-turn animation wait: 5 seconds +/- 500 milliseconds. */
    fun getTurnStartDelay(): Int = getRandomAround(5_000, 500)

    /** Short non-action pauses retain their old 150 ms center point. */
    fun getShortActionDelay(): Int = getRandomAround(150, 50)

    /** Randomized mode-entry polling around the former 1 second/5 second values. */
    fun getModeEntryDelay(): Long = getRandom(750, 1_250).toLong()

    fun getModeEntryInterval(): Long = getRandom(4_000, 6_000).toLong()

    /**
     * Randomized delay for a user-facing transition or retry.  This is kept
     * separate from the exact delay API so polling/timeout code can remain
     * deterministic where that is important for recovery.
     */
    fun getInteractionDelay(center: Int): Int {
        val safeCenter = max(1, center)
        return getRandomAround(safeCenter, max(25, safeCenter / 10))
    }

    /** Small, varied pauses between sampled mouse-path points. */
    fun getMouseStepDelay(): Int = getRandom(7, 18)

    fun <T> shuffle(list: MutableList<T>) {
        synchronized(RANDOM) {
            for (index in list.lastIndex downTo 1) {
                val swapIndex = RANDOM.nextInt(index + 1)
                val value = list[index]
                list[index] = list[swapIndex]
                list[swapIndex] = value
            }
        }
    }

    fun getHugeRandom(): Int {
        return getRandom(3000, 5000)
    }

    fun getLongRandom(): Int {
        return getRandom(2000, 3000)
    }

    fun getMediumRandom(): Int {
        return getRandom(1000, 1600)
    }

    fun getShortMediumRandom(): Int {
        return getRandom(500, 800)
    }

    fun getShortRandom(): Int {
        return getRandom(250, 450)
    }

    fun getTinyRandom(): Int {
        return getRandom(100, 200)
    }

    fun getHumanRandom(): Int {
        return getRandom(200, 2000)
    }

}
