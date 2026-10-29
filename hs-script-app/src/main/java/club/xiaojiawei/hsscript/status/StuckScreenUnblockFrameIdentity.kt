package club.xiaojiawei.hsscript.status

/**
 * Screen continuity for the idle recovery ladder. Exact structural reconnect
 * recognition is stable across dialog animation. Positively classified screens
 * use semantic identity; UNKNOWN frames keep the perceptual hash fallback.
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
        semanticScreenIdentity: String? = null,
    ): Proof = Proof(
        processId = processId,
        windowId = windowId,
        perceptualFingerprint = perceptualFingerprint,
        screenIdentity = when {
            exactReconnectDialog -> "exact-reconnect-dialog"
            !semanticScreenIdentity.isNullOrBlank() -> semanticScreenIdentity
            else -> "perceptual:$perceptualFingerprint"
        },
        reason = when {
            exactReconnectDialog -> "exact-reconnect-structure-animation-tolerant"
            !semanticScreenIdentity.isNullOrBlank() -> "semantic-screen-state"
            else -> "perceptual-frame-fallback"
        },
    )

    /** Known screens use stable state evidence; animation-sensitive image hashes are only a fallback for UNKNOWN. */
    fun semanticScreenIdentity(
        screenKind: String?,
        screenMode: String?,
        expectedRunMode: String? = null,
        observedRunMode: String? = null,
    ): String? {
        if (screenKind.isNullOrBlank() || screenKind == "UNKNOWN") return null
        return if (screenKind == "DECK_SELECTION") {
            "DECK_SELECTION|expected=${expectedRunMode ?: "UNKNOWN"}|observed=${observedRunMode ?: "UNKNOWN"}"
        } else {
            "${screenKind.uppercase()}|mode=${screenMode ?: "UNKNOWN"}"
        }
    }

    fun isSameClientScreen(expected: Proof, observed: Proof): Boolean =
        expected.processId == observed.processId && expected.windowId == observed.windowId &&
            expected.screenIdentity == observed.screenIdentity
}
