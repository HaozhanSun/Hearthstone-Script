package club.xiaojiawei.hsscript.controller.javafx

import club.xiaojiawei.controls.Modal
import club.xiaojiawei.controls.NotificationManager
import club.xiaojiawei.controls.ProgressModal
import club.xiaojiawei.controls.Time
import club.xiaojiawei.controls.ico.EditIco
import club.xiaojiawei.controls.ico.HelpIco
import club.xiaojiawei.hsscript.bean.WorkTime
import club.xiaojiawei.hsscript.bean.WorkTimeRule
import club.xiaojiawei.hsscript.bean.WorkTimeRuleSet
import club.xiaojiawei.hsscript.bean.tableview.NumCallback
import club.xiaojiawei.hsscript.bean.tableview.TableDragCallback
import club.xiaojiawei.hsscript.enums.*
import club.xiaojiawei.hsscript.interfaces.StageHook
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscript.status.WorkTimeStatus
import club.xiaojiawei.hsscript.utils.ConfigExUtil
import club.xiaojiawei.hsscript.utils.WorkTimeRuleBulkEdit
import club.xiaojiawei.hsscript.utils.WorkTimeRuleBulkEditRequest
import club.xiaojiawei.hsscript.utils.WorkTimeJitter
import club.xiaojiawei.hsscript.utils.go
import club.xiaojiawei.hsscript.utils.runUI
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import club.xiaojiawei.kt.dsl.StyleSize
import club.xiaojiawei.kt.dsl.button
import club.xiaojiawei.kt.dsl.comboBox
import club.xiaojiawei.kt.dsl.hbox
import club.xiaojiawei.tablecell.TextFieldTableCellUI
import javafx.beans.property.DoubleProperty
import javafx.beans.value.ChangeListener
import javafx.collections.ObservableList
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.control.cell.CheckBoxTableCell
import javafx.scene.layout.FlowPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.scene.text.Text
import javafx.util.Duration
import javafx.util.StringConverter
import java.net.URL
import java.util.*

private const val SELECTED_OPERATION_STYLE_CLASS = "label-ui-success"

/**
 * @author 肖嘉威
 * @date 2025/4/8 12:18
 */
