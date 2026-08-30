package club.xiaojiawei.hsscript.listener

import club.xiaojiawei.hsscript.bean.WorkTimeRule
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.WindowEnum
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.WorkTimeStatus
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscript.utils.WindowUtil
import club.xiaojiawei.hsscript.utils.WorkTimeJitter
import club.xiaojiawei.hsscript.utils.WorkTimeWindow
import club.xiaojiawei.hsscript.utils.StartupRunWindow
import club.xiaojiawei.hsscript.utils.go
import club.xiaojiawei.hsscript.utils.runUI
import club.xiaojiawei.hsscriptbase.bean.LRunnable
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.util.isFalse
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.value.ChangeListener
import javafx.stage.Stage
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Random
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 工作状态
 *
 * @author 肖嘉威
 * @date 2023/9/10 22:04
 */
object WorkTimeListener {
    private var checkWorkTask: ScheduledFuture<*>? = null

    private data class JitterKey(
        val ruleSetId: String,
        val date: LocalDate,
        val ruleIndex: Int,
        val startSeconds: Int,
        val endSeconds: Int,
        val jitterSeconds: Int,
    )

    private val jitterCacheLock = Any()
    private val jitterCache = mutableMapOf<JitterKey, WorkTimeJitter.Window>()
    private var jitterCacheObservedToday: LocalDate? = null
    private val startupRunWindow = StartupRunWindow()
    private var startupWorkTimeRule: WorkTimeRule? = null
    private var currentScheduleWindow: WorkTimeJitter.Window? = null
    private var currentScheduleRuleSetId: String? = null
    private var currentScheduleRuleIndex: Int? = null
    private var lastScheduleDecision: String? = null

    private fun timestamp(): String = ZonedDateTime.now().toString()

    private fun scheduleWindowDescription(): String {
        val window = currentScheduleWindow
        if (window == null) {
            return startupRunWindow.deadline()?.let { "startup-override deadline=$it" } ?: "none"
        }
        return "ruleSet=${currentScheduleRuleSetId ?: "?"} " +
            "ruleIndex=${currentScheduleRuleIndex ?: "?"} " +
            "effective=${window.start}-${window.end} " +
            "offsets=${window.startOffsetSeconds},${window.endOffsetSeconds}s"
    }

    private fun jitteredWindow(
        ruleSetId: String,
        ruleIndex: Int,
        rule: WorkTimeRule,
        date: LocalDate,
    ): WorkTimeJitter.Window? {
        val baseStart = rule.workTime.parseStartTime()?.withSecond(0) ?: return null
        val parsedEnd = rule.workTime.parseEndTime() ?: return null
        // A configured end minute includes the whole minute.  Keep this
        // canonical value for every caller so cached jitter never differs
        // between schedule checks and next-period display.
        val baseEnd = parsedEnd.withSecond(59)
        val jitterSeconds = WorkTimeJitter.normalizeSeconds(
            WorkTimeStatus.readOnlyWorkTimeRuleSet()
                .toList()
                .find { it.id == ruleSetId }
                ?.jitterSeconds
                ?: 0,
        )
        val key = JitterKey(
            ruleSetId = ruleSetId,
            date = date,
            ruleIndex = ruleIndex,
            startSeconds = baseStart.toSecondOfDay(),
            endSeconds = baseEnd.toSecondOfDay(),
            jitterSeconds = jitterSeconds,
        )
        synchronized(jitterCacheLock) {
            // Keep plans for today and future schedule days.  getNextWorkPeriodInfo
            // queries several future dates in one pass, so clearing whenever the
            // requested date changes would reroll those plans on every poll.
            val today = LocalDate.now()
            if (jitterCacheObservedToday == null || !jitterCacheObservedToday!!.equals(today)) {
                jitterCache.entries.removeIf { it.key.date.isBefore(today.minusDays(1)) }
                jitterCacheObservedToday = today
            }
            return jitterCache.getOrPut(key) {
                val window = WorkTimeJitter.jitterWindow(
                    start = baseStart,
                    end = baseEnd,
                    maxSeconds = jitterSeconds,
                    random = Random(),
                )
                log.info {
                    "SCHEDULE_EFFECTIVE_WINDOW timestamp=${timestamp()} date=$date " +
                        "ruleSet=$ruleSetId ruleIndex=$ruleIndex " +
                        "planned=$baseStart-$baseEnd effective=${window.start}-${window.end} " +
                        "offsets=${window.startOffsetSeconds},${window.endOffsetSeconds}s " +
                        "maxJitterSeconds=$jitterSeconds"
                }
                window
            }
        }
    }

