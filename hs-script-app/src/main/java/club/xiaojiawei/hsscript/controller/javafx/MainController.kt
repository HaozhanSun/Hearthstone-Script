package club.xiaojiawei.hsscript.controller.javafx

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import club.xiaojiawei.controls.CopyLabel
import club.xiaojiawei.controls.Modal
import club.xiaojiawei.controls.NotificationManager
import club.xiaojiawei.controls.ico.AbstractIco
import club.xiaojiawei.controls.ico.ClearIco
import club.xiaojiawei.hsscript.appender.ExtraLogAppender
import club.xiaojiawei.hsscript.bean.DownloaderParam
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.bean.single.WarEx.resetStatistics
import club.xiaojiawei.hsscript.component.ConfigCheckBox
import club.xiaojiawei.hsscript.component.UrlLabel
import club.xiaojiawei.hsscript.component.WorkTimeItem
import club.xiaojiawei.hsscript.controller.javafx.view.MainView
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.WindowEnum
import club.xiaojiawei.hsscript.listener.VersionListener
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscript.status.DebugRunController
import club.xiaojiawei.hsscript.status.DebugRunLease
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.RuntimeSelectionSnapshot
import club.xiaojiawei.hsscript.status.RuntimeSelectionState
import club.xiaojiawei.hsscript.status.RuntimeSelectionUiContract
import club.xiaojiawei.hsscript.status.StrategyDefaultDeckSlotBindings
import club.xiaojiawei.hsscript.status.WorkTimeStatus
import club.xiaojiawei.hsscript.utils.ConfigExUtil
import club.xiaojiawei.hsscript.utils.ConfigUtil.getString
import club.xiaojiawei.hsscript.utils.ConfigUtil.putString
import club.xiaojiawei.hsscript.utils.FXUtil
import club.xiaojiawei.hsscript.utils.SystemUtil.copyToClipboard
import club.xiaojiawei.hsscript.utils.UiLogFormatter
import club.xiaojiawei.hsscript.utils.WindowUtil
import club.xiaojiawei.hsscript.utils.go
import club.xiaojiawei.hsscript.utils.runUI
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.config.submitExtra
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum.Companion.fromString
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import club.xiaojiawei.hsscriptbase.util.isFalse
import club.xiaojiawei.hsscriptbase.util.isTrue
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import javafx.animation.RotateTransition
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.collections.SetChangeListener
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.fxml.FXML
import javafx.scene.control.Label
import javafx.scene.control.OverrunStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javafx.scene.control.Toggle
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.AnchorPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.geometry.Pos
import javafx.scene.layout.Priority
import javafx.stage.Popup
import javafx.util.Duration
import javafx.util.StringConverter
import java.net.URL
import java.time.LocalDate
import java.util.*

/**
 * @author 肖嘉威
 * @date 2023/2/21 12:33
 */
private const val LOG_CONTENT_PADDING = 10.0

class MainController : MainView() {

    @FXML
    private lateinit var debugRunModeCheckBox: ConfigCheckBox

    @FXML
    private lateinit var debugRunStatus: Label

    private val uiLogTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var isNotHoverLog = true

    private val displayedTotalGameCount = SimpleStringProperty("0")
    private val displayedPlayedGameCount = SimpleStringProperty("0")
    private val displayedWinningPercentage = SimpleStringProperty("?")
    private val displayedGameTime = SimpleStringProperty("0")
    private val displayedExperience = SimpleStringProperty("0")

    private val runModeMap: MutableMap<RunModeEnum, MutableList<DeckStrategy>> = EnumMap(RunModeEnum::class.java)

    private val workTimeChangeId = "main-ui"

    private var initDate = LocalDate.now()
    private var applyingRuntimeSnapshot = false

