package club.xiaojiawei.hsscript.e2ereplay

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Test-only deterministic replay engine for the supervised game lifecycle.
 *
 * This deliberately consumes fixture contracts instead of invoking any runtime
 * service. It is therefore safe to run on a developer machine without opening
 * Hearthstone, attaching OCR, or sending native input.
 */
object OfflineE2EReplayEngine {
    private val mapper = jacksonObjectMapper()

    fun loadManifest(): ReplayManifest =
        mapper.readValue(resourceText("e2e-replay/manifest.json"))

    fun replay(manifest: ReplayManifest = loadManifest()): List<ReplayTransition> {
        var runtime = ReplayRuntimeState.startup()
        val transitions = mutableListOf<ReplayTransition>()

        manifest.stages.forEach { stage ->
            validateFixtureMetadata(stage)
            validateInputContinuity(stage, runtime)
            validateStageContract(stage)

            val before = runtime
            runtime = stage.expectedOutput.toRuntimeState()
            transitions += ReplayTransition(stage.id, before, runtime, stage.allowedActions)
        }

        requireStage(
            manifest.stages.lastOrNull()?.id == "terminal-wait",
            "terminal-wait",
            "terminal-wait-bypassed",
        )
        requireStage(
            runtime.state == "COMPLETE" && runtime.terminalObserved && !runtime.dispatchAllowed,
            "terminal-wait",
            "terminal-not-authoritative",
        )
        return transitions
    }

    private fun validateFixtureMetadata(stage: ReplayStage) {
        val screenshot = readResourceJson<ScreenshotManifest>(
            "e2e-replay/${stage.screenshot.manifest}",
        )
        requireStage(screenshot.stage == stage.id, stage.id, "screenshot-stage-mismatch")
        requireStage(screenshot.path == stage.screenshot.imagePath, stage.id, "screenshot-path-mismatch")
        requireStage(
            screenshot.captureRequired == stage.screenshot.captureRequired,
            stage.id,
            "screenshot-capture-contract-mismatch",
        )
        if (screenshot.path == null) {
            requireStage(!screenshot.retained, stage.id, "unretained-screenshot-marked-retained")
        } else {
            requireStage(screenshot.retained, stage.id, "retained-screenshot-missing-retained-flag")
        }

        val ocr = readResourceJson<OcrObservation>("e2e-replay/${stage.ocr.path}")
        requireStage(ocr.provider == stage.ocr.provider, stage.id, "ocr-provider-mismatch")
        requireStage(ocr.status == stage.ocr.status, stage.id, "ocr-status-mismatch")
        requireStage(ocr.text == stage.ocr.text, stage.id, "ocr-text-mismatch")
        requireStage(ocr.confidence == stage.ocr.confidence, stage.id, "ocr-confidence-mismatch")
        requireStage(stage.failureReasons.isNotEmpty(), stage.id, "failure-reason-contract-missing")

        val powerLog = resourceText("e2e-replay/${stage.powerLogFragment}")
        stage.powerLogEvents.forEach { event ->
            requireStage(powerLog.contains(event), stage.id, "powerlog-event-not-observed:$event")
        }
        requireStage(
            stage.stateMachineInput.actions.none { it in stage.forbiddenActions },
            stage.id,
            "forbidden-action-declared-as-input",
        )
        requireStage(
            stage.stateMachineInput.actions.all { it in stage.allowedActions },
            stage.id,
            "input-action-not-allowed",
        )
    }

    private fun validateInputContinuity(stage: ReplayStage, runtime: ReplayRuntimeState) {
        val input = stage.stateMachineInput
        requireStage(input.state == runtime.state, stage.id, "state-input-discontinuity")
        // Listener attachment intentionally discovers the current log after
        // matchmaking. Its input is the old cursor/path; path continuity is
        // checked by the listener-attach contract below, not against the
        // matchmaking state which has no log yet.
        if (stage.id != "listener-attach") {
            requireStage(input.powerLogPath == runtime.powerLogPath, stage.id, "powerlog-path-input-discontinuity")
            requireStage(input.baselineOffset == runtime.baselineOffset, stage.id, "baseline-input-discontinuity")
        }
        requireStage(input.listenerAttached == runtime.listenerAttached, stage.id, "listener-input-discontinuity")
        requireStage(input.foregroundConfirmed == runtime.foregroundConfirmed, stage.id, "foreground-input-discontinuity")
        requireStage(input.paused == runtime.paused, stage.id, "pause-input-discontinuity")
        requireStage(input.boundarySequence == runtime.boundarySequence, stage.id, "boundary-input-discontinuity")
        requireStage(input.milestones == runtime.milestones, stage.id, "milestone-input-discontinuity")
    }

