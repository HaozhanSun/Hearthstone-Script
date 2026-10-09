package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.status.FreshPostResultDestinationPolicy

/**
 * Owns one result-page cleanup episode across the normal game-over callback
 * and later screen-recovery callbacks. Input acceptance is deliberately not
 * represented here; only a fresh destination observation can complete it.
 */
class TerminalPageCleanupCoordinator(
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

    @Synchronized
    fun reserveRankProgressInput(ticket: Ticket, maxRankProgressInputs: Int): Int? {
        if (!isActive(ticket)) return null
        if (rankProgressInputs >= maxRankProgressInputs) return null
        val input = reserveInput(ticket) ?: return null
        rankProgressInputs += 1
        return input
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(generation, state, probes, inputs, rankProgressInputs, failedEpisodeRearms, failureReason)

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

    /**
     * Complete the active terminal-cleanup flight from a fresh, recognized
     * destination observation made by another recovery consumer. This is
     * deliberately stricter than input acceptance: the caller must still
     * provide both the current terminal capability and a foreground-authorized
     * HOME/DECK_SELECTION/MATCHMAKING frame.
     */
    @Synchronized
    fun confirmFreshDestination(
        screenKind: String?,
        confidence: Int,
        freshCaptureAuthorized: Boolean,
        terminalCleanupAuthorized: Boolean,
        confirmTerminalCleanup: () -> Boolean,
    ): Boolean {
        if (!FreshPostResultDestinationPolicy.isConfirmed(
                screenKind,
                confidence,
                freshCaptureAuthorized,
            ) || !terminalCleanupAuthorized
        ) return false
        if (state == State.RUNNING) expireIfOverdue()
        if (state !in setOf(State.RUNNING, State.HELD, State.FAILED)) return false
        if (!confirmTerminalCleanup()) return false
        activeTicket = null
        state = State.COMPLETED
        return true
    }

    @Synchronized
    fun hold(ticket: Ticket): Boolean {
        if (!isActive(ticket)) return false
        holdCurrent()
        return true
    }

    @Synchronized
    fun fail(ticket: Ticket, reason: String): Boolean {
        if (!isActive(ticket)) return false
        failCurrent(reason)
        return true
    }

    @Synchronized
    fun interrupt(ticket: Ticket): Boolean {
        if (!isActive(ticket)) return false
        activeTicket = null
        state = State.IDLE
        return true
    }

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

    /**
     * A deadline may be renewed once only when the same terminal capability
     * and a fresh authorized frame still proves that the same terminal
     * continuation is shown. This includes the result page and its
     * proof-gated rank-progress Continue screen; neither grants matchmaking.
     * Global input/probe budgets remain cumulative, including already queued
     * or rejected inputs; the click result itself is never acceptance proof.
     */
    @Synchronized
    fun rearmAfterDeadline(
        failedTicket: Ticket,
        paused: Boolean,
        terminalCleanupAuthorized: Boolean,
        freshCaptureAuthorized: Boolean,
        terminalContinuationVisible: Boolean,
    ): Ticket? {
        if (paused || !terminalCleanupAuthorized || !freshCaptureAuthorized || !terminalContinuationVisible) return null
        if (failedTicket.generation != generation || state != State.FAILED) return null
        if (failureReason != "episode-deadline-exceeded") return null
        if (failedEpisodeRearms >= MAX_FAILED_EPISODE_REARMS || probes >= maxProbes || inputs >= maxInputs) return null
        val ticket = Ticket(generation)
        activeTicket = ticket
        state = State.RUNNING
        failureReason = null
        startedAtMillis = monotonicTimeMillis()
        failedEpisodeRearms += 1
        return ticket
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
        const val DEFAULT_MAX_INPUTS = 16
        const val DEFAULT_MAX_PROBES = 20
        /** Result artwork is normally present within the game-over screenshot delay. */
        const val DEFAULT_INITIAL_PROBE_DELAY_MILLIS = 5_000L
        /** Keep the bounded fallback responsive without sending a click burst. */
        const val DEFAULT_PROBE_INTERVAL_MILLIS = 8_000L
        /** A terminal cleanup may never defer all ordinary menu input for a minute. */
        const val DEFAULT_MAX_DURATION_MILLIS = 55_000L
        const val MAX_FAILED_EPISODE_REARMS = 1
    }
}
