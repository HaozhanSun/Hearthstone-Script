package club.xiaojiawei.hsscript.component

import club.xiaojiawei.controls.Time
import club.xiaojiawei.hsscript.bean.WorkTime
import club.xiaojiawei.hsscript.bean.WorkTimeRule
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscript.status.RuntimeSelectionUiContract
import club.xiaojiawei.hsscript.status.WorkTimeStatus
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.control.CheckBox
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox

/**
 * @author 肖嘉威
 * @date 2025/4/10 13:03
 */
class WorkTimeItem(
    val workTimeRule: WorkTimeRule,
    val changeId: String,
    private val ruleSetId: String? = null,
    private val ruleIndex: Int? = null,
) : HBox() {

    @FXML
    protected lateinit var startTime: Time

    @FXML
    protected lateinit var endTime: Time

    @FXML
    protected lateinit var enableCheckBox: CheckBox
    private var runtimeTooltip: Tooltip? = null

    init {
        val fxmlLoader = FXMLLoader(javaClass.getResource("/fxml/component/WorkTimeItem.fxml"))
        fxmlLoader.setRoot(this)
        fxmlLoader.setController(this)
        fxmlLoader.load<Any>()
        afterLoaded()
    }

    private fun afterLoaded() {
        startTime.time = workTimeRule.workTime.startTime
        endTime.time = workTimeRule.workTime.endTime
        enableCheckBox.isSelected = workTimeRule.enable
        updateRuntimeHighlight()
        DeckStrategyManager.runtimeSelectionSnapshotProperty.addListener { _, _, _ -> updateRuntimeHighlight() }

        startTime.readOnlyTimeProperty().addListener { observable, oldValue, newValue ->
            newValue ?: return@addListener
            workTimeRule.workTime = WorkTime(WorkTime.pattern.format(newValue), workTimeRule.workTime.endTime)
            WorkTimeStatus.storeWorkTimeRuleSet(changeId = changeId)

        }

        endTime.readOnlyTimeProperty().addListener { observable, oldValue, newValue ->
            newValue ?: return@addListener
            workTimeRule.workTime = WorkTime(workTimeRule.workTime.startTime, WorkTime.pattern.format(newValue))
            WorkTimeStatus.storeWorkTimeRuleSet(changeId = changeId)
        }

        enableCheckBox.selectedProperty().addListener { observable, oldValue, newValue ->
            workTimeRule.enable = newValue
            WorkTimeStatus.storeWorkTimeRuleSet(changeId = changeId)
        }
    }

    private fun updateRuntimeHighlight() {
        val snapshot = DeckStrategyManager.currentRuntimeSelectionSnapshot()
        styleClass.remove(RuntimeSelectionUiContract.ACTIVE_WORK_TIME_STYLE)
        styleClass.remove(RuntimeSelectionUiContract.SWITCHING_WORK_TIME_STYLE)
        RuntimeSelectionUiContract.workTimeStyleFor(snapshot, ruleSetId, ruleIndex)?.let { style ->
            styleClass.add(style)
            runtimeTooltip?.let { Tooltip.uninstall(this, it) }
            runtimeTooltip = Tooltip(RuntimeSelectionUiContract.statusText(snapshot)).also { Tooltip.install(this, it) }
        } ?: run {
            runtimeTooltip?.let { Tooltip.uninstall(this, it) }
            runtimeTooltip = null
        }
    }
}
