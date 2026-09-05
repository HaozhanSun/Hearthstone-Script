package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.ocr.OcrHealth
import club.xiaojiawei.hsscript.ocr.OcrProviderKind
import club.xiaojiawei.hsscript.ocr.OcrProviderMode
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.ocr.OcrTextBridge
import club.xiaojiawei.hsscript.ocr.PaddleXOcrException
import club.xiaojiawei.hsscript.ocr.PaddleXOcrSettings
import club.xiaojiawei.hsscript.status.surrender.CurrentRankDetector
import club.xiaojiawei.hsscript.status.surrender.SurrenderPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Offline E2E replay for the provider/rank boundary.  It loads retained
 * runtime screenshots, but never launches Hearthstone or sends input.
 */
class OfflinePaddleXOcrMulliganE2ETest {

    private companion object {
        val FIXTURE_JSON = Json { ignoreUnknownKeys = true }
    }

    private val originalSettingsProvider = OcrRuntime.settingsProvider
    private val originalBridgeFactory = OcrRuntime.paddleXBridgeFactory
    private val originalProviderModeProvider = OcrRuntime.providerModeProvider
    private val originalUnknownStateDirectory = System.getProperty("hs.script.unknown-state.dir")
    private val originalPause = PauseStatus.isPause
    private lateinit var testEvidenceDirectory: Path

    @BeforeEach
    fun isolateEvidenceOutput() {
        testEvidenceDirectory = Files.createTempDirectory("offline-paddlex-e2e-evidence-")
        System.setProperty("hs.script.unknown-state.dir", testEvidenceDirectory.toString())
        PauseStatus.isPause = false
    }

