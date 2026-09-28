package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Generation gate for additive Beta recovery work; upstream recovery paths do not use it. */
internal class ScreenRecoveryGate(initiallyEnabled: Boolean = false) {
    private val enabled = AtomicBoolean(initiallyEnabled)
    private val generation = AtomicLong(1L)
    private val activeTasks = ConcurrentHashMap.newKeySet<Future<*>>()

    fun isEnabled(): Boolean = enabled.get()

    fun currentGeneration(): Long = generation.get()

    fun tokenOrNull(): Long? = generation.get().takeIf { enabled.get() }

    fun isCurrent(token: Long?): Boolean = token != null && enabled.get() && generation.get() == token

    fun setEnabled(value: Boolean) {
        if (enabled.getAndSet(value) == value) return
        generation.incrementAndGet()
        if (!value) {
            activeTasks.toList().forEach { it.cancel(true) }
            activeTasks.clear()
        }
    }

    fun track(token: Long, task: Future<*>): Boolean {
        if (!isCurrent(token)) {
            task.cancel(true)
            return false
        }
        activeTasks.add(task)
        if (!isCurrent(token) && activeTasks.remove(task)) {
            task.cancel(true)
            return false
        }
        return true
    }

    fun forget(task: Future<*>) {
        activeTasks.remove(task)
    }
}

/**
 * Runtime switch for additive Beta recovery extensions. Upstream lifecycle,
 * startup-screen, result-screen, and watchdog paths remain independent.
 */
internal object ScreenRecoveryRuntime {
    private val initialized = AtomicBoolean(false)
    private val gate = ScreenRecoveryGate()

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        val enabled = ConfigUtil.getBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED)
        gate.setEnabled(enabled)
        ConfigUtil.addBooleanChangeListener { key, value ->
            if (key == ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED) {
                gate.setEnabled(value)
                BetaScreenRecoveryService.onFeatureChanged(value)
            }
        }
        BetaScreenRecoveryService.onFeatureChanged(enabled)
    }

    fun isEnabled(): Boolean {
        initialize()
        return gate.isEnabled()
    }

    fun generation(): Long {
        initialize()
        return gate.currentGeneration()
    }

    fun tokenOrNull(): Long? {
        initialize()
        return gate.tokenOrNull()
    }

    /** Run optional Beta work only when the current feature generation admits it. */
    fun runIfEnabled(action: (Long) -> Unit): Boolean {
        val token = tokenOrNull() ?: return false
        if (!isCurrent(token)) return false
        action(token)
        return isCurrent(token)
    }

    fun isCurrent(token: Long?): Boolean {
        initialize()
        return gate.isCurrent(token)
    }

    fun track(token: Long, task: Future<*>): Boolean {
        initialize()
        return gate.track(token, task)
    }

    fun forget(task: Future<*>) = gate.forget(task)
}
