package club.xiaojiawei.hsscript.starter

import club.xiaojiawei.hsscript.core.Core
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ScreenStateRecovery
import club.xiaojiawei.hsscript.status.TaskManager
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.config.LISTEN_LOG_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.util.isFalse
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * @author 肖嘉威
 * @date 2024/9/28 21:58
 */
class ExceptionListenStarter : AbstractStarter() {

    private var errorScheduledFuture: ScheduledFuture<*>? = null
    private var uncertainIdleProbeAttempts = 0

    init {
        TaskManager.addTask(this)
    }

    private fun closeListener() {
        errorScheduledFuture?.let {
            it.isDone.isFalse {
                it.cancel(true)
                errorScheduledFuture = null
            }
        }
    }

    override fun execStart() {
        closeListener()
        if (System.getProperty("hs.script.e2e") == "true") {
            log.info { "E2E运行：禁用空闲重启器，避免匹配等待阶段重启真实对局" }
            return
        }
        log.info { "开始监听异常情况" }
        Core.lastActiveTime = System.currentTimeMillis()
        uncertainIdleProbeAttempts = 0
        errorScheduledFuture = LISTEN_LOG_THREAD_POOL.scheduleWithFixedDelay({
            if (PauseStatus.isPause || !WorkTimeListener.working) {
                closeListener()
                return@scheduleWithFixedDelay
            }
            val idleTime = ConfigUtil.getLong(ConfigEnum.IDLE_MAXIMUM_TIME)
            val now = System.currentTimeMillis()
            val idleForMs = now - Core.lastActiveTime
            val observedScreen = if (idleForMs > idleTime * 60_000L) {
                runCatching { ScreenStateRecovery.observeFreshScreenForWatchdog()?.screen }
                    .onFailure { error ->
                        log.warn(error) {
                            "IDLE_RESTART_SCREEN_PROBE_FAILED idleForMs=$idleForMs " +
                                "uncertainProbeAttempts=$uncertainIdleProbeAttempts"
                        }
                    }
                    .getOrNull()
            } else {
                null
            }
            when (
                IdleRestartGuardPolicy.evaluate(
                    idleForMs = idleForMs,
                    idleLimitMs = idleTime * 60_000L,
                    observedScreen = observedScreen,
                    uncertainProbeAttempts = uncertainIdleProbeAttempts,
                )
            ) {
                IdleRestartGuardPolicy.Decision.NOT_IDLE -> {
                    uncertainIdleProbeAttempts = 0
                }
                IdleRestartGuardPolicy.Decision.DEFER_ACTIVE_MATCHMAKING -> {
                    uncertainIdleProbeAttempts = 0
                    Core.lastActiveTime = now
                    log.info {
                        "IDLE_RESTART_DEFERRED reason=active-matchmaking-screen " +
                            "screen=$observedScreen idleForMs=$idleForMs powerLogActivity=not-required"
                    }
                }
                IdleRestartGuardPolicy.Decision.DEFER_UNCERTAIN_SCREEN -> {
                    uncertainIdleProbeAttempts++
                    log.warn {
                        "IDLE_RESTART_PROBE_DEFERRED reason=uncertain-screen " +
                            "screen=${observedScreen ?: "UNKNOWN"} " +
                            "attempt=$uncertainIdleProbeAttempts/${IdleRestartGuardPolicy.MAX_UNCERTAIN_PROBES} " +
                            "idleForMs=$idleForMs"
                    }
                }
                IdleRestartGuardPolicy.Decision.RESTART -> {
                    log.info {
                        "空闲时间超过${idleTime}分钟，准备重启游戏 " +
                            "screen=${observedScreen ?: "UNKNOWN"} " +
                            "uncertainProbeAttempts=$uncertainIdleProbeAttempts"
                    }
                    Core.restart()
                    Core.lastActiveTime = now
                    uncertainIdleProbeAttempts = 0
                }
            }
        }, 0, 1, TimeUnit.MINUTES)
    }

    override fun stopAll() {
        super.stopAll()
        closeListener()
    }

}
