package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Fail-closed permit policy for the exact startup "Your Quests" overlay. */
internal object StartupQuestOverlayDismissPolicy {
    const val DISPATCH_ACTION = "recovery.startup-quest-overlay.top-left"
    const val MAX_TAPS = 3
    const val MAX_CAPTURE_AGE_MS = 1_500L
    const val PERMIT_LIFETIME_MS = 1_200L
    const val MIN_CONFIDENCE = 92
    const val TOP_LEFT_INSET_PX = 8

    enum class Action { TAP_TOP_LEFT, RESOLVE_SAFE_TRANSITION, STOP }

    data class Observation(
        val nowMs: Long,
        val captureAtMs: Long,
        val captureTrusted: Boolean,
        val currentPid: Long?,
        val expectedPid: Long?,
        val currentWindow: String?,
        val expectedWindow: String?,
        val exactQuestOverlay: Boolean,
        val confidence: Int,
        val noRecentMatchmakingIntent: Boolean,
        val noGameIntent: Boolean,
        val safeTransition: Boolean,
    )

    data class Decision(val action: Action, val tapNumber: Int, val reason: String)

    fun decide(tapsAttempted: Int, observation: Observation): Decision {
        val reason = when {
            !observation.captureTrusted -> "capture-untrusted"
            observation.captureAtMs > observation.nowMs ||
                observation.nowMs - observation.captureAtMs > MAX_CAPTURE_AGE_MS -> "capture-stale"
            observation.expectedPid == null || observation.currentPid != observation.expectedPid -> "pid-mismatch"
            observation.expectedWindow.isNullOrBlank() ||
                observation.currentWindow != observation.expectedWindow -> "window-mismatch"
            !observation.noRecentMatchmakingIntent -> "recent-matchmaking-intent"
            !observation.noGameIntent -> "game-intent"
            observation.safeTransition && !observation.exactQuestOverlay -> "safe-screen-transition"
            !observation.exactQuestOverlay -> "exact-quest-overlay-not-confirmed"
            observation.confidence < MIN_CONFIDENCE -> "confidence-below-threshold"
            tapsAttempted >= MAX_TAPS -> "max-taps-reached"
            else -> null
        }
        return when (reason) {
            null -> Decision(Action.TAP_TOP_LEFT, tapsAttempted + 1, "fresh-exact-overlay")
            "safe-screen-transition" -> Decision(Action.RESOLVE_SAFE_TRANSITION, tapsAttempted, reason)
            else -> Decision(Action.STOP, tapsAttempted, reason)
        }
    }

    fun topLeftPoint(clientBounds: Rectangle): Point? {
        if (clientBounds.width <= TOP_LEFT_INSET_PX || clientBounds.height <= TOP_LEFT_INSET_PX) return null
        return Point(clientBounds.x + TOP_LEFT_INSET_PX, clientBounds.y + TOP_LEFT_INSET_PX)
    }

    fun topLeftPixel(clientBounds: Rectangle): Point? {
        if (clientBounds.width <= 0 || clientBounds.height <= 0) return null
        return Point(clientBounds.x, clientBounds.y)
    }
}

/** A short-lived, single-use authorization tied to one current game window and point. */
internal class StartupQuestOverlayDismissPermitStore(
    private val lifetimeMs: Long = StartupQuestOverlayDismissPolicy.PERMIT_LIFETIME_MS,
) {
    internal class Permit internal constructor(
        internal val pid: Long,
        internal val window: String,
        internal val x: Int,
        internal val y: Int,
        internal val issuedAtMs: Long,
        internal val nonce: Long,
    )

    private val nonce = AtomicLong()
    private val current = AtomicReference<Permit?>(null)

    fun issue(pid: Long, window: String, point: Point, nowMs: Long): Permit? {
        if (pid <= 0L || window.isBlank()) return null
        return Permit(pid, window, point.x, point.y, nowMs, nonce.incrementAndGet()).also(current::set)
    }

    fun isValid(permit: Permit?, pid: Long?, window: String?, point: Point, nowMs: Long): Boolean {
        if (permit == null || current.get() !== permit) return false
        return permit.pid == pid && permit.window == window && permit.x == point.x && permit.y == point.y &&
            nowMs >= permit.issuedAtMs && nowMs - permit.issuedAtMs <= lifetimeMs
    }

    fun consume(permit: Permit?, pid: Long?, window: String?, point: Point, nowMs: Long): Boolean =
        isValid(permit, pid, window, point, nowMs) && current.compareAndSet(permit, null)

    fun revoke() { current.set(null) }
}

internal object StartupQuestOverlayDismissAuthorization {
    val permits = StartupQuestOverlayDismissPermitStore()
}

/** Keeps queue/game input fenced while the startup modal remains unresolved; never changes pause state. */
internal object StartupQuestOverlayRecoveryFence {
    const val MAX_REOBSERVATIONS = 12
    private val active = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private val taps = AtomicInteger(0)
    private val observations = AtomicInteger(0)
    private val escalation = StartupQuestOverlayEscalationPolicy()
    @Volatile private var sessionIdentity: Pair<Long, String>? = null

    fun begin() {
        if (active.compareAndSet(false, true)) {
            taps.set(0)
            observations.set(0)
            sessionIdentity = null
            escalation.reset()
        }
    }

    fun isActive() = active.get()
    fun tapsAttempted() = taps.get()
    fun recordTapAttempt() = taps.incrementAndGet()
    fun recordObservation() = observations.incrementAndGet()
    fun observeSession(pid: Long, window: String) {
        val identity = pid to window
        if (sessionIdentity != identity) {
            synchronized(this) {
                if (sessionIdentity != identity) {
                    sessionIdentity = identity
                    taps.set(0)
                    escalation.reset()
                }
            }
        }
    }
    fun escalationDecision(evidence: StartupQuestOverlayEscalationPolicy.Evidence) = escalation.observe(evidence)
    fun tryBeginObservation() = active.get() && inFlight.compareAndSet(false, true)
    fun finishObservation() { inFlight.set(false) }

    fun resolve() {
        active.set(false)
        inFlight.set(false)
        taps.set(0)
        observations.set(0)
        sessionIdentity = null
        escalation.reset()
        StartupQuestOverlayDismissAuthorization.permits.revoke()
    }

    internal fun resetForTest() = resolve()
}
