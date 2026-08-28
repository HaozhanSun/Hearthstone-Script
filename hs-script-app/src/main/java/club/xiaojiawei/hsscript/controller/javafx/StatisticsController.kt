package club.xiaojiawei.hsscript.controller.javafx

import club.xiaojiawei.controls.ProgressModal
import club.xiaojiawei.controls.ico.OfflineIco
import club.xiaojiawei.controls.ico.OnlineIco
import club.xiaojiawei.hsscript.interfaces.StageHook
import club.xiaojiawei.hsscript.statistics.Record
import club.xiaojiawei.hsscript.statistics.RecordDaoEx
import club.xiaojiawei.hsscript.utils.runUI
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import javafx.beans.property.DoubleProperty
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.geometry.Pos
import javafx.scene.chart.BarChart
import javafx.scene.chart.CategoryAxis
import javafx.scene.chart.LineChart
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.PieChart
import javafx.scene.chart.XYChart
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.DatePicker
import javafx.scene.control.Label
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.scene.text.Text
import javafx.util.StringConverter
import java.net.URL
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ResourceBundle
import kotlin.math.max

/** Statistics view with explicit played-only and experimental filters. */
class StatisticsController : Initializable, StageHook {

    data class StrategyItem(val id: String?, val name: String)

    @FXML protected lateinit var totalCount: Text
    @FXML protected lateinit var avgWR: Text
    @FXML protected lateinit var playedCount: Text
    @FXML protected lateinit var playedWR: Text
    @FXML protected lateinit var surrenderedCount: Text
    @FXML protected lateinit var unknownSurrenderCount: Text
    @FXML protected lateinit var totalEXP: Text
    @FXML protected lateinit var totalDuration: Text
    @FXML protected lateinit var avgRoundDuration: Text
    @FXML protected lateinit var avgEXPPerGame: Text
    @FXML protected lateinit var avgEXPPerMinute: Text

    @FXML protected lateinit var wrPane: StackPane
    @FXML protected lateinit var strategyPane: StackPane
    @FXML protected lateinit var runModePane: StackPane
    @FXML protected lateinit var timePane: StackPane
    @FXML protected lateinit var durationPane: StackPane
    @FXML protected lateinit var rootPane: StackPane
    @FXML protected lateinit var strategyComboBox: ComboBox<StrategyItem>

    @FXML protected lateinit var startDatePicker: DatePicker
    @FXML protected lateinit var endDatePicker: DatePicker
    @FXML protected lateinit var startHour: ComboBox<String>
    @FXML protected lateinit var startMinute: ComboBox<String>
    @FXML protected lateinit var endHour: ComboBox<String>
    @FXML protected lateinit var endMinute: ComboBox<String>

    @FXML protected lateinit var nonSurrenderOnly: CheckBox
    @FXML protected lateinit var knownSurrenderOnly: CheckBox
    @FXML protected lateinit var excludePractice: CheckBox

    @FXML protected lateinit var mainProgressModal: ProgressModal
    @FXML protected lateinit var unBindIco: OfflineIco
    @FXML protected lateinit var bindIco: OnlineIco

    private var progress: DoubleProperty? = null
    private var isInit = false
    private var suppressStrategyChange = false
    private var suppressRangeChange = false
    private var loadGeneration = 0
    private var allRecords: List<Record> = emptyList()
    private var selectedStrategyId: String? = null

