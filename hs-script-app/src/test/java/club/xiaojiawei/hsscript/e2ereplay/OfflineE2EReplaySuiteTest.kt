package club.xiaojiawei.hsscript.e2ereplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OfflineE2EReplaySuiteTest {
    private val expectedStageIds = listOf(
        "startup",
        "hub",
        "mode-entry",
        "matchmaking",
        "listener-attach",
        "create-game",
        "mulligan",
        "gameplay-controls",
        "terminal-wait",
    )

    @Test
    fun `manifest routes the complete lifecycle and every evidence contract`() {
        val manifest = OfflineE2EReplayEngine.loadManifest()

        assertEquals(1, manifest.schemaVersion)
        assertEquals(expectedStageIds, manifest.stages.map { it.id })
        assertTrue(manifest.sourceRef.matches(Regex("[0-9a-f]{40}")))
        assertTrue(manifest.upstreamRef.matches(Regex("[0-9a-f]{40}")))
        assertTrue(manifest.stages.all { it.screenshot.manifest.isNotBlank() })
        assertTrue(manifest.stages.all { it.ocr.path.isNotBlank() })
        assertTrue(manifest.stages.all { it.powerLogFragment.isNotBlank() })
        assertTrue(manifest.stages.all { it.failureReasons.isNotEmpty() })
    }

    @Test
    fun `replays startup through terminal without real clients`() {
        val transitions = OfflineE2EReplayEngine.replay()

        assertEquals(expectedStageIds, transitions.map { it.stageId })
        assertEquals("STARTUP", transitions.first().before.state)
        assertEquals("COMPLETE", transitions.last().after.state)
        assertTrue(transitions.last().after.terminalObserved)
        assertTrue(!transitions.last().after.dispatchAllowed)
        assertEquals(1, transitions.single { it.stageId == "create-game" }.after.boundarySequence)
        assertEquals(
            listOf("mulligan", "our-turn", "out-card"),
            transitions.single { it.stageId == "gameplay-controls" }.after.milestones,
        )
    }

    @Test
    fun `rejects a stale listener path or reused old offset`() {
        val manifest = OfflineE2EReplayEngine.loadManifest()
        val original = manifest.stages.first { it.id == "listener-attach" }
        val broken = original.copy(
            expectedOutput = original.expectedOutput.copy(
                powerLogPath = original.stateMachineInput.powerLogPath,
                baselineOffset = original.stateMachineInput.baselineOffset,
            ),
        )

        val error = assertFailsWith<IllegalStateException> {
            OfflineE2EReplayEngine.replay(manifest.replaceStage(broken))
        }
        assertTrue(error.message.orEmpty().contains("stale-power-log-path"))
    }

    @Test
    fun `rejects CREATE_GAME without an authoritative boundary`() {
        val manifest = OfflineE2EReplayEngine.loadManifest()
        val original = manifest.stages.first { it.id == "create-game" }
        val broken = original.copy(
            traceEvents = listOf("E2E_READINESS_READY"),
        )

        val error = assertFailsWith<IllegalStateException> {
            OfflineE2EReplayEngine.replay(manifest.replaceStage(broken))
        }
        assertTrue(error.message.orEmpty().contains("game-boundary-missing"))
    }

    @Test
    fun `rejects gameplay input when foreground is not confirmed`() {
        val manifest = OfflineE2EReplayEngine.loadManifest()
        val original = manifest.stages.first { it.id == "gameplay-controls" }
        val broken = original.copy(
            stateMachineInput = original.stateMachineInput.copy(foregroundConfirmed = false),
        )

        val error = assertFailsWith<IllegalStateException> {
            OfflineE2EReplayEngine.replay(manifest.replaceStage(broken))
        }
        assertTrue(error.message.orEmpty().contains("foreground-input-discontinuity"))
    }

    @Test
    fun `rejects invalid F2 and F1 pause gate transitions`() {
        val manifest = OfflineE2EReplayEngine.loadManifest()
        val original = manifest.stages.first { it.id == "gameplay-controls" }
        val broken = original.copy(
            controlTransitions = listOf(
                ControlTransition("F2", beforePaused = false, afterPaused = false),
                ControlTransition("F1", beforePaused = false, afterPaused = false),
            ),
        )

        val error = assertFailsWith<IllegalStateException> {
            OfflineE2EReplayEngine.replay(manifest.replaceStage(broken))
        }
        assertTrue(error.message.orEmpty().contains("input-sent-while-paused"))
    }

    @Test
    fun `rejects terminal wait without authoritative playstate`() {
        val manifest = OfflineE2EReplayEngine.loadManifest()
        val original = manifest.stages.first { it.id == "terminal-wait" }
        val broken = original.copy(powerLogEvents = emptyList())

        val error = assertFailsWith<IllegalStateException> {
            OfflineE2EReplayEngine.replay(manifest.replaceStage(broken))
        }
        assertTrue(error.message.orEmpty().contains("terminal-not-authoritative"))
    }

    private fun ReplayManifest.replaceStage(replacement: ReplayStage): ReplayManifest =
        copy(stages = stages.map { if (it.id == replacement.id) replacement else it })
}
