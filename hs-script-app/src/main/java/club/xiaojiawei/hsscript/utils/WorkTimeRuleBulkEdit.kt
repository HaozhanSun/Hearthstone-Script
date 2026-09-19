package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.bean.WorkTimeRule
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum

data class WorkTimeRuleBulkEditRequest(
    val runMode: RunModeEnum? = null,
    val strategyId: String? = null,
    val strategyAllowedRunModes: Set<RunModeEnum>? = null,
    val deckPos: Set<Int>? = null,
    val enable: Boolean? = null,
) {
    fun hasSelectedFields(): Boolean =
        runMode != null || strategyId != null || deckPos != null || enable != null
}

data class WorkTimeRuleBulkEditValidation(
    val missingSelectedFields: Boolean,
    val unconfiguredRows: List<Int>,
    val incompatibleStrategyRows: List<Int>,
) {
    val canApply: Boolean =
        !missingSelectedFields && unconfiguredRows.isEmpty() && incompatibleStrategyRows.isEmpty()
}

data class WorkTimeRuleBulkEditResult(
    val updatedCount: Int,
    val validation: WorkTimeRuleBulkEditValidation,
)

object WorkTimeRuleBulkEdit {
    fun validate(
        rules: List<WorkTimeRule>,
        request: WorkTimeRuleBulkEditRequest,
    ): WorkTimeRuleBulkEditValidation {
        val unconfiguredRows =
            rules
                .mapIndexedNotNull { index, rule ->
                    if (rule.workTime.parseStartTime() == null || rule.workTime.parseEndTime() == null) {
                        index + 1
                    } else {
                        null
                    }
                }
        val incompatibleStrategyRows =
            request
                .strategyAllowedRunModes
                ?.takeIf { request.strategyId != null && it.isNotEmpty() }
                ?.let { allowedRunModes ->
                    rules.mapIndexedNotNull { index, rule ->
                        val effectiveRunMode = request.runMode ?: rule.runMode
                        if (allowedRunModes.contains(effectiveRunMode)) null else index + 1
                    }
                } ?: emptyList()

        return WorkTimeRuleBulkEditValidation(
            missingSelectedFields = !request.hasSelectedFields(),
            unconfiguredRows = unconfiguredRows,
            incompatibleStrategyRows = incompatibleStrategyRows,
        )
    }

    fun apply(
        rules: List<WorkTimeRule>,
        request: WorkTimeRuleBulkEditRequest,
    ): WorkTimeRuleBulkEditResult {
        val validation = validate(rules, request)
        require(validation.canApply) { "Work time bulk edit request is not valid: $validation" }

        for (rule in rules) {
            request.runMode?.let { rule.runMode = it }
            request.strategyId?.let { rule.strategyId = it }
            request.deckPos?.let { rule.deckPos = it.toSortedSet() }
            request.enable?.let { rule.enable = it }
        }
        return WorkTimeRuleBulkEditResult(rules.size, validation)
    }
}