    override fun initialize(
        url: URL?,
        resourceBundle: ResourceBundle?,
    ) {
        versionText.text = formatVersionText(VersionListener.currentRelease.tagName, BuildInfo.RELEASE_CHANNEL_LABEL)
        val startupDebugRun = DebugRunController.enableDefaultAfterRestart()
        debugRunModeCheckBox.isSelected = startupDebugRun.state == DebugRunLease.State.ACTIVE
        updateDebugRunStatus()
        bindStatisticsViews()
        refreshStatisticsViews()
        addListener()
        initModeAndDeck()
        reloadWorkTime()
        go {
            while (true) {
                Thread.sleep(30_000)
                if (LocalDate.now() > initDate) {
                    initDate = LocalDate.now()
                    log.info { "新的一天，应用新的工作时间规则" }
                    reloadWorkTime()
                }
            }
        }
        go {
            while (true) {
                Thread.sleep(1000)
                runUI { updateDebugRunStatus() }
            }
        }
    }

    private fun bindStatisticsViews() {
        totalGameCount.textProperty().bind(displayedTotalGameCount)
        playedGameCount.textProperty().bind(displayedPlayedGameCount)
        winningPercentage.textProperty().bind(displayedWinningPercentage)
        gameTime.textProperty().bind(displayedGameTime)
        exp.textProperty().bind(displayedExperience)
        logTotalGameCount.textProperty().bind(displayedTotalGameCount)
        logPlayedGameCount.textProperty().bind(displayedPlayedGameCount)
        logWinningPercentage.textProperty().bind(displayedWinningPercentage)
        logGameTime.textProperty().bind(displayedGameTime)
        logExp.textProperty().bind(displayedExperience)
    }

    private fun refreshStatisticsViews() {
        val snapshot = MainStatisticsSnapshot.from(
            totalGameCount = WarEx.effectiveGameCount,
            playedGameCount = WarEx.playedCount,
            playedWinCount = WarEx.playedWinCount,
            hangingTimeMinutes = WarEx.hangingTime,
            experience = WarEx.hangingEXP,
        )
        displayedTotalGameCount.set(snapshot.totalGameCount)
        displayedPlayedGameCount.set(snapshot.playedGameCount)
        displayedWinningPercentage.set(snapshot.winningPercentage)
        displayedGameTime.set(snapshot.gameTime)
        displayedExperience.set(snapshot.experience)
    }

    @FXML
    protected fun toggleDebugRun() {
        if (debugRunModeCheckBox.isSelected) {
            DebugRunController.enable("ui-toggle-on")
        } else {
            DebugRunController.disable("ui-toggle-off")
        }
        updateDebugRunStatus()
    }

    private fun updateDebugRunStatus() {
        if (!::debugRunStatus.isInitialized) return
        val snapshot = DebugRunController.snapshot()
        debugRunStatus.text = when (snapshot.state) {
            DebugRunLease.State.DISABLED -> "DISABLED"
            DebugRunLease.State.ACTIVE -> "ACTIVE · ${formatDebugRunRemaining(snapshot.remainingMillis)}"
            DebugRunLease.State.EXPIRED -> "EXPIRED"
        }
    }

