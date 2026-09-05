package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.WorkTimeRuleSet
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.utils.ConfigExUtil
import javafx.beans.property.ReadOnlyListProperty
import javafx.beans.property.ReadOnlyListWrapper
import javafx.collections.FXCollections
import java.time.LocalDate

/**
 * @author 肖嘉威
 * @date 2025/4/10 13:11
 */
object WorkTimeStatus {
    private val workTimeSettingListeners = mutableListOf<(List<String>, String?) -> Unit>()

    private val workTimeRuleSetListeners = mutableListOf<(List<WorkTimeRuleSet>, String?) -> Unit>()

    private val workTimeSetting by lazy {
        ReadOnlyListWrapper<String>(FXCollections.observableArrayList<String>(ConfigExUtil.getWorkTimeSetting()))
    }

    private val workTimeRuleSet by lazy {
        ReadOnlyListWrapper<WorkTimeRuleSet>(FXCollections.observableArrayList<WorkTimeRuleSet>(ConfigExUtil.getWorkTimeRuleSet()))
    }

    fun readOnlyWorkTimeSetting(): ReadOnlyListProperty<String> = workTimeSetting.readOnlyProperty

    fun readOnlyWorkTimeRuleSet(): ReadOnlyListProperty<WorkTimeRuleSet> = workTimeRuleSet.readOnlyProperty

    fun nowWorkTimeRuleSet(): WorkTimeRuleSet? = resolveWorkTimeRuleSet(
        workTimeRuleSet.toList(),
        workTimeSetting.toList(),
        LocalDate.now().dayOfWeek.value - 1,
    )

    /**
     * Resolve the configured preset by its persisted id.  Keeping this lookup
     * explicit prevents callers from accidentally treating the first preset
     * as the active one when the weekday mapping points at another preset.
     */
    fun resolveWorkTimeRuleSet(
        ruleSets: List<WorkTimeRuleSet>,
        setting: List<String>,
        dayIndex: Int,
    ): WorkTimeRuleSet? {
        val selectedId = setting.getOrNull(dayIndex).orEmpty()
        if (selectedId.isEmpty()) return null
        return ruleSets.find { it.id == selectedId }
    }

    fun addWorkTimeSettingListener(listener: (List<String>, String?) -> Unit) {
        workTimeSettingListeners.add(listener)
    }

    fun removeWorkTimeSettingListener(listener: (List<String>, String?) -> Unit) {
        workTimeSettingListeners.remove(listener)
    }

    fun addWorkTimeRuleSetListener(listener: (List<WorkTimeRuleSet>, String?) -> Unit) {
        workTimeRuleSetListeners.add(listener)
    }

    fun removeWorkTimeRuleSetListener(listener: (List<WorkTimeRuleSet>, String?) -> Unit) {
        workTimeRuleSetListeners.remove(listener)
    }

    fun storeWorkTimeSetting(
        workTimeSettingList: List<String> = workTimeSetting,
        changeId: String? = null,
    ) {
        ConfigExUtil.storeWorkTimeSetting(workTimeSettingList)
        if (workTimeSettingList !== workTimeSetting) {
            workTimeSetting.setAll(workTimeSettingList)
        }
        workTimeSettingListeners.toList().forEach { listener ->
            listener.invoke(workTimeSetting, changeId)
        }
        WorkTimeListener.checkWork()
        WorkTimeListener.tryWork()
    }

    fun storeWorkTimeRuleSet(
        workTimeRuleSetList: List<WorkTimeRuleSet> = workTimeRuleSet,
        changeId: String? = null,
    ) {
        ConfigExUtil.storeWorkTimeRuleSet(workTimeRuleSetList)
        if (workTimeRuleSetList !== workTimeRuleSet) {
            workTimeRuleSet.setAll(workTimeRuleSetList)
        }
        workTimeRuleSetListeners.toList().forEach { listener ->
            listener.invoke(workTimeRuleSet, changeId)
        }
        WorkTimeListener.checkWork()
        WorkTimeListener.tryWork()
    }

    /**
     * Persist a complete schedule in one state transition.  The UI must read
     * the weekday ids before replacing the preset list; otherwise the
     * replacement can fire ComboBox selection listeners and the subsequent
     * mapping read may capture a stale/default preset.
     */
    fun storeWorkTimeSchedule(
        workTimeRuleSetList: List<WorkTimeRuleSet>,
        workTimeSettingList: List<String>,
        changeId: String? = null,
    ) {
        ConfigExUtil.storeWorkTimeRuleSet(workTimeRuleSetList)
        ConfigExUtil.storeWorkTimeSetting(workTimeSettingList)
        workTimeRuleSet.setAll(workTimeRuleSetList)
        workTimeSetting.setAll(workTimeSettingList)

        workTimeRuleSetListeners.toList().forEach { listener ->
            listener.invoke(workTimeRuleSet, changeId)
        }
        workTimeSettingListeners.toList().forEach { listener ->
            listener.invoke(workTimeSetting, changeId)
        }
        WorkTimeListener.checkWork()
        WorkTimeListener.tryWork()
    }
}
