package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.util.concurrent.atomic.AtomicReference

/** Fresh, process-bound proof that the visible client is at an online menu. */
internal object StartupInputReadiness {
    private data class Proof(val pid: Int, val hwnd: String, val screen: String, val observedAt: Long)
    private data class ReconnectPermit(
        val pid: Int,
        val hwnd: String,
        val point: Point,
        val expiresAt: Long,
    )
    private data class NoticeDismissPermit(
        val pid: Int,
        val hwnd: String,
        val point: Point,
        val expiresAt: Long,
    )

    private val proof = AtomicReference<Proof?>(null)
    private val reconnectPermit = AtomicReference<ReconnectPermit?>(null)
    private val noticeDismissPermit = AtomicReference<NoticeDismissPermit?>(null)
    private val betaResultConfirmationPermit = AtomicReference<NoticeDismissPermit?>(null)

    const val HUB_TRADITIONAL_BATTLE_ACTION = "mode.select-traditional"
    const val HUB_MODE_CHOOSER_PROOF_MAX_AGE_MS = 30_000L
    const val DECK_SELECTION_START_PROOF_MAX_AGE_MS = 8_000L
    const val DECK_MODE_NAVIGATION_PROOF_MAX_AGE_MS = 30_000L

    fun recordOnlineMenu(
        pid: Int,
        hwnd: String,
        screen: String,
        modalVisible: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        if (pid <= 0 || hwnd.isBlank() || modalVisible || screen !in ONLINE_MENU_SCREENS) {
            invalidate(pid, hwnd)
            return
        }
        reconnectPermit.set(null)
        noticeDismissPermit.updateAndGet { current ->
            if (current != null && current.pid == pid && current.hwnd == hwnd) null else current
        }
        betaResultConfirmationPermit.updateAndGet { current ->
            if (current != null && current.pid == pid && current.hwnd == hwnd) null else current
        }
        proof.set(Proof(pid, hwnd, screen, now))
    }

    /** Narrow proof for the exact traditional-mode button on the HUB mode chooser. */
    fun recordHubModeChooser(pid: Int, hwnd: String, now: Long = System.currentTimeMillis()) {
        if (pid <= 0 || hwnd.isBlank()) {
            invalidate(pid, hwnd)
            return
        }
        reconnectPermit.set(null)
        betaResultConfirmationPermit.set(null)
        proof.set(Proof(pid, hwnd, HUB_MODE_CHOOSER_SCREEN, now))
    }

    /** Narrow startup proof for the enabled Start control on an exact deck-selection screen. */
    fun recordDeckSelectionStart(
        pid: Int,
        hwnd: String,
        confidence: Int,
        evidence: String,
        expectedRunMode: String?,
        observedRunMode: String?,
        enabledStartButtonVisible: Boolean,
        modalVisible: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (pid <= 0 || hwnd.isBlank() || confidence < 90 ||
            evidence !in DECK_SELECTION_START_EVIDENCE || !enabledStartButtonVisible || modalVisible ||
            expectedRunMode !in CONFIRMABLE_TOURNAMENT_MODES || observedRunMode != expectedRunMode
        ) {
            invalidate(pid, hwnd)
            return false
        }
        reconnectPermit.set(null)
        noticeDismissPermit.set(null)
        betaResultConfirmationPermit.set(null)
        proof.set(Proof(pid, hwnd, DECK_SELECTION_START_SCREEN, now))
        return true
    }

    /** Fresh exact deck-selection proof authorizes only the named Back-to-mode-selector action. */
    fun recordDeckSelectionModeNavigation(
        pid: Int,
        hwnd: String,
        expectedRunMode: String?,
        observedRunMode: String?,
        confidence: Int,
        evidence: String,
        enabledStartButtonVisible: Boolean,
        modalVisible: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (pid <= 0 || hwnd.isBlank() || confidence < 90 ||
            evidence !in DECK_SELECTION_START_EVIDENCE || !enabledStartButtonVisible || modalVisible ||
            expectedRunMode !in setOf("STANDARD", "WILD") || observedRunMode == expectedRunMode
        ) {
            invalidate(pid, hwnd)
            return false
        }
        reconnectPermit.set(null)
        noticeDismissPermit.set(null)
        betaResultConfirmationPermit.set(null)
        proof.set(Proof(pid, hwnd, DECK_SELECTION_MODE_NAV_SCREEN, now))
        return true
    }

