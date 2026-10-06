package club.xiaojiawei.hsscript.utils

/**
 * Owns one result-page cleanup episode across the normal game-over callback
 * and later screen-recovery callbacks. Input acceptance is deliberately not
 * represented here; only a fresh destination observation can complete it.
 */
internal class TerminalPageCleanupCoordinator(
    private val maxInputs: Int = DEFAULT_MAX_INPUTS,
    private val maxProbes: Int = DEFAULT_MAX_PROBES,
    private val maxDurationMillis: Long = DEFAULT_MAX_DURATION_MILLIS,
    private val monotonicTimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    enum class State { IDLE, RUNNING, HELD, FAILED, COMPLETED }
    enum class BeginState { STARTED, ALREADY_RUNNING, HELD, FAILED, COMPLETED }

    class Ticket internal constructor(val generation: Long)
    data class BeginResult(val state: BeginState, val ticket: Ticket?)
    data class Snapshot(
        val generation: Long,
        val state: State,
        val probes: Int,
        val inputs: Int,
        val rankProgressInputs: Int,
        val failedEpisodeRearms: Int,
        val failureReason: String?,
    )

    private var generation = 0L
    private var state = State.IDLE
    private var activeTicket: Ticket? = null
    private var probes = 0
    private var inputs = 0
    private var rankProgressInputs = 0
    private var failedEpisodeRearms = 0
    private var failureReason: String? = null
    private var startedAtMillis: Long? = null

    init {
        require(maxInputs > 0)
        require(maxProbes > 0)
        require(maxDurationMillis > 0)
    }

    @Synchronized
    fun begin(): BeginResult {
        expireIfOverdue()
        return when (state) {
            State.RUNNING -> BeginResult(BeginState.ALREADY_RUNNING, activeTicket)
            // Return the generation token so recovery may still prove a fresh
            // destination frame and release the hold without dispatching input.
            State.HELD -> BeginResult(BeginState.HELD, Ticket(generation))
            State.FAILED -> BeginResult(BeginState.FAILED, Ticket(generation))
            State.COMPLETED -> BeginResult(BeginState.COMPLETED, null)
            State.IDLE -> {
                val ticket = Ticket(generation)
                activeTicket = ticket
                state = State.RUNNING
                startedAtMillis = monotonicTimeMillis()
                BeginResult(BeginState.STARTED, ticket)
            }
        }
    }

    /** Returns a shared probe number, or holds the episode after its bound. */
    @Synchronized
    fun nextProbe(ticket: Ticket): Int? {
        if (!isActive(ticket)) return null
        if (expireIfOverdue()) return null
        if (probes >= maxProbes) {
            failCurrent("probe-budget-exhausted")
            return null
        }
        probes += 1
        return probes
    }

    /** Reserves a global input slot before dispatch, across all callbacks. */
    @Synchronized
    fun reserveInput(ticket: Ticket): Int? {
        if (!isActive(ticket)) return null
        if (expireIfOverdue()) return null
        if (inputs >= maxInputs) {
            failCurrent("input-budget-exhausted")
            return null
        }
        inputs += 1
        return inputs
    }

    /** Reserves both the shared input slot and the smaller rank-page retry budget. */
    @Synchronized
    fun reserveRankProgressInput(ticket: Ticket, maxRankProgressInputs: Int): Int? {
        if (!isActive(ticket)) return null
        if (rankProgressInputs >= maxRankProgressInputs) return null
        val input = reserveInput(ticket) ?: return null
        rankProgressInputs += 1
        return input
    }

    /**
     * Permit one new bounded worker only after a deadline failure dispatched
     * no input and a fresh, authorized capture still positively sees RESULT.
     * Probe/input budgets remain cumulative; stale callbacks lose ownership.
     */
    @Synchronized
    fun rearmAfterNoInputDeadline(
        failedTicket: Ticket,
        freshCaptureAuthorized: Boolean,
        resultPageVisible: Boolean?,
    ): Ticket? {
        if (!freshCaptureAuthorized || resultPageVisible != true) return null
        if (failedTicket.generation != generation || state != State.FAILED) return null
        if (failureReason != "episode-deadline-exceeded" || inputs != 0) return null
        if (failedEpisodeRearms >= MAX_FAILED_EPISODE_REARMS || probes >= maxProbes) return null
        val ticket = Ticket(generation)
        activeTicket = ticket
        state = State.RUNNING
        failureReason = null
        startedAtMillis = monotonicTimeMillis()
        failedEpisodeRearms += 1
        return ticket
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(generation, state, probes, inputs, rankProgressInputs, failedEpisodeRearms, failureReason)

    /** Only a separately authorized post-input capture may call this. */
    @Synchronized
    fun confirmDestination(ticket: Ticket): Boolean {
        if (ticket.generation != generation || state == State.COMPLETED) return false
        if (state == State.RUNNING && expireIfOverdue()) return false
        if (state == State.RUNNING && activeTicket != ticket) return false
        if (state != State.RUNNING && state != State.HELD && state != State.FAILED) return false
        activeTicket = null
        state = State.COMPLETED
        return true
    }

    /** Non-confirmation is terminal for this episode: keep ordinary dispatch held. */
    @Synchronized
    fun hold(ticket: Ticket): Boolean {
        if (!isActive(ticket)) return false
        holdCurrent()
        return true
    }

    /** A bounded episode ends visibly and cannot be silently restarted by a callback. */
    @Synchronized
    fun fail(ticket: Ticket, reason: String): Boolean {
        if (!isActive(ticket)) return false
        failCurrent(reason)
        return true
    }

    /** Pause can stop the worker without discarding the shared input budget. */
    @Synchronized
    fun interrupt(ticket: Ticket): Boolean {
        if (!isActive(ticket)) return false
        activeTicket = null
        state = State.IDLE
        return true
    }

    /** A new authoritative CREATE_GAME boundary is the only episode reset. */
    @Synchronized
    fun resetForNewGame() {
        generation += 1
        activeTicket = null
        state = State.IDLE
        probes = 0
        inputs = 0
        rankProgressInputs = 0
        failedEpisodeRearms = 0
        failureReason = null
        startedAtMillis = null
    }

    private fun expireIfOverdue(): Boolean {
        val startedAt = startedAtMillis ?: return false
        if (state != State.RUNNING || monotonicTimeMillis() - startedAt < maxDurationMillis) return false
        failCurrent("episode-deadline-exceeded")
        return true
    }

    private fun isActive(ticket: Ticket): Boolean =
        ticket.generation == generation && state == State.RUNNING && activeTicket == ticket

    private fun holdCurrent() {
        activeTicket = null
        state = State.HELD
    }

    private fun failCurrent(reason: String) {
        activeTicket = null
        state = State.FAILED
        failureReason = reason
    }

    companion object {
        // The v4.16.565 live trace dispatched at least eight inputs across
        // recovery re-entries. Keep a 2x margin, but spend at most one input
        // per fresh, positive result-screen capture.
        const val DEFAULT_MAX_INPUTS = 16
        const val DEFAULT_MAX_PROBES = 20
        const val DEFAULT_MAX_DURATION_MILLIS = 120_000L
        const val MAX_FAILED_EPISODE_REARMS = 1
    }
}