    val launch: Unit by lazy {
        checkWorkTask =
            EXTRA_THREAD_POOL.scheduleWithFixedDelay(
                LRunnable {
                    checkWork()
                    tryWork()
                },
                0,
                30,
                TimeUnit.SECONDS,
            )
        workingProperty.addListener { _, oldValue, newValue ->
            if (oldValue != newValue) {
                log.info {
                    "SCHEDULE_RUNTIME timestamp=${timestamp()} " +
                        "event=${if (newValue) "STARTED" else "STOPPED"} " +
                        "normalScheduleActive=$isDuringWorkDate " +
                        "startupOverrideActive=${startupRunWindow.isActive()} " +
                        "window=${scheduleWindowDescription()}"
                }
            }
        }
        WarEx.inWarProperty.addListener { _, _, newValue ->
            if (!newValue && PauseStatus.isStart) {
                checkWork()
                if (cannotWork()) {
                    cannotWorkLog()
                    workingProperty.set(false)
                    execOperate(prevClosestWorkTimeRule)
                }
            }
        }
        log.info { "工作时段监听已启动" }
    }

    /**
     * 执行工作时间段结束后的操作
     */
    private fun execOperate(workTimeRule: WorkTimeRule?) {
        val operates = workTimeRule?.operates ?: return

        val alert: AtomicReference<Stage?> = AtomicReference<Stage?>()
        val countdownTime = 10
        val future =
            go {
                for (i in 0 until countdownTime) {
                    if (PauseStatus.isStart) {
                        Thread.sleep(1000)
                    } else {
                        break
                    }
                }
                runUI {
                    alert.get()?.hide()
                }
                for (operate in operates) {
                    if (PauseStatus.isStart) {
                        operate.exec().isFalse {
                            log.error {
                                operate.value + "执行失败"
                            }
                        }
                    } else {
                        return@go
                    }
                }
            }
        val operationName = operates.map { it.value }
        val text = "${countdownTime}秒后执行：$operationName"
        log.info { "工作时间段结束，$text" }
        runUI {
            alert.set(
                WindowUtil
                    .createAlert(
                        text,
                        null,
                        {
                            future.cancel(true)
                            runUI {
                                alert.get()?.hide()
                            }
                        },
                        null,
                        WindowUtil.getStage(WindowEnum.MAIN),
                        "阻止",
                    ).apply {
                        show()
                    },
            )
        }
    }

    var isDuringWorkDate = false

    /**
     * 是否处于工作中
     */
    private val workingProperty = SimpleBooleanProperty(false)

    /**
     * 当前工作时间规则
     */
    private var currentWorkTimeRule: WorkTimeRule? = null

    var closestWorkTimeRule: WorkTimeRule? = null
        private set

    var working: Boolean
        get() {
            return workingProperty.get()
        }
        set(value) {
            workingProperty.set(value)
        }

    fun addWorkStatusListener(listener: ChangeListener<Boolean>) {
        workingProperty.addListener(listener)
    }

    fun removeWorkStatusListener(listener: ChangeListener<Boolean>) {
        workingProperty.removeListener(listener)
    }

    fun canWork(): Boolean = startupRunWindow.shouldWork(isDuringWorkDate)

    fun cannotWork(): Boolean = !canWork()

    /**
     * Handles an explicit start request.  When the schedule is closed, it
     * opens the configured temporary startup window instead of showing the
     * sleep prompt.  The regular schedule remains authoritative after the
     * temporary window expires.
     */
    @Synchronized
    fun requestStart(): Boolean {
        checkWork()
        if (!isDuringWorkDate) {
            val durationMinutes = ConfigUtil.getInt(ConfigEnum.STARTUP_RUN_WINDOW_MINUTES).coerceAtLeast(0)
            if (startupRunWindow.beginIfOutsideSchedule(durationMinutes, inSchedule = false)) {
                startupWorkTimeRule = closestWorkTimeRule ?: getTodayWorkTimeRules().firstOrNull()
                if (startupWorkTimeRule != null) {
                    closestWorkTimeRule = startupWorkTimeRule
                }
                log.info {
                    "启动后强制运行已开始：${durationMinutes}分钟，截止=${startupRunWindow.deadline()}；到期后恢复正常时间表"
                }
            }
        } else {
            startupRunWindow.clear()
            startupWorkTimeRule = null
        }
        return canWork()
    }

