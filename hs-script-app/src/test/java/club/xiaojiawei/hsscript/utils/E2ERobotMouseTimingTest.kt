package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.bean.AttackInputTraceIds
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class E2ERobotMouseTimingTest {
    @Test
    fun `gameplay movement delay gives a 1 point 5 speedup across a representative path`() {
        val sampleCount = 24
        val baseDelayMs = 9 // Divisible by 1.5 so rounding does not obscure the ratio.
        val originalDurationMs = sampleCount * baseDelayMs
        val fasterDurationMs = sampleCount * GameplayMouseMovementTiming.scaleStepDelayMs(baseDelayMs)

        assertEquals(6, GameplayMouseMovementTiming.scaleStepDelayMs(baseDelayMs))
        assertEquals(originalDurationMs / GameplayMouseMovementTiming.GAMEPLAY_SPEED_FACTOR, fasterDurationMs.toDouble())
        assertEquals(216, originalDurationMs)
        assertEquals(144, fasterDurationMs)
    }

    @Test
    fun `scaled movement delay clamps to safe bounds`() {
        assertEquals(GameplayMouseMovementTiming.MIN_STEP_DELAY_MS, GameplayMouseMovementTiming.scaleStepDelayMs(Int.MIN_VALUE))
        assertEquals(GameplayMouseMovementTiming.MIN_STEP_DELAY_MS, GameplayMouseMovementTiming.scaleStepDelayMs(1))
        assertEquals(GameplayMouseMovementTiming.MAX_SCALED_STEP_DELAY_MS, GameplayMouseMovementTiming.scaleStepDelayMs(18))
        assertEquals(GameplayMouseMovementTiming.MAX_SCALED_STEP_DELAY_MS, GameplayMouseMovementTiming.scaleStepDelayMs(Int.MAX_VALUE))
        for (baseDelayMs in 7..18) {
            val scaledDelayMs = GameplayMouseMovementTiming.scaleStepDelayMs(baseDelayMs)
            assertTrue(scaledDelayMs in GameplayMouseMovementTiming.MIN_STEP_DELAY_MS..GameplayMouseMovementTiming.MAX_SCALED_STEP_DELAY_MS)
        }
    }

    @Test
    fun `normal e2e gameplay robot path uses faster delay while recovery and click waits stay scoped`() {
        val source = Files.readString(
            Path.of("src", "main", "java", "club", "xiaojiawei", "hsscript", "utils", "MouseUtil.kt"),
        )
        val robotMovement = source.substringAfter("private fun moveRobotAlongCurve(")
            .substringBefore("/**", missingDelimiterValue = "")
        val nativeMovement = source.substringAfter("private fun moveNativeAlongCurve(")
            .substringBefore("/**", missingDelimiterValue = "")
        val normalRobotClick = source.substringAfter("private fun clickWithE2ERobot(")
            .substringBefore("private fun clickMulliganWithE2ERobot(")
        val normalGameplayClick = source.substringAfter("fun leftButtonClick(\n        pos: Point")
            .substringBefore("/**\n     * Use the configured MESSAGE semantics")
        val recoveryClick = source.substringAfter("internal fun leftButtonClickForRecovery(")
            .substringBefore("private fun clickWithE2ERobot(")
        val mulliganClick = source.substringAfter("private fun clickMulliganWithE2ERobot(")
            .substringBefore("fun leftButtonClickMulligan(")
        val dragClickSource = Files.readString(
            Path.of("src", "main", "java", "club", "xiaojiawei", "hsscript", "bean", "GameRect.kt"),
        )
        assertTrue(robotMovement.contains("GameplayMouseMovementTiming.scaleStepDelayMs(RandomUtil.getMouseStepDelay())"))
        assertTrue(nativeMovement.contains("GameplayMouseMovementTiming.scaleStepDelayMs(RandomUtil.getMouseStepDelay())"))
        assertTrue(normalGameplayClick.contains("clickWithE2ERobot("))
        assertTrue(normalGameplayClick.contains("recoveryCapability = recoveryCapability"))
        assertTrue(normalRobotClick.contains("moveRobotAlongCurve(e2eRobot"))
        assertTrue(normalRobotClick.contains("dispatchActionOverride ?:"), "Robot path retains semantic action overrides")
        assertTrue(normalRobotClick.contains("RobotInputDispatchBoundary.dispatchIfAuthorized"))
        assertTrue(
            normalGameplayClick.contains("dispatchActionOverride = dispatchActionOverride"),
            "semantic action must cross from click adapter into Robot boundary",
        )
        assertTrue(normalRobotClick.contains("delay(RandomUtil.getInteractionDelay(35))"))
        assertTrue(recoveryClick.contains("sendE2eWindowsClick(Point(pos.x, pos.y))"))
        assertTrue(!recoveryClick.contains("GameplayMouseMovementTiming.scaleStepDelayMs"))
        assertTrue(mulliganClick.contains("e2eRobot.mouseMove(pos.x, pos.y)"))
        assertTrue(mulliganClick.contains("e2eRobot.delay(120)"))
        assertTrue(dragClickSource.contains("SystemUtil.delay(80, 140)"))
        assertTrue(dragClickSource.contains("SystemUtil.delay(60, 120)"))
        assertTrue(GameplayMouseMovementTiming.GAMEPLAY_SPEED_FACTOR == 1.5)
    }

    @Test
    fun `attack trace ids correlate each attack with the next attack start`() {
        val ids = AttackInputTraceIds()
        val first = ids.next()
        val second = ids.next()

        assertEquals(null, first.previousAttackId)
        assertEquals(first.attackId, second.previousAttackId)
        assertTrue(first.attackId != second.attackId)
    }

    @Test
    fun `attack sequence emits correlated click and movement timing stages`() {
        val gameRectSource = Files.readString(
            Path.of("src", "main", "java", "club", "xiaojiawei", "hsscript", "bean", "GameRect.kt"),
        )
        val cardActionSource = Files.readString(
            Path.of("src", "main", "java", "club", "xiaojiawei", "hsscript", "bean", "CommonCardAction.kt"),
        )
        val traceSource = Files.readString(
            Path.of("src", "main", "java", "club", "xiaojiawei", "hsscript", "bean", "AttackInputTimingTrace.kt"),
        )

        for (stage in listOf(
            "SOURCE_CLICK_DISPATCH_START",
            "SOURCE_CLICK_DISPATCH_END",
            "MOVEMENT_START",
            "MOVEMENT_END",
            "TARGET_CLICK_DISPATCH_START",
            "TARGET_CLICK_DISPATCH_END",
        )) {
            assertTrue(gameRectSource.contains("\"$stage\""), "missing attack trace stage $stage")
        }
        assertTrue(traceSource.contains("stage=NEXT_ATTACK_START nextAttackId="))
        assertTrue(cardActionSource.contains("lClickMoveLClick(endRect, attackTraceId = AttackInputTimingTrace.begin())"))
        assertTrue(cardActionSource.contains("attackTraceId = AttackInputTimingTrace.begin()"))
    }
}