    fun recordStartupOnlineScreen(
        pid: Int,
        hwnd: String,
        screen: String,
        confidence: Int,
        evidence: String,
        expectedRunMode: String? = null,
        observedRunMode: String? = null,
        enabledStartButtonVisible: Boolean,
        modalVisible: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean = if (screen == "DECK_SELECTION") {
        recordDeckSelectionStart(
            pid = pid,
            hwnd = hwnd,
            confidence = confidence,
            evidence = evidence,
            expectedRunMode = expectedRunMode,
            observedRunMode = observedRunMode,
            enabledStartButtonVisible = enabledStartButtonVisible,
            modalVisible = modalVisible,
            now = now,
        )
    } else {
        val valid = pid > 0 && hwnd.isNotBlank() && screen in ONLINE_MENU_SCREENS && !modalVisible
        recordOnlineMenu(pid, hwnd, screen, modalVisible, now)
        valid
    }

    fun isDeckSelectionStartReady(
        pid: Int,
        hwnd: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = proof.get()?.let {
        it.pid == pid && it.hwnd == hwnd && it.screen == DECK_SELECTION_START_SCREEN &&
            now >= it.observedAt && now - it.observedAt <= DECK_SELECTION_START_PROOF_MAX_AGE_MS
    } == true

    fun isDeckSelectionModeNavigationReady(
        pid: Int,
        hwnd: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = proof.get()?.let {
        it.pid == pid && it.hwnd == hwnd && it.screen == DECK_SELECTION_MODE_NAV_SCREEN &&
            now >= it.observedAt && now - it.observedAt <= DECK_MODE_NAVIGATION_PROOF_MAX_AGE_MS
    } == true

    fun invalidate(pid: Int? = null, hwnd: String? = null) {
        proof.updateAndGet { current ->
            if (current != null && (pid == null || current.pid == pid) &&
                (hwnd == null || current.hwnd == hwnd)
            ) null else current
        }
        reconnectPermit.set(null)
        noticeDismissPermit.updateAndGet { current ->
            if (current != null && (pid == null || current.pid == pid) &&
                (hwnd == null || current.hwnd == hwnd)
            ) null else current
        }
        betaResultConfirmationPermit.updateAndGet { current ->
            if (current != null && (pid == null || current.pid == pid) &&
                (hwnd == null || current.hwnd == hwnd)
            ) null else current
        }
    }

    fun isMenuReady(pid: Int, hwnd: String, mode: String?): Boolean =
        proof.get()?.let { it.pid == pid && it.hwnd == hwnd && mode in allowedModesByScreen[it.screen].orEmpty() } == true

    fun isHubTraditionalBattleSelectionReady(
        pid: Int,
        hwnd: String,
        mode: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean = proof.get()?.let {
        it.pid == pid && it.hwnd == hwnd && it.screen == HUB_MODE_CHOOSER_SCREEN && mode == "HUB" &&
            now >= it.observedAt && now - it.observedAt <= HUB_MODE_CHOOSER_PROOF_MAX_AGE_MS
    } == true

    fun issueReconnectPermit(pid: Int, hwnd: String, point: Point, now: Long): Boolean {
        if (pid <= 0 || hwnd.isBlank() || point.x < 0 || point.y < 0) return false
        proof.set(null)
        reconnectPermit.set(ReconnectPermit(pid, hwnd, Point(point), now + RECONNECT_PERMIT_LIFETIME_MS))
        return true
    }

    fun hasReconnectPermit(now: Long = System.currentTimeMillis()): Boolean =
        reconnectPermit.get()?.let { now <= it.expiresAt } == true

    fun reconnectPermitMatches(pid: Int, hwnd: String, point: Point, now: Long): Boolean =
        reconnectPermit.get()?.let {
            now <= it.expiresAt && it.pid == pid && it.hwnd == hwnd && it.point == point
        } == true

    fun consumeReconnectPermit(pid: Int, hwnd: String, point: Point, now: Long): Boolean {
        val current = reconnectPermit.get() ?: return false
        return current.pid == pid && current.hwnd == hwnd && current.point == point &&
            now <= current.expiresAt && reconnectPermit.compareAndSet(current, null)
    }

    fun issueNoticeDismissPermit(pid: Int, hwnd: String, point: Point, now: Long): Boolean {
        if (pid <= 0 || hwnd.isBlank() || point.x < 0 || point.y < 0) return false
        proof.set(null)
        reconnectPermit.set(null)
        noticeDismissPermit.set(
            NoticeDismissPermit(pid, hwnd, Point(point), now + StartupNoticeRecoveryPolicy.PERMIT_LIFETIME_MS),
        )
        return true
    }

    fun issueBetaResultConfirmationPermit(pid: Int, hwnd: String, point: Point, now: Long): Boolean {
        if (pid <= 0 || hwnd.isBlank() || point.x < 0 || point.y < 0) return false
        proof.set(null)
        reconnectPermit.set(null)
        noticeDismissPermit.set(null)
        betaResultConfirmationPermit.set(
            NoticeDismissPermit(pid, hwnd, Point(point), now + BetaStartupResultConfirmationPolicy.PERMIT_LIFETIME_MS),
        )
        return true
    }

    fun hasBetaResultConfirmationPermit(pid: Int, hwnd: String, point: Point, now: Long): Boolean =
        betaResultConfirmationPermit.get()?.let {
            now <= it.expiresAt && it.pid == pid && it.hwnd == hwnd && it.point == point
        } == true

    fun hasBetaResultConfirmationPermit(pid: Int, hwnd: String, now: Long): Boolean =
        betaResultConfirmationPermit.get()?.let { now <= it.expiresAt && it.pid == pid && it.hwnd == hwnd } == true

    fun consumeBetaResultConfirmationPermit(pid: Int, hwnd: String, point: Point, now: Long): Boolean {
        val current = betaResultConfirmationPermit.get() ?: return false
        return current.pid == pid && current.hwnd == hwnd && current.point == point &&
            now <= current.expiresAt && betaResultConfirmationPermit.compareAndSet(current, null)
    }

    fun hasNoticeDismissPermit(pid: Int, hwnd: String, point: Point, now: Long): Boolean =
        noticeDismissPermit.get()?.let {
            now <= it.expiresAt && it.pid == pid && it.hwnd == hwnd && it.point == point
        } == true

    fun hasNoticeDismissPermit(pid: Int, hwnd: String, now: Long): Boolean =
        noticeDismissPermit.get()?.let { now <= it.expiresAt && it.pid == pid && it.hwnd == hwnd } == true

    fun consumeNoticeDismissPermit(pid: Int, hwnd: String, point: Point, now: Long): Boolean {
        val current = noticeDismissPermit.get() ?: return false
        return current.pid == pid && current.hwnd == hwnd && current.point == point &&
            now <= current.expiresAt && noticeDismissPermit.compareAndSet(current, null)
    }

    internal fun resetForTest() {
        proof.set(null)
        reconnectPermit.set(null)
        noticeDismissPermit.set(null)
        betaResultConfirmationPermit.set(null)
    }

    private val ONLINE_MENU_SCREENS = setOf(
        "HOME", "TOURNAMENT", "DECK_SELECTION", "HOME_TASK_OVERLAY",
        "GAME_MODE", "COLLECTION", "PACK_OPENING", "SHOP_OVERLAY",
    )

    private const val HUB_MODE_CHOOSER_SCREEN = "HUB_MODE_CHOOSER"
    private const val DECK_SELECTION_START_SCREEN = "DECK_SELECTION_START"
    private const val DECK_SELECTION_MODE_NAV_SCREEN = "DECK_SELECTION_MODE_NAV"
    private val CONFIRMABLE_TOURNAMENT_MODES = setOf("STANDARD", "WILD")
    private val DECK_SELECTION_START_EVIDENCE = setOf(
        "deck-selection-title", "deck-selection-title-roi", "deck-selection-start-control-visual",
    )

    private val allowedModesByScreen = mapOf(
        "HOME" to setOf("HUB"),
        "HOME_TASK_OVERLAY" to setOf("HUB"),
        "TOURNAMENT" to setOf("TOURNAMENT"),
        "DECK_SELECTION" to setOf("TOURNAMENT"),
        "GAME_MODE" to setOf("GAME_MODE"),
        "COLLECTION" to setOf("COLLECTIONMANAGER"),
        "PACK_OPENING" to setOf("PACKOPENING"),
        "SHOP_OVERLAY" to setOf("HUB"),
    )

    const val RECONNECT_PERMIT_LIFETIME_MS = 2_000L
}

/** Pure startup barrier shared by the last dispatch gate and offline policy tests. */
internal object StartupInputAuthorizationPolicy {
    /** Recovery may touch the selected deck only after the exact fresh mode-matched Start proof exists. */
    fun dispatchDeckSelectionRecoveryIfReady(
        startupScreenConfirmed: Boolean,
        dispatch: () -> Unit,
    ): Boolean {
        if (!startupScreenConfirmed) return false
        dispatch()
        return true
    }

    fun allowsHubTraditionalBattleSelection(
        action: String,
        processId: Int?,
        hwnd: String?,
        exactWindowVerified: Boolean,
        freshHubChooserReady: Boolean,
    ): Boolean = action == StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION &&
        processId != null && processId > 0 && !hwnd.isNullOrBlank() && exactWindowVerified &&
        freshHubChooserReady

    fun allowsDeckSelectionStart(
        action: String,
        processId: Int?,
        hwnd: String?,
        exactWindowVerified: Boolean,
        freshDeckSelectionStartReady: Boolean,
    ): Boolean = action == "matchmaking.start" &&
        processId != null && processId > 0 && !hwnd.isNullOrBlank() && exactWindowVerified &&
        freshDeckSelectionStartReady

    fun allows(
        action: String,
        currentSessionReady: Boolean,
        freshOnlineMenuReady: Boolean,
        reconnectPermitValid: Boolean = false,
        terminalCleanupCapabilityValid: Boolean = false,
        rankSurrenderCapabilityValid: Boolean = false,
        startupTraditionalBattleSelectionReady: Boolean = false,
        startupNoticeDismissPermitValid: Boolean = false,
        betaResultConfirmationPermitValid: Boolean = false,
        startupQuestOverlayDismissPermitValid: Boolean = false,
        freshDeckSelectionStartReady: Boolean = false,
        startupDeckSelectionModeNavigationReady: Boolean = false,
    ): Boolean = if (action == StartupNoticeRecoveryPolicy.DISPATCH_ACTION) {
        // A non-empty Power.log can make the session model ready while the
        // client is still on this modal. The exact permit plus final active
        // game/queue checks remain decisive for this one known button.
        startupNoticeDismissPermitValid
    } else if (action == BetaStartupResultConfirmationPolicy.DISPATCH_ACTION) {
        betaResultConfirmationPermitValid
    } else if (action == StartupDeckModeNavigationPolicy.ACTION) {
        startupDeckSelectionModeNavigationReady
    } else if (action == StartupQuestOverlayDismissPolicy.DISPATCH_ACTION) {
        startupQuestOverlayDismissPermitValid
    } else currentSessionReady || freshOnlineMenuReady ||
        (action == "matchmaking.start" && freshDeckSelectionStartReady) ||
        (action.startsWith("startup.") && action != StartupNoticeRecoveryPolicy.DISPATCH_ACTION) ||
        (action == StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION &&
            startupTraditionalBattleSelectionReady) ||
        (action == "recovery.reconnect" && reconnectPermitValid) ||
        (action == "terminal-result.dismiss" && terminalCleanupCapabilityValid) ||
        (action == "surrender.request" && rankSurrenderCapabilityValid)

    fun mayIssuePreSessionReconnectPermit(
        screenKind: String,
        currentSessionReady: Boolean,
        activeGame: Boolean,
        activeMatchmaking: Boolean,
        freshCapture: Boolean,
        exactWindowAndForeground: Boolean,
    ): Boolean = screenKind.equals("RECONNECT", ignoreCase = true) && !currentSessionReady &&
        !activeGame && !activeMatchmaking && freshCapture && exactWindowAndForeground
}

/** Call-site handoff keeps HUB entry on one named action and never falls back to a generic click. */
internal object StartupModeEntryDispatchPolicy {
    fun dispatchTraditionalBattleIfReady(
        chooserProofFresh: Boolean,
        paused: Boolean,
        dispatch: (String) -> Unit,
    ): Boolean {
        if (paused || !chooserProofFresh) return false
        dispatch(StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION)
        return true
    }
}
