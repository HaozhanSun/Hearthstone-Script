package club.xiaojiawei.hsscript.status

/** Narrow permission to back out of an unconfirmed startup deck chooser to select the configured format. */
internal object StartupDeckModeNavigationPolicy {
    const val ACTION = "startup.mode-return-to-selector"

    enum class Denial {
        NONE,
        WRONG_PURPOSE,
        WRONG_SCREEN,
        WEAK_SCREEN_PROOF,
        NO_ENABLED_START_CONTROL,
        MODAL_VISIBLE,
        MODE_ALREADY_CONFIRMED,
        UNSUPPORTED_TARGET,
        SESSION_READY,
        ACTIVE_GAME,
        ACTIVE_QUEUE,
        TERMINAL,
        UNTRUSTED_WINDOW,
    }

    data class Decision(val allowed: Boolean, val denial: Denial)

    fun decide(
        purpose: String,
        screenKind: String,
        confidence: Int,
        evidence: String,
        expectedRunMode: String?,
        observedRunMode: String?,
        enabledStartButtonVisible: Boolean,
        modalVisible: Boolean,
        currentSessionReady: Boolean,
        activeGame: Boolean,
        activeMatchmaking: Boolean,
        terminal: Boolean,
        trustedCurrentWindow: Boolean,
    ): Decision {
        val denial = when {
            purpose !in setOf("STARTUP_CLIENT_PROBE", "STALE_CLIENT_PROBE") -> Denial.WRONG_PURPOSE
            screenKind != "DECK_SELECTION" -> Denial.WRONG_SCREEN
            confidence < 90 || evidence !in setOf(
                "deck-selection-title", "deck-selection-title-roi", "deck-selection-start-control-visual",
            ) ->
                Denial.WEAK_SCREEN_PROOF
            !enabledStartButtonVisible -> Denial.NO_ENABLED_START_CONTROL
            modalVisible -> Denial.MODAL_VISIBLE
            expectedRunMode !in setOf("STANDARD", "WILD") -> Denial.UNSUPPORTED_TARGET
            observedRunMode == expectedRunMode -> Denial.MODE_ALREADY_CONFIRMED
            currentSessionReady -> Denial.SESSION_READY
            activeGame -> Denial.ACTIVE_GAME
            activeMatchmaking -> Denial.ACTIVE_QUEUE
            terminal -> Denial.TERMINAL
            !trustedCurrentWindow -> Denial.UNTRUSTED_WINDOW
            else -> Denial.NONE
        }
        return Decision(denial == Denial.NONE, denial)
    }

    /** The final input gate rechecks every runtime fence when the Back click is dispatched. */
    fun allowsDispatch(
        action: String,
        proofFresh: Boolean,
        exactWindowVerified: Boolean,
        currentSessionReady: Boolean,
        activeGame: Boolean,
        activeMatchmaking: Boolean,
        terminal: Boolean,
    ): Boolean = action == ACTION && proofFresh && exactWindowVerified &&
        !currentSessionReady && !activeGame && !activeMatchmaking && !terminal

    /** Keeps the integration boundary explicit: only an allowed Back action can reach the dispatcher. */
    fun dispatchIfAllowed(decision: Decision, dispatch: () -> Boolean): Boolean =
        decision.allowed && dispatch()

    /** Production/test seam: the tooltip-specific image proof permits only Return, then demands re-probe. */
    fun dispatchWrongModeTooltipReturn(
        image: java.awt.image.BufferedImage,
        expectedMode: String?,
        decision: Decision,
        dispatchReturn: () -> Boolean,
        requestFreshSelectorProbe: () -> Unit,
    ): Boolean {
        if (expectedMode != "WILD" || !ScreenStateRecovery.isWrongModeDeckTooltip(image) || !decision.allowed) return false
        val accepted = dispatchReturn()
        if (accepted) requestFreshSelectorProbe()
        return accepted
    }
}

/** One automatic Back attempt per continuous startup deck-selection screen. */
internal class StartupDeckModeNavigationAttemptGate {
    data class Decision(val allowed: Boolean, val attempt: Int, val reason: String)

    private var processId: Int? = null
    private var hwnd: String? = null
    private var attempts = 0

    @Synchronized
    fun tryBegin(pid: Int, window: String): Decision {
        if (pid <= 0 || window.isBlank()) return Decision(false, attempts, "invalid-window")
        if (processId != pid || hwnd != window) {
            processId = pid
            hwnd = window
            attempts = 0
        }
        if (attempts >= MAX_ATTEMPTS) return Decision(false, attempts, "attempt-budget-exhausted")
        attempts++
        return Decision(true, attempts, "single-safe-navigation-attempt")
    }

    @Synchronized
    fun reset() {
        processId = null
        hwnd = null
        attempts = 0
    }

    companion object {
        const val MAX_ATTEMPTS = 1
    }
}