    private val hourItems = (0..23).map { "%02d".format(it) }
    private val minuteItems = (0..59).map { "%02d".format(it) }

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        // Do not show the modal during FXML initialization. The controls are
        // not ready yet, and onShowing() is responsible for starting the first
        // load and owning its completion handle.
    }

    override fun onShowing() {
        if (isInit) return
        isInit = true

        startHour.items.setAll(hourItems)
        endHour.items.setAll(hourItems)
        startMinute.items.setAll(minuteItems)
        endMinute.items.setAll(minuteItems)
        startDatePicker.value = LocalDate.now()
        endDatePicker.value = LocalDate.now()
        selectTime(startHour, startMinute, LocalTime.MIDNIGHT)
        selectTime(endHour, endMinute, LocalTime.of(23, 59))

        strategyComboBox.converter = object : StringConverter<StrategyItem>() {
            override fun toString(value: StrategyItem?): String = value?.name ?: ""
            override fun fromString(value: String?): StrategyItem? = null
        }
        strategyComboBox.selectionModel.selectedItemProperty().addListener { _, _, value ->
            if (suppressStrategyChange) return@addListener
            selectedStrategyId = value?.id
            render(filteredRecords())
        }

        startDatePicker.valueProperty().addListener { _, _, value ->
            if (!suppressRangeChange && bindIco.isVisible) syncDate(endDatePicker, value)
            loadData()
        }
        endDatePicker.valueProperty().addListener { _, _, value ->
            if (!suppressRangeChange && bindIco.isVisible) syncDate(startDatePicker, value)
            loadData()
        }
        listOf(startHour, startMinute, endHour, endMinute).forEach { combo ->
            combo.valueProperty().addListener { _, _, _ -> loadData() }
        }
        nonSurrenderOnly.selectedProperty().addListener { _, _, _ -> render(filteredRecords()) }
        knownSurrenderOnly.selectedProperty().addListener { _, _, _ -> render(filteredRecords()) }
        excludePractice.selectedProperty().addListener { _, _, _ -> render(filteredRecords()) }

        suppressStrategyChange = true
        strategyComboBox.items.setAll(StrategyItem(null, "所有"))
        strategyComboBox.selectionModel.selectFirst()
        suppressStrategyChange = false
        loadData()
    }

    private fun selectTime(hour: ComboBox<String>, minute: ComboBox<String>, time: LocalTime) {
        hour.selectionModel.select("%02d".format(time.hour))
        minute.selectionModel.select("%02d".format(time.minute))
    }

    private fun syncDate(target: DatePicker, value: LocalDate?) {
        suppressRangeChange = true
        target.value = value
        suppressRangeChange = false
    }

    private fun calcStartDateTime(): LocalDateTime? = combineDateTime(startDatePicker, startHour, startMinute)

    private fun calcEndDateTimeExclusive(): LocalDateTime? =
        combineDateTime(endDatePicker, endHour, endMinute)?.plusMinutes(1)

    private fun combineDateTime(
        picker: DatePicker,
        hour: ComboBox<String>,
        minute: ComboBox<String>,
    ): LocalDateTime? {
        val date = picker.value ?: return null
        val h = hour.value?.toIntOrNull() ?: return null
        val m = minute.value?.toIntOrNull() ?: return null
        return LocalDateTime.of(date, LocalTime.of(h, m))
    }

    private fun loadData() {
        val start = calcStartDateTime() ?: return
        val endExclusive = calcEndDateTimeExclusive() ?: return
        val generation = ++loadGeneration
        if (!start.isBefore(endExclusive)) {
            allRecords = emptyList()
            render(emptyList())
            mainProgressModal.hide(progress)
            progress = null
            return
        }

        val loadProgress = mainProgressModal.show()
        progress = loadProgress
        EXTRA_THREAD_POOL.submit {
            try {
                val records = RecordDaoEx.queryRecord(start, endExclusive)
                runUI {
                    if (generation != loadGeneration) return@runUI
                    try {
                        allRecords = records
                        populateStrategies(records)
                        render(filteredRecords())
                    } finally {
                        mainProgressModal.hide(loadProgress)
                        if (progress === loadProgress) progress = null
                    }
                }
            } catch (error: Throwable) {
                log.error(error) { "读取统计数据失败：$start -> $endExclusive" }
                runUI {
                    if (generation == loadGeneration) {
                        mainProgressModal.hide(loadProgress)
                        if (progress === loadProgress) progress = null
                    }
                }
            }
        }
    }

    private fun populateStrategies(records: List<Record>) {
        val oldId = selectedStrategyId
        val items = records
            .groupBy { it.strategyId }
            .map { (id, values) -> StrategyItem(id, values.firstOrNull()?.strategyName ?: "未知") }
            .sortedBy { it.name }
        val selected = items.firstOrNull { it.id == oldId }
        suppressStrategyChange = true
        strategyComboBox.items.setAll(StrategyItem(null, "所有"))
        strategyComboBox.items.addAll(items)
        strategyComboBox.selectionModel.select(selected ?: strategyComboBox.items.first())
        selectedStrategyId = selected?.id
        suppressStrategyChange = false
    }

    private fun filteredRecords(source: List<Record> = allRecords): List<Record> = source.filter { record ->
        (selectedStrategyId == null || record.strategyId == selectedStrategyId) &&
                (!nonSurrenderOnly.isSelected || record.surrendered == false) &&
                (!knownSurrenderOnly.isSelected || record.surrendered != null) &&
                (!excludePractice.isSelected || record.runMode != RunModeEnum.PRACTICE)
    }

    private fun render(records: List<Record>) {
        initStrategyPane(records)
        initRunModePane(records)
        initTimePane(records)
        initDurationPane(records)
        initWRPane(records)
        initSummarizePane(records)
    }

    private fun initStrategyPane(records: List<Record>) {
        val values = records.groupBy { it.strategyId }
            .map { (_, list) ->
                PieChart.Data("${list.firstOrNull()?.strategyName ?: "未知"}\t${list.size}次", list.size.toDouble())
            }
        strategyPane.children.setAll(PieChart().apply {
            title = "策略占比（当前筛选）"
            data.addAll(values)
            isClockwise = true
            labelsVisible = true
            startAngle = 90.0
        })
    }

    private fun initRunModePane(records: List<Record>) {
        val values = records.groupBy { it.runMode }
            .map { (mode, list) -> PieChart.Data("${mode?.comment ?: "未知"}\t${list.size}次", list.size.toDouble()) }
        runModePane.children.setAll(PieChart().apply {
            title = "模式占比（当前筛选）"
            data.addAll(values)
            isClockwise = true
            labelsVisible = true
            startAngle = 90.0
        })
    }

    private fun initTimePane(records: List<Record>) {
        val hours = (0..23).map { "%02d点".format(it) }
        val counts = records.groupingBy { it.endTime?.format(DateTimeFormatter.ofPattern("HH"))?.let { value -> "${value}点" } ?: "未知" }
            .eachCount()
        val xAxis = CategoryAxis().apply { label = "结束时间"; categories.addAll(hours) }
        val maxCount = max(1, hours.maxOfOrNull { counts[it] ?: 0 } ?: 0)
        val chart = LineChart(xAxis, NumberAxis(0.0, (maxCount + 1).toDouble(), max(1, (maxCount + 1) / 5).toDouble())).apply {
            title = "活跃时间分布"
            isLegendVisible = false
        }
        chart.data.add(XYChart.Series<String, Number>().apply {
            hours.forEach { data.add(XYChart.Data<String, Number>(it, counts[it] ?: 0)) }
        })
        timePane.children.setAll(chart)
    }

    private fun initDurationPane(records: List<Record>) {
        val durationCounts = records.mapNotNull { durationMinutes(it) }
            .groupingBy { it }
            .eachCount()
            .toSortedMap()
        val categories = durationCounts.keys.map { it.toString() }
        val xAxis = CategoryAxis().apply { label = "时长（分钟）"; this.categories.addAll(categories) }
        val maxCount = max(1, durationCounts.values.maxOrNull() ?: 0)
        val chart = BarChart(xAxis, NumberAxis(0.0, (maxCount + 1).toDouble(), max(1, (maxCount + 1) / 5).toDouble())).apply {
            title = "每局时长统计"
            isLegendVisible = false
            barGap = 1.0
        }
        chart.data.add(XYChart.Series<String, Number>().apply {
            durationCounts.forEach { (minutes, count) -> data.add(XYChart.Data<String, Number>(minutes.toString(), count).apply { node = createDataLabel(count) }) }
        })
        durationPane.children.setAll(chart)
    }

    private fun initWRPane(records: List<Record>) {
        val rates = records.groupBy { it.strategyId to it.strategyName }.map { (strategy, games) ->
            val wins = games.count { it.result == true }
            (strategy.second ?: "未知") to if (games.isEmpty()) 0.0 else wins * 100.0 / games.size
        }
        val xAxis = CategoryAxis().apply { label = "策略" }
        val yAxis = NumberAxis(0.0, 100.0, 10.0).apply { label = "胜率 (%)"; isAutoRanging = false }
        val chart = BarChart(xAxis, yAxis).apply {
            title = "策略胜率对比（当前筛选）"
            isLegendVisible = false
        }
        chart.data.add(XYChart.Series<String, Number>().apply {
            rates.forEach { (name, rate) -> data.add(XYChart.Data<String, Number>(name, rate).apply { node = createPercentDataLabel(rate) }) }
        })
        wrPane.children.setAll(chart)
    }

    private fun initSummarizePane(records: List<Record>) {
        val durations = records.mapNotNull { durationSeconds(it) }
        val totalSeconds = durations.sum()
        val played = records.filter { it.surrendered == false }
        val totalExperience = records.sumOf { it.experience ?: 0 }
        val overallWins = records.count { it.result == true }
        val playedWins = played.count { it.result == true }
        val xpPerMinute = if (totalSeconds > 0) totalExperience * 60.0 / totalSeconds else null

        totalCount.text = records.size.toString()
        avgWR.text = percentage(overallWins, records.size)
        playedCount.text = played.size.toString()
        playedWR.text = percentage(playedWins, played.size)
        surrenderedCount.text = records.count { it.surrendered == true }.toString()
        unknownSurrenderCount.text = records.count { it.surrendered == null }.toString()
        totalEXP.text = totalExperience.toString()
        totalDuration.text = formatDuration(totalSeconds.takeIf { durations.isNotEmpty() })
        avgRoundDuration.text = formatDuration((totalSeconds.toDouble() / durations.size).toLong().takeIf { durations.isNotEmpty() })
        avgEXPPerGame.text = if (records.isEmpty()) "—" else decimal(totalExperience.toDouble() / records.size)
        avgEXPPerMinute.text = xpPerMinute?.let(::decimal) ?: "—"
    }

    private fun durationSeconds(record: Record): Long? {
        val start = record.startTime ?: return null
        val end = record.endTime ?: return null
        return Duration.between(start, end).seconds.takeIf { it >= 0 }
    }

    private fun durationMinutes(record: Record): Int? = durationSeconds(record)?.let { ((it + 30) / 60).toInt() }

    private fun percentage(wins: Int, games: Int): String = if (games == 0) "—" else "%.2f%%".format(wins * 100.0 / games)

    private fun decimal(value: Double): String = "%.2f".format(value)

    private fun formatDuration(seconds: Long?): String {
        if (seconds == null) return "—"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60
        return when {
            hours > 0 -> "${hours}小时${minutes}分钟"
            minutes > 0 -> "${minutes}分${remainder}秒"
            else -> "${remainder}秒"
        }
    }

    private fun createPercentDataLabel(value: Number): StackPane = StackPane(Label("%.2f%%".format(value.toDouble())).apply {
        style = "-fx-font-size: 12px;-fx-padding: 1 0 0 0;"
    }).apply { alignment = Pos.TOP_CENTER }

    private fun createDataLabel(value: Number): StackPane = StackPane(Label(value.toString()).apply {
        style = "-fx-font-size: 12px;-fx-padding: 1 0 0 0;"
    }).apply { alignment = Pos.TOP_CENTER }

    @FXML
    protected fun changeStatus(mouseEvent: MouseEvent) {
        bindIco.isVisible = !bindIco.isVisible
        unBindIco.isVisible = !unBindIco.isVisible
    }
}
