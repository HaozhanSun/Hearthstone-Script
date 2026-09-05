package club.xiaojiawei.hsscript.listener

import club.xiaojiawei.hsscript.bean.WorkTimeRule
import club.xiaojiawei.hsscript.bean.WorkTimeRuleSet
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.WindowEnum
import club.xiaojiawei.hsscript.status.DebugRunController
import club.xiaojiawei.hsscript.status.DebugRunLease
import club.xiaojiawei.hsscript.status.Mode
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ScheduleOverrideInfo
import club.xiaojiawei.hsscript.status.ScheduleOverrideLogGate
import club.xiaojiawei.hsscript.status.TaskManager
import club.xiaojiawei.hsscript.status.WorkTimeStatus
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscript.utils.WindowUtil
import club.xiaojiawei.hsscript.utils.WorkTimeJitter
import club.xiaojiawei.hsscript.utils.WorkTimeWindow
import club.xiaojiawei.hsscript.utils.StartupRunWindow
import club.xiaojiawei.hsscript.utils.go
import club.xiaojiawei.hsscript.utils.runUI
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscriptbase.bean.LRunnable
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.util.isFalse
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.value.ChangeListener
import javafx.stage.Stage
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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

    private data class ScheduledRuleWindow(
        val ruleSetId: String,
        val ruleIndex: Int,
        val rule: WorkTimeRule,
        val scheduleDate: LocalDate,
        val window: WorkTimeJitter.Window,
        val occurrence: WorkTimeWindow.Occurrence,
    )

    private val jitterCacheLock = Any()
    private val jitterCache = mutableMapOf<JitterKey, WorkTimeJitter.Window>()
    private var jitterCacheObservedToday: LocalDate? = null
    private val startupRunWindow = StartupRunWindow()
    private val overrideLogGate = ScheduleOverrideLogGate()
    private var startupWorkTimeRule: WorkTimeRule? = null
    private var lastStartupOverrideInfo: ScheduleOverrideInfo? = null
    private var currentScheduleWindow: WorkTimeJitter.Window? = null
    private var currentScheduleRuleSetId: String? = null
    private var currentScheduleRuleIndex: Int? = null
    private var currentScheduleDate: LocalDate? = null
    private var lastScheduleDecision: String? = null

    private fun timestamp(): String = ZonedDateTime.now().toString()

    private fun currentModeName(): String = runCatching {
        (Mode.currMode ?: Mode.nextMode)?.name ?: "UNKNOWN"
    }.getOrDefault("UNKNOWN")

    private fun currentProviderName(): String = runCatching {
        OcrRuntime.currentProvider().name
    }.getOrDefault("UNKNOWN")

    /**
     * Returns the active temporary gate, giving the explicit DebugRun lease
     * precedence over the older startup window when both are present.
     */
    private fun currentScheduleOverride(): ScheduleOverrideInfo? {
        DebugRunController.currentOverrideInfo()?.let { return it }
        val snapshot = startupRunWindow.snapshot()
        if (!snapshot.active || snapshot.deadline == null) return null
        return ScheduleOverrideInfo(
            source = "startup-window",
            runId = snapshot.runId ?: "unknown",
            deadline = snapshot.deadline,
            mode = currentModeName(),
            provider = currentProviderName(),
        )
    }

    private fun reconcileStartupRuntime(overrideInfo: ScheduleOverrideInfo?) {
        val previous = lastStartupOverrideInfo
        if (overrideInfo?.source == "startup-window") {
            if (previous?.runId != overrideInfo.runId) {
                logStartupRuntime("STARTED", overrideInfo)
            }
            lastStartupOverrideInfo = overrideInfo
            return
        }
        if (previous != null) {
            val event = if (!Instant.now().isBefore(previous.deadline)) "EXPIRED" else "STOPPED"
            logStartupRuntime(event, previous)
            lastStartupOverrideInfo = null
        }
    }

    private fun logStartupRuntime(event: String, info: ScheduleOverrideInfo) {
        log.info {
            "SCHEDULE_RUNTIME timestamp=${timestamp()} event=$event source=${info.source} " +
                "runId=${info.runId} deadline=${info.deadline} mode=${info.mode} " +
                "provider=${info.provider} normalScheduleActive=$scheduledDuringWorkDate"
        }
    }

    private fun logOverrideSuppression(info: ScheduleOverrideInfo, reasons: Set<String>, normalScheduleActive: Boolean) {
        if (!overrideLogGate.consume(info)) return
        log.info {
            "SCHEDULE_OVERRIDE_SUPPRESSED_OUTSIDE_HOURS source=${info.source} " +
                "runId=${info.runId} deadline=${info.deadline} mode=${info.mode} " +
                "provider=${info.provider} reasons=${reasons.joinToString(",")} " +
                "normalScheduleActive=$normalScheduleActive"
        }
    }

    private fun scheduleWindowDescription(): String {
        val window = currentScheduleWindow
        if (window == null) {
            return startupRunWindow.deadline()?.let { "startup-override deadline=$it" } ?: "none"
        }
        return "ruleSet=${currentScheduleRuleSetId ?: "?"} " +
            "ruleIndex=${currentScheduleRuleIndex ?: "?"} " +
            "scheduleDate=${currentScheduleDate ?: "?"} " +
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
                val occurrence = WorkTimeWindow.occurrence(date, window.start, window.end)
                log.info {
                    "SCHEDULE_EFFECTIVE_WINDOW timestamp=${timestamp()} date=$date " +
                        "ruleSet=$ruleSetId ruleIndex=$ruleIndex " +
                        "planned=$baseStart-$baseEnd effective=${window.start}-${window.end} " +
                        "interpretation=${occurrence.interpretation} " +
                        "occurrence=${occurrence.start}-${occurrence.end} " +
                        "durationMinutes=${occurrence.durationMinutes()} " +
                        "offsets=${window.startOffsetSeconds},${window.endOffsetSeconds}s " +
                        "maxJitterSeconds=$jitterSeconds"
                }
                window
            }
        }
    }

    private fun scheduledWindows(
        now: LocalDateTime,
        firstDayOffset: Int,
        lastDayOffset: Int,
    ): List<ScheduledRuleWindow> {
        val workTimeSetting = WorkTimeStatus.readOnlyWorkTimeSetting().toList()
        if (workTimeSetting.isEmpty()) return emptyList()

        val ruleSets = WorkTimeStatus.readOnlyWorkTimeRuleSet().toList()
        val today = now.toLocalDate()
        val currentDayIndex = today.dayOfWeek.value - 1
        val windows = mutableListOf<ScheduledRuleWindow>()

        for (dayOffset in firstDayOffset..lastDayOffset) {
            val dayIndex = Math.floorMod(currentDayIndex + dayOffset, workTimeSetting.size)
            val ruleSetId = workTimeSetting.getOrNull(dayIndex) ?: continue
            val ruleSet: WorkTimeRuleSet = ruleSets.find { it.id == ruleSetId } ?: continue
            val scheduleDate = today.plusDays(dayOffset.toLong())

            for ((ruleIndex, rule) in ruleSet.getTimeRules().filter { it.enable }.withIndex()) {
                val window = jitteredWindow(ruleSetId, ruleIndex, rule, scheduleDate) ?: continue
                windows += ScheduledRuleWindow(
                    ruleSetId = ruleSetId,
                    ruleIndex = ruleIndex,
                    rule = rule,
                    scheduleDate = scheduleDate,
                    window = window,
                    occurrence = WorkTimeWindow.occurrence(scheduleDate, window.start, window.end),
                )
            }
        }

        return windows.sortedBy { it.occurrence.start }
    }

    private fun activeScheduleWindow(now: LocalDateTime = LocalDateTime.now()): ScheduledRuleWindow? =
        scheduledWindows(now, -1, 0)
            .firstOrNull { it.occurrence.contains(now) }

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

    /** True only when the ordinary configured schedule contains the current time. */
    private var scheduledDuringWorkDate = false

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
                reconcileStartupRuntime(currentScheduleOverride())
                log.info {
                    "启动后强制运行已开始：${durationMinutes}分钟，截止=${startupRunWindow.deadline()}；到期后恢复正常时间表"
                }
            }
        } else {
            startupRunWindow.clear()
            startupWorkTimeRule = null
            reconcileStartupRuntime(null)
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
        // Debug/Test Run only bypasses the gate. It must not make a preset
        // rule appear active or change the normal preset's mode/deck/actions.
        return if (scheduledDuringWorkDate) currentWorkTimeRule else if (startupRunWindow.isActive()) startupWorkTimeRule else null
    }

    fun isInsideConfiguredSchedule(): Boolean = scheduledDuringWorkDate

    /** Called by DebugRunController after its monotonic lease expires. */
    fun onDebugRunExpired() {
        checkWork()
        if (!scheduledDuringWorkDate && !startupRunWindow.isActive()) {
            TaskManager.closeAllTasks()
            if (workingProperty.get()) workingProperty.set(false)
            log.info { "DEBUG_OVERRIDE_EXPIRED_WORK_STOP reason=lease-expired-outside-schedule" }
        }
    }

    /**
     * 获取当前生效的工作时间规则（不管是否处于工作时间内）
     * @return 返回当前时间段对应的WorkTimeRule，如果没有找到则返回null
     */
    fun getActiveWorkTimeRule(): WorkTimeRule? {
        return activeScheduleWindow()?.rule
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

    @Synchronized
    fun checkWork() {
        val overrideInfoAtStart = currentScheduleOverride()
        val suppressionReasons = linkedSetOf<String>()
        val now = LocalDateTime.now()
        val activeWindow = activeScheduleWindow(now)
        var closestWorkTimeRule: WorkTimeRule? = activeWindow?.rule

        currentWorkTimeRule = activeWindow?.rule
        activeWindow?.let {
            this.closestWorkTimeRule = it.rule
        }

        if (activeWindow == null) {
            var minDiffSec = Long.MAX_VALUE
            val currentDate = now.toLocalDate()
            for (scheduledWindow in scheduledWindows(now, -1, 0)) {
                val diffSec = scheduledWindow.occurrence.secondsSinceEnd(now)
                if (diffSec > 0 && scheduledWindow.occurrence.end.toLocalDate().isEqual(currentDate) && diffSec < minDiffSec) {
                    minDiffSec = diffSec
                    closestWorkTimeRule = scheduledWindow.rule
                }
            }
        }

        var overrideInfo = currentScheduleOverride()
        val canWork = activeWindow != null
        scheduledDuringWorkDate = canWork
        isDuringWorkDate = DebugRunLease.effectiveCanWork(canWork, overrideInfo?.source == "debug-run")
        currentScheduleWindow = activeWindow?.window
        currentScheduleRuleSetId = activeWindow?.ruleSetId
        currentScheduleRuleIndex = activeWindow?.ruleIndex
        currentScheduleDate = activeWindow?.scheduleDate
        if (canWork) {
            startupRunWindow.clear()
            startupWorkTimeRule = null
            // The normal schedule takes precedence.  Re-read after clearing
            // a stale startup window so its lifecycle is logged as stopped.
            overrideInfo = currentScheduleOverride()
            isDuringWorkDate = DebugRunLease.effectiveCanWork(canWork, overrideInfo?.source == "debug-run")
        }
        reconcileStartupRuntime(overrideInfo)
        prevClosestWorkTimeRule = closestWorkTimeRule

        val startupOverrideActive = overrideInfo?.source == "startup-window"
        val decision = if (canWork) {
            "ACTIVE:${currentScheduleRuleSetId ?: "?"}:${currentScheduleRuleIndex ?: "?"}"
        } else if (DebugRunController.isActive()) {
            "DEBUG_OVERRIDE"
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

        if (!canWork && overrideInfo != null) {
            suppressionReasons += "outside-hours"
            logOverrideSuppression(overrideInfo, suppressionReasons, canWork)
        }
        if (suppressionReasons.isNotEmpty() && overrideInfo == null && overrideInfoAtStart != null) {
            logOverrideSuppression(overrideInfoAtStart, suppressionReasons, canWork)
        }

        // 调试日志：retain this warning for the normal schedule only.  An
        // active temporary override already has an explicit auditable record.
        if (!canWork && prevClosestWorkTimeRule != null && overrideInfo == null) {
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
        val now = LocalDateTime.now()
        if (working || activeScheduleWindow(now) != null) return 0L

        val readOnlyWorkTimeSetting = WorkTimeStatus.readOnlyWorkTimeSetting()
        if (readOnlyWorkTimeSetting.isEmpty()) return -1L

        // 检查今天和后续几天的工作时间
        val totalDays = readOnlyWorkTimeSetting.size
        for (dayOffset in 0 until totalDays) {
            val seconds = getSecondsUntilNextWorkPeriodForDay(dayOffset, now)
            if (seconds > 0) return seconds
        }

        return -1L
    }

    /**
     * 获取指定天的下一个工作时间段开始的秒数
     * @param dayOffset 天数偏移量 (0为今天，1为明天，以此类推)
     * @return 距离该天最近工作时间开始的秒数，如果没有找到返回-1L
     */
    private fun getSecondsUntilNextWorkPeriodForDay(dayOffset: Int, now: LocalDateTime): Long {
        val minDiffSec =
            scheduledWindows(now, dayOffset, dayOffset)
                .map { it.occurrence.secondsUntilStart(now) }
                .filter { it > 0 }
                .minOrNull()

        return minDiffSec ?: -1L
    }

    /**
     * 获取下一个工作时间段的详细信息
     * @return Pair<WorkTimeRule?, Long> - 工作规则和距离开始的秒数
     */
    fun getNextWorkPeriodInfo(): Pair<WorkTimeRule?, Long> {
        val now = LocalDateTime.now()
        val activeWindow = activeScheduleWindow(now)
        if (working || activeWindow != null) {
            return Pair(getCurrentWorkTimeRule() ?: activeWindow?.rule ?: startupWorkTimeRule, 0L)
        }

        val readOnlyWorkTimeSetting = WorkTimeStatus.readOnlyWorkTimeSetting()
        if (readOnlyWorkTimeSetting.isEmpty()) return Pair(null, -1L)

        var nearestRule: WorkTimeRule? = null
        var nearestSeconds: Long = Long.MAX_VALUE

        // 检查所有天的工作时间
        val totalDays = readOnlyWorkTimeSetting.size
        for (dayOffset in 0 until totalDays) {
            for (scheduledWindow in scheduledWindows(now, dayOffset, dayOffset)) {
                val diffSec = scheduledWindow.occurrence.secondsUntilStart(now)
                if (diffSec > 0 && diffSec < nearestSeconds) {
                    nearestSeconds = diffSec
                    nearestRule = scheduledWindow.rule
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