    @AfterEach
    fun restoreRuntime() {
        OcrRuntime.settingsProvider = originalSettingsProvider
        OcrRuntime.paddleXBridgeFactory = originalBridgeFactory
        OcrRuntime.providerModeProvider = originalProviderModeProvider
        if (originalUnknownStateDirectory == null) {
            System.clearProperty("hs.script.unknown-state.dir")
        } else {
            System.setProperty("hs.script.unknown-state.dir", originalUnknownStateDirectory)
        }
        PauseStatus.isPause = originalPause
        if (::testEvidenceDirectory.isInitialized) {
            testEvidenceDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replays PaddleX rank success and mulligan timeline from retained screenshot`() {
        val fixture = loadFixture()
        val image = loadImage(fixture.frame("mulligan"))
        val replay = MulliganReplay(fixture.timeline)

        assertEquals(
            listOf(
                "WAITING_FOR_RANK",
                "RANK_RETRY_1",
                "RANK_RETRY_2",
                "SAFE_BLOCK_NO_SURRENDER",
                "CONTINUE_MULLIGAN",
            ),
            fixture.timeline.map { it.expectedState },
        )
        assertTrue(fixture.timeline.first().line.contains("MULLIGAN_STATE value=INPUT"))
        assertTrue(fixture.timeline.last().line.contains("MULLIGAN_STATE value=DONE"))
        configurePaddleX { "8" }
        val result = replay.run(image, Path.of(fixture.frame("mulligan").screenshotPath))

        assertEquals(listOf("MULLIGAN_INPUT", "WAITING_FOR_RANK", "CONTINUE_MULLIGAN"), result.states)
        assertEquals(listOf("CONTINUE_MULLIGAN"), result.actions)
        assertEquals(1, result.attempts)
        assertEquals(OcrProviderKind.PADDLEX, result.provider)
        assertFalse(result.pause)
        assertEquals(5000L, result.attemptTimes.single())
        assertEquals(8, result.rank)
        assertTrue(Files.isRegularFile(result.sourceScreenshot))
        assertTrue(fixture.timeline.map { it.atMs }.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `replays Legendary tier, empty or unknown OCR, exception timeout and process exit without pause`() {
        val fixture = loadFixture()
        val image = loadImage(fixture.frame("mulligan"))
        assertEquals(CurrentRankDetector.RankTier.LEGEND, CurrentRankDetector.parseTierText("Legendary"))

        val cases = listOf(
            "empty" to { "" },
            "unknown-tier" to { "UNKNOWN" },
            "paddleocr-exception" to { error("PaddleOCR exception") },
            "sidecar-timeout" to { throw PaddleXOcrException("PaddleX OCR sidecar timed out") },
            "sidecar-process-exit" to { throw PaddleXOcrException("PaddleX sidecar exited exitCode=1") },
        )

        cases.forEach { (name, response) ->
            PauseStatus.isPause = false
            configurePaddleX(response)
            val replay = MulliganReplay(fixture.timeline, maxRetries = 3)
            val result = replay.run(image, Path.of(fixture.frame("mulligan").screenshotPath))

            assertEquals("SAFE_BLOCK_NO_SURRENDER", result.states.last(), name)
            assertEquals(listOf("SAFE_BLOCK_NO_SURRENDER"), result.actions, name)
            assertEquals(3, result.attempts, name)
            assertEquals(listOf(5000L, 12000L, 19000L), result.attemptTimes, name)
            assertEquals(OcrProviderKind.PADDLEX, result.provider, name)
            assertFalse(result.pause, name)
            assertEquals(null, result.rank, name)
            assertTrue(Files.isRegularFile(result.sourceScreenshot), name)
        }
    }

    @Test
    fun `AUTO fallback to legacy is explicit and still completes mulligan`() {
        val fixture = loadFixture()
        val image = loadImage(fixture.frame("mulligan"))
        configureRuntime(OcrProviderMode.AUTO) {
            throw PaddleXOcrException("PaddleX OCR sidecar timed out")
        }

        val result = OcrRuntime.recognizeResult(
            image = image,
            desc = "offline-mulligan-rank",
            legacyOcr = { "8" },
        )

        assertEquals("8", result.text)
        assertEquals(OcrProviderKind.LEGACY, OcrRuntime.lastProviderUsed())
        assertFalse(PauseStatus.isPause)
        assertTrue(Files.isRegularFile(Path.of(fixture.frame("mulligan").screenshotPath)))
    }

    @Test
    fun `terminal win and loss screenshots outrank surrender in replay`() {
        val fixture = loadFixture()
        val cases = listOf(
            "win" to ("胜利 点击继续" to ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECORD_WIN),
            "lost" to ("败北 点击继续" to ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECORD_LOSS),
        )

        cases.forEach { (frameId, expectedCase) ->
            val expected = expectedCase.second
            val image = loadImage(fixture.frame(frameId))
            configurePaddleX { if (frameId == "win") "胜利 点击继续" else "败北 点击继续" }
            val observation = ScreenWatchdog.inspectForSurrender(
                state = "mode=GAMEPLAY|mulliganState=WAITING_FOR_RANK",
                attempts = 3,
                trigger = "offline-terminal-$frameId",
                captureProvider = { image },
                ocrProvider = { captured ->
                    OcrRuntime.recognize(captured, "offline-terminal-$frameId") { "legacy-must-not-run" }
                },
            )

            assertEquals(expected, observation.action, frameId)
            assertEquals(OcrProviderKind.PADDLEX.name, observation.provider, frameId)
            assertTrue(
                observation.screenshotPath == null || Files.isRegularFile(Path.of(observation.screenshotPath)),
                frameId,
            )
            assertFalse(PauseStatus.isPause, frameId)
        }
    }

    @Test
    fun `unknown screenshot and capture failure stay bounded without a surrender click`() {
        val fixture = loadFixture()
        val image = loadImage(fixture.frame("unknown"))
        configurePaddleX { "画面无法判定" }

        val unknown = ScreenWatchdog.inspectForSurrender(
            state = "mode=GAMEPLAY|mulliganState=WAITING_FOR_RANK|attempts=3",
            attempts = 3,
            trigger = "offline-unknown-bounded",
            captureProvider = { image },
            ocrProvider = { captured ->
                OcrRuntime.recognize(captured, "offline-unknown-bounded") { "legacy-must-not-run" }
            },
        )
        assertEquals(ScreenWatchdogKind.UNKNOWN, unknown.kind)
        assertEquals(ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_PAUSE_UNKNOWN, unknown.action)
        assertEquals(OcrProviderKind.PADDLEX.name, unknown.provider)
        assertFalse(PauseStatus.isPause)
        assertTrue(unknown.reason.isNotBlank(), "the bounded recovery reason must be auditable")
        assertTrue(Files.isRegularFile(Path.of(fixture.frame("unknown").screenshotPath)))

        val captureFailed = ScreenWatchdog.inspectForSurrender(
            state = "mode=GAMEPLAY|mulliganState=WAITING_FOR_RANK",
            attempts = 3,
            trigger = "offline-capture-failure",
            captureProvider = { null },
            ocrProvider = { error("OCR must not run after capture failure") },
        )
        assertEquals(ScreenWatchdogKind.CAPTURE_FAILED, captureFailed.kind)
        assertEquals(ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_PAUSE_UNKNOWN, captureFailed.action)
        assertEquals(null, captureFailed.screenshotPath)
        assertFalse(PauseStatus.isPause)
    }

    private fun configurePaddleX(response: () -> String) = configureRuntime(OcrProviderMode.PADDLEX_ONLY, response)

    private fun configureRuntime(mode: OcrProviderMode, response: () -> String) {
        OcrRuntime.providerModeProvider = { mode }
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(true, "offline-python", "offline-module", "cpu", "offline-cache", 1000)
        }
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: BufferedImage, desc: String): String = response()

                override fun healthCheck(): OcrHealth =
                    OcrHealth(true, OcrProviderKind.PADDLEX, "offline-fake", "fixture")
            }
        }
    }

    private fun loadFixture(): Fixture = Fixture::class.java
        .getResourceAsStream("/offline-ocr/mulligan-e2e-fixture.json")!!
        .bufferedReader()
        .use { FIXTURE_JSON.decodeFromString(it.readText()) }

    private fun loadImage(frame: Frame): BufferedImage {
        val path = Path.of(frame.screenshotPath)
        assertTrue(Files.isRegularFile(path), "missing retained screenshot id=${frame.id} path=$path")
        val image = ImageIO.read(path.toFile())
        return image ?: error("unreadable retained screenshot id=${frame.id}")
    }

    @Serializable
    private data class Fixture(
        val schemaVersion: Int,
        val description: String,
        val frames: List<Frame>,
        val timeline: List<TimelineEvent>,
    ) {
        fun frame(id: String): Frame = frames.single { it.id == id }
    }

    @Serializable
    private data class Frame(
        val id: String,
        val screenshotPath: String,
        val sourceRunId: String,
        val role: String,
    )

    @Serializable
    private data class TimelineEvent(
        val atMs: Long,
        val line: String,
        val expectedState: String,
    )

    private data class ReplayResult(
        val states: List<String>,
        val actions: List<String>,
        val attempts: Int,
        val attemptTimes: List<Long>,
        val provider: OcrProviderKind,
        val rank: Int?,
        val pause: Boolean,
        val sourceScreenshot: Path,
    )

    private class MulliganReplay(
        private val timeline: List<TimelineEvent>,
        private val initialDelayMs: Long = 5000L,
        private val retryIntervalMs: Long = 7000L,
        private val maxRetries: Int = 1,
    ) {
        fun run(image: BufferedImage, sourceScreenshot: Path): ReplayResult {
            val states = mutableListOf("MULLIGAN_INPUT", "WAITING_FOR_RANK")
            val actions = mutableListOf<String>()
            val attemptTimes = (0 until maxRetries).map { initialDelayMs + it * retryIntervalMs }
            assertTrue(timeline.any { it.atMs == initialDelayMs && it.line.contains("RANK_OCR_ATTEMPT") })
            var detection: CurrentRankDetector.Detection? = null
            attemptTimes.forEach { _ ->
                if (detection != null) return@forEach
                detection = CurrentRankDetector.detectCapturedImage(image)
            }

            if (detection?.rank != null) {
                states += "CONTINUE_MULLIGAN"
                actions += "CONTINUE_MULLIGAN"
            } else {
                val decision = SurrenderPolicy.blockForUnresolvedRank(attemptTimes.size)
                states += "SAFE_BLOCK_NO_SURRENDER"
                actions += if (decision.shouldSurrender) "SURRENDER" else "SAFE_BLOCK_NO_SURRENDER"
            }
            return ReplayResult(
                states = states,
                actions = actions,
                attempts = attemptTimes.size,
                attemptTimes = attemptTimes,
                provider = OcrRuntime.lastProviderUsed(),
                rank = detection?.rank,
                pause = PauseStatus.isPause,
                sourceScreenshot = sourceScreenshot,
            )
        }
    }
}
