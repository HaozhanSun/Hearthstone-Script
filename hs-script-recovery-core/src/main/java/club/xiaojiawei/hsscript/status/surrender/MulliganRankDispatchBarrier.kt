package club.xiaojiawei.hsscript.status.surrender

/** Per-game barrier between Mulligan INPUT and the first ordinary UI action. */
object MulliganRankDispatchBarrier {
    enum class State { IDLE, PENDING, ELIGIBLE, SURRENDER_REQUIRED }
    class SurrenderCapability internal constructor(internal val generation: Long)

    private val lock = Any()
    @Volatile private var state = State.IDLE
    private var generation = 0L
    private var surrenderCapability: SurrenderCapability? = null
    private var surrenderCapabilityConsumed = false

    fun beginCurrentGame(): Long = synchronized(lock) {
        generation++
        state = State.PENDING
        surrenderCapability = null
        surrenderCapabilityConsumed = false
        generation
    }

    fun authorizeEligibleRank(ticket: Long, rank: Int): Boolean = synchronized(lock) {
        if (ticket != generation || state != State.PENDING || !isEligibleRank(rank)) return false
        state = State.ELIGIBLE
        true
    }

    fun requireSurrender(ticket: Long): SurrenderCapability? = synchronized(lock) {
        if (ticket != generation || state != State.PENDING) return null
        state = State.SURRENDER_REQUIRED
        SurrenderCapability(ticket).also { surrenderCapability = it }
    }

    /** An authoritative terminal Power.log state wins over any late rank decision. */
    fun completeTerminalWithoutSurrender(ticket: Long): Boolean = synchronized(lock) {
        if (ticket != generation || state !in setOf(State.PENDING, State.SURRENDER_REQUIRED)) return false
        state = State.IDLE
        surrenderCapability = null
        surrenderCapabilityConsumed = false
        true
    }

    fun completeSurrender(): Boolean = synchronized(lock) {
        if (state != State.SURRENDER_REQUIRED) return false
        state = State.IDLE
        surrenderCapability = null
        surrenderCapabilityConsumed = false
        true
    }

    fun resetForNewGame() = synchronized(lock) {
        generation++
        state = State.IDLE
        surrenderCapability = null
        surrenderCapabilityConsumed = false
    }

    fun currentState(): State = state

    fun isSurrenderCapabilityValid(capability: SurrenderCapability?): Boolean = synchronized(lock) {
        state == State.SURRENDER_REQUIRED && capability != null && capability === surrenderCapability &&
            capability.generation == generation && !surrenderCapabilityConsumed
    }

    fun consumeSurrenderCapability(capability: SurrenderCapability?): Boolean = synchronized(lock) {
        if (!isSurrenderCapabilityValid(capability)) return false
        surrenderCapabilityConsumed = true
        true
    }

    fun resetForTest() = synchronized(lock) {
        generation = 0L
        state = State.IDLE
        surrenderCapability = null
        surrenderCapabilityConsumed = false
    }

    private fun isEligibleRank(rank: Int): Boolean = rank == 5 || rank == 10 || rank > 20
}
