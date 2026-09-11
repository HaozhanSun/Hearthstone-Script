package club.xiaojiawei.hsscript.utils

/**
 * Keeps repeated successful window polling out of the normal operational
 * feed. The full state is still available when file logging is set to DEBUG.
 */
internal class E2EWindowDiscoveryLogGate {

    enum class State {
        FOUND,
        FALLBACK_COORDINATES,
        MISSING,
    }

    enum class Decision {
        INFO_STATE_CHANGE,
        WARN_FAILURE_CHANGE,
        DEBUG_DUPLICATE,
    }

    private var lastFingerprint: String? = null

    @Synchronized
    fun classify(state: State, handle: String?, processId: Int?): Decision {
        val fingerprint = "$state|${handle ?: "none"}|${processId ?: 0}"
        if (fingerprint == lastFingerprint) return Decision.DEBUG_DUPLICATE
        lastFingerprint = fingerprint
        return when (state) {
            State.FOUND -> Decision.INFO_STATE_CHANGE
            State.FALLBACK_COORDINATES, State.MISSING -> Decision.WARN_FAILURE_CHANGE
        }
    }
}

/**
 * Independent protection for the JavaFX feed. It handles a future caller
 * accidentally emitting the same discovery line at INFO without hiding a
 * changed handle, failure state, or recovery.
 */
internal class E2EWindowDiscoveryUiGate {
    private var lastFingerprint: String? = null

    @Synchronized
    fun shouldShow(message: String): Boolean {
        if (!message.startsWith("E2E_WINDOW_DISCOVERY")) return true
        val fingerprint = message.substringBefore(" duplicate=")
        if (fingerprint == lastFingerprint) return false
        lastFingerprint = fingerprint
        return true
    }
}