    private fun formatDebugRunRemaining(remainingMillis: Long): String {
        val totalSeconds = (remainingMillis + 999L) / 1000L
        return String.format("%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    /**
     * 初始化模式和卡组
     */
    private fun initModeAndDeck() {
        defaultDeckSlotBox.items.setAll((StrategyDefaultDeckSlotBindings.MIN_DECK_SLOT..StrategyDefaultDeckSlotBindings.MAX_DECK_SLOT).toList())
        runModeBox.converter =
            object : StringConverter<RunModeEnum?>() {
                override fun toString(runModeEnum: RunModeEnum?): String? = runModeEnum?.comment ?: ""

                override fun fromString(s: String?): RunModeEnum? =
                    if (s == null || s.isBlank()) null else RunModeEnum.valueOf(s)
            }
        deckStrategyBox.converter =
            object : StringConverter<DeckStrategy?>() {
                override fun toString(deckStrategy: DeckStrategy?): String = deckStrategy?.name() ?: ""

                override fun fromString(s: String): DeckStrategy? = null
            }

        reloadRunMode()

        //        模式更改监听
        runModeBox.selectionModel
            .selectedItemProperty()
            .addListener { observable: ObservableValue<out RunModeEnum?>?, oldValue: RunModeEnum?, newValue: RunModeEnum? ->
                if (applyingRuntimeSnapshot) return@addListener
                val deckStrategies = if (newValue == null) null else runModeMap[newValue]
                deckStrategies?.let {
                    deckStrategyBox.items.setAll(deckStrategies.sortedBy { it.id() })
                } ?: let {
                    deckStrategyBox.items.clear()
                }
                putString(ConfigEnum.DEFAULT_RUN_MODE, newValue?.name ?: "", true)
                DeckStrategyManager.currentRunMode = newValue
            }

        //        卡组更改监听
        deckStrategyBox.selectionModel
            .selectedItemProperty()
            .addListener { observable: ObservableValue<out DeckStrategy?>?, oldValue: DeckStrategy?, newValue: DeckStrategy? ->
                if (applyingRuntimeSnapshot) return@addListener
                if (newValue == null) {
                    runModeMap[runModeBox.selectionModel.selectedItem]?.find { it == oldValue }?.let {
                        deckStrategyBox.selectionModel.select(oldValue)
                        return@addListener
                    }
                } else {
//                将卡组策略的第一个运行模式改为当前运行模式
                    for (i in newValue.runModes.indices) {
                        val runModeEnum = newValue.runModes[i]
                        if (runModeEnum == runModeBox.value) {
                            newValue.runModes[i] = newValue.runModes[0]
                            newValue.runModes[0] = runModeEnum
                            break
                        }
                    }
                }
                DeckStrategyManager.currentDeckStrategy = newValue
                updateDefaultDeckSlotView(newValue)
            }

        val defaultDeckId = getString(ConfigEnum.DEFAULT_DECK_STRATEGY)
        val defaultRunModeEnum = fromString(getString(ConfigEnum.DEFAULT_RUN_MODE))
        val defaultDeck =
            DeckStrategyManager.deckStrategies
                .stream()
                .filter { deckStrategy: DeckStrategy ->
                    defaultDeckId == deckStrategy.id() &&
                            deckStrategy.runModes.size > 0 &&
                            (
                                    defaultRunModeEnum == null ||
                                            Arrays
                                                .stream(deckStrategy.runModes)
                                                .anyMatch { runModeEnum: RunModeEnum -> runModeEnum == defaultRunModeEnum }
                                    )
                }.findFirst()
        if (defaultDeck.isPresent) {
            val deckStrategy = defaultDeck.get()
            deckStrategy.runModes
            runModeBox.value =
                Objects.requireNonNullElseGet(
                    defaultRunModeEnum,
                ) { deckStrategy.runModes[0] }
            deckStrategyBox.value = deckStrategy
            updateDefaultDeckSlotView(deckStrategy)
            val deckCode = deckStrategy.deckCode()
            if (!deckCode.isEmpty()) {
                log.info { "当前卡组代码↓" }
                log.info { "$${deckCode}" }
            }
        }

        DeckStrategyManager.currentDeckStrategyProperty.addListener {
                observableValue: ObservableValue<out DeckStrategy?>?,
                deck: DeckStrategy?,
                t1: DeckStrategy?,
            ->
            if (t1 != null && DeckStrategyManager.currentRuntimeSelectionSnapshot().state == RuntimeSelectionState.USER_DEFAULT) {
                t1.runModes
                runModeBox.value = t1.runModes[0]
                deckStrategyBox.value = t1
            }
        }
        DeckStrategyManager.runtimeSelectionSnapshotProperty.addListener { _, _, snapshot ->
            runUI { applyRuntimeSnapshot(snapshot) }
        }
        applyRuntimeSnapshot(DeckStrategyManager.currentRuntimeSelectionSnapshot())
    }

    private fun applyRuntimeSnapshot(snapshot: RuntimeSelectionSnapshot) {
        applyingRuntimeSnapshot = true
        try {
            runModeBox.styleClass.remove(RuntimeSelectionUiContract.SCHEDULE_COMBO_STYLE)
            deckStrategyBox.styleClass.remove(RuntimeSelectionUiContract.SCHEDULE_COMBO_STYLE)
            if (snapshot.fromSchedule) {
                runModeBox.styleClass.add(RuntimeSelectionUiContract.SCHEDULE_COMBO_STYLE)
                deckStrategyBox.styleClass.add(RuntimeSelectionUiContract.SCHEDULE_COMBO_STYLE)
            }
            val prompt = RuntimeSelectionUiContract.promptText(snapshot)
            runModeBox.promptText = prompt
            deckStrategyBox.promptText = prompt
            val mode = snapshot.runMode
            if (mode == null) {
                runModeBox.value = null
                deckStrategyBox.items.clear()
                deckStrategyBox.value = null
                return
            }
            if (!runModeBox.items.contains(mode)) {
                runModeBox.items.add(mode)
            }
            runModeBox.value = mode
            val strategies = runModeMap[mode].orEmpty().sortedBy { it.id() }
            deckStrategyBox.items.setAll(strategies)
            deckStrategyBox.value = snapshot.strategyId?.let { strategyId -> strategies.find { it.id() == strategyId } }
            if (snapshot.strategyId != null && deckStrategyBox.value == null) {
                deckStrategyBox.promptText = "策略未加载"
            }
            updateDefaultDeckSlotView(deckStrategyBox.value)
        } finally {
            applyingRuntimeSnapshot = false
        }
        reloadWorkTime(workTimeChangeId)
    }

    private fun updateDefaultDeckSlotView(strategy: DeckStrategy?) {
        val enabled = strategy != null
        defaultDeckSlotBox.isDisable = !enabled
        saveDefaultDeckSlotBtn.isDisable = !enabled
        if (!enabled) {
            defaultDeckSlotBox.value = null
            return
        }
        val choice = StrategyDefaultDeckSlotBindings.chooseDeckSlots(
            rule = null,
            strategyId = strategy.id(),
            globalDeckSlots = ConfigExUtil.getChooseDeckPos(),
        )
        defaultDeckSlotBox.value = choice.deckSlots.firstOrNull()
    }

    @FXML
    protected fun saveDefaultDeckSlotBinding() {
        val strategy = deckStrategyBox.value
        if (strategy == null) {
            notificationManger.showInfo("请先选择策略", 2)
            return
        }
        val deckSlot = defaultDeckSlotBox.value
        if (deckSlot == null || deckSlot !in StrategyDefaultDeckSlotBindings.MIN_DECK_SLOT..StrategyDefaultDeckSlotBindings.MAX_DECK_SLOT) {
            notificationManger.showInfo("请选择1-9号卡组槽位", 2)
            return
        }
        StrategyDefaultDeckSlotBindings.storeBinding(strategy.id(), deckSlot)
        DeckStrategyManager.refreshRuntimeSelectionSnapshot("default-deck-slot-saved")
        updateDefaultDeckSlotView(strategy)
        notificationManger.showSuccess("已保存默认槽位：${strategy.name()} -> $deckSlot", 2)
        log.info { "STRATEGY_DEFAULT_DECK_SLOT_SAVED strategy=${strategy.id()} name=${strategy.name()} deckSlot=$deckSlot" }
    }

    fun reloadRunMode() {
        runModeMap.clear()
        for (deckStrategy in DeckStrategyManager.deckStrategies) {
            for (runModeEnum in deckStrategy.runModes) {
                val strategies = runModeMap.getOrDefault(runModeEnum, ArrayList())
                strategies.add(deckStrategy)
                runModeMap[runModeEnum] = strategies
            }
        }
        runModeBox.items.setAll(runModeMap.keys)
    }

    private fun appendLog(event: ILoggingEvent) {
        runUI {
            val list = logVBox.children
//                大于二百五条就清空,防止性能问题
            if (list.size > 250) {
                list.clear()
            }
            val label = CopyLabel()
            label.notificationManager = notificationManger
            label.styleClass.add("logEntry")
            label.isWrapText = true
            label.textOverrun = OverrunStyle.CLIP
            bindLogWidth(label)

            val levelInt = event.level.levelInt
            val rawMessage = event.formattedMessage.orEmpty()
            var message = UiLogFormatter.format(rawMessage)
            //                处理需要复制的文本
            if (rawMessage.startsWith("$")) {
                label.text = rawMessage.substring(1)
                label.styleClass.add("copyLog")
                val anchorPane = wrapLabel(label)
                bindLogWidth(anchorPane)
                list.add(anchorPane)
                return@runUI
            }
            val timestamp = uiLogTimeFormatter.format(
                Instant.ofEpochMilli(event.timeStamp).atZone(ZoneId.systemDefault())
            )
            message = "[$timestamp] $message"
            // 为日志上颜色
            if (event.throwableProxy == null && levelInt <= Level.INFO_INT) {
                label.text = message
            } else if (levelInt <= Level.WARN_INT) {
                label.text = message
                label.styleClass.add("warnLog")
            } else {
                label.text = "$message，查看脚本日志获取详细错误信息"
                label.styleClass.add("errorLog")
            }
            val screenshotTarget = UiLogFormatter.fileTarget(rawMessage)
            if (screenshotTarget == null) {
                list.add(label)
            } else {
                // Standalone log labels are width-bound. Unbind this one before
                // placing it beside the link so the link remains visible.
                label.prefWidthProperty().unbind()
                label.prefWidth = Region.USE_COMPUTED_SIZE
                val screenshotLink = UrlLabel().apply {
                    text = "打开截图"
                    file = screenshotTarget
                    isFocusTraversable = false
                    styleClass.add("logScreenshotLink")
                    tooltip = Tooltip("打开截图文件")
                }
                val row = HBox(4.0, label, screenshotLink).apply {
                    alignment = Pos.CENTER_LEFT
                    maxWidth = Double.MAX_VALUE
                    styleClass.add("logEntry")
                }
                label.maxWidth = Double.MAX_VALUE
                HBox.setHgrow(label, Priority.ALWAYS)
                bindLogWidth(row)
                list.add(row)
            }
        }
    }

    private fun bindLogWidth(node: Region) {
        node.maxWidth = Double.MAX_VALUE
        node.prefWidthProperty().bind(logVBox.widthProperty().subtract(LOG_CONTENT_PADDING))
    }

    private fun wrapLabel(label: Label): AnchorPane {
        val anchorPane = AnchorPane()
        anchorPane.styleClass.add("hoverRootNode")
        val node = FXUtil.buildCopyNode({ copyToClipboard(label.text) })
        node.styleClass.add("hoverChildrenNode")
        anchorPane.children.add(label)
        anchorPane.children.add(node)
        AnchorPane.setLeftAnchor(label, 0.0)
        AnchorPane.setRightAnchor(label, 25.0)
        AnchorPane.setRightAnchor(node, 5.0)
        AnchorPane.setTopAnchor(node, 5.0)
        return anchorPane
    }

    private fun addListener() {
        WorkTimeStatus.addWorkTimeSettingListener { list, id ->
            reloadWorkTime(id)
        }
        WorkTimeStatus.addWorkTimeRuleSetListener { list, id ->
            reloadWorkTime(id)
        }
        downloadProgress.progressProperty().addListener { _, _, newValue ->
            val progress = newValue.toDouble()
            val downloading = progress > 0.0 && progress < 1.0
            downloadProgress.isVisible = downloading
            downloadProgress.isManaged = downloading
            downloadProgress.tooltip = Tooltip("下载进度：${String.format("%.1f", progress * 100)}%")
        }
//        日志监听
        Thread({
            while (true) {
                appendLog(ExtraLogAppender.logQueue.take())
            }
        }, "Show Log Thread").start()

        //        暂停状态监听
        PauseStatus.addChangeListener { _, _, t1: Boolean ->
            // PauseStatus is intentionally changed by worker threads.  The
            // controls belong to JavaFX's application thread; mutating the
            // ToggleGroup directly here throws an IllegalStateException and
            // can leave the process looking like it closed by itself.
            runUI {
                if (t1) {
                    pauseToggleGroup.selectToggle(pauseButton)
                } else {
                    pauseToggleGroup.selectToggle(startButton)
                }
            }
        }
        //        工作状态监听
        WorkTimeListener.addWorkStatusListener { _, _, t1: Boolean ->
            runUI {
                if (t1) {
                    accordion.expandedPane = titledPaneLog
                } else {
                    accordion.expandedPane = titledPaneControl
                }
            }
        }

        // WarEx is updated by the Power.log listener pool. Every telemetry
        // mutation must be marshalled to the JavaFX event thread so both panes
        // always show the same played-game values.
        val refreshStatistics = ChangeListener<Number> { _, _, _ ->
            runUI { refreshStatisticsViews() }
        }
        WarEx.effectiveGameCountProperty.addListener(refreshStatistics)
        WarEx.playedCountProperty.addListener(refreshStatistics)
        WarEx.playedWinCountProperty.addListener(refreshStatistics)
        WarEx.hangingTimeProperty.addListener(refreshStatistics)
        WarEx.hangingEXPProperty.addListener(refreshStatistics)
        DeckStrategyManager.deckStrategies.addListener(
            SetChangeListener { _: SetChangeListener.Change<out DeckStrategy?>? ->
                runUI { reloadRunMode() }
            } as SetChangeListener<in DeckStrategy?>,
        )
        //        是否在下载中监听
        VersionListener.downloadingReadOnlyProperty().addListener { observable, oldValue, newValue ->
            Platform.runLater {
                updateBtn.isDisable = newValue
            }
        }
        //        监听日志自动滑到底部
        logVBox
            .heightProperty()
            .addListener { observable: ObservableValue<out Number>, oldValue: Number, newValue: Number ->
                if (isNotHoverLog) {
                    logScrollPane.vvalue = logScrollPane.vmax
                }
            }
        VersionListener.canUpdateReadOnlyProperty().addListener { observable, oldValue, newValue ->
            Platform.runLater {
                flushBtn.isVisible = !newValue
                flushBtn.isManaged = !newValue
                updateBtn.isVisible = newValue
                updateBtn.isManaged = newValue
            }
        }
        val btnPressedStyleClass = "btnPressed"
        pauseToggleGroup
            .selectedToggleProperty()
            .addListener { _, toggle: Toggle?, t1: Toggle? ->
                if (t1 == null) {
                    if (toggle != null) {
                        pauseToggleGroup.selectToggle(toggle)
                    }
                } else {
                    startButton.styleClass.remove(btnPressedStyleClass)
                    pauseButton.styleClass.remove(btnPressedStyleClass)
                    if (t1 === startButton) {
                        startIco.color = "gray"
                        pauseIco.color = "black"
                        startButton.styleClass.add(btnPressedStyleClass)
                    } else if (t1 === pauseButton) {
                        pauseIco.color = "gray"
                        startIco.color = "black"
                        pauseButton.styleClass.add(btnPressedStyleClass)
                        val graphic = pauseButton.graphic as AbstractIco
                        graphic.color = "gray"
                    }
                }
            }
    }

    private fun createMenuPopup(): Popup {
        val popup = Popup()

        val label = Label("清空")
        label.onMouseClicked =
            EventHandler { event1: MouseEvent? ->
                logVBox.children.clear()
                popup.hide()
            }
        label.style = "-fx-padding: 5 10 5 10"
        label.graphic = ClearIco()
        label.styleClass.addAll("bg-hover-ui", "radius-ui")

        val vBox: VBox =
            object : VBox(label) {
                init {
                    style =
                        "-fx-effect: dropshadow(gaussian, rgba(128, 128, 128, 0.67), 10, 0, 3, 3);-fx-padding: 5 3 5 3;-fx-background-color: white"
                }
            }
        vBox.styleClass.add("radius-ui")

        popup.isAutoHide = true
        popup.content.add(vBox)
        return popup
    }

    fun reloadWorkTime(changeId: String? = null) {
        if (changeId == workTimeChangeId) return
        runUI {
            workTimePane.children.clear()
            workTimeRuleSetId.text = ""
            val workTimeRuleSet = WorkTimeStatus.nowWorkTimeRuleSet() ?: return@runUI
            val timeRules = workTimeRuleSet.getTimeRules()
            workTimeRuleSetId.text = workTimeRuleSet.getName()
            for ((ruleIndex, rule) in timeRules.withIndex()) {
                workTimePane.children.add(WorkTimeItem(rule, workTimeChangeId, workTimeRuleSet.id, ruleIndex))
            }
        }
    }

    @FXML
    protected fun flushVersion() {
        val transition = RotateTransition(Duration.millis(1200.0), flushIco)
        transition.fromAngle = 0.0
        transition.toAngle = 360.0
        transition.cycleCount = Timeline.INDEFINITE
        transition.play()
        go {
            runCatching {
                VersionListener.checkVersion()
            }
            transition.stop()
        }
    }

    @FXML
    protected fun openSettings() {
        WindowUtil.showStage(WindowEnum.SETTINGS, rootPane.scene.window)
    }

    @FXML
    protected fun updateVersion() {
        if (VersionListener.canUpdate) {
            downloadProgress.progress
            val release = VersionListener.latestRelease ?: return

            val param = DownloaderParam(0.1) { progress, totalSize, downloadedBytes ->
                runUI {
                    downloadProgress.progress = progress / 100
                }
            }
            VersionListener.asyncDownloadLatestRelease(
                false,
                param,
            ) { path: String? ->
                if (path == null) {
                    runUI {
                        WindowUtil
                            .createAlert(
                                String.format("新版本<%s>下载失败", release.tagName),
                                "",
                                rootPane.scene.window,
                            ).show()
                    }
                } else {
                    runUI {
                        WindowUtil
                            .createAlert(
                                "新版本[" + release.tagName + "]下载完毕",
                                "现在更新？",
                                { event: ActionEvent? -> VersionListener.execUpdate(path) },
                                {},
                                rootPane.scene.window,
                            ).show()
                    }
                }
            }
        }
    }

    @FXML
    protected fun resetStatistics(event: MouseEvent) {
        if (event.button != MouseButton.PRIMARY) return
        val modal =
            Modal(
                rootPane,
                null,
                "重置统计数据？",
                {
                    Platform.runLater {
                        resetStatistics()
                        refreshStatisticsViews()
                        notificationManger.showSuccess("统计数据已重置", 2)
                    }
                },
                {},
            )
        modal.isMaskClosable = true
        modal.show()
    }

    @FXML
    protected fun mouseEnteredLog() {
        isNotHoverLog = false
    }

    @FXML
    protected fun mouseExitedLog() {
        isNotHoverLog = true
    }

    @FXML
    protected fun mouseClickedLog(event: MouseEvent) {
        if (event.button == MouseButton.SECONDARY && !logVBox.children.isEmpty()) {
            val menuPopup = createMenuPopup()
            menuPopup.anchorX = event.screenX - 5
            menuPopup.anchorY = event.screenY - 5
            menuPopup.show(rootPane.scene.window)
        }
    }

    @FXML
    protected fun openVersionMsg(mouseEvent: MouseEvent?) {
        WindowUtil.showStage(WindowEnum.VERSION_MSG, rootPane.scene.window)
    }

    @FXML
    protected fun openStatistics(actionEvent: ActionEvent) {
        WindowUtil.showStage(WindowEnum.STATISTICS, rootPane.scene.window)
    }

    @FXML
    protected fun start() {
        log.info { "手动开始" }
        submitExtra {
            PauseStatus.setPauseReturn(false).isTrue {
                runUI {
                    pauseToggleGroup.selectToggle(pauseButton)
                }
            }
        }
    }

    @FXML
    protected fun pause() {
        log.info { "手动暂停" }
        submitExtra {
            PauseStatus.setPauseReturn(true).isFalse {
                runUI {
                    pauseToggleGroup.selectToggle(startButton)
                }
            }
        }
    }

    @FXML
    protected fun editWorkTime() {
        WindowUtil.showStage(WindowEnum.TIME_SETTINGS)
    }

    fun getNotificationManagerInstance(): NotificationManager<Any> = notificationManger
}

internal fun formatVersionText(version: String, channelLabel: String): String =
    "当前版本：$version · 渠道：$channelLabel"
