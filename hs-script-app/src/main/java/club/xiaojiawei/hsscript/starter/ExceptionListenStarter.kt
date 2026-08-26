package club.xiaojiawei.hsscript.starter

import club.xiaojiawei.hsscript.core.Core
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.PauseStatus
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
        errorScheduledFuture = LISTEN_LOG_THREAD_POOL.scheduleWithFixedDelay({
            if (PauseStatus.isPause || !WorkTimeListener.working) {
                closeListener()
                return@scheduleWithFixedDelay
            }
            val idleTime = ConfigUtil.getLong(ConfigEnum.IDLE_MAXIMUM_TIME)
            if (System.currentTimeMillis() - Core.lastActiveTime > idleTime * 60_000L
            ) {
                log.info { "空闲时间超过${idleTime}分钟，准备重启游戏" }
                Core.restart()
                Core.lastActiveTime = System.currentTimeMillis()
            }
        }, 0, 1, TimeUnit.MINUTES)
    }

    override fun stopAll() {
        super.stopAll()
        closeListener()
    }

}
