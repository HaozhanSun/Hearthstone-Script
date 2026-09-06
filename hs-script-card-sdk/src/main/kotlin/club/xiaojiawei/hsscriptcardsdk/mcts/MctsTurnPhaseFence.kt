package club.xiaojiawei.hsscriptcardsdk.mcts

/**
 * Monotonic phase memory for a receding-horizon turn controller.
 *
 * A new MCTS root is intentionally built after every live action, but the
 * root must not forget phases already completed earlier in the same action
 * cycle. A full live rescan may start a new cycle, which is how a dynamic
 * cost reduction becomes eligible after combat without being misclassified as
 * an ordering violation.
 */
class MctsTurnPhaseFence(initialCycle: Int = 1) {
    private var highestCompletedPhase: MctsActionOrderPhase? = null
    private var heroAttackCompleted = false
    private var postHeroAttackLocationConsumed = false
    private var cycle = initialCycle.coerceAtLeast(1)

    fun isActive(): Boolean =
        highestCompletedPhase != null || heroAttackCompleted

    /** Begin a new cycle after a full live rescan found additional work. */
    fun startNewCycle(): Int {
        cycle++
        highestCompletedPhase = null
        heroAttackCompleted = false
        postHeroAttackLocationConsumed = false
        return cycle
    }

    fun allows(
        phase: MctsActionOrderPhase?,
        isEndTurn: Boolean,
        endTurnLegal: Boolean,
    ): Boolean {
        if (isEndTurn) return endTurnLegal
        phase ?: return false
        if (heroAttackCompleted) {
            return !postHeroAttackLocationConsumed &&
                phase === MctsActionOrderPhase.POST_HERO_ATTACK_LOCATION
        }
        val highest = highestCompletedPhase ?: return true
        return phase.monotonicRank >= highest.monotonicRank
    }

    fun observe(phase: MctsActionOrderPhase?) {
        when (phase) {
            MctsActionOrderPhase.CLIFFSIDE_HERO_ATTACK,
            MctsActionOrderPhase.HERO_ATTACK -> {
                heroAttackCompleted = true
                highestCompletedPhase = phase
            }
            MctsActionOrderPhase.POST_HERO_ATTACK_LOCATION -> {
                highestCompletedPhase = phase
                if (heroAttackCompleted) postHeroAttackLocationConsumed = true
            }
            null -> Unit
            else -> {
                val current = highestCompletedPhase
                if (current == null || phase.monotonicRank > current.monotonicRank) {
                    highestCompletedPhase = phase
                }
            }
        }
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "cycle" to cycle,
        "highestCompletedPhase" to highestCompletedPhase?.name,
        "heroAttackCompleted" to heroAttackCompleted,
        "postHeroAttackLocationConsumed" to postHeroAttackLocationConsumed,
    )
}