class TimeSettingsController :
    Initializable,
    StageHook {

    @FXML
    protected lateinit var accordion: Accordion

    @FXML
    protected lateinit var applyRulePane: TitledPane

    @FXML
    protected lateinit var setRulePane: TitledPane

    @FXML
    protected lateinit var sunComboBox: ComboBox<WorkTimeRuleSet?>

    @FXML
    protected lateinit var satComboBox: ComboBox<WorkTimeRuleSet?>

    @FXML
    protected lateinit var friComboBox: ComboBox<WorkTimeRuleSet?>

    @FXML
    protected lateinit var thuComboBox: ComboBox<WorkTimeRuleSet?>

    @FXML
    protected lateinit var wedComboBox: ComboBox<WorkTimeRuleSet?>

    @FXML
    protected lateinit var tueComboBox: ComboBox<WorkTimeRuleSet?>

    @FXML
    protected lateinit var monComboBox: ComboBox<WorkTimeRuleSet?>

    @FXML
    protected lateinit var everyDayComboBox: ComboBox<WorkTimeRuleSet?>

    @FXML
    protected lateinit var progressModal: ProgressModal

    @FXML
    protected lateinit var notificationManager: NotificationManager<Any>

    @FXML
    protected lateinit var noSetCol: TableColumn<WorkTimeRuleSet, Number?>

    @FXML
    protected lateinit var nameSetCol: TableColumn<WorkTimeRuleSet, String?>

    @FXML
    protected lateinit var workTimeRuleSetTable: TableView<WorkTimeRuleSet>

    @FXML
    protected lateinit var selectedWorkTimeRuleTable: TableView<WorkTimeRule>

    @FXML
    protected lateinit var selectedTimeCol: TableColumn<WorkTimeRule, WorkTime?>

    @FXML
    protected lateinit var selectedAfterOperationCol: TableColumn<WorkTimeRule, Set<OperateEnum>?>

    @FXML
    protected lateinit var selectedDeckPosCol: TableColumn<WorkTimeRule, Set<Int>?>

    @FXML
    protected lateinit var selectedRunModeCol: TableColumn<WorkTimeRule, RunModeEnum?>

    @FXML
    protected lateinit var selectedStrategyCol: TableColumn<WorkTimeRule, String?>

    @FXML
    protected lateinit var selectedPairedStrategyCol: TableColumn<WorkTimeRule, Map<Int, String>?>

    @FXML
    protected lateinit var selectedEnableCol: TableColumn<WorkTimeRule, Boolean>

    @FXML
    protected lateinit var jitterSecondsField: TextField

    @FXML
    protected lateinit var rootPane: Pane

    private var progress: DoubleProperty? = null

    private lateinit var workTimeRuleSet: ObservableList<WorkTimeRuleSet>

    private val dateComboBoxList = mutableListOf<ComboBox<WorkTimeRuleSet?>>()

    private var isInit = false

    private var updatingJitterField = false

    private val EDIT_STYLE =
        "-fx-border-radius:10;-fx-background-color: transparent;-fx-border-width: 1;-fx-border-color:gray;-fx-background-insets: 0"

    private val runModeMap: MutableMap<RunModeEnum, MutableList<DeckStrategy>> = EnumMap(RunModeEnum::class.java)

    private val runModeListener = mutableMapOf<WorkTimeRule, ChangeListener<RunModeEnum>>()

    override fun initialize(
        p0: URL?,
        p1: ResourceBundle?,
    ) {
        progress = progressModal.show()
        workTimeRuleSet = workTimeRuleSetTable.items
        jitterSecondsField.textFormatter =
            TextFormatter<String> { change ->
                if (change.controlNewText.matches(Regex("\\d{0,5}"))) change else null
            }
        jitterSecondsField.textProperty().addListener { _, _, newValue ->
            if (updatingJitterField) return@addListener
            workTimeRuleSetTable.selectionModel.selectedItem?.let { selected ->
                selected.jitterSeconds = newValue.toIntOrNull() ?: 0
            }
        }
        jitterSecondsField.focusedProperty().addListener { _, _, focused ->
            if (!focused) commitJitterSeconds()
        }
        jitterSecondsField.setOnAction { commitJitterSeconds() }
    }

    fun reloadData() {
        loadWorkTimeRuleSet()
        updateDateComboBoxItems()
        loadWorkTimeSetting()
    }

    private fun reloadDeck() {
        runModeMap.clear()
        for (deckStrategy in DeckStrategyManager.deckStrategies) {
            for (runModeEnum in deckStrategy.runModes) {
                val strategies = runModeMap.getOrDefault(runModeEnum, ArrayList())
                strategies.add(deckStrategy)
                runModeMap[runModeEnum] = strategies
            }
        }
    }

    override fun onHidden() {
        for (entry in runModeListener) {
            entry.key.runModeProperty.removeListener(entry.value)
        }
        runModeListener.clear()
        runModeMap.clear()
        timePaneMap.clear()
    }

    override fun onShowing() {
        super.onShowing()
        if (isInit) {
            reloadData()
            reloadDeck()
            return
        }
        workTimeRuleSetTable.rowFactory = TableDragCallback<WorkTimeRuleSet, WorkTimeRuleSet>()
        selectedWorkTimeRuleTable.rowFactory =
            object : TableDragCallback<WorkTimeRule, WorkTimeRule>() {
                override fun dragged(srcIndex: Int, destIndex: Int) {
                    val workTimeRuleSet = workTimeRuleSetTable.selectionModel.selectedItem ?: return
                    workTimeRuleSet.setTimeRules(selectedWorkTimeRuleTable.items)
                    workTimeRuleSet.getTimeRules()
                }
            }
        isInit = true
        initTimeRuleSetTable()
        initSelectedTimeRuleTable()
        initApplyRulePane()

        dateComboBoxList.addAll(
            listOf(
                everyDayComboBox.apply {
                    items = workTimeRuleSetTable.items
                    valueProperty().addListener { observable, oldValue, newValue ->
                        newValue?.let {
                            isSetAllDate = true
                            for (i in 1 until dateComboBoxList.size) {
                                dateComboBoxList[i].value = it
                            }
                            isSetAllDate = false
                        }
                    }
                },
                monComboBox.apply { checkEveryDate(this) },
                tueComboBox.apply { checkEveryDate(this) },
                wedComboBox.apply { checkEveryDate(this) },
                thuComboBox.apply { checkEveryDate(this) },
                friComboBox.apply { checkEveryDate(this) },
                satComboBox.apply { checkEveryDate(this) },
                sunComboBox.apply { checkEveryDate(this) },
            ),
        )
        go {
            reloadData()
            reloadDeck()
            runUI {
                if (workTimeRuleSetTable.items.isNotEmpty()) {
                    workTimeRuleSetTable.selectionModel.selectFirst()
                }
                progressModal.hide(progress)
            }
        }
    }

    private var isSetAllDate = false

    private fun checkEveryDate(dateComboBox: ComboBox<WorkTimeRuleSet?>) {
        dateComboBox.items = workTimeRuleSetTable.items
        dateComboBox.valueProperty().addListener { _, _, newValue ->
            if (isSetAllDate) return@addListener
            val ruleTypeSet = mutableSetOf<WorkTimeRuleSet?>()
            for (i in 1 until dateComboBoxList.size) {
                ruleTypeSet.add(dateComboBoxList[i].value)
            }
            if (ruleTypeSet.size > 1) {
                everyDayComboBox.value = null
            } else if (ruleTypeSet.size == 1) {
                everyDayComboBox.value = ruleTypeSet.first()
            }
        }
    }

    private fun initApplyRulePane() {
        val converter =
            object : StringConverter<WorkTimeRuleSet?>() {
                override fun toString(p0: WorkTimeRuleSet?): String? = p0?.getName()

                override fun fromString(p0: String?): WorkTimeRuleSet? = null
            }
        everyDayComboBox.converter = converter
        monComboBox.converter = converter
        tueComboBox.converter = converter
        wedComboBox.converter = converter
        thuComboBox.converter = converter
        friComboBox.converter = converter
        satComboBox.converter = converter
        sunComboBox.converter = converter
    }

    private fun initTimeRuleSetTable() {
        workTimeRuleSetTable.isEditable = true
        workTimeRuleSetTable.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
            if (newValue == null) {
                selectedWorkTimeRuleTable.items.clear()
                updateJitterField(null)
                return@addListener
            }
            selectedWorkTimeRuleTable.items.setAll(newValue.getTimeRules())
            updateJitterField(newValue)
        }
        noSetCol.cellValueFactory = NumCallback()
        nameSetCol.isEditable = true
        nameSetCol.setCellValueFactory { cellData -> cellData.value.nameProperty() }
        nameSetCol.setCellFactory { p ->
            object : TextFieldTableCellUI<WorkTimeRuleSet?, String?>(
                object : StringConverter<String?>() {
                    override fun toString(`object`: String?): String? = `object`

                    override fun fromString(string: String?): String? = string
                },
            ) {
                override fun cancelEdit() {
                    if (workTimeRuleSetTable.items[index].id.isEmpty()) {
                        super.cancelEdit()
                        notificationManager.showInfo("不允许修改该规则", 2)
                        return
                    }
                    super.commitEdit(node.text)
                    updateDateComboBoxItems()
                }

                override fun commitEdit(p0: String?) {
                    if (workTimeRuleSetTable.items[index].id.isEmpty()) {
                        super.cancelEdit()
                        notificationManager.showInfo("不允许修改该规则", 2)
                        return
                    }
                    super.commitEdit(p0)
                    updateDateComboBoxItems()
                }
            }
        }
    }

    private fun updateDateComboBoxItems() {
        for (box in dateComboBoxList) {
            val selectedItem = box.selectionModel.selectedItem
            box.selectionModel.select(null)
            box.selectionModel.select(selectedItem)
        }
    }

    private fun loadWorkTimeRuleSet() {
        val workTimeRuleSet = WorkTimeStatus.readOnlyWorkTimeRuleSet().get()
        val selectedItem = workTimeRuleSetTable.selectionModel.selectedItem
        // The table is rebuilt from the persisted model.  Drop cell graphics
        // from the previous model so controls cannot display stale values.
        timePaneMap.clear()
        this.workTimeRuleSet.setAll(workTimeRuleSet)
        workTimeRuleSetTable.selectionModel.select(selectedItem)
        workTimeRuleSetTable.refresh()
        updateJitterField(workTimeRuleSetTable.selectionModel.selectedItem)
    }

    private fun updateJitterField(ruleSet: WorkTimeRuleSet?) {
        updatingJitterField = true
        try {
            jitterSecondsField.text = ruleSet?.jitterSeconds?.toString() ?: ""
            jitterSecondsField.isDisable = ruleSet == null
        } finally {
            updatingJitterField = false
        }
    }

    private fun commitJitterSeconds() {
        val selected = workTimeRuleSetTable.selectionModel.selectedItem ?: return
        val normalized = WorkTimeJitter.normalizeSeconds(jitterSecondsField.text.toIntOrNull() ?: 0)
        selected.jitterSeconds = normalized
        updateJitterField(selected)
    }

    private fun loadWorkTimeSetting() {
        val workTimeSetting = ConfigExUtil.getWorkTimeSetting()
        for ((i, id) in workTimeSetting.withIndex()) {
            dateComboBoxList[i + 1].selectionModel.select(workTimeRuleSet.find { it.id == id })
        }
    }

    private fun initSelectedTimeRuleTable() {
        selectedWorkTimeRuleTable.isEditable = true

        selectedTimeCol.setCellValueFactory { cellData -> cellData.value.workTimeProperty }
        selectedTimeCol.setCellFactory { p ->
            ColTableCell { index -> buildTimePane(selectedWorkTimeRuleTable.items[index]) }
        }

        selectedAfterOperationCol.setCellValueFactory { cellData -> cellData.value.operatesProperty }
        selectedAfterOperationCol.setCellFactory { p ->
            ColTableCell { index -> buildOperationPane(selectedWorkTimeRuleTable.items[index]) }
        }

        selectedRunModeCol.setCellValueFactory { cellData -> cellData.value.runModeProperty }
        selectedRunModeCol.setCellFactory { p ->
            ColTableCell { index -> buildRunModePane(selectedWorkTimeRuleTable.items[index]) }
        }

        selectedStrategyCol.setCellValueFactory { cellData -> cellData.value.strategyIdProperty }
        selectedStrategyCol.setCellFactory { p ->
            ColTableCell { index -> buildStrategyPane(selectedWorkTimeRuleTable.items[index]) }
        }

        selectedDeckPosCol.setCellValueFactory { cellData -> cellData.value.deckPosProperty }
        selectedDeckPosCol.setCellFactory { p ->
            ColTableCell { index -> buildDeckPosPane(selectedWorkTimeRuleTable.items[index]) }
        }

        selectedPairedStrategyCol.setCellValueFactory { cellData -> cellData.value.pairedStrategyIdsProperty }
        selectedPairedStrategyCol.setCellFactory { p ->
            ColTableCell { index -> buildPairedStrategyPane(selectedWorkTimeRuleTable.items[index]) }
        }

        selectedEnableCol.setCellValueFactory { cellData -> cellData.value.enableProperty }
        selectedEnableCol.setCellFactory { p ->
            object : CheckBoxTableCell<WorkTimeRule, Boolean>() {
                override fun updateItem(
                    item: Boolean?,
                    empty: Boolean,
                ) {
                    super.updateItem(item, empty)
                    graphic?.let {
                        val styleClass = "check-box-ui"
                        if (!it.styleClass.contains(styleClass)) {
                            it.styleClass.add(styleClass)
                            it.styleClass.add("check-box-ui-main")
                        }
                    }
                }
            }
        }
    }

    private class ColTableCell<T, S>(
        var buildGraphic: (Int) -> Node,
    ) : TableCell<T, S>() {
        override fun updateItem(
            item: S?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            if (item == null || empty) {
                graphic = null
                return
            }
            graphic = this.buildGraphic(index)
        }
    }

    private var timePaneMap = mutableMapOf<WorkTimeRule, HBox>()

    private fun buildTimePane(item: WorkTimeRule): HBox {
        timePaneMap[item]?.let { return it }
        val startTime = Time()
        val endTime = Time()
        val pane =
            HBox(
                startTime.apply {
                    localTime = item.workTime.parseStartTime()
                    readOnlyTimeProperty().addListener { _, _, newValue ->
                        newValue ?: return@addListener
                        item.workTime.startTime = WorkTime.pattern.format(newValue)
                    }
                },
                Text("-"),
                endTime.apply {
                    localTime = item.workTime.parseEndTime()
                    readOnlyTimeProperty().addListener { _, _, newValue ->
                        newValue ?: return@addListener
                        item.workTime.endTime = WorkTime.pattern.format(newValue)
                    }
                },
            ).apply {
                padding = Insets(1.0)
                alignment = Pos.CENTER
                spacing = 3.0
            }
        timePaneMap[item] = pane
        return pane
    }

    private fun buildRunModePane(item: WorkTimeRule): Node {
        return comboBox(runModeMap.keys.toList()) {
            styleNormal(StyleSize.SMALL)
            value(item.runMode)
            converter {
                it?.comment
            }
            addValueListener { _, _, newValue ->
                item.runMode = newValue
            }
        }
    }

    private fun buildStrategyPane(item: WorkTimeRule): Node {
        val comboBox = comboBox() {
            runModeMap[item.runMode]?.let { strategies ->
                items(strategies)
                value(strategies.find { it.id() == item.strategyId })
            }
            styleNormal(StyleSize.SMALL)
            converter {
                it?.name()
            }
            addValueListener { _, _, newValue ->
                item.strategyId = newValue?.id() ?: ""
            }
        }

        val listener = ChangeListener<RunModeEnum> { _, _, newValue ->
            runModeMap[item.runMode]?.let { strategies ->
                comboBox.items.setAll(strategies)
                comboBox.value = strategies.find { it.id() == item.strategyId }
            }
        }
        runModeListener[item]?.let {
            item.runModeProperty.removeListener(it)
        }
        runModeListener[item] = listener
        item.runModeProperty.addListener(listener)
        return comboBox
    }

    private fun buildDeckPosPane(item: WorkTimeRule): Pane {
        return hbox {
            alignCenter()
            spacing(5.0)
            addText(item.deckPos.sorted().joinToString(","))
            addLabel {
                graphic(button {
                    style(EDIT_STYLE)
                    cursor(Cursor.HAND)
                    graphic(EditIco("main-color"))
                    onAction {
                        handleEditDeckPos(item)
                    }
                })
            }
        }
    }

    private fun handleEditDeckPos(item: WorkTimeRule) {
        val deckPosCopy = item.deckPos.toMutableSet()
        Modal(
            rootPane,
            "设置卡组位", hbox {
                spacing(10.0)
                for (i in 1..9) {
                    addCheckBox("$i") {
                        style("-fx-text-fill: black;")
                        styleMain()
                        selected(item.deckPos.contains(i))
                        addSelectedListener { _, _, newValue ->
                            if (newValue) {
                                deckPosCopy += i
                            } else {
                                deckPosCopy -= i
                            }
                        }
                    }
                }
            }, {
                item.deckPos = deckPosCopy
                item.pairedStrategyIds = item.pairedStrategyIds.filterKeys { it in deckPosCopy }
            }
        ).show()
    }

    private fun buildPairedStrategyPane(item: WorkTimeRule): Pane {
        val strategyById = DeckStrategyManager.deckStrategies.associateBy { it.id() }
        val text = item.deckPos.sorted().map { deckSlot ->
            val strategyId = item.pairedStrategyIds[deckSlot]?.takeIf { it.isNotBlank() }
            val strategyName = strategyId?.let { strategyById[it]?.name() ?: "缺失:$it" } ?: "默认策略"
            "$deckSlot:$strategyName"
        }.joinToString(",").ifBlank { "默认策略" }
        return hbox {
            alignCenter()
            spacing(5.0)
            addText(text)
            addLabel {
                graphic(button {
                    style(EDIT_STYLE)
                    cursor(Cursor.HAND)
                    graphic(EditIco("main-color"))
                    onAction { handleEditPairedStrategies(item) }
                })
            }
        }
    }

    private fun handleEditPairedStrategies(item: WorkTimeRule) {
        val deckSlots = item.deckPos.sorted()
        if (deckSlots.isEmpty()) {
            notificationManager.showInfo("请先设置卡组位", 2)
            return
        }
        val strategies = runModeMap[item.runMode].orEmpty().sortedBy { it.name() }
        val comboBoxes = deckSlots.associateWith { deckSlot ->
            ComboBox<DeckStrategy?>().apply {
                styleClass.addAll("combo-box-ui", "combo-box-ui-small", "combo-box-ui-normal")
                items.add(null)
                items.addAll(strategies)
                value = strategies.find { it.id() == item.pairedStrategyIds[deckSlot] }
                converter = object : StringConverter<DeckStrategy?>() {
                    override fun toString(strategy: DeckStrategy?): String = strategy?.name() ?: "默认策略"
                    override fun fromString(string: String?): DeckStrategy? = null
                }
            }
        }
        val content = VBox(8.0).apply {
            padding = Insets(8.0)
            comboBoxes.forEach { (deckSlot, comboBox) ->
                children.add(HBox(8.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.add(Label("卡组位$deckSlot"))
                    children.add(comboBox)
                })
            }
        }
        Modal(rootPane, "设置配套策略", content, {
            item.pairedStrategyIds = comboBoxes.mapNotNull { (deckSlot, comboBox) ->
                comboBox.value?.id()?.let { deckSlot to it }
            }.toMap()
            selectedWorkTimeRuleTable.refresh()
        }, {}).show()
    }

    @FXML
    protected fun bulkEditRules(actionEvent: ActionEvent) {
        val workTimeRuleSet = workTimeRuleSetTable.selectionModel.selectedItem ?: return
        if (workTimeRuleSet.id.isEmpty()) {
            notificationManager.showInfo("不允许修改该规则", 2)
            return
        }
        val rules = selectedWorkTimeRuleTable.items
        if (rules.isEmpty()) {
            notificationManager.showInfo("当前预设没有时间段", 2)
            return
        }

        val modeCheckBox = CheckBox("模式").apply { styleClass.addAll("check-box-ui", "check-box-ui-main") }
        val modeComboBox =
            ComboBox<RunModeEnum>().apply {
                styleClass.addAll("combo-box-ui", "combo-box-ui-normal")
                items.setAll(runModeMap.keys.sortedBy { it.ordinal })
                value = rules.firstOrNull()?.runMode ?: RunModeEnum.STANDARD
                converter =
                    object : StringConverter<RunModeEnum?>() {
                        override fun toString(runModeEnum: RunModeEnum?): String = runModeEnum?.comment ?: ""

                        override fun fromString(string: String?): RunModeEnum? = null
                    }
                isDisable = true
            }
        val strategyCheckBox = CheckBox("策略").apply { styleClass.addAll("check-box-ui", "check-box-ui-main") }
        val strategyComboBox =
            ComboBox<DeckStrategy>().apply {
                styleClass.addAll("combo-box-ui", "combo-box-ui-normal")
                converter =
                    object : StringConverter<DeckStrategy?>() {
                        override fun toString(strategy: DeckStrategy?): String = strategy?.name() ?: ""

                        override fun fromString(string: String?): DeckStrategy? = null
                    }
                isDisable = true
            }
        fun updateStrategyItems(runMode: RunModeEnum?) {
            val strategies =
                if (modeCheckBox.isSelected && runMode != null) {
                    runModeMap[runMode].orEmpty()
                } else {
                    DeckStrategyManager.deckStrategies
                }
            val selected = strategyComboBox.value
            strategyComboBox.items.setAll(strategies)
            strategyComboBox.value = selected?.takeIf { strategies.any { strategy -> strategy.id() == it.id() } }
        }
        modeCheckBox.selectedProperty().addListener { _, _, selected ->
            modeComboBox.isDisable = !selected
            updateStrategyItems(modeComboBox.value)
        }
        strategyCheckBox.selectedProperty().addListener { _, _, selected ->
            strategyComboBox.isDisable = !selected
            if (selected) updateStrategyItems(modeComboBox.value)
        }
        modeComboBox.valueProperty().addListener { _, _, runMode -> updateStrategyItems(runMode) }
        updateStrategyItems(modeComboBox.value)

        val deckPosCheckBox = CheckBox("卡组位").apply { styleClass.addAll("check-box-ui", "check-box-ui-main") }
        val deckPosBoxes =
            (1..9).map { deckPos ->
                CheckBox(deckPos.toString()).apply {
                    styleClass.addAll("check-box-ui", "check-box-ui-main")
                    isSelected = rules.firstOrNull()?.deckPos?.contains(deckPos) == true
                    isDisable = true
                }
            }
        deckPosCheckBox.selectedProperty().addListener { _, _, selected ->
            deckPosBoxes.forEach { it.isDisable = !selected }
        }

        val enableCheckBox = CheckBox("启用").apply { styleClass.addAll("check-box-ui", "check-box-ui-main") }
        val enableValueCheckBox =
            CheckBox("设为启用").apply {
                styleClass.addAll("check-box-ui", "check-box-ui-main")
                isSelected = true
                isDisable = true
            }
        enableCheckBox.selectedProperty().addListener { _, _, selected -> enableValueCheckBox.isDisable = !selected }

        val content =
            GridPane().apply {
                hgap = 12.0
                vgap = 10.0
                padding = Insets(8.0)
                add(modeCheckBox, 0, 0)
                add(modeComboBox, 1, 0)
                add(strategyCheckBox, 0, 1)
                add(strategyComboBox, 1, 1)
                add(deckPosCheckBox, 0, 2)
                add(
                    HBox(8.0).apply {
                        children.addAll(deckPosBoxes)
                        alignment = Pos.CENTER_LEFT
                    },
                    1,
                    2,
                )
                add(enableCheckBox, 0, 3)
                add(enableValueCheckBox, 1, 3)
            }

        Modal(rootPane, "批量编辑时间段", content, {
            val request = buildBulkEditRequest(
                modeCheckBox,
                modeComboBox,
                strategyCheckBox,
                strategyComboBox,
                deckPosCheckBox,
                deckPosBoxes,
                enableCheckBox,
                enableValueCheckBox,
            ) ?: return@Modal
            val validation = WorkTimeRuleBulkEdit.validate(rules, request)
            when {
                validation.missingSelectedFields -> {
                    notificationManager.showInfo("请选择要批量修改的字段", 2)
                    return@Modal
                }

                validation.unconfiguredRows.isNotEmpty() -> {
                    notificationManager.showError(
                        "存在未配置时间的行：${validation.unconfiguredRows.joinToString(",")}",
                        3,
                    )
                    return@Modal
                }

                validation.incompatibleStrategyRows.isNotEmpty() -> {
                    notificationManager.showError(
                        "策略与第${validation.incompatibleStrategyRows.joinToString(",")}行模式不兼容",
                        3,
                    )
                    return@Modal
                }
            }

            val result = WorkTimeRuleBulkEdit.apply(rules, request)
            workTimeRuleSet.setTimeRules(rules.toList())
            selectedWorkTimeRuleTable.refresh()
            workTimeRuleSetTable.refresh()
            // Reuse the controller's complete schedule persistence path so the
            // bulk edit updates both the stored rules and the active preset
            // mapping without relying on a removed legacy helper.
            save()
            notificationManager.showSuccess("已批量更新${result.updatedCount}行", 2)
        }, {}).show()
    }

    private fun buildBulkEditRequest(
        modeCheckBox: CheckBox,
        modeComboBox: ComboBox<RunModeEnum>,
        strategyCheckBox: CheckBox,
        strategyComboBox: ComboBox<DeckStrategy>,
        deckPosCheckBox: CheckBox,
        deckPosBoxes: List<CheckBox>,
        enableCheckBox: CheckBox,
        enableValueCheckBox: CheckBox,
    ): WorkTimeRuleBulkEditRequest? {
        val runMode =
            if (modeCheckBox.isSelected) {
                modeComboBox.value ?: run {
                    notificationManager.showInfo("请选择模式", 2)
                    return null
                }
            } else {
                null
            }
        val strategy =
            if (strategyCheckBox.isSelected) {
                strategyComboBox.value ?: run {
                    notificationManager.showInfo("请选择策略", 2)
                    return null
                }
            } else {
                null
            }
        val deckPos =
            if (deckPosCheckBox.isSelected) {
                deckPosBoxes.filter { it.isSelected }.map { it.text.toInt() }.toSet().also {
                    if (it.isEmpty()) {
                        notificationManager.showInfo("请至少选择一个卡组位", 2)
                        return null
                    }
                }
            } else {
                null
            }
        return WorkTimeRuleBulkEditRequest(
            runMode = runMode,
            strategyId = strategy?.id(),
            strategyAllowedRunModes = strategy?.runModes?.toSet(),
            deckPos = deckPos,
            enable = if (enableCheckBox.isSelected) enableValueCheckBox.isSelected else null,
        )
    }

    private fun buildOperationPane(item: WorkTimeRule): Pane {
        val pane =
            HBox().apply {
                children.addAll(
                    item
                        .operates
                        .toMutableList()
                        .apply { sortBy { it.order } }
                        .map {
                            Label(it.value).apply {
                                styleClass.addAll(
                                    "label-ui",
                                    "label-ui-small",
                                    SELECTED_OPERATION_STYLE_CLASS,
                                    "radius-ui",
                                )
                                if (it === OperateEnum.SLEEP_SYSTEM || it === OperateEnum.LOCK_SCREEN) {
                                    contentDisplay = ContentDisplay.RIGHT
                                    graphic =
                                        Label().apply graphic@{
                                            this@graphic.graphic = HelpIco()
                                            this@graphic.tooltip =
                                                Tooltip(
                                                    if (it ===
                                                        OperateEnum.SLEEP_SYSTEM
                                                    ) {
                                                        "在Windows设置中关闭唤醒电脑需要重新登录选项"
                                                    } else {
                                                        "锁屏后无法自动解锁"
                                                    },
                                                ).apply tooltip@{
                                                    showDuration = Duration.seconds(60.0)
                                                }
                                        }
                                }
                            }
                        }.toList(),
                )
                children.add(buildOperationEditBtn(item))
                alignment = Pos.CENTER
                spacing = 5.0
            }
        return pane
    }

    private fun buildOperationEditBtn(item: WorkTimeRule): Button =
        Button().apply {
            graphic = EditIco("main-color")
            style = EDIT_STYLE
            cursor = Cursor.HAND

            val operates = item.operates
            val selectedOperateEnums = mutableSetOf<OperateEnum>()

            onAction =
                EventHandler<ActionEvent> {
                    val content =
                        FlowPane().apply {
                            val selectAllLabel = Label("全选")
                            val isSelectAll: () -> Unit = {
                                if (selectedOperateEnums.size == OperateEnum.entries.size) {
                                    if (!selectAllLabel.styleClass.contains(SELECTED_OPERATION_STYLE_CLASS)) {
                                        selectAllLabel.styleClass.add(SELECTED_OPERATION_STYLE_CLASS)
                                    }
                                } else {
                                    selectAllLabel.styleClass.remove(SELECTED_OPERATION_STYLE_CLASS)
                                }
                            }
                            children.addAll(
                                OperateEnum.entries
                                    .toMutableList()
                                    .apply { sortBy { it.order } }
                                    .map { operation ->
                                        Label(operation.value).apply label@{
                                            styleClass.addAll("label-ui", "label-ui-small", "radius-ui")
                                            if (operates.contains(operation)) {
                                                selectedOperateEnums.add(operation)
                                                styleClass.add(SELECTED_OPERATION_STYLE_CLASS)
                                            }
                                            (this@label).onMouseClicked =
                                                EventHandler { e ->
                                                    if (styleClass.contains(SELECTED_OPERATION_STYLE_CLASS)) {
                                                        selectedOperateEnums.remove(operation)
                                                        styleClass.remove(SELECTED_OPERATION_STYLE_CLASS)
                                                    } else {
                                                        selectedOperateEnums.add(operation)
                                                        styleClass.add(SELECTED_OPERATION_STYLE_CLASS)
                                                    }
                                                    isSelectAll()
                                                }
                                        }
                                    },
                            )
                            children.add(
                                selectAllLabel.apply label@{
                                    styleClass.addAll("label-ui", "label-ui-small", "radius-ui")
                                    isSelectAll()
                                    (this@label).onMouseClicked =
                                        EventHandler { e ->
                                            if (styleClass.contains(SELECTED_OPERATION_STYLE_CLASS)) {
                                                selectedOperateEnums.clear()
                                                for (node in children) {
                                                    node.styleClass.remove(SELECTED_OPERATION_STYLE_CLASS)
                                                }
                                            } else {
                                                selectedOperateEnums.addAll(OperateEnum.values())
                                                for (node in children) {
                                                    if (!node.styleClass.contains(SELECTED_OPERATION_STYLE_CLASS)) {
                                                        node.styleClass.add(SELECTED_OPERATION_STYLE_CLASS)
                                                    }
                                                }
                                            }
                                        }
                                },
                            )
                            cursor = Cursor.HAND
                            hgap = 5.0
                            vgap = 5.0
                        }
                    Modal(rootPane, "修改", content, {
                        item.operates = selectedOperateEnums
                    }, {})
                        .apply {
                            isMaskClosable = true
                        }.show()
                }
        }

    private fun selectedWorkTimeSetting(): List<String> {
        val list = dateComboBoxList.toMutableList().apply { removeFirst() }
        return list.map { it.value?.id ?: "" }
    }

    @FXML
    protected fun save() {
        commitJitterSeconds()
        // Capture the weekday ids before replacing the shared preset model.
        // Replacing that model fires selection-normalization listeners, so
        // reading the ComboBoxes after storeWorkTimeRuleSet can capture a
        // stale/default preset even though the save itself succeeds.
        val workTimeSetting = WorkTimeStatus.resolveSavedWorkTimeSetting(
            currentSetting = selectedWorkTimeSetting(),
            selectedRuleSetId = workTimeRuleSetTable.selectionModel.selectedItem?.id.orEmpty(),
            applyRulePaneExpanded = applyRulePane.isExpanded,
        )
        WorkTimeStatus.storeWorkTimeSchedule(
            workTimeRuleSetTable.items.toList(),
            workTimeSetting,
        )
        updateDateComboBoxItems()
        notificationManager.showSuccess("保存成功", 2)
    }

    @FXML
    protected fun addRulerSet(actionEvent: ActionEvent) {
        workTimeRuleSetTable.items.add(
            WorkTimeRuleSet("预设${workTimeRuleSetTable.items.size + 1}"),
        )
        updateDateComboBoxItems()
    }

    @FXML
    protected fun delRulerSet(actionEvent: ActionEvent) {
        val index = workTimeRuleSetTable.selectionModel.selectedIndex
        if (index == -1) return
        if (workTimeRuleSetTable.selectionModel.selectedItem.id
                .isEmpty()
        ) {
            notificationManager.showInfo("不允许删除该规则", 2)
            return
        }
        workTimeRuleSetTable.items.removeAt(index)
        updateDateComboBoxItems()
    }

    @FXML
    protected fun copyRulerSet(actionEvent: ActionEvent) {
        val selectedItem = workTimeRuleSetTable.selectionModel.selectedItem ?: return
        val newItem = selectedItem.clone()
        newItem.reGenerateId()
        newItem.setName("副本${workTimeRuleSetTable.items.size + 1}")
        workTimeRuleSetTable.items.add(newItem)
        workTimeRuleSetTable.selectionModel.selectLast()
    }

    @FXML
    protected fun addRuler(actionEvent: ActionEvent) {
        val workTimeRuleSet = workTimeRuleSetTable.selectionModel.selectedItem ?: return
        if (workTimeRuleSet.id.isEmpty()) {
            notificationManager.showInfo("不允许修改该规则", 2)
            return
        }
        val workTimeRule =
            WorkTimeRule(
                DEFAULT_WORK_TIME.clone(),
                DEFAULT_OPERATIONS.toSet(),
                DEFAULT_RUN_MODE_ENUM,
                DEFAULT_DECK_STRATEGY_ID,
                DEFAULT_DECK_POS.toSet(),
                true,
            )
        workTimeRuleSet.setTimeRules(workTimeRuleSet.getTimeRules() + workTimeRule)
        selectedWorkTimeRuleTable.items.add(workTimeRule)
        selectedWorkTimeRuleTable.selectionModel.selectLast()
    }

    @FXML
    protected fun delRuler(actionEvent: ActionEvent) {
        val workTimeRule = selectedWorkTimeRuleTable.selectionModel.selectedItem ?: return
        val workTimeRuleSet = workTimeRuleSetTable.selectionModel.selectedItem ?: return
        workTimeRuleSet.setTimeRules(
            workTimeRuleSet.getTimeRules().toMutableList().apply {
                remove(workTimeRule)
            },
        )
        selectedWorkTimeRuleTable.items.remove(workTimeRule)
    }

    @FXML
    protected fun copyRuler(actionEvent: ActionEvent) {
        val selectedItem = selectedWorkTimeRuleTable.selectionModel.selectedItem ?: return
        val selectedRuleSeItem = workTimeRuleSetTable.selectionModel.selectedItem ?: return
        val newItem = selectedItem.clone()
        selectedRuleSeItem.setTimeRules(selectedRuleSeItem.getTimeRules() + newItem)
        selectedWorkTimeRuleTable.items.add(newItem)
        selectedWorkTimeRuleTable.selectionModel.selectLast()
    }
}
