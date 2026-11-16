package club.xiaojiawei.hsscript.status

/** One accepted Confirm dispatch per continuous, process-bound startup error modal. */
internal class StartupErrorDialogDismissalGate {
    data class Decision(val allowed: Boolean, val attempt: Int, val reason: String)

    private var processId: Long? = null
    private var windowId: String? = null
    private var acceptedAttempts = 0
    private var reservationInFlight = false

    @Synchronized
    fun tryBegin(processId: Long, windowId: String): Decision {
        if (processId <= 0L || windowId.isBlank()) {
            return Decision(false, acceptedAttempts, "invalid-window")
        }
        if (this.processId != processId || this.windowId != windowId) {
            this.processId = processId
            this.windowId = windowId
            acceptedAttempts = 0
            reservationInFlight = false
        }
        if (acceptedAttempts >= MAX_ACCEPTED_ATTEMPTS) {
            return Decision(false, acceptedAttempts, "confirm-already-accepted-awaiting-transition")
        }
        if (reservationInFlight) {
            return Decision(false, acceptedAttempts + 1, "confirm-dispatch-in-flight")
        }
        reservationInFlight = true
        return Decision(true, acceptedAttempts + 1, "fresh-error-modal-confirmed")
    }

    /** Count the one-shot budget only after the input adapter accepts the dispatch. */
    @Synchronized
    fun resolve(processId: Long, windowId: String, inputAccepted: Boolean): Boolean {
        if (this.processId != processId || this.windowId != windowId || !reservationInFlight) return false
        reservationInFlight = false
        if (inputAccepted) acceptedAttempts++
        return inputAccepted
    }

    /** A fresh non-modal frame starts a new error-dialog incident budget. */
    @Synchronized
    fun reset() {
        processId = null
        windowId = null
        acceptedAttempts = 0
        reservationInFlight = false
    }

    companion object {
        const val MAX_ACCEPTED_ATTEMPTS = 1
    }
}
