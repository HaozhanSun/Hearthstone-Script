package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A generation-based gate shared by optional Beta screen-recovery entry points. */
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
 * Opt-in recovery runtime. Normal Power.log-driven mode/phase processing does
 * not depend on this feature and is never stopped by the switch.
 */
internal object ScreenRecoveryRuntime {
    private val initialized = AtomicBoolean(false)
    private val gate = ScreenRecoveryGate()

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        gate.setEnabled(ConfigUtil.getBoolean(ConfigEnum.BETA_SCREEN_RECOVERY_ENABLED))
        ConfigUtil.addBooleanChangeListener { key, value ->
            if (key == ConfigEnum.BETA_SCREEN_RECOVERY_ENABLED) gate.setEnabled(value)
        }
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
