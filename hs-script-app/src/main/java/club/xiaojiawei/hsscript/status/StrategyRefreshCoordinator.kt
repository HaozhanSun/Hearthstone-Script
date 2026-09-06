package club.xiaojiawei.hsscript.status

/**
 * Coordinates a strategy refresh without changing the strategy used by an
 * already-running turn. A request is only applied at a turn boundary; a
 * loader failure consumes the request but leaves the caller's active strategy
 * unchanged so the automation can continue safely.
 */
class StrategyRefreshCoordinator(
    private val eventSink: (String) -> Unit = {},
) {

    enum class Status {
        NOT_REQUESTED,
        DEFERRED_ACTIVE_TURN,
        APPLIED,
        FAILED,
    }

    data class Outcome<T>(
        val status: Status,
        val requestId: Long? = null,
        val reason: String? = null,
        val previous: T? = null,
        val replacement: T? = null,
        val error: Throwable? = null,
    )

    private data class Pending(val id: Long, val reason: String)

    private val lock = Any()
    private var nextRequestId = 0L
    private var pending: Pending? = null
    private var activeTurn = false

    fun request(reason: String = "manual"): Long {
        synchronized(lock) {
            pending?.let {
                eventSink("STRATEGY_REFRESH_COALESCED requestId=${it.id} reason=${it.reason}")
                return it.id
            }
            val request = Pending(++nextRequestId, reason.ifBlank { "manual" })
            pending = request
            eventSink("STRATEGY_REFRESH_REQUESTED requestId=${request.id} reason=${request.reason}")
            return request.id
        }
    }

    fun markTurnStarted() {
        synchronized(lock) {
            activeTurn = true
        }
    }

    fun markTurnEnded() {
        synchronized(lock) {
            activeTurn = false
        }
    }

    fun hasPendingRequest(): Boolean = synchronized(lock) { pending != null }

    fun <T> applyAtTurnBoundary(
        previous: T?,
        loader: () -> T?,
    ): Outcome<T> {
        val request: Pending
        synchronized(lock) {
            val queued = pending
                ?: return Outcome(Status.NOT_REQUESTED, previous = previous)
            if (activeTurn) {
                eventSink(
                    "STRATEGY_REFRESH_DEFERRED requestId=${queued.id} " +
                        "reason=active-turn"
                )
                return Outcome(
                    Status.DEFERRED_ACTIVE_TURN,
                    requestId = queued.id,
                    reason = queued.reason,
                    previous = previous,
                )
            }
            pending = null
            request = queued
        }

        return try {
            val replacement = loader()
            eventSink(
                "STRATEGY_REFRESH_APPLIED requestId=${request.id} " +
                    "reason=${request.reason}"
            )
            Outcome(
                Status.APPLIED,
                requestId = request.id,
                reason = request.reason,
                previous = previous,
                replacement = replacement,
            )
        } catch (error: Throwable) {
            eventSink(
                "STRATEGY_REFRESH_FAILED requestId=${request.id} " +
                    "reason=${request.reason} error=${error.javaClass.simpleName}:" +
                    "${error.message ?: "unknown"}"
            )
            Outcome(
                Status.FAILED,
                requestId = request.id,
                reason = request.reason,
                previous = previous,
                replacement = previous,
                error = error,
            )
        }
    }
}
