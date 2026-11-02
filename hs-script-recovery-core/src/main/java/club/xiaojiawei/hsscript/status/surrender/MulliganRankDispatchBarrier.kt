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
    private var temporaryRunAuthorized = false

    fun beginCurrentGame(): Long = synchronized(lock) {
        generation++
        state = State.PENDING
        surrenderCapability = null
        surrenderCapabilityConsumed = false
        temporaryRunAuthorized = false
        generation
    }

    fun authorizeEligibleRank(
        ticket: Long,
        rank: Int,
        allowAnyVerifiedNumericRank: Boolean = false,
        authorizationStillValid: () -> Boolean = { true },
    ): Boolean = synchronized(lock) {
        val rankAllowed = rank in ELIGIBLE_RANKS ||
            (allowAnyVerifiedNumericRank && (rank in 1..10 || rank > 20))
        if (ticket != generation || state != State.PENDING || !rankAllowed || !authorizationStillValid()) return false
        state = State.ELIGIBLE
        temporaryRunAuthorized = false
        true
    }

    /** Open the first-action barrier only for an explicitly live temporary-run authorization. */
    fun authorizeTemporaryRun(
        ticket: Long,
        authorizationStillValid: () -> Boolean,
    ): Boolean = synchronized(lock) {
        if (ticket != generation || state != State.PENDING || !authorizationStillValid()) return false
        state = State.ELIGIBLE
        temporaryRunAuthorized = true
        true
    }

    fun hasTemporaryRunAuthorization(): Boolean = synchronized(lock) {
        state == State.ELIGIBLE && temporaryRunAuthorized
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
        temporaryRunAuthorized = false
        true
    }

    /** Complete any rank preflight state after the current game's local terminal result is proven. */
    fun completeForCurrentTerminalResult(): Boolean = synchronized(lock) {
        if (state == State.IDLE) return true
        state = State.IDLE
        surrenderCapability = null
        surrenderCapabilityConsumed = false
        temporaryRunAuthorized = false
        true
    }

    fun completeSurrender(): Boolean = synchronized(lock) {
        if (state != State.SURRENDER_REQUIRED) return false
        state = State.IDLE
        surrenderCapability = null
        surrenderCapabilityConsumed = false
        temporaryRunAuthorized = false
        true
    }

    fun resetForNewGame() = synchronized(lock) {
        generation++
        state = State.IDLE
        surrenderCapability = null
        surrenderCapabilityConsumed = false
        temporaryRunAuthorized = false
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
        temporaryRunAuthorized = false
    }

    private val ELIGIBLE_RANKS = setOf(5, 10)
}
