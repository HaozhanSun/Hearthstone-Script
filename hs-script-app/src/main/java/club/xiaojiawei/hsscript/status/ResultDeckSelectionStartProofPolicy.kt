package club.xiaojiawei.hsscript.status

/** Mint the short-lived Start proof only from the exact authorized result-destination frame. */
internal object ResultDeckSelectionStartProofPolicy {
    const val MAX_CAPTURE_AGE_MS = StartupInputReadiness.DECK_SELECTION_START_PROOF_MAX_AGE_MS

    fun record(
        captureAuthorized: Boolean,
        captureAgeMs: Long,
        pid: Int?,
        hwnd: String?,
        confidence: Int,
        evidence: String,
        expectedMode: String?,
        observedMode: String?,
        enabledStartVisible: Boolean,
        modalVisible: Boolean,
        now: Long,
    ): Boolean {
        val configuredMode = expectedMode in setOf("WILD", "STANDARD")
        val selectedModePermitValid = pid != null && hwnd != null && configuredMode &&
            observedMode == "UNKNOWN" && StartupInputReadiness.hasSelectedTournamentModeStartPermit(
                pid, hwnd, expectedMode, now,
            )
        val modeIdentityReady = observedMode == expectedMode || selectedModePermitValid
        if (!captureAuthorized || captureAgeMs !in 0..MAX_CAPTURE_AGE_MS || !configuredMode) {
            StartupInputReadiness.invalidate(pid, hwnd)
            return false
        }
        if (!modeIdentityReady) {
            // A result-page transition can briefly produce UNKNOWN or a
            // disabled Start control.  That incomplete frame must not erase
            // the independently selected same-window mode permit needed by
            // the next fresh deck-selection capture.  A contradictory,
            // positively observed mode still invalidates all proof.
            if (observedMode == "UNKNOWN") {
                StartupInputReadiness.invalidateScreenProofPreservingSelectedMode(pid, hwnd)
            } else {
                StartupInputReadiness.invalidate(pid, hwnd)
            }
            return false
        }
        val proofPid = pid
        val proofHwnd = hwnd
        if (proofPid == null || proofHwnd == null) {
            StartupInputReadiness.invalidate(pid, hwnd)
            return false
        }
        return StartupInputReadiness.recordDeckSelectionStart(
            pid = proofPid,
            hwnd = proofHwnd,
            confidence = confidence,
            evidence = evidence,
            expectedRunMode = expectedMode,
            observedRunMode = observedMode,
            enabledStartButtonVisible = enabledStartVisible,
            modalVisible = modalVisible,
            now = now,
        )
    }
}
