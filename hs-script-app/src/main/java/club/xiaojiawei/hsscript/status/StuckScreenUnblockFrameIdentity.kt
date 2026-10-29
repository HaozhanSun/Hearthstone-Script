package club.xiaojiawei.hsscript.status

/**
 * Screen continuity for the idle recovery ladder. Exact structural reconnect
 * recognition is stable across dialog animation; all other screens keep the
 * perceptual fingerprint so a real frame change ends the ladder.
 */
internal object StuckScreenUnblockFrameIdentity {
    data class Proof(
        val processId: Int,
        val windowId: String,
        val perceptualFingerprint: String,
        val screenIdentity: String,
        val reason: String,
    ) {
        val fingerprint: String
            get() = "$processId|$windowId|$screenIdentity"
    }

    fun create(
        processId: Int,
        windowId: String,
        perceptualFingerprint: String,
        exactReconnectDialog: Boolean,
    ): Proof = Proof(
        processId = processId,
        windowId = windowId,
        perceptualFingerprint = perceptualFingerprint,
        screenIdentity = if (exactReconnectDialog) "exact-reconnect-dialog" else perceptualFingerprint,
        reason = if (exactReconnectDialog) "exact-reconnect-structure-animation-tolerant" else "perceptual-frame",
    )

    fun isSameClientScreen(expected: Proof, observed: Proof): Boolean =
        expected.processId == observed.processId && expected.windowId == observed.windowId &&
            expected.screenIdentity == observed.screenIdentity
}
