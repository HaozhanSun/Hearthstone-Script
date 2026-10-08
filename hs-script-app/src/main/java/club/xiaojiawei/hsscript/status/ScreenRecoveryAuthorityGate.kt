package club.xiaojiawei.hsscript.status

/** Evidence required before a desktop screenshot may be attributed to Hearthstone. */
internal data class ScreenRecoveryAuthorityEvidence(
    val processAlive: Boolean,
    val windowPresent: Boolean,
    val windowVerified: Boolean,
    val foregroundConfirmed: Boolean,
    val sameWindow: Boolean,
    val capturedPixelsVerified: Boolean = false,
    val currentSessionReady: Boolean = false,
    val safeObservedMenuTransition: Boolean = false,
)

/** Side-effect-free gate shared by capture, recovery dispatch, and offline tests. */
internal object ScreenRecoveryAuthorityGate {
    private val safeMenuScreens = setOf("HOME", "TOURNAMENT", "DECK_SELECTION")

    fun isSafeObservedMenuTransition(
        expectedScreen: String,
        observedScreen: String?,
        confidence: Int,
        freshCurrentWindowCapture: Boolean,
    ): Boolean = freshCurrentWindowCapture && confidence >= 85 &&
        expectedScreen in safeMenuScreens && observedScreen == expectedScreen

    fun isAuthorized(evidence: ScreenRecoveryAuthorityEvidence): Boolean =
        evidence.processAlive && evidence.windowPresent && evidence.windowVerified &&
            evidence.foregroundConfirmed && evidence.sameWindow && evidence.capturedPixelsVerified &&
            (evidence.currentSessionReady || evidence.safeObservedMenuTransition)

    fun isCapturePreAuthorized(evidence: ScreenRecoveryAuthorityEvidence): Boolean =
        evidence.processAlive && evidence.windowPresent && evidence.windowVerified &&
            evidence.foregroundConfirmed && evidence.sameWindow

    fun <T> captureIfAuthorized(
        evidence: ScreenRecoveryAuthorityEvidence,
        capture: () -> T?,
    ): T? = if (isCapturePreAuthorized(evidence)) capture() else null

    fun dispatchIfAuthorized(
        evidence: ScreenRecoveryAuthorityEvidence,
        dispatch: () -> Boolean,
    ): Boolean = if (isAuthorized(evidence)) dispatch() else false
}
