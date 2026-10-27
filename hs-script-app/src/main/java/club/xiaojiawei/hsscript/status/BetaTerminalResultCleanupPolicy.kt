package club.xiaojiawei.hsscript.status

/** Beta-only authorization for waking the bounded result-page worker on late same-game proof. */
internal object BetaTerminalResultCleanupPolicy {
    const val CONTINUE_ACTION = "terminal-result.dismiss"
    private const val MAX_CAPTURE_AGE_MS = 5_000L

    data class Evidence(
        val channel: String,
        val action: String,
        val working: Boolean,
        val paused: Boolean,
        val gameProcessAlive: Boolean,
        val currentWindowBoundToGame: Boolean,
        val currentPowerLogHasCompleteTerminalProof: Boolean,
        val proofIdentity: String?,
        val currentSessionIdentity: String?,
        val mandatoryRankSurrenderPending: Boolean,
        val terminalCleanupPending: Boolean,
        val terminalCleanupCapabilityValid: Boolean,
        val captureAuthorized: Boolean,
        val resultPageVisible: Boolean?,
        val continueControlVisible: Boolean,
        val captureAgeMs: Long,
    )

    fun allows(evidence: Evidence): Boolean =
        evidence.channel.trim().lowercase() == "beta" &&
            evidence.action == CONTINUE_ACTION &&
            evidence.working && !evidence.paused &&
            evidence.gameProcessAlive && evidence.currentWindowBoundToGame &&
            evidence.currentPowerLogHasCompleteTerminalProof &&
            !evidence.proofIdentity.isNullOrBlank() &&
            evidence.proofIdentity == evidence.currentSessionIdentity &&
            !evidence.mandatoryRankSurrenderPending &&
            evidence.terminalCleanupPending && evidence.terminalCleanupCapabilityValid &&
            evidence.captureAuthorized && evidence.resultPageVisible == true &&
            evidence.continueControlVisible && evidence.captureAgeMs in 0..MAX_CAPTURE_AGE_MS
}