    private fun validateStageContract(stage: ReplayStage) {
        when (stage.id) {
            "startup" -> {
                requireStage(stage.expectedOutput.state == "HUB", stage.id, "startup-screen-unclassified")
                requireStage(!stage.expectedOutput.dispatchAllowed, stage.id, "unexpected-gameplay-action")
            }

            "hub" -> {
                requireStage(stage.ocr.status == "CONTRACT_ACCEPTED", stage.id, "hub-ocr-contract-rejected")
                requireStage(
                    "SCREEN_RECOVERY_OBSERVATION detected=HUB" in stage.traceEvents,
                    stage.id,
                    "hub-popup-not-dismissed",
                )
                requireStage(stage.expectedOutput.foregroundConfirmed, stage.id, "foreground-unconfirmed")
            }

            "mode-entry" -> {
                requireStage("MODE_SELECTED mode=WILD" in stage.traceEvents, stage.id, "wild-mode-not-confirmed")
                requireStage(stage.expectedOutput.state == "MATCHMAKING", stage.id, "mode-entry-screen-unclassified")
            }

            "matchmaking" -> {
                requireStage("MATCHMAKING_STARTED" in stage.traceEvents, stage.id, "matchmaking-timeout")
                requireStage(!stage.expectedOutput.dispatchAllowed, stage.id, "create-game-not-observed")
            }

            "listener-attach" -> {
                val input = stage.stateMachineInput
                val output = stage.expectedOutput
                requireStage(input.powerLogPath != null && output.powerLogPath != null, stage.id, "stale-power-log-path")
                requireStage(input.powerLogPath != output.powerLogPath, stage.id, "stale-power-log-path")
                requireStage(input.baselineOffset > 0 && output.baselineOffset == 0L, stage.id, "old-offset-reused")
                requireStage(output.listenerAttached, stage.id, "listener-attach-handshake-missing")
                requireStage(
                    stage.powerLogEvents.containsAll(listOf("E2E_POWERLOG_PATH_SWITCH", "handshake=listener-attached")),
                    stage.id,
                    "listener-attach-handshake-missing",
                )
            }

            "create-game" -> {
                requireStage(
                    stage.powerLogEvents.containsAll(listOf("CREATE_GAME", "BEGIN_MULLIGAN")),
                    stage.id,
                    "create-game-not-after-baseline",
                )
                requireStage(
                    stage.traceEvents.contains("E2E_GAME_BOUNDARY sequence=1"),
                    stage.id,
                    "game-boundary-missing",
                )
                requireStage(
                    stage.expectedOutput.boundarySequence == stage.stateMachineInput.boundarySequence + 1,
                    stage.id,
                    "stale-game-lineage",
                )
                requireStage(stage.expectedOutput.state == "MULLIGAN", stage.id, "game-boundary-missing")
            }

            "mulligan" -> {
                requireStage(stage.stateMachineInput.boundarySequence > 0, stage.id, "milestone-before-boundary")
                requireStage(
                    stage.powerLogEvents.containsAll(listOf("BEGIN_MULLIGAN", "MAIN_ACTION")),
                    stage.id,
                    "mulligan-screen-not-confirmed",
                )
                requireStage("MULLIGAN_INPUT_SENT" in stage.traceEvents, stage.id, "mulligan-action-not-accepted")
                requireStage(
                    "E2E_MILESTONE milestone=mulligan" in stage.traceEvents &&
                        "mulligan" in stage.expectedOutput.milestones,
                    stage.id,
                    "mulligan-action-not-accepted",
                )
            }

            "gameplay-controls" -> {
                val input = stage.stateMachineInput
                requireStage(input.foregroundConfirmed, stage.id, "foreground-unconfirmed")
                requireStage(!input.paused, stage.id, "input-sent-while-paused")
                requireStage(input.boundarySequence > 0, stage.id, "milestone-before-boundary")
                requireStage("STEP value=MAIN_ACTION" in stage.powerLogEvents, stage.id, "gameplay-step-not-observed")
                requireStage("E2E_MILESTONE milestone=our-turn" in stage.traceEvents, stage.id, "milestone-before-boundary")
                requireStage("E2E_MILESTONE milestone=out-card" in stage.traceEvents, stage.id, "card-action-not-traceable")
                requireStage("PAUSE_ACTIVE source=F2" in stage.traceEvents, stage.id, "input-sent-while-paused")
                requireStage("RESUME_ACTIVE source=F1" in stage.traceEvents, stage.id, "input-sent-while-paused")
                requireStage(
                    stage.controlTransitions == listOf(
                        ControlTransition("F2", beforePaused = false, afterPaused = true),
                        ControlTransition("F1", beforePaused = true, afterPaused = false),
                    ),
                    stage.id,
                    "input-sent-while-paused",
                )
                requireStage(
                    stage.expectedOutput.milestones.containsAll(listOf("mulligan", "our-turn", "out-card")),
                    stage.id,
                    "card-action-not-traceable",
                )
                requireStage("END_TURN_GUARD" in input.actions, stage.id, "end-turn-before-action-scan")
            }

            "terminal-wait" -> {
                requireStage(stage.stateMachineInput.boundarySequence > 0, stage.id, "result-before-milestones")
                requireStage(stage.stateMachineInput.milestones.isNotEmpty(), stage.id, "result-before-milestones")
                requireStage(
                    stage.powerLogEvents.any { it.contains("PLAYSTATE value=CONCEDED") || it.contains("PLAYSTATE value=LOST") || it.contains("PLAYSTATE value=WON") },
                    stage.id,
                    "terminal-not-authoritative",
                )
                requireStage("E2E_GAME_RESULT_REJECTED" in stage.traceEvents, stage.id, "terminal-wait-bypassed")
                requireStage(stage.expectedOutput.state == "COMPLETE", stage.id, "terminal-wait-bypassed")
                requireStage(stage.expectedOutput.terminalObserved, stage.id, "terminal-not-authoritative")
                requireStage(!stage.expectedOutput.dispatchAllowed, stage.id, "terminal-wait-bypassed")
            }

            else -> error("offline replay stage '${stage.id}' is not routed")
        }
    }

