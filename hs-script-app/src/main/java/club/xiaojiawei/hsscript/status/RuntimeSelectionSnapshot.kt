package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.listener.WorkTimeListener.ScheduleRuleSnapshot
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum

enum class RuntimeSelectionState {
    USER_DEFAULT,
    SCHEDULE_SWITCHING,
    SCHEDULE_CONFIRMED,
    SCHEDULE_GAME_LOCKED,
    SCHEDULE_REFUSED,
}

data class RuntimeSelectionSnapshot(
    val state: RuntimeSelectionState,
    val ruleSetId: String? = null,
    val ruleIndex: Int? = null,
    val effectiveWindow: String? = null,
    val runMode: RunModeEnum? = null,
    val deckSlot: Int? = null,
    val strategyId: String? = null,
    val strategyName: String? = null,
    val selectionReason: String? = null,
    val fallbackReason: String? = null,
) {
    val fromSchedule: Boolean
        get() = state != RuntimeSelectionState.USER_DEFAULT

    val confirmed: Boolean
        get() = state == RuntimeSelectionState.SCHEDULE_CONFIRMED || state == RuntimeSelectionState.SCHEDULE_GAME_LOCKED

    fun matchesRule(
        ruleSetId: String?,
        ruleIndex: Int?,
    ): Boolean =
        fromSchedule &&
            this.ruleSetId != null &&
            this.ruleIndex != null &&
            this.ruleSetId == ruleSetId &&
            this.ruleIndex == ruleIndex
}

object RuntimeSelectionUiContract {
    const val ACTIVE_WORK_TIME_STYLE = "workTimeItemActive"
    const val SWITCHING_WORK_TIME_STYLE = "workTimeItemSwitching"
    const val SCHEDULE_COMBO_STYLE = "runtimeScheduleSelection"

    fun promptText(snapshot: RuntimeSelectionSnapshot): String =
        when (snapshot.state) {
            RuntimeSelectionState.USER_DEFAULT -> ""
            RuntimeSelectionState.SCHEDULE_SWITCHING -> "时间段切换中"
            RuntimeSelectionState.SCHEDULE_CONFIRMED -> "时间段已生效"
            RuntimeSelectionState.SCHEDULE_GAME_LOCKED -> "本局锁定"
            RuntimeSelectionState.SCHEDULE_REFUSED -> "时间段策略不可用"
        }

    fun workTimeStyleFor(
        snapshot: RuntimeSelectionSnapshot,
        ruleSetId: String?,
        ruleIndex: Int?,
    ): String? =
        if (!snapshot.matchesRule(ruleSetId, ruleIndex)) {
            null
        } else if (snapshot.confirmed) {
            ACTIVE_WORK_TIME_STYLE
        } else {
            SWITCHING_WORK_TIME_STYLE
        }

    fun statusText(snapshot: RuntimeSelectionSnapshot): String =
        buildString {
            append(promptText(snapshot).ifBlank { "用户默认配置" })
            snapshot.effectiveWindow?.let { append(" $it") }
            snapshot.deckSlot?.let { append(" 卡槽$it") }
            snapshot.strategyName?.let { append(" $it") }
            snapshot.fallbackReason?.let { append(" $it") }
        }
}

object RuntimeSelectionSnapshotFactory {
    fun userDefault(
        runMode: RunModeEnum?,
        strategyId: String?,
        strategyName: String?,
    ): RuntimeSelectionSnapshot =
        RuntimeSelectionSnapshot(
            state = RuntimeSelectionState.USER_DEFAULT,
            runMode = runMode,
            strategyId = strategyId,
            strategyName = strategyName,
        )

    fun schedule(
        ruleSnapshot: ScheduleRuleSnapshot,
        deckSlot: Int?,
        decision: WorkTimeSlotStrategyDecision,
        strategyName: String?,
        gameLocked: Boolean,
    ): RuntimeSelectionSnapshot {
        val state =
            when {
                !decision.accepted -> RuntimeSelectionState.SCHEDULE_REFUSED
                deckSlot == null -> RuntimeSelectionState.SCHEDULE_SWITCHING
                gameLocked -> RuntimeSelectionState.SCHEDULE_GAME_LOCKED
                else -> RuntimeSelectionState.SCHEDULE_CONFIRMED
            }
        return RuntimeSelectionSnapshot(
            state = state,
            ruleSetId = ruleSnapshot.ruleSetId,
            ruleIndex = ruleSnapshot.ruleIndex,
            effectiveWindow = ruleSnapshot.effectiveWindow,
            runMode = ruleSnapshot.rule.runMode,
            deckSlot = deckSlot,
            strategyId = decision.strategyId,
            strategyName = strategyName,
            selectionReason = decision.selectionReason,
            fallbackReason = decision.fallbackReason,
        )
    }
}