    fun tryWork() {
        if (canWork() && PauseStatus.isStart) {
            workingProperty.set(true)
        } else if (!canWork() && working && !WarEx.inWar) {
            workingProperty.set(false)
            execOperate(prevClosestWorkTimeRule)
        }
    }

    /**
     * 获取当前的工作时间规则
     * @return 如果当前处于工作时间内，返回对应的WorkTimeRule；否则返回null
     */
    fun getCurrentWorkTimeRule(): WorkTimeRule? {
        return if (isDuringWorkDate) currentWorkTimeRule else if (startupRunWindow.isActive()) startupWorkTimeRule else null
    }

    /**
     * 获取当前生效的工作时间规则（不管是否处于工作时间内）
     * @return 返回当前时间段对应的WorkTimeRule，如果没有找到则返回null
     */
    fun getActiveWorkTimeRule(): WorkTimeRule? {
        val readOnlyWorkTimeSetting = WorkTimeStatus.readOnlyWorkTimeSetting()
        val dayIndex = LocalDate.now().dayOfWeek.value - 1
        if (dayIndex >= readOnlyWorkTimeSetting.size) return null

        val id = readOnlyWorkTimeSetting[dayIndex]
        return WorkTimeStatus.readOnlyWorkTimeRuleSet().toList().find { it.id == id }?.let { ruleSet ->
            val timeRules = ruleSet.getTimeRules().filter { it.enable }
            val nowTime = LocalTime.now()

            // 寻找当前时间所在的工作时间段
            timeRules.withIndex().find { (index, rule) ->
                val window = jitteredWindow(id, index, rule, LocalDate.now())
                    ?: return@find false
                WorkTimeWindow.contains(nowTime, window.start, window.end)
            }?.value
        }
    }

    /**
     * 获取今天所有启用的工作时间规则
     * @return 返回今天所有启用的WorkTimeRule列表
     */
    fun getTodayWorkTimeRules(): List<WorkTimeRule> {
        val readOnlyWorkTimeSetting = WorkTimeStatus.readOnlyWorkTimeSetting()
        val dayIndex = LocalDate.now().dayOfWeek.value - 1
        if (dayIndex >= readOnlyWorkTimeSetting.size) return emptyList()

        val id = readOnlyWorkTimeSetting[dayIndex]
        return WorkTimeStatus.readOnlyWorkTimeRuleSet().toList().find { it.id == id }?.let { ruleSet ->
            ruleSet.getTimeRules().filter { it.enable }
        } ?: emptyList()
    }

    /**
     * 检查是否有紧接着的下一个工作时间段
     * @param currentEndTime 当前工作时间段的结束时间
     * @return true如果有紧接着的工作时间段，false如果没有
     */
    private fun hasImmediateNextWorkPeriod(currentEndTime: LocalTime): Boolean {
        val readOnlyWorkTimeSetting = WorkTimeStatus.readOnlyWorkTimeSetting()
        val dayIndex = LocalDate.now().dayOfWeek.value - 1
        if (dayIndex >= readOnlyWorkTimeSetting.size) return false

        val id = readOnlyWorkTimeSetting[dayIndex]
        return WorkTimeStatus.readOnlyWorkTimeRuleSet().toList().find { it.id == id }?.let { ruleSet ->
            val timeRules = ruleSet.getTimeRules().filter { it.enable }

            // 检查是否有在当前结束时间后立即开始的工作时间段
            timeRules.withIndex().any { (index, rule) ->
                val window = jitteredWindow(id, index, rule, LocalDate.now())
                    ?: return@any false
                val startTime = window.start
                // 允许少量时间间隔（比如1分钟内）认为是连续的
                val timeDiff = startTime.toSecondOfDay() - currentEndTime.toSecondOfDay()
                timeDiff in 0..60 // 60秒内的间隔认为是连续的
            }
        } ?: false
    }

