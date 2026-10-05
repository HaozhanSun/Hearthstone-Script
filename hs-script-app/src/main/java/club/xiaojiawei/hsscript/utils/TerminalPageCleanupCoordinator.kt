package club.xiaojiawei.hsscript.utils

/**
 * Owns one result-page cleanup episode across the normal game-over callback
 * and later screen-recovery callbacks. Input acceptance is deliberately not
 * represented here; only a fresh destination observation can complete it.
 */
internal class TerminalPageCleanupCoordinator(
    private val maxInputs: Int = DEFAULT_MAX_INPUTS,
    private val maxProbes: Int = DEFAULT_MAX_PROBES,
) {
    enum class State { IDLE, RUNNING, HELD, COMPLETED }
    enum class BeginState { STARTED, ALREADY_RUNNING, HELD, COMPLETED }

    class Ticket internal constructor(val generation: Long)
    data class BeginResult(val state: BeginState, val ticket: Ticket?)
    data class Snapshot(val generation: Long, val state: State, val probes: Int, val inputs: Int)

    private var generation = 0L
    private var state = State.IDLE
    private var activeTicket: Ticket? = null
    private var probes = 0
    private var inputs = 0

    init {
        require(maxInputs > 0)
        require(maxProbes > 0)
    }

    @Synchronized
    fun begin(): BeginResult = when (state) {
        State.RUNNING -> BeginResult(BeginState.ALREADY_RUNNING, activeTicket)
        // Return the generation token so recovery may still prove a fresh
        // destination frame and release the hold without dispatching input.
        State.HELD -> BeginResult(BeginState.HELD, Ticket(generation))
        State.COMPLETED -> BeginResult(BeginState.COMPLETED, null)
        State.IDLE -> {
            val ticket = Ticket(generation)
            activeTicket = ticket
            state = State.RUNNING
            BeginResult(BeginState.STARTED, ticket)
        }
    }

    /** Returns a shared probe number, or holds the episode after its bound. */
    @Synchronized
    fun nextProbe(ticket: Ticket): Int? {
        if (!isActive(ticket)) return null
        if (probes >= maxProbes) {
            holdCurrent()
            return null
        }
        probes += 1
        return probes
    }

    /** Reserves a global input slot before dispatch, across all callbacks. */
    @Synchronized
    fun reserveInput(ticket: Ticket): Int? {
        if (!isActive(ticket)) return null
        if (inputs >= maxInputs) {
            holdCurrent()
            return null
        }
        inputs += 1
        return inputs
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(generation, state, probes, inputs)

    /** Only a separately authorized post-input capture may call this. */
    @Synchronized
    fun confirmDestination(ticket: Ticket): Boolean {
        if (ticket.generation != generation || state == State.COMPLETED) return false
        if (state == State.RUNNING && activeTicket != ticket) return false
        if (state != State.RUNNING && state != State.HELD) return false
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
    }

    private fun isActive(ticket: Ticket): Boolean =
        ticket.generation == generation && state == State.RUNNING && activeTicket == ticket

    private fun holdCurrent() {
        activeTicket = null
        state = State.HELD
    }

    companion object {
        const val DEFAULT_MAX_INPUTS = 2
        const val DEFAULT_MAX_PROBES = 4
    }
}
