package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicReference
import club.xiaojiawei.hsscriptbase.config.log

/** Fresh, process-bound proof that the visible client is at an online menu. */
internal object StartupInputReadiness {
    private data class Proof(
        val pid: Int,
        val hwnd: String,
        val screen: String,
        val observedAt: Long,
        val expectedMode: String? = null,
        val observedMode: String? = null,
    )
    private data class SelectedModePermit(val pid: Int, val hwnd: String, val mode: String, val observedAt: Long)
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
    private val selectedModePermit = AtomicReference<SelectedModePermit?>(null)
    private val reconnectPermit = AtomicReference<ReconnectPermit?>(null)
    private val noticeDismissPermit = AtomicReference<NoticeDismissPermit?>(null)
    private val betaResultConfirmationPermit = AtomicReference<NoticeDismissPermit?>(null)

    const val HUB_TRADITIONAL_BATTLE_ACTION = "mode.select-traditional"
    const val HUB_MODE_CHOOSER_PROOF_MAX_AGE_MS = 30_000L
    const val DECK_SELECTION_START_PROOF_MAX_AGE_MS = 8_000L
    const val DECK_MODE_NAVIGATION_PROOF_MAX_AGE_MS = 30_000L
    const val SELECTED_MODE_START_PERMIT_MAX_AGE_MS = 30_000L

    /** Recent exact selector observation; permits only the Start action in the same client window. */
    fun recordSelectedTournamentMode(pid: Int, hwnd: String, mode: String, now: Long = System.currentTimeMillis()) {
        if (pid <= 0 || hwnd.isBlank() || mode !in CONFIRMABLE_TOURNAMENT_MODES) {
            clearSelectedTournamentMode(pid, hwnd)
            return
        }
        selectedModePermit.set(SelectedModePermit(pid, hwnd, mode, now))
    }