    @Synchronized
    fun checkWork() {
        var canWork = false
        var closestWorkTimeRule: WorkTimeRule? = null
        var activeScheduleWindow: WorkTimeJitter.Window? = null
        var activeScheduleRuleSetId: String? = null
        var activeScheduleRuleIndex: Int? = null

        val readOnlyWorkTimeSetting = WorkTimeStatus.readOnlyWorkTimeSetting()
        val dayIndex = LocalDate.now().dayOfWeek.value - 1
        if (dayIndex >= readOnlyWorkTimeSetting.size) {
            isDuringWorkDate = false
            currentWorkTimeRule = null
            prevClosestWorkTimeRule = null
            return
        }

        val id = readOnlyWorkTimeSetting[dayIndex]
        WorkTimeStatus.readOnlyWorkTimeRuleSet().toList().find { it.id == id }?.let { ruleSet ->
            val timeRules = ruleSet.getTimeRules().filter { it.enable } // 只处理启用的规则
            val nowTime = LocalTime.now()
            val nowSecondOfDay = nowTime.toSecondOfDay()

            var minDiffSec: Int = Int.MAX_VALUE

            // 重置当前工作时间规则
            currentWorkTimeRule = null

            for ((index, rule) in timeRules.withIndex()) {
                val window = jitteredWindow(id, index, rule, LocalDate.now())
                    ?: continue
                val startTime = window.start
                val endTime = window.end

                if (WorkTimeWindow.contains(nowTime, startTime, endTime)) {
                    canWork = true
                    closestWorkTimeRule = rule
                    currentWorkTimeRule = rule // 设置当前工作时间规则
                    this.closestWorkTimeRule = rule
                    activeScheduleWindow = window
                    activeScheduleRuleSetId = id
                    activeScheduleRuleIndex = index
                    break
                } else {
                    // 找出最近刚结束的工作时间段（用于执行收尾操作）
                    val diffSec = nowSecondOfDay - endTime.toSecondOfDay()
                    if (diffSec in 1 until minDiffSec) {
                        minDiffSec = diffSec
                        closestWorkTimeRule = rule
                    }
                }
            }
        }

        isDuringWorkDate = canWork
        currentScheduleWindow = activeScheduleWindow
        currentScheduleRuleSetId = activeScheduleRuleSetId
        currentScheduleRuleIndex = activeScheduleRuleIndex
        if (canWork) {
            startupRunWindow.clear()
            startupWorkTimeRule = null
        }
        prevClosestWorkTimeRule = closestWorkTimeRule

        val startupOverrideActive = startupRunWindow.isActive()
        val decision = if (canWork) {
            "ACTIVE:${currentScheduleRuleSetId ?: "?"}:${currentScheduleRuleIndex ?: "?"}"
        } else if (startupOverrideActive) {
            "STARTUP_OVERRIDE"
        } else {
            "OUTSIDE"
        }
        if (decision != lastScheduleDecision) {
            lastScheduleDecision = decision
            log.info {
                "SCHEDULE_DECISION timestamp=${timestamp()} decision=$decision " +
                    "normalScheduleActive=$canWork startupOverrideActive=$startupOverrideActive " +
                    "window=${scheduleWindowDescription()}"
            }
        }

        // 调试日志
        if (!canWork && prevClosestWorkTimeRule != null) {
            log.debug { "当前不在工作时间，最近结束的工作时间段：${prevClosestWorkTimeRule?.workTime}" }
        }
    }

    var prevClosestWorkTimeRule: WorkTimeRule? = null
        private set

    fun cannotWorkLog() {
        val context = "现在是下班时间 🌜"
        SystemUtil.notice(context)
        log.info { context }
    }

    /**
     * 获取下一次可工作的时间
     * @return 距离下一次工作时间的秒数，如果没有找到返回-1L，如果当前正在工作返回0L
     */
    fun getSecondsUntilNextWorkPeriod(): Long {
        if (working) return 0L

        val readOnlyWorkTimeSetting = WorkTimeStatus.readOnlyWorkTimeSetting()
        val currentDayIndex = LocalDate.now().dayOfWeek.value - 1
        if (currentDayIndex >= readOnlyWorkTimeSetting.size) return -1L

        // 先检查今天剩余的工作时间
        val todaySeconds = getSecondsUntilNextWorkPeriodForDay(currentDayIndex, 0)
        if (todaySeconds > 0) return todaySeconds

        // 检查后续几天的工作时间
        val totalDays = readOnlyWorkTimeSetting.size
        for (dayOffset in 1 until totalDays) {
            val dayIndex = (currentDayIndex + dayOffset) % totalDays
            val seconds = getSecondsUntilNextWorkPeriodForDay(dayIndex, dayOffset)
            if (seconds > 0) return seconds
        }

        return -1L
    }

