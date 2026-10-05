package club.xiaojiawei.hsscript.status

/** Evidence required before a desktop screenshot may be attributed to Hearthstone. */
internal data class ScreenRecoveryAuthorityEvidence(
    val processAlive: Boolean,
    val windowPresent: Boolean,
    val windowVerified: Boolean,
    val foregroundConfirmed: Boolean,
    val sameWindow: Boolean,
)

/** Side-effect-free gate shared by capture, recovery dispatch, and offline tests. */
internal object ScreenRecoveryAuthorityGate {
    fun isAuthorized(evidence: ScreenRecoveryAuthorityEvidence): Boolean =
        evidence.processAlive && evidence.windowPresent && evidence.windowVerified &&
            evidence.foregroundConfirmed && evidence.sameWindow

    fun <T> captureIfAuthorized(
        evidence: ScreenRecoveryAuthorityEvidence,
        capture: () -> T?,
    ): T? = if (isAuthorized(evidence)) capture() else null

    fun dispatchIfAuthorized(
        evidence: ScreenRecoveryAuthorityEvidence,
        dispatch: () -> Boolean,
    ): Boolean = if (isAuthorized(evidence)) dispatch() else false
}