    private inline fun <reified T> readResourceJson(path: String): T =
        mapper.readValue(resourceText(path))

    private fun resourceText(path: String): String =
        requireNotNull(OfflineE2EReplayEngine::class.java.classLoader.getResourceAsStream(path)) {
            "offline replay resource missing: $path"
        }.bufferedReader().use { it.readText() }

    private fun requireStage(condition: Boolean, stageId: String, reason: String) {
        if (!condition) {
            error("offline replay stage '$stageId' failed: $reason")
        }
    }
}

data class ReplayManifest(
    val schemaVersion: Int,
    val fixtureId: String,
    val sourceRef: String,
    val upstreamRef: String,
    val playerGameId: String,
    val stages: List<ReplayStage>,
)

data class ReplayStage(
    val id: String,
    val screenshot: ScreenshotRef,
    val ocr: OcrRef,
    val powerLogFragment: String,
    val powerLogEvents: List<String>,
    val traceEvents: List<String>,
    val stateMachineInput: ReplayStateInput,
    val expectedOutput: ReplayExpectedOutput,
    val controlTransitions: List<ControlTransition> = emptyList(),
    val allowedActions: List<String>,
    val forbiddenActions: List<String>,
    val failureReasons: List<String>,
)

data class ScreenshotRef(
    val manifest: String,
    val imagePath: String?,
    val captureRequired: Boolean,
)

data class ScreenshotManifest(
    val stage: String,
    val path: String?,
    val sourceEvidence: String?,
    val retained: Boolean,
    val captureRequired: Boolean,
    val note: String,
)

data class OcrRef(
    val path: String,
    val provider: String,
    val status: String,
    val text: String,
    val confidence: Int,
)

data class OcrObservation(
    val provider: String,
    val status: String,
    val text: String,
    val confidence: Int,
)

data class ReplayStateInput(
    val state: String,
    val powerLogPath: String?,
    val baselineOffset: Long,
    val listenerAttached: Boolean,
    val foregroundConfirmed: Boolean,
    val paused: Boolean,
    val boundarySequence: Int,
    val milestones: List<String>,
    val actions: List<String>,
)

data class ReplayExpectedOutput(
    val state: String,
    val powerLogPath: String?,
    val baselineOffset: Long,
    val listenerAttached: Boolean,
    val foregroundConfirmed: Boolean,
    val paused: Boolean,
    val boundarySequence: Int,
    val milestones: List<String>,
    val terminalObserved: Boolean,
    val dispatchAllowed: Boolean,
) {
    fun toRuntimeState() = ReplayRuntimeState(
        state = state,
        powerLogPath = powerLogPath,
        baselineOffset = baselineOffset,
        listenerAttached = listenerAttached,
        foregroundConfirmed = foregroundConfirmed,
        paused = paused,
        boundarySequence = boundarySequence,
        milestones = milestones,
        terminalObserved = terminalObserved,
        dispatchAllowed = dispatchAllowed,
    )
}

data class ControlTransition(
    val key: String,
    val beforePaused: Boolean,
    val afterPaused: Boolean,
)

data class ReplayRuntimeState(
    val state: String,
    val powerLogPath: String?,
    val baselineOffset: Long,
    val listenerAttached: Boolean,
    val foregroundConfirmed: Boolean,
    val paused: Boolean,
    val boundarySequence: Int,
    val milestones: List<String>,
    val terminalObserved: Boolean,
    val dispatchAllowed: Boolean,
) {
    companion object {
        fun startup() = ReplayRuntimeState(
            state = "STARTUP",
            powerLogPath = null,
            baselineOffset = 0,
            listenerAttached = false,
            foregroundConfirmed = false,
            paused = false,
            boundarySequence = 0,
            milestones = emptyList(),
            terminalObserved = false,
            dispatchAllowed = false,
        )
    }
}

data class ReplayTransition(
    val stageId: String,
    val before: ReplayRuntimeState,
    val after: ReplayRuntimeState,
    val declaredAllowedActions: List<String>,
)
