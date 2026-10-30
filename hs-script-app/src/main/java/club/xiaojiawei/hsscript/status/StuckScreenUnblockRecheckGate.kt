package club.xiaojiawei.hsscript.status

/** Leased gate for post-click probes; timed-out callbacks lose authority to act. */
internal class StuckScreenUnblockRecheckGate(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    data class Lease internal constructor(val generation: Long, val startedAtMs: Long)

    private var generation = 0L
    private var active: Lease? = null

    @Synchronized
    fun tryBegin(nowMs: Long): Lease? {
        expireIfTimedOut(nowMs)
        if (nowMs <= 0L || active != null) return null
        return Lease(++generation, nowMs).also { active = it }
    }

    /** Acquires before enqueue and releases on completion, task failure, or enqueue rejection. */
    fun schedule(
        nowMs: Long,
        enqueue: (() -> Unit) -> Unit,
        currentTimeMs: () -> Long = System::currentTimeMillis,
        action: (Lease) -> Unit,
    ): Boolean {
        val lease = tryBegin(nowMs) ?: return false
        return try {
            enqueue {
                try {
                    if (isCurrent(lease, currentTimeMs())) action(lease)
                } finally {
                    finish(lease)
                }
            }
            true
        } catch (_: Throwable) {
            finish(lease)
            false
        }
    }

    @Synchronized
    fun expireIfTimedOut(nowMs: Long): Lease? {
        val current = active ?: return null
        if (nowMs < current.startedAtMs || nowMs - current.startedAtMs < timeoutMs) return null
        active = null
        return current
    }

    @Synchronized
    fun isInFlight(nowMs: Long): Boolean {
        expireIfTimedOut(nowMs)
        return active != null
    }

    @Synchronized
    fun isCurrent(lease: Lease, nowMs: Long): Boolean {
        expireIfTimedOut(nowMs)
        return active == lease
    }

    @Synchronized
    fun isCurrentGeneration(token: Long, nowMs: Long): Boolean {
        expireIfTimedOut(nowMs)
        return active?.generation == token
    }

    /** Releases only its own generation; an expired callback cannot clear a newer lease. */
    @Synchronized
    fun finish(lease: Lease): Boolean {
        if (active != lease) return false
        active = null
        return true
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 45_000L
    }
}
