package club.xiaojiawei.hsscript.status

import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CardBackRewardOverlayRecoveryTest {
    @Test
    fun `v746 card back reward takes the result fast overlay branch before Event Quest`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream(FIXTURE)).use { it.readBytes() }
        assertEquals(FIXTURE_SHA256, sha256(bytes))
        val image = ImageIO.read(bytes.inputStream())
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)

        val evidence = CardBackRewardOverlayVisualClassifier.inspect(image)
        assertTrue(evidence.isCardBackReward, "card-back reward signature must be structural: $evidence")
        assertTrue(
            StartupQuestOverlayVisualClassifier.inspect(image).isQuestOverlay,
            "the deployed broad Event Quest geometry is the false-positive this fixture records",
        )
        assertFalse(StartupQuestOverlayVisualClassifier.isQuestOverlay(image),
            "the current structural classifier must keep the card-back reward out of the Event Quest identity")
        assertFalse(ScreenWatchdog.hasVerifiedGameMenuOverlay(image),
            "the production result observers must reach the fast overlay decision for this fixture")
        assertTrue(UpstreamScreenStateRecovery.continueControlVisibleForFixture(image))
        assertTrue(ScreenStateRecovery.continueControlVisibleForFixture(image))
        assertEquals("CARD_BACK_REWARD", UpstreamScreenStateRecovery.classifyFastResultOverlayForTest(image))
        assertEquals("CARD_BACK_REWARD", ScreenStateRecovery.classifyFastResultOverlayForTest(image))

        val quest = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/event-quest-completion-popup-20261030.png",
        )))
        assertTrue(StartupQuestOverlayVisualClassifier.isQuestOverlay(quest))
        assertFalse(CardBackRewardOverlayVisualClassifier.isCardBackReward(quest),
            "a genuine Event Quest popup must remain outside the card-back result signature")
        assertEquals("EVENT_QUEST", UpstreamScreenStateRecovery.classifyFastResultOverlayForTest(quest))
        assertEquals(UpstreamScreenStateRecovery.classifyFastResultOverlayForTest(quest),
            ScreenStateRecovery.classifyFastResultOverlayForTest(quest))

        val rankProgression = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/result-flow/incident-rank-progress-2-20261021-124207.png",
        )))
        assertFalse(CardBackRewardOverlayVisualClassifier.isCardBackReward(rankProgression))
        assertEquals(null, UpstreamScreenStateRecovery.classifyFastResultOverlayForTest(rankProgression))
        assertEquals(null, ScreenStateRecovery.classifyFastResultOverlayForTest(rankProgression))

    }

    @Test
    fun `card back observation drives bounded Continue and reconciles on fresh destination`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(FIXTURE)))
        val capturedAtNanos = System.nanoTime()
        val cardBackObservation = requireNotNull(
            UpstreamScreenStateRecovery.observeFastResultOverlayForTest(image, capturedAtNanos),
        )
        assertTrue(cardBackObservation.resultVisible == true)
        assertEquals(capturedAtNanos, cardBackObservation.capturedAtNanos)
        assertEquals(
            cardBackObservation,
            ScreenStateRecovery.observeFastResultOverlayForTest(image, capturedAtNanos),
            "both real result observers must use the same card-back decision and bounded Continue observation",
        )
        assertTrue(cardBackObservation.visualOnlyResultEvidence)
        assertFalse(cardBackObservation.rankProgressVisible)
        assertTrue(cardBackObservation.continueControlVisible)
        assertTrue(cardBackObservation.gameMenuOverlayVisible)
        assertFalse(TerminalResultMenuOverlayPolicy.shouldBlockContinue(cardBackObservation),
            "the modal flag still permits only the visible result Continue path")

        val observerExecutor = Executors.newSingleThreadScheduledExecutor()
        val inputExecutor = Executors.newSingleThreadScheduledExecutor()
        val observations = AtomicInteger()
        val dispatches = AtomicInteger()
        val dispatched = CountDownLatch(1)
        val reconciled = CountDownLatch(1)
        val controller = TerminalResultSequenceController(
            observerExecutor = observerExecutor,
            inputExecutor = inputExecutor,
            initialInputCount = 0,
            initialRetryIntervalMillis = 0L,
            retryIntervalMillis = { 60_000L },
            observerIntervalMillis = 20L,
            monitorIntervalMillis = 10L,
            maxDurationMillis = 5_000L,
            observe = {
                if (observations.getAndIncrement() == 0) {
                    cardBackObservation
                } else {
                    ResultScreenObservation(
                        resultVisible = false,
                        captureAuthorized = true,
                        destinationScreen = "DECK_SELECTION",
                        destinationConfidence = 95,
                    )
                }
            },
            terminalProofValid = { true },
            paused = { false },
            sameGame = { true },
            dispatchContinue = { page, attempt, _, markDispatch ->
                assertEquals(TerminalResultSequenceController.Page.DEFEAT, page)
                assertEquals(1, attempt)
                dispatches.incrementAndGet()
                markDispatch()
                dispatched.countDown()
                TerminalResultSequenceController.DispatchOutcome(attempted = true, dispatchAccepted = true)
            },
            confirmDestination = { observation ->
                assertEquals("DECK_SELECTION", observation.destinationScreen)
                true
            },
            onStopped = { _, destinationConfirmed ->
                if (destinationConfirmed) reconciled.countDown()
            },
        )
        try {
            controller.start()
            assertTrue(dispatched.await(3, TimeUnit.SECONDS), "card-back result must dispatch one bounded Continue")
            assertTrue(reconciled.await(3, TimeUnit.SECONDS), "fresh deck-selection destination must stop the sequence")
            assertEquals(1, dispatches.get())
        } finally {
            controller.cancel("test-cleanup")
            observerExecutor.shutdownNow()
            inputExecutor.shutdownNow()
        }
    }

    @Test
    fun `card back without visible Continue remains a result identity and dispatches nothing`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(FIXTURE)))
        val observation = requireNotNull(
            FastResultOverlayRecoveryPolicy.observation(
                kind = FastResultOverlayRecoveryPolicy.classify(
                    continueControlVisible = false,
                    startupQuestOverlayVisual = true,
                    image = image,
                ),
                continueControlVisible = false,
                capturedAtNanos = 77L,
            ),
        )
        assertTrue(observation.resultVisible == true)
        assertFalse(observation.continueControlVisible)
        assertFalse(observation.gameMenuOverlayVisible.not())
        assertEquals("CARD_BACK_REWARD", FastResultOverlayRecoveryPolicy.classify(
            continueControlVisible = false,
            startupQuestOverlayVisual = true,
            image = image,
        )?.name)
        assertTrue(
            TerminalResultMenuOverlayPolicy.shouldBlockContinue(observation),
            "without a fresh visible Continue band the terminal controller must issue zero input",
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02X".format(it) }

    private companion object {
        const val FIXTURE = "/offline-ocr/screen-recovery/card-back-reward-overlay-v746-20261101.png"
        const val FIXTURE_SHA256 = "60B6110F397AE2173185A4387F8497A04CCF69EB1B7F7BE14F667D7D418C88A3"
    }
}
