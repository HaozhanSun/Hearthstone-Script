package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.WorkTime
import club.xiaojiawei.hsscript.bean.WorkTimeRule
import club.xiaojiawei.hsscript.enums.OperateEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener.ScheduleRuleSnapshot
import club.xiaojiawei.hsscript.utils.WorkTimeWindow
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeSelectionSnapshotTest {
    @Test
    fun `A time settings window is wide enough for mode strategy and paired strategy columns`() {
        val moduleRoot = moduleRoot()
        val fxml = Files.readString(moduleRoot.resolve(Path.of("src", "main", "resources", "fxml", "timeSettings.fxml")))
        val windowEnum = Files.readString(moduleRoot.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "enums", "WindowEnum.kt",
        )))

        assertTrue(fxml.contains("prefWidth=\"1240.0\""))
        assertTrue(fxml.contains("selectedRunModeCol\" text=\"模式\" prefWidth=\"140\""))
        assertTrue(fxml.contains("selectedStrategyCol\" text=\"策略\" prefWidth=\"180\""))
        assertTrue(fxml.contains("selectedPairedStrategyCol\" text=\"配套策略\" prefWidth=\"220\""))
        assertTrue(fxml.contains("minWidth=\"760\""))
        assertTrue(windowEnum.contains("width = 1240.0"))
        assertTrue(windowEnum.contains("height = 600.0"))
    }

    @Test
    fun `B main ui display is derived from confirmed runtime snapshot and does not imply config writeback`() {
        val ruleSnapshot = snapshot(
            ruleIndex = 2,
            rule = rule(
                "06:46",
                "07:21",
                RunModeEnum.STANDARD,
                setOf(3),
                mapOf(3 to CANNON_WARRIOR),
                CANNON_WARRIOR,
            ),
            effectiveWindow = "06:46-07:21:59",
        )
        val decision = WorkTimeSlotStrategyBinding.resolve(
            rule = ruleSnapshot.rule,
            deckSlot = 3,
            maxDeckSlots = 9,
            strategyExists = { it == CANNON_WARRIOR },
        )
        val snapshot = RuntimeSelectionSnapshotFactory.schedule(
            ruleSnapshot = ruleSnapshot,
            deckSlot = 3,
            decision = decision,
            strategyName = "Cannon Warrior",
            gameLocked = false,
        )

        assertEquals(RuntimeSelectionState.SCHEDULE_CONFIRMED, snapshot.state)
        assertEquals(RunModeEnum.STANDARD, snapshot.runMode)
        assertEquals(3, snapshot.deckSlot)
        assertEquals(CANNON_WARRIOR, snapshot.strategyId)
        assertTrue(RuntimeSelectionUiContract.statusText(snapshot).contains("Cannon Warrior"))
        assertTrue(RuntimeSelectionUiContract.statusText(snapshot).contains("卡槽3"))

        val controller = Files.readString(moduleRoot().resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "controller", "javafx", "MainController.kt",
        )))
        assertTrue(controller.contains("applyingRuntimeSnapshot"))
        assertTrue(controller.contains("return@addListener"))
        assertTrue(controller.contains("runtimeSelectionSnapshotProperty.addListener"))
        assertTrue(controller.contains("applyRuntimeSnapshot"))
    }

    @Test
    fun `C current time row highlight uses the same runtime snapshot as mode and strategy display`() {
        val second = snapshot(
            ruleIndex = 1,
            rule = rule("05:07", "05:31", RunModeEnum.WILD, setOf(1), mapOf(1 to PIRATE_DEMON_HUNTER), PIRATE_DEMON_HUNTER),
            effectiveWindow = "05:07-05:31:59",
        )
        val third = snapshot(
            ruleIndex = 2,
            rule = rule("06:46", "07:21", RunModeEnum.STANDARD, setOf(3), mapOf(3 to CANNON_WARRIOR), CANNON_WARRIOR),
            effectiveWindow = "06:46-07:21:59",
        )
        val firstDisplay = scheduleSnapshot(second, 1, PIRATE_DEMON_HUNTER, "海盗瞎 MCTS")
        val secondDisplay = scheduleSnapshot(third, 3, CANNON_WARRIOR, "Cannon Warrior")

        assertEquals(RuntimeSelectionUiContract.ACTIVE_WORK_TIME_STYLE, RuntimeSelectionUiContract.workTimeStyleFor(firstDisplay, "preset-a", 1))
        assertNull(RuntimeSelectionUiContract.workTimeStyleFor(firstDisplay, "preset-a", 2))
        assertEquals(RuntimeSelectionUiContract.ACTIVE_WORK_TIME_STYLE, RuntimeSelectionUiContract.workTimeStyleFor(secondDisplay, "preset-a", 2))
        assertNull(RuntimeSelectionUiContract.workTimeStyleFor(secondDisplay, "preset-a", 1))
        assertEquals(secondDisplay.runMode, RunModeEnum.STANDARD)
        assertEquals(secondDisplay.strategyName, "Cannon Warrior")
    }

    @Test
    fun `offline log replay at 0707 proves actual runtime and main ui snapshot stay consistent`() {
        val rows =
            listOf(
                snapshot(
                    ruleIndex = 1,
                    rule = rule("05:07", "05:31", RunModeEnum.WILD, setOf(1), mapOf(1 to PIRATE_DEMON_HUNTER), PIRATE_DEMON_HUNTER),
                    effectiveWindow = "05:07-05:31:59",
                ),
                snapshot(
                    ruleIndex = 2,
                    rule = rule("06:46", "07:21", RunModeEnum.STANDARD, setOf(3), mapOf(3 to CANNON_WARRIOR), CANNON_WARRIOR),
                    effectiveWindow = "06:46-07:21:59",
                ),
            )

        val before = activeSnapshotAt(LocalDateTime.of(2026, 9, 10, 5, 8), rows)
        val at0707 = activeSnapshotAt(LocalDateTime.of(2026, 9, 10, 7, 7), rows)
        val logLines =
            listOf(before, at0707).joinToString("\n") { snapshot ->
                "RUNTIME_UI_SNAPSHOT time=${snapshot.effectiveWindow} state=${snapshot.state} " +
                    "ruleIndex=${snapshot.ruleIndex} mode=${snapshot.runMode} deckSlot=${snapshot.deckSlot} " +
                    "strategy=${snapshot.strategyId} strategyName=${snapshot.strategyName} " +
                    "highlight=${RuntimeSelectionUiContract.workTimeStyleFor(snapshot, snapshot.ruleSetId, snapshot.ruleIndex)} " +
                    "ui=${RuntimeSelectionUiContract.statusText(snapshot)}"
            }

        appendEvidence(logLines)

        assertEquals(RuntimeSelectionState.SCHEDULE_CONFIRMED, at0707.state)
        assertEquals(2, at0707.ruleIndex)
        assertEquals(RunModeEnum.STANDARD, at0707.runMode)
        assertEquals(3, at0707.deckSlot)
        assertEquals(CANNON_WARRIOR, at0707.strategyId)
        assertTrue(RuntimeSelectionUiContract.statusText(at0707).contains("Cannon Warrior"))
        assertEquals(RuntimeSelectionUiContract.ACTIVE_WORK_TIME_STYLE, RuntimeSelectionUiContract.workTimeStyleFor(at0707, "preset-a", 2))
        assertFalse(logLines.lineSequence().any { it.contains("ruleIndex=2") && it.contains(PIRATE_DEMON_HUNTER) })
    }

    @Test
    fun `runtime snapshots cover midnight boundaries disabled rows overlap no hit startup and in-game lock`() {
        val disabled = snapshot(
            ruleIndex = 0,
            rule = rule("06:00", "08:00", RunModeEnum.WILD, setOf(1), mapOf(1 to PIRATE_DEMON_HUNTER), PIRATE_DEMON_HUNTER, enable = false),
            effectiveWindow = "06:00-08:00:59",
        )
        val crossMidnight = snapshot(
            ruleIndex = 1,
            rule = rule("23:43", "00:18", RunModeEnum.STANDARD, setOf(3), mapOf(3 to CANNON_WARRIOR), CANNON_WARRIOR),
            effectiveWindow = "23:43-00:18:59",
        )
        val earlierOverlap = snapshot(
            ruleIndex = 2,
            rule = rule("07:00", "07:30", RunModeEnum.WILD, setOf(1), mapOf(1 to PIRATE_DEMON_HUNTER), PIRATE_DEMON_HUNTER),
            effectiveWindow = "07:00-07:30:59",
        )
        val laterOverlap = snapshot(
            ruleIndex = 3,
            rule = rule("07:07", "07:21", RunModeEnum.STANDARD, setOf(3), mapOf(3 to CANNON_WARRIOR), CANNON_WARRIOR),
            effectiveWindow = "07:07-07:21:59",
        )

        val rows = listOf(disabled, crossMidnight, earlierOverlap, laterOverlap)
        assertEquals(earlierOverlap.ruleIndex, activeRuleAt(LocalDateTime.of(2026, 9, 10, 7, 7), rows)?.ruleIndex)
        assertNull(activeRuleAt(LocalDateTime.of(2026, 9, 10, 6, 30), rows))
        assertNotNull(activeRuleAt(LocalDateTime.of(2026, 9, 10, 23, 50), rows))
        assertNotNull(activeRuleAt(LocalDateTime.of(2026, 9, 11, 0, 17), rows, LocalDate.of(2026, 9, 10)))
        assertNull(activeRuleAt(LocalDateTime.of(2026, 9, 11, 0, 19), rows, LocalDate.of(2026, 9, 10)))

        val switching = RuntimeSelectionSnapshotFactory.schedule(
            ruleSnapshot = laterOverlap,
            deckSlot = null,
            decision = WorkTimeSlotStrategyBinding.resolve(laterOverlap.rule, null, 9) { it == CANNON_WARRIOR },
            strategyName = "Cannon Warrior",
            gameLocked = false,
        )
        val locked = scheduleSnapshot(laterOverlap, 3, CANNON_WARRIOR, "Cannon Warrior", gameLocked = true)

        assertEquals(RuntimeSelectionState.SCHEDULE_SWITCHING, switching.state)
        assertEquals(RuntimeSelectionUiContract.SWITCHING_WORK_TIME_STYLE, RuntimeSelectionUiContract.workTimeStyleFor(switching, "preset-a", 3))
        assertEquals(RuntimeSelectionState.SCHEDULE_GAME_LOCKED, locked.state)
        assertEquals(RuntimeSelectionUiContract.ACTIVE_WORK_TIME_STYLE, RuntimeSelectionUiContract.workTimeStyleFor(locked, "preset-a", 3))
        assertTrue(RuntimeSelectionUiContract.statusText(locked).contains("本局锁定"))

        val listener = Files.readString(moduleRoot().resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "listener", "WorkTimeListener.kt",
        )))
        assertTrue(listener.contains("for ((ruleIndex, rule) in ruleSet.getTimeRules().withIndex())"))
        assertFalse(listener.contains("filter { it.enable }.withIndex()"))
    }

    private fun activeSnapshotAt(
        now: LocalDateTime,
        rows: List<ScheduleRuleSnapshot>,
    ): RuntimeSelectionSnapshot =
        activeRuleAt(now, rows)
            ?.let { row ->
                val slot = row.rule.deckPos.single()
                scheduleSnapshot(row, slot, row.rule.pairedStrategyIds.getValue(slot), strategyName(row.rule.pairedStrategyIds.getValue(slot)))
            }
            ?: RuntimeSelectionSnapshotFactory.userDefault(null, null, null)

    private fun activeRuleAt(
        now: LocalDateTime,
        rows: List<ScheduleRuleSnapshot>,
        scheduleDate: LocalDate = now.toLocalDate(),
    ): ScheduleRuleSnapshot? =
        rows
            .filter { it.rule.enable }
            .mapNotNull { row ->
                val start = row.rule.workTime.parseStartTime() ?: return@mapNotNull null
                val end = row.rule.workTime.parseEndTime() ?: return@mapNotNull null
                val occurrence = WorkTimeWindow.occurrence(scheduleDate, start, end)
                if (occurrence.contains(now)) row to occurrence.start else null
            }
            .sortedBy { it.second }
            .firstOrNull()
            ?.first

    private fun scheduleSnapshot(
        row: ScheduleRuleSnapshot,
        deckSlot: Int,
        strategyId: String,
        strategyName: String,
        gameLocked: Boolean = false,
    ): RuntimeSelectionSnapshot {
        val decision = WorkTimeSlotStrategyBinding.resolve(row.rule, deckSlot, 9) { it == strategyId }
        return RuntimeSelectionSnapshotFactory.schedule(row, deckSlot, decision, strategyName, gameLocked)
    }

    private fun snapshot(
        ruleIndex: Int,
        rule: WorkTimeRule,
        effectiveWindow: String,
    ): ScheduleRuleSnapshot =
        ScheduleRuleSnapshot(
            ruleSetId = "preset-a",
            ruleIndex = ruleIndex,
            rule = rule,
            scheduleDate = LocalDate.of(2026, 9, 10),
            effectiveWindow = effectiveWindow,
        )

    private fun rule(
        start: String,
        end: String,
        runMode: RunModeEnum,
        deckPos: Set<Int>,
        pairedStrategyIds: Map<Int, String>,
        strategyId: String,
        enable: Boolean = true,
    ): WorkTimeRule =
        WorkTimeRule(
            WorkTime(start, end),
            setOf(OperateEnum.CLOSE_GAME),
            runMode,
            strategyId,
            deckPos,
            enable,
            pairedStrategyIds,
        )

    private fun strategyName(strategyId: String): String =
        when (strategyId) {
            CANNON_WARRIOR -> "Cannon Warrior"
            PIRATE_DEMON_HUNTER -> "海盗瞎 MCTS"
            else -> "未知策略"
        }

    private fun moduleRoot(): Path =
        listOf(Path.of("."), Path.of("hs-script-app"))
            .first { Files.isRegularFile(it.resolve(Path.of("src", "main", "resources", "fxml", "timeSettings.fxml"))) }

    private fun appendEvidence(text: String) {
        val evidence = Path.of("target", "offline-evidence", "runtime-selection-snapshot.log")
        Files.createDirectories(evidence.parent)
        Files.writeString(
            evidence,
            text.trimEnd() + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    companion object {
        private const val PIRATE_DEMON_HUNTER = DEFAULT_PIRATE_DEMON_HUNTER_STRATEGY_ID
        private const val CANNON_WARRIOR = "e71234fa-9-standard-cannon-warrior-v1-0-9b1f-4d29-8f4f"
    }
}
