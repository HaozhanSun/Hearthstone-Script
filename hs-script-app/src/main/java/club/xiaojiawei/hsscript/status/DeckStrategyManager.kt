package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.bean.WorkTimeRule
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.listener.WorkTimeListener.ScheduleRuleSnapshot
import club.xiaojiawei.hsscript.status.PluginManager.DECK_STRATEGY_PLUGINS
import club.xiaojiawei.hsscript.status.PluginManager.loadDeckProperty
import club.xiaojiawei.hsscript.utils.ConfigExUtil
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
    data class ScheduleDeckSelection(
        val ruleSnapshot: ScheduleRuleSnapshot,
        val deckSlot: Int,
        val decision: WorkTimeSlotStrategyDecision,
        val assignmentReason: String,
    )

    private val scheduleSelectionLock = Any()
    private var pendingScheduleDeckSelection: ScheduleDeckSelection? = null
    private var activeGameScheduleDeckSelection: ScheduleDeckSelection? = null

    /**
     * 当前卡组策略
     */
    val currentDeckStrategyProperty: ObjectProperty<DeckStrategy?> = SimpleObjectProperty()
    val currentRunModeProperty: ObjectProperty<RunModeEnum?> = SimpleObjectProperty()
    val runtimeSelectionSnapshotProperty: ObjectProperty<RuntimeSelectionSnapshot> =
        SimpleObjectProperty(RuntimeSelectionSnapshotFactory.userDefault(null, null, null))

    var currentDeckStrategy
        set(value) {
            currentDeckStrategyProperty.set(value)
            refreshRuntimeSelectionSnapshot("user-strategy-change")
        }
        get():DeckStrategy? {
            return if (isScheduleOverrideActive()) {
                currentRuntimeSelectionSnapshot().strategyId?.let { strategyId ->
                    deckStrategies.find { it.id() == strategyId }
                }
            } else {
                currentDeckStrategyProperty.get()
            }
        }

    var currentRunMode
        set(value) {
            currentRunModeProperty.set(value)
            refreshRuntimeSelectionSnapshot("user-mode-change")
        }
        get():RunModeEnum? {
            return if (isScheduleOverrideActive()) {
                currentRuntimeSelectionSnapshot().runMode
            } else {
                currentRunModeProperty.get()
            }
        }

    /**
     * 所有卡组策略
     */
    val deckStrategies: ObservableSet<DeckStrategy> = FXCollections.observableSet()

    /** Start a new game using the strategy selected by the user. */
    @Synchronized
    fun beginGame() {
        synchronized(scheduleSelectionLock) {
            if (activeGameScheduleDeckSelection == null) {
                activeGameScheduleDeckSelection = pendingScheduleDeckSelection
            }
            activeGameScheduleDeckSelection?.let { selection ->
                publishRuntimeSelectionSnapshotLocked(snapshotForSelection(selection, gameLocked = true))
                log.info {
                    "SCHEDULE_RULE_SNAPSHOT_LOCKED ruleSet=${selection.ruleSnapshot.ruleSetId ?: "?"} " +
                        "ruleIndex=${selection.ruleSnapshot.ruleIndex ?: "?"} deckSlot=${selection.deckSlot} " +
                        "pairedStrategy=${selection.decision.strategyId ?: "n/a"} " +
                        "effectiveWindow=${selection.ruleSnapshot.effectiveWindow} " +
                        "assignmentReason=${selection.assignmentReason} " +
                        "selectionReason=${selection.decision.selectionReason} " +
                        "fallback=${selection.decision.fallbackReason ?: "none"}"
                }
            }
        }
        log.info { "当前对局使用已选策略；投降规则由对手英雄与当前排位决定" }
    }

    fun recordScheduleDeckSlotSelection(
        ruleSnapshot: ScheduleRuleSnapshot?,
        deckSlot: Int,
        maxDeckSlots: Int,
        assignmentReason: String = "single-configured-deck-slot",
    ): WorkTimeSlotStrategyDecision {
        val decision = WorkTimeSlotStrategyBinding.resolve(
            rule = ruleSnapshot?.rule,
            deckSlot = deckSlot,
            maxDeckSlots = maxDeckSlots,
            strategyExists = { strategyId -> hasDeckStrategyForRule(ruleSnapshot?.rule, strategyId) },
        )
        synchronized(scheduleSelectionLock) {
            if (ruleSnapshot != null && !WarEx.inWar) {
                pendingScheduleDeckSelection = ScheduleDeckSelection(ruleSnapshot, deckSlot, decision, assignmentReason)
                publishRuntimeSelectionSnapshotLocked(snapshotForSelection(pendingScheduleDeckSelection!!, gameLocked = false))
            }
        }
        log.info {
            "SCHEDULE_SLOT_STRATEGY_SELECTION ruleSet=${ruleSnapshot?.ruleSetId ?: "?"} " +
                "ruleIndex=${ruleSnapshot?.ruleIndex ?: "?"} deckSlot=$deckSlot " +
                "pairedStrategy=${decision.strategyId ?: "n/a"} " +
                "effectiveWindow=${ruleSnapshot?.effectiveWindow ?: "n/a"} " +
                "assignmentReason=$assignmentReason selectionReason=${decision.selectionReason} " +
                "fallback=${decision.fallbackReason ?: "none"}"
        }
        return decision
    }

    fun clearScheduleDeckSlotSelection(reason: String) {
        synchronized(scheduleSelectionLock) {
            if (!WarEx.inWar) {
                pendingScheduleDeckSelection = null
                activeGameScheduleDeckSelection = null
                refreshRuntimeSelectionSnapshotLocked(reason)
            }
        }
        log.info { "SCHEDULE_SLOT_STRATEGY_SELECTION_CLEARED reason=$reason" }
    }

    fun refreshRuntimeSelectionSnapshot(reason: String = "refresh") {
        synchronized(scheduleSelectionLock) {
            refreshRuntimeSelectionSnapshotLocked(reason)
        }
    }

    fun currentRuntimeSelectionSnapshot(): RuntimeSelectionSnapshot =
        runtimeSelectionSnapshotProperty.get()
            ?: RuntimeSelectionSnapshotFactory.userDefault(
                currentRunModeProperty.get(),
                currentDeckStrategyProperty.get()?.id(),
                currentDeckStrategyProperty.get()?.name(),
            )

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
        WarEx.addEndCallback(Runnable {
            synchronized(scheduleSelectionLock) {
                activeGameScheduleDeckSelection = null
                pendingScheduleDeckSelection = null
                refreshRuntimeSelectionSnapshotLocked("game-end")
            }
            log.info { "SCHEDULE_RULE_SNAPSHOT_RELEASED reason=game-end" }
        })
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

    private fun hasDeckStrategyForRule(
        rule: WorkTimeRule?,
        strategyId: String,
    ): Boolean =
        deckStrategies.any { strategy ->
            strategy.id() == strategyId && (rule == null || strategy.runModes.contains(rule.runMode))
        }

    private fun currentScheduleSelection(): ScheduleDeckSelection? = synchronized(scheduleSelectionLock) {
        activeGameScheduleDeckSelection ?: pendingScheduleDeckSelection
    }

    private fun isScheduleOverrideActive(): Boolean =
        ConfigUtil.getBoolean(ConfigEnum.WORK_TIME_RULE_HIGH_PRIORITY) && WorkTimeListener.isInsideConfiguredSchedule()

    private fun refreshRuntimeSelectionSnapshotLocked(reason: String) {
        val snapshot =
            if (isScheduleOverrideActive()) {
                activeGameScheduleDeckSelection?.let { snapshotForSelection(it, gameLocked = true) }
                    ?: pendingScheduleDeckSelection?.let { snapshotForSelection(it, gameLocked = false) }
                    ?: snapshotForCurrentSchedule()
            } else {
                RuntimeSelectionSnapshotFactory.userDefault(
                    currentRunModeProperty.get(),
                    currentDeckStrategyProperty.get()?.id(),
                    currentDeckStrategyProperty.get()?.name(),
                )
            }
        publishRuntimeSelectionSnapshotLocked(snapshot, reason)
    }

    private fun snapshotForCurrentSchedule(): RuntimeSelectionSnapshot {
        val ruleSnapshot = WorkTimeListener.currentScheduleRuleSnapshot()
            ?: return RuntimeSelectionSnapshotFactory.userDefault(
                currentRunModeProperty.get(),
                currentDeckStrategyProperty.get()?.id(),
                currentDeckStrategyProperty.get()?.name(),
            )
        val deckSlotChoice = StrategyDefaultDeckSlotBindings.chooseDeckSlots(
            rule = ruleSnapshot.rule,
            strategyId = null,
            globalDeckSlots = ConfigExUtil.getChooseDeckPos(),
            maxDeckSlots = 9,
        )
        val slot = deckSlotChoice.deckSlots.singleOrNull()
        val decision = WorkTimeSlotStrategyBinding.resolve(
            rule = ruleSnapshot.rule,
            deckSlot = slot,
            maxDeckSlots = 9,
            strategyExists = { strategyId -> hasDeckStrategyForRule(ruleSnapshot.rule, strategyId) },
        )
        return RuntimeSelectionSnapshotFactory.schedule(
            ruleSnapshot = ruleSnapshot,
            deckSlot = slot,
            decision = decision,
            strategyName = decision.strategyId?.let(::strategyName),
            gameLocked = false,
        )
    }

    private fun snapshotForSelection(
        selection: ScheduleDeckSelection,
        gameLocked: Boolean,
    ): RuntimeSelectionSnapshot =
        RuntimeSelectionSnapshotFactory.schedule(
            ruleSnapshot = selection.ruleSnapshot,
            deckSlot = selection.deckSlot,
            decision = selection.decision,
            strategyName = selection.decision.strategyId?.let(::strategyName),
            gameLocked = gameLocked,
        )

    private fun publishRuntimeSelectionSnapshotLocked(
        snapshot: RuntimeSelectionSnapshot,
        reason: String = "schedule-selection",
    ) {
        val previous = runtimeSelectionSnapshotProperty.get()
        if (previous == snapshot) return
        runtimeSelectionSnapshotProperty.set(snapshot)
        log.info {
            "SCHEDULE_RUNTIME_SNAPSHOT reason=$reason state=${snapshot.state} " +
                "ruleSet=${snapshot.ruleSetId ?: "?"} ruleIndex=${snapshot.ruleIndex ?: "?"} " +
                "mode=${snapshot.runMode?.name ?: "n/a"} deckSlot=${snapshot.deckSlot ?: "n/a"} " +
                "strategy=${snapshot.strategyId ?: "n/a"} strategyName=${snapshot.strategyName ?: "n/a"} " +
                "effectiveWindow=${snapshot.effectiveWindow ?: "n/a"} " +
                "selectionReason=${snapshot.selectionReason ?: "n/a"} fallback=${snapshot.fallbackReason ?: "none"}"
        }
    }

    private fun strategyName(strategyId: String): String? =
        deckStrategies.find { it.id() == strategyId }?.name()
}
