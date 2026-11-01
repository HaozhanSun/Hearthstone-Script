package club.xiaojiawei.hsscript.status

/** Narrow startup permissions for safely correcting a freshly confirmed configured-format mismatch. */
internal object StartupDeckModeNavigationPolicy {
    const val ACTION = "startup.mode-return-to-selector"
    const val OPEN_SELECTOR_ACTION = "startup.mode-open-selector"
    const val SELECT_WILD_ACTION = "startup.mode-select-wild"
    const val MODE_SELECTOR_SCREEN = "TOURNAMENT_MODE_SELECTOR"

    fun isModeProofProbe(purpose: ScreenRecoveryCapturePurpose): Boolean =
        purpose == ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE ||
            purpose == ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE ||
            purpose == ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE ||
            purpose == ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY

    /**
     * A deck-selection probe is read-only until a fresh, mode-matched Start proof
     * exists. Once that exact proof is recorded, the normal final matchmaking
     * dispatch gate is responsible for the one Start input, regardless of which
     * probe discovered the screen. In particular, an idle probe must not route
     * an already-confirmed mode into mismatch correction (or suppress Start).
     */
    fun requiresClassificationOnly(
        purpose: ScreenRecoveryCapturePurpose,
        screenKind: String,
        freshConfiguredModeStartProof: Boolean,
    ): Boolean = screenKind == "DECK_SELECTION" &&
        (!isModeProofProbe(purpose) || !freshConfiguredModeStartProof)

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
        wrongModeDeckTooltipVisible: Boolean = false,
    ): Decision {
        val exactWildMismatchTooltip = wrongModeDeckTooltipVisible &&
            expectedRunMode == "WILD" && observedRunMode == "STANDARD"
        val denial = when {
            purpose !in setOf(
                "STARTUP_CLIENT_PROBE", "STALE_CLIENT_PROBE", "IDLE_CLIENT_PROBE", "SCREEN_STATE_RECOVERY",
            ) -> Denial.WRONG_PURPOSE
            screenKind != "DECK_SELECTION" -> Denial.WRONG_SCREEN
            confidence < 90 || evidence !in setOf(
                "deck-selection-title", "deck-selection-title-roi", "deck-selection-start-control-visual",
            ) ->
                Denial.WEAK_SCREEN_PROOF
            !enabledStartButtonVisible && !exactWildMismatchTooltip -> Denial.NO_ENABLED_START_CONTROL
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
    ): Boolean = action in setOf(ACTION, OPEN_SELECTOR_ACTION, SELECT_WILD_ACTION) &&
        proofFresh && exactWindowVerified &&
        !currentSessionReady && !activeGame && !activeMatchmaking && !terminal

    /** Keeps the integration boundary explicit: only an allowed Back action can reach the dispatcher. */
    fun dispatchIfAllowed(decision: Decision, dispatch: () -> Boolean): Boolean =
        decision.allowed && dispatch()

    /** Production/test seam: exact tooltip or confirmed Standard title permits Return, then demands re-probe. */
    fun dispatchWrongModeTooltipReturn(
        image: java.awt.image.BufferedImage,
        expectedMode: String?,
        decision: Decision,
        dispatchReturn: () -> Boolean,
        requestFreshSelectorProbe: () -> Unit,
    ): Boolean {
        val exactModeMismatch = TournamentModeConfirmation.isConfirmedModeMismatch(image, expectedMode)
        if (expectedMode != "WILD" ||
            (!ScreenStateRecovery.isWrongModeDeckTooltip(image) && !exactModeMismatch) ||
            !decision.allowed
        ) return false
        val accepted = dispatchReturn()
        if (accepted) requestFreshSelectorProbe()
        return accepted
    }

    /** Open only the mode crest from a fresh, confirmed Standard/Wild-mismatch page; selection needs a later probe. */
    fun dispatchOpenSelectorForVerifiedDeckMismatch(
        image: java.awt.image.BufferedImage,
        expectedMode: String?,
        decision: Decision,
        dispatchOpenSelector: () -> Boolean,
        requestFreshSelectorProbe: () -> Unit,
    ): Boolean {
        val exactOppositeMode = expectedMode == "WILD" && (
            TournamentModeConfirmation.isConfirmedModeMismatch(image, expectedMode) ||
                ScreenStateRecovery.isWrongModeDeckTooltip(image)
            )
        if (!exactOppositeMode || !decision.allowed) return false
        val accepted = dispatchOpenSelector()
        if (accepted) requestFreshSelectorProbe()
        return accepted
    }

    /** Mode option text is actionable only on the freshly captured centered selector modal. */
    fun hasFreshWildSelectorEvidence(
        screenKind: String?,
        modalVisible: Boolean,
        wildTargetLabelVisible: Boolean,
        ocrText: String,
    ): Boolean {
        val text = ocrText.lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), "")
        val hasStandard = text.contains("标准对战") || text.contains("标准模式")
        return screenKind == MODE_SELECTOR_SCREEN && modalVisible && wildTargetLabelVisible && hasStandard
    }

}

/** One automatic Back attempt per continuous startup deck-selection screen. */
internal class StartupDeckModeNavigationAttemptGate {
    data class Decision(val allowed: Boolean, val attempt: Int, val reason: String)

    private var processId: Int? = null
    private var hwnd: String? = null
    private var attempts = 0
    private var reservationInFlight = false

    @Synchronized
    fun tryBegin(pid: Int, window: String): Decision {
        if (pid <= 0 || window.isBlank()) return Decision(false, attempts, "invalid-window")
        if (processId != pid || hwnd != window) {
            processId = pid
            hwnd = window
            attempts = 0
            reservationInFlight = false
        }
        if (attempts >= MAX_ATTEMPTS) return Decision(false, attempts, "attempt-budget-exhausted")
        if (reservationInFlight) return Decision(false, attempts + 1, "navigation-dispatch-in-flight")
        reservationInFlight = true
        return Decision(true, attempts + 1, "reserved-until-input-accepted")
    }

    /** Commit the one-attempt budget only after the final desktop dispatcher accepted the click. */
    @Synchronized
    fun resolve(pid: Int, window: String, inputAccepted: Boolean): Boolean {
        if (processId != pid || hwnd != window || !reservationInFlight) return false
        reservationInFlight = false
        if (inputAccepted) attempts++
        return inputAccepted
    }

    @Synchronized
    fun reset() {
        processId = null
        hwnd = null
        attempts = 0
        reservationInFlight = false
    }

    companion object {
        const val MAX_ATTEMPTS = 1
    }
}