    fun hasSelectedTournamentModeStartPermit(
        pid: Int,
        hwnd: String,
        mode: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean = selectedModePermit.get()?.let {
        it.pid == pid && it.hwnd == hwnd && it.mode == mode && now >= it.observedAt &&
            now - it.observedAt <= SELECTED_MODE_START_PERMIT_MAX_AGE_MS
    } == true

    fun clearSelectedTournamentMode(pid: Int? = null, hwnd: String? = null) {
        selectedModePermit.updateAndGet { current ->
            if (current != null && (pid == null || current.pid == pid) &&
                (hwnd == null || current.hwnd == hwnd)
            ) null else current
        }
    }

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
        clearSelectedTournamentMode(pid, hwnd)
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
        val modeIdentityReady = expectedRunMode in CONFIRMABLE_TOURNAMENT_MODES &&
            (observedRunMode == expectedRunMode ||
                (observedRunMode == "UNKNOWN" && hasSelectedTournamentModeStartPermit(
                    pid, hwnd, expectedRunMode, now,
                )))
        if (pid <= 0 || hwnd.isBlank() || confidence < 90 ||
            evidence !in DECK_SELECTION_START_EVIDENCE || !enabledStartButtonVisible || modalVisible ||
            !modeIdentityReady
        ) {
            // A detected mismatch invalidates the independently selected mode.
            // UNKNOWN OCR does not: a fresh exact deck-selection capture plus
            // an unexpired, same-window selected-mode permit is sufficient.
            if (observedRunMode != "UNKNOWN") clearSelectedTournamentMode(pid, hwnd)
            invalidateScreenProof(pid, hwnd)
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
        proof.set(Proof(
            pid = pid,
            hwnd = hwnd,
            screen = DECK_SELECTION_MODE_NAV_SCREEN,
            observedAt = now,
            expectedMode = expectedRunMode,
            observedMode = observedRunMode,
        ))
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
        expectedMode: String? = null,
    ): Boolean = proof.get()?.let {
        it.pid == pid && it.hwnd == hwnd && it.screen == DECK_SELECTION_MODE_NAV_SCREEN &&
            now >= it.observedAt && now - it.observedAt <= DECK_MODE_NAVIGATION_PROOF_MAX_AGE_MS &&
            (expectedMode == null || it.expectedMode == expectedMode && it.observedMode != expectedMode)
    } == true

    fun invalidate(pid: Int? = null, hwnd: String? = null) {
        invalidateScreenProof(pid, hwnd)
        clearSelectedTournamentMode(pid, hwnd)
    }

    private fun invalidateScreenProof(pid: Int? = null, hwnd: String? = null) {
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

    /** Traditional Battle consumes the exact observed chooser proof; another click needs a fresh capture. */
    fun consumeHubModeChooser(pid: Int, hwnd: String, now: Long = System.currentTimeMillis()): Boolean {
        val current = proof.get() ?: return false
        if (current.pid != pid || current.hwnd != hwnd || current.screen != HUB_MODE_CHOOSER_SCREEN ||
            now < current.observedAt || now - current.observedAt > HUB_MODE_CHOOSER_PROOF_MAX_AGE_MS
        ) return false
        return proof.compareAndSet(current, null)
    }

    fun issueReconnectPermit(pid: Int, hwnd: String, point: Point, now: Long): Boolean {
        if (pid <= 0 || hwnd.isBlank() || point.x < 0 || point.y < 0) return false
        proof.set(null)
        selectedModePermit.set(null)
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
        currentSessionReady: Boolean = false,
        activeGame: Boolean = false,
        activeMatchmaking: Boolean = false,
        terminal: Boolean = false,
    ): Boolean = action == StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION &&
        processId != null && processId > 0 && !hwnd.isNullOrBlank() && exactWindowVerified &&
        freshHubChooserReady && !currentSessionReady && !activeGame && !activeMatchmaking && !terminal

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
        selectedModeStartPermitValid: Boolean = false,
        startupDeckSelectionModeNavigationReady: Boolean = false,
    ): Boolean = if (action == StartupNoticeRecoveryPolicy.DISPATCH_ACTION) {
        // A non-empty Power.log can make the session model ready while the
        // client is still on this modal. The exact permit plus final active
        // game/queue checks remain decisive for this one known button.
        startupNoticeDismissPermitValid
    } else if (action == BetaStartupResultConfirmationPolicy.DISPATCH_ACTION) {
        betaResultConfirmationPermitValid
    } else if (action in setOf(
            StartupDeckModeNavigationPolicy.ACTION,
            StartupDeckModeNavigationPolicy.OPEN_SELECTOR_ACTION,
            StartupDeckModeNavigationPolicy.SELECT_WILD_ACTION,
        )
    ) {
        startupDeckSelectionModeNavigationReady
    } else if (action == StartupQuestOverlayDismissPolicy.DISPATCH_ACTION) {
        startupQuestOverlayDismissPermitValid
    } else if (action == "matchmaking.start") {
        // Current-session/Power.log readiness is not mode proof. Start requires
        // either a fresh image-matched deck screen or a recent exact mode-selector observation.
        freshDeckSelectionStartReady || selectedModeStartPermitValid
    } else currentSessionReady || freshOnlineMenuReady ||
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
    private val focusTransactionLock = ReentrantLock()
    private val activeModeEntry = AtomicReference<Pair<Int, String>?>(null)

    fun dispatchTraditionalBattleIfReady(
        chooserProofFresh: Boolean,
        paused: Boolean,
        processId: Int,
        hwnd: String,
        dispatch: (String) -> Boolean,
    ): Boolean {
        if (paused || !chooserProofFresh || processId <= 0 || hwnd.isBlank()) {
            log.info {
                "STARTUP_MODE_ENTRY_DISPATCH_BLOCKED reason=" +
                    when {
                        paused -> "paused"
                        !chooserProofFresh -> "chooser-proof-missing-or-stale"
                        processId <= 0 -> "invalid-process"
                        else -> "invalid-window"
                    } + " pid=$processId hwnd=${hwnd.ifBlank { "none" }} action=" +
                    StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION
            }
            return false
        }
        val waitStartedAt = System.nanoTime()
        val acquired = try {
            focusTransactionLock.tryLock(3, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            log.warn {
                "STARTUP_MODE_ENTRY_DISPATCH_BLOCKED reason=focus-transaction-timeout pid=$processId " +
                    "hwnd=$hwnd waitMs=${(System.nanoTime() - waitStartedAt) / 1_000_000L} action=" +
                    StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION
            }
            return false
        }
        val identity = processId to hwnd
        activeModeEntry.set(identity)
        return try {
            val accepted = dispatch(StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION)
            val proofConsumed = accepted && StartupInputReadiness.consumeHubModeChooser(processId, hwnd)
            log.info {
                "STARTUP_MODE_ENTRY_DISPATCH_RESULT result=${if (accepted) "ACCEPTED" else "REJECTED"} " +
                    "pid=$processId hwnd=$hwnd waitMs=${(System.nanoTime() - waitStartedAt) / 1_000_000L} " +
                    "action=${StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION} proofConsumed=$proofConsumed " +
                    "retry=FRESH_HUB_SCREEN_PROBE_ONLY"
            }
            accepted
        } finally {
            activeModeEntry.compareAndSet(identity, null)
            focusTransactionLock.unlock()
        }
    }

    /** Captures skip focus changes while mode-entry input owns the same HWND. */
    fun <T> withRecoveryCapture(processId: Int, hwnd: String, capture: () -> T): T? {
        if (activeModeEntry.get() == (processId to hwnd)) return null
        if (!focusTransactionLock.tryLock()) return null
        return try {
            if (activeModeEntry.get() == (processId to hwnd)) null else capture()
        } finally {
            focusTransactionLock.unlock()
        }
    }

    internal fun isModeEntryInFlight(processId: Int, hwnd: String): Boolean =
        activeModeEntry.get() == (processId to hwnd)
}