    /**
     * 获取指定天的下一个工作时间段开始的秒数
     * @param dayIndex 星期索引 (0-6，0为周一)
     * @param dayOffset 天数偏移量 (0为今天，1为明天，以此类推)
     * @return 距离该天最近工作时间开始的秒数，如果没有找到返回-1L
     */
    private fun getSecondsUntilNextWorkPeriodForDay(dayIndex: Int, dayOffset: Int): Long {
        val readOnlyWorkTimeSetting = WorkTimeStatus.readOnlyWorkTimeSetting()
        if (dayIndex >= readOnlyWorkTimeSetting.size) return -1L

        val id = readOnlyWorkTimeSetting[dayIndex]
        return WorkTimeStatus.readOnlyWorkTimeRuleSet().toList().find { it.id == id }?.let { ruleSet ->
            val timeRules = ruleSet.getTimeRules().filter { it.enable }
            val nowTime = LocalTime.now()
            val nowSecondOfDay = nowTime.toSecondOfDay()

            var minDiffSec: Long = Long.MAX_VALUE

            for ((index, rule) in timeRules.withIndex()) {
                val window = jitteredWindow(
                    id,
                    index,
                    rule,
                    LocalDate.now().plusDays(dayOffset.toLong()),
                ) ?: continue
                val startSecondOfDay = window.start.toSecondOfDay().toLong()

                val diffSec: Long = if (dayOffset == 0) {
                    // 今天：只考虑未来的时间
                    startSecondOfDay - nowSecondOfDay
                } else {
                    // 其他天：加上天数偏移的秒数
                    startSecondOfDay + dayOffset * 24 * 3600L - nowSecondOfDay
                }

                if (diffSec > 0 && diffSec < minDiffSec) {
                    minDiffSec = diffSec
                }
            }

            if (minDiffSec == Long.MAX_VALUE) -1L else minDiffSec
        } ?: -1L
    }

    /**
     * 获取下一个工作时间段的详细信息
     * @return Pair<WorkTimeRule?, Long> - 工作规则和距离开始的秒数
     */
    fun getNextWorkPeriodInfo(): Pair<WorkTimeRule?, Long> {
        if (working) return Pair(getCurrentWorkTimeRule() ?: startupWorkTimeRule, 0L)

        val readOnlyWorkTimeSetting = WorkTimeStatus.readOnlyWorkTimeSetting()
        val currentDayIndex = LocalDate.now().dayOfWeek.value - 1
        if (currentDayIndex >= readOnlyWorkTimeSetting.size) return Pair(null, -1L)

        val nowTime = LocalTime.now()
        val nowSecondOfDay = nowTime.toSecondOfDay()

        var nearestRule: WorkTimeRule? = null
        var nearestSeconds: Long = Long.MAX_VALUE

        // 检查所有天的工作时间
        val totalDays = readOnlyWorkTimeSetting.size
        for (dayOffset in 0 until totalDays) {
            val dayIndex = (currentDayIndex + dayOffset) % totalDays
            val id = readOnlyWorkTimeSetting[dayIndex]

            WorkTimeStatus.readOnlyWorkTimeRuleSet().toList().find { it.id == id }?.let { ruleSet ->
                val timeRules = ruleSet.getTimeRules().filter { it.enable }

                for ((index, rule) in timeRules.withIndex()) {
                    val window = jitteredWindow(
                        id,
                        index,
                        rule,
                        LocalDate.now().plusDays(dayOffset.toLong()),
                    ) ?: continue
                    val startSecondOfDay = window.start.toSecondOfDay().toLong()

                    val diffSec: Long = if (dayOffset == 0) {
                        startSecondOfDay - nowSecondOfDay
                    } else {
                        startSecondOfDay + dayOffset * 24 * 3600L - nowSecondOfDay
                    }

                    if (diffSec > 0 && diffSec < nearestSeconds) {
                        nearestSeconds = diffSec
                        nearestRule = rule
                    }
                }
            }
        }

        return if (nearestSeconds == Long.MAX_VALUE) {
            Pair(null, -1L)
        } else {
            Pair(nearestRule, nearestSeconds)
        }
    }
}
