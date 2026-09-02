package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.PluginManager.DECK_STRATEGY_PLUGINS
import club.xiaojiawei.hsscript.status.PluginManager.loadDeckProperty
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptpluginsdk.bean.PluginWrapper
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ObservableSet
import java.util.stream.Stream

/**
 * @author 肖嘉威
 * @date 2024/9/7 15:17
 */
object DeckStrategyManager {

    /**
     * 当前卡组策略
     */
    val currentDeckStrategyProperty: ObjectProperty<DeckStrategy?> = SimpleObjectProperty()
    val currentRunModeProperty: ObjectProperty<RunModeEnum?> = SimpleObjectProperty()

    var currentDeckStrategy
        set(value) = currentDeckStrategyProperty.set(value)
        get():DeckStrategy? {
            return effectiveSelection(
                highPrioritySchedule = ConfigUtil.getBoolean(ConfigEnum.WORK_TIME_RULE_HIGH_PRIORITY),
                ordinaryScheduleActive = WorkTimeListener.isInsideConfiguredSchedule(),
                scheduleSelection = WorkTimeListener.closestWorkTimeRule?.strategyId?.let { strategyId ->
                    deckStrategies.find { it.id() == strategyId }
                },
                userSelection = currentDeckStrategyProperty.get(),
            )
        }

    var currentRunMode
        set(value) = currentRunModeProperty.set(value)
        get():RunModeEnum? {
            return effectiveSelection(
                highPrioritySchedule = ConfigUtil.getBoolean(ConfigEnum.WORK_TIME_RULE_HIGH_PRIORITY),
                ordinaryScheduleActive = WorkTimeListener.isInsideConfiguredSchedule(),
                scheduleSelection = WorkTimeListener.closestWorkTimeRule?.runMode,
                userSelection = currentRunModeProperty.get(),
            )
        }

    /**
     * 所有卡组策略
     */
    val deckStrategies: ObservableSet<DeckStrategy> = FXCollections.observableSet()

    /** Start a new game using the strategy selected by the user. */
    @Synchronized
    fun beginGame() {
        log.info { "当前对局使用已选策略；投降规则由对手英雄与当前排位决定" }
    }

    init {
        currentDeckStrategyProperty.addListener { _: ObservableValue<out DeckStrategy?>?, _: DeckStrategy?, newStrategy: DeckStrategy? ->
            if (newStrategy == null) {
                ConfigUtil.putString(ConfigEnum.DEFAULT_DECK_STRATEGY, "")
            } else if (ConfigUtil.getString(ConfigEnum.DEFAULT_DECK_STRATEGY) != newStrategy.id()
            ) {
                ConfigUtil.putString(ConfigEnum.DEFAULT_DECK_STRATEGY, newStrategy.id())
                val text = "挂机策略改为: ${newStrategy.name()}，模式: ${currentRunMode?.comment}"
                SystemUtil.notice(text)
                log.info { text }
                if (newStrategy.deckCode().isNotBlank()) {
                    log.info { "$" + newStrategy.deckCode() }
                }
            }
        }

        loadDeckProperty().addListener { _: ObservableValue<out Boolean>?, _: Boolean?, t1: Boolean ->
            if (t1) {
                reload()
            }
        }
    }

    private fun load(): List<DeckStrategy> {
        return DECK_STRATEGY_PLUGINS.values.stream()
            .flatMap { list: List<PluginWrapper<DeckStrategy>> -> list.stream() }
            .flatMap { deckPluginWrapper: PluginWrapper<DeckStrategy> ->
                if (!deckPluginWrapper.isListen) {
                    deckPluginWrapper.addEnabledListener { _: ObservableValue<out Boolean?>?, _: Boolean?, _: Boolean? ->
                        reload()
                    }
                }
                if (deckPluginWrapper.isEnabled()) deckPluginWrapper.spiInstance.stream()
                    .filter { deckStrategy: DeckStrategy ->
                        deckStrategy.pluginId = deckPluginWrapper.plugin.id()
                        deckStrategy.name().isNotBlank() && deckStrategy.id()
                            .isNotBlank() && deckStrategy.runModes.isNotEmpty()
                    } else Stream.empty()
            }.toList()
    }

    /**
     * A high-priority work-time rule is authoritative only while the ordinary
     * schedule is actually active. DebugRun intentionally bypasses that gate
     * without activating a preset rule; in that case the persisted UI choice
     * remains the only valid deck/mode selection.
     */
    internal fun <T> effectiveSelection(
        highPrioritySchedule: Boolean,
        ordinaryScheduleActive: Boolean,
        scheduleSelection: T?,
        userSelection: T?,
    ): T? = if (highPrioritySchedule && ordinaryScheduleActive) {
        scheduleSelection ?: userSelection
    } else {
        userSelection
    }

    private fun reload() {
        log.info { "刷新策略库" }
        deckStrategies.clear()
        deckStrategies.addAll(load())
    }

}
