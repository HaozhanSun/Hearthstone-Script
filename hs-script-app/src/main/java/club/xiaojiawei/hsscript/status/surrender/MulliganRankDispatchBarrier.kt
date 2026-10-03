package club.xiaojiawei.hsscript.status.surrender

/**
 * Per-game hard barrier between our Mulligan INPUT and the first ordinary UI
 * action. Only fresh, policy-verified rank 5/10 evidence releases the barrier.
 */
object MulliganRankDispatchBarrier {
    enum class State { IDLE, PENDING, ELIGIBLE, SURRENDER_REQUIRED }

    class SurrenderCapability internal constructor(internal val generation: Long)

    private val lock = Any()
    @Volatile private var state = State.IDLE
    private var generation = 0L
    private var surrenderCapability: SurrenderCapability? = null
    private var surrenderCapabilityConsumed = false

    internal fun beginCurrentGame(): Long = synchronized(lock) {
        generation++
        state = State.PENDING
        surrenderCapability = null
        surrenderCapabilityConsumed = false
        generation
    }

    internal fun authorizeEligibleRank(ticket: Long, rank: Int): Boolean = synchronized(lock) {
        if (ticket != generation || state != State.PENDING || rank !in ELIGIBLE_RANKS) return false
        state = State.ELIGIBLE
        true
    }

    internal fun requireSurrender(ticket: Long): SurrenderCapability? = synchronized(lock) {
        if (ticket != generation || state != State.PENDING) return null
        state = State.SURRENDER_REQUIRED
        SurrenderCapability(ticket).also { surrenderCapability = it }
    }

    /** An authoritative terminal Power.log state wins over any late rank decision. */
    internal fun completeTerminalWithoutSurrender(ticket: Long): Boolean = synchronized(lock) {
        if (ticket != generation || state !in setOf(State.PENDING, State.SURRENDER_REQUIRED)) return false
        state = State.IDLE
        surrenderCapability = null
        surrenderCapabilityConsumed = false
        true
    }

    /** Release only after the mandatory surrender reached authoritative terminal/out-of-game evidence. */
    internal fun completeSurrender(): Boolean = synchronized(lock) {
        if (state != State.SURRENDER_REQUIRED) return false
        state = State.IDLE
        surrenderCapability = null
        surrenderCapabilityConsumed = false
        true
    }

    internal fun resetForNewGame() = synchronized(lock) {
        generation++
        state = State.IDLE
        surrenderCapability = null
        surrenderCapabilityConsumed = false
    }

    internal fun currentState(): State = state

    internal fun isSurrenderCapabilityValid(capability: SurrenderCapability?): Boolean = synchronized(lock) {
        state == State.SURRENDER_REQUIRED && capability != null &&
            capability === surrenderCapability && capability.generation == generation &&
            !surrenderCapabilityConsumed
    }

    /** The capability authorizes exactly one request, never retries or ordinary game input. */
    internal fun consumeSurrenderCapability(capability: SurrenderCapability?): Boolean = synchronized(lock) {
        if (!isSurrenderCapabilityValid(capability)) return false
        surrenderCapabilityConsumed = true
        true
    }

    internal fun resetForTest() = synchronized(lock) {
        generation = 0L
        state = State.IDLE
        surrenderCapability = null
        surrenderCapabilityConsumed = false
    }

    private val ELIGIBLE_RANKS = setOf(5, 10)
}
