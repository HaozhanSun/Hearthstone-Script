package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscript.ocr.OcrHealth
import club.xiaojiawei.hsscript.ocr.OcrProviderKind
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.ocr.OcrTextBridge
import club.xiaojiawei.hsscript.ocr.PaddleXOcrSettings
import club.xiaojiawei.hsscript.status.DebugScreenshotRing
import club.xiaojiawei.hsscript.status.ActionDispatchGate
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscript.statistics.Record
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.awt.Color
import java.awt.image.BufferedImage
import java.time.LocalDateTime
import javax.imageio.ImageIO

class SurrenderPolicyTest {

    @Test
    fun `persistent streaks are reconstructed in end time order after restart`() {
        val base = LocalDateTime.of(2026, 8, 28, 12, 0)
        val records = listOf(
            Record(id = 3, result = true, surrendered = false, endTime = base.plusMinutes(3)),
            Record(id = 1, result = true, surrendered = false, endTime = base.plusMinutes(1)),
            Record(id = 2, result = true, surrendered = false, endTime = base.plusMinutes(2)),
            Record(id = 4, result = true, surrendered = false, endTime = base.plusMinutes(4)),
        )

        assertEquals(
            PersistentStreakSnapshot(consecutiveSurrenders = 0, consecutiveWins = 4),
            SurrenderPolicy.persistentStreakSnapshot(records),
        )
        assertEquals(
            "consecutive-wins-over-five",
            SurrenderPolicy.evaluatePersistentStreakGuard(
                PersistentStreakSnapshot(consecutiveSurrenders = 0, consecutiveWins = 5),
            )?.ruleId,
        )
    }

    @Test
    fun `seven persisted surrenders block the eighth automatic surrender`() {
        val base = LocalDateTime.of(2026, 8, 28, 13, 0)
        val records = (1..7).map { index ->
            Record(
                id = index,
                result = false,
                surrendered = true,
                endTime = base.plusMinutes(index.toLong()),
            )
        }

        val snapshot = SurrenderPolicy.persistentStreakSnapshot(records)
        assertEquals(7, snapshot.consecutiveSurrenders)
        assertEquals(0, snapshot.consecutiveWins)
        assertEquals(
            "consecutive-surrenders-over-seven",
            SurrenderPolicy.evaluatePersistentStreakGuard(snapshot)?.ruleId,
        )
    }

    @Test
    fun streakGuardRequestsSurrenderAfterFiveWinsAndBlocksAfterSevenSurrenders() {
        val winDecision = SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 0, consecutiveWins = 5),
        )
        assertTrue(winDecision!!.shouldSurrender)
        assertFalse(winDecision.blocksAutomaticSurrender)

        val surrenderDecision = SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 7, consecutiveWins = 0),
        )
        assertFalse(surrenderDecision!!.shouldSurrender)
        assertTrue(surrenderDecision.blocksAutomaticSurrender)
    }

    @Test
    fun `never surrender bypasses five-win request but preserves seven-surrender block`() {
        val fiveWins = SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 0, consecutiveWins = 5),
        )!!
        val sevenSurrenders = SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 7, consecutiveWins = 0),
        )!!

        assertNull(SurrenderPolicy.applyNeverSurrenderStreakPolicy(fiveWins, neverSurrenderEnabled = true))
        assertEquals(
            sevenSurrenders,
            SurrenderPolicy.applyNeverSurrenderStreakPolicy(sevenSurrenders, neverSurrenderEnabled = true),
        )
        assertTrue(
            SurrenderPolicy.applyNeverSurrenderStreakPolicy(sevenSurrenders, neverSurrenderEnabled = true)
                ?.blocksAutomaticSurrender == true,
        )
    }

    @Test
    fun `non surrender loss and unknown legacy flag reset streaks`() {
        val base = LocalDateTime.of(2026, 8, 28, 14, 0)
        val records = listOf(
            Record(id = 1, result = true, surrendered = false, endTime = base.plusMinutes(1)),
            Record(id = 2, result = true, surrendered = false, endTime = base.plusMinutes(2)),
            Record(id = 3, result = false, surrendered = false, endTime = base.plusMinutes(3)),
            Record(id = 4, result = false, surrendered = true, endTime = base.plusMinutes(4)),
            Record(id = 5, result = false, surrendered = null, endTime = base.plusMinutes(5)),
            Record(id = 6, result = false, surrendered = true, endTime = base.plusMinutes(6)),
        )

        assertEquals(
            PersistentStreakSnapshot(consecutiveSurrenders = 1, consecutiveWins = 0),
            SurrenderPolicy.persistentStreakSnapshot(records),
        )
        assertNull(
            SurrenderPolicy.evaluatePersistentStreakGuard(
                PersistentStreakSnapshot(consecutiveSurrenders = 1, consecutiveWins = 0),
            ),
        )
    }

    @Test
    fun surrenderStateRequiresGameplayOrActiveWar() {
        assertFalse(SurrenderPolicy.hasConfirmedGameState(null, false))
        assertFalse(SurrenderPolicy.hasConfirmedGameState(ModeEnum.HUB, false))
        assertTrue(SurrenderPolicy.hasConfirmedGameState(ModeEnum.GAMEPLAY, false))
        assertTrue(SurrenderPolicy.hasConfirmedGameState(null, true))
    }

    @Test
    fun originalHunterHeroIsEligible() {
        val result = SurrenderPolicy.evaluateOpponentHeroName("雷克萨")

        assertTrue(result.matched)
        assertFalse(result.shouldSurrender)
    }

    @Test
    fun originalHeroFullLocalizedNameIsEligible() {
        val result = SurrenderPolicy.evaluateOpponentHeroName("玛法里奥·怒风")

        assertTrue(result.matched)
        assertFalse(result.shouldSurrender)
    }

    @Test
    fun defaultDeathKnightLichKingIsEligibleByName() {
        val result = SurrenderPolicy.evaluateOpponentHeroName("巫妖王")

        assertTrue(result.matched)
        assertFalse(result.shouldSurrender)
    }

    @Test
    fun defaultDeathKnightLichKingIsEligibleByStableCardId() {
        val result = SurrenderPolicy.evaluateOpponentHero("localized-name-not-needed", "HERO_11")

        assertTrue(result.matched)
        assertFalse(result.shouldSurrender)
    }

    @Test
    fun nonOriginalHeroIsRejected() {
        val result = SurrenderPolicy.evaluateOpponentHeroName("死亡猎手雷克萨")

        assertFalse(result.matched)
        assertTrue(result.shouldSurrender)
    }

    @Test
    fun unresolvedHeroWaitsWithoutSurrender() {
        val result = SurrenderPolicy.evaluateOpponentHeroName("UNKNOWN ENTITY [cardType=INVALID]")

        assertFalse(result.matched)
        assertFalse(result.shouldSurrender)
        assertEquals("opponent-hero-name-not-resolved", result.reason)
    }

    @Test
    fun resolvedNonOriginalHeroRequestsSurrenderBeforeMulligan() {
        val war = warWithRivalHero("星界雪怒")

        SurrenderPolicy.resetForNewGame()
        val result = SurrenderPolicy.evaluateOpponentHeroBeforeMulligan(war)

        assertTrue(result != null)
        assertTrue(result!!.shouldSurrender)
        assertFalse(result.matched)
    }

    @Test
    fun resolvedOriginalHeroContinuesBeforeMulligan() {
        val war = warWithRivalHero("雷克萨")

        SurrenderPolicy.resetForNewGame()
        val result = SurrenderPolicy.evaluateOpponentHeroBeforeMulligan(war)

        assertTrue(result == null)
    }

    @Test
    fun originalHeroAtFortyHealthDoesNotTriggerLegacyHealthSurrender() {
        val war = warWithRivalHero("加尔鲁什").apply {
            rival.playArea.hero!!.health = 40
            rival.playArea.hero!!.damage = 0
        }

        assertNull(SurrenderPolicy.evaluateTurnStart(war))
    }

    @Test
    fun rankOcrParserAcceptsPlainAndLocalizedRankText() {
        assertEquals(10, CurrentRankDetector.parseRankText("白银10"))
        assertEquals(9, CurrentRankDetector.parseRankText("当前等级：9"))
        assertEquals(8, CurrentRankDetector.parseRankText("\uFF18"))
        assertEquals(8, CurrentRankDetector.parseRankText("商8"))
    }

    @Test
    fun rankOcrParserRejectsUnrelatedOrInvalidNumbers() {
        assertNull(CurrentRankDetector.parseRankText("Kenneth Sun"))
        assertNull(CurrentRankDetector.parseRankText("laz8"))
        assertNull(CurrentRankDetector.parseRankText("laz 8"))
        assertNull(CurrentRankDetector.parseRankText("等级：8，名字：laz8"))
        assertNull(CurrentRankDetector.parseRankText("等级：11"))
        assertNull(CurrentRankDetector.parseRankText("等级：0"))
        assertNull(CurrentRankDetector.parseRankText("01404"))
        assertNull(CurrentRankDetector.parseRankText("8 9"))
        assertNull(CurrentRankDetector.parseRankText(""))
    }

    @Test
    fun rankTierParserRecognizesLocalizedLeagueNames() {
        assertEquals(CurrentRankDetector.RankTier.SILVER, CurrentRankDetector.parseTierText("白银10"))
        assertEquals(CurrentRankDetector.RankTier.GOLD, CurrentRankDetector.parseTierText("黄金10"))
        assertEquals(CurrentRankDetector.RankTier.BRONZE, CurrentRankDetector.parseTierText("青铜9"))
        assertEquals(CurrentRankDetector.RankTier.UNKNOWN, CurrentRankDetector.parseTierText("Kenneth Sun"))
    }

    @Test
    fun rankResolverUsesTheHighestConfidenceCandidate() {
        assertEquals(
            1,
            CurrentRankDetector.resolveRankCandidates(listOf("1", "", "10", "1")),
        )
        assertEquals(
            8,
            CurrentRankDetector.resolveRankCandidates(listOf("8", "8", "10")),
        )
    }

    @Test
    fun rankResolverUsesTheBestNumericCandidateFromAVisualHint() {
        assertEquals(
            7,
            CurrentRankDetector.resolveRankCandidates(listOf("7", "7", "7"), visualTenHint = true),
        )
        assertEquals(
            2,
            CurrentRankDetector.resolveRankCandidates(listOf("", "2", "", ""), visualTenHint = true),
        )
        assertEquals(
            1,
            CurrentRankDetector.resolveRankCandidates(listOf("9", "1", "", "1"), visualTenHint = true),
        )
    }

    @Test
    fun rankResolverDoesNotInventConfidenceWithoutNativeScore() {
        val candidate = CurrentRankDetector.resolveRankCandidate(listOf("9", "1", "", "1"))

        assertTrue(candidate != null)
        assertEquals(1, candidate!!.rank)
        assertNull(candidate.confidence)
    }

    @Test
    fun rankResolverAcceptsVisualTenHintWhenBadgeArtworkCreatesOnlyNoise() {
        // Captured from the real 1920x1080 rank-10 badge. The broad badge
        // crop included the portrait and shield ornament, so OCR returned
        // invalid multi-digit noise instead of the visible 10.
        assertEquals(
            10,
            CurrentRankDetector.resolveRankCandidates(
                listOf("939", "51", "191", "91", "", ""),
                visualTenHint = true,
            ),
        )
    }

    @Test
    fun `historical rank OCR samples stay numeric-only and fail closed`() {
        // 2026-08-31 PaddleX sample: the badge was visible, but the old wide
        // ROI returned the username-like text "Ke恶魔".
        assertNull(CurrentRankDetector.parseRankText("Ke恶魔"))
        // 2026-09-02 legacy sample: the clean numeric OCR result is valid.
        assertEquals(10, CurrentRankDetector.parseRankText("10"))
        // 2026-09-03 PaddleX sample: a localized prefix is allowed when the
        // only numeric token is an in-range rank.
        assertEquals(8, CurrentRankDetector.parseRankText("商8"))
        assertNull(CurrentRankDetector.parseRankText("等级：8，名字：laz8"))
        assertNull(CurrentRankDetector.parseRankText("8 9"))
    }

    @Test
    fun `rank OCR recognizes ten real screenshots when fixture directory is configured`() {
        val fixtureFiles = System.getProperty("rank.screenshot.files")
            ?.split(File.pathSeparator)
            ?.filter(String::isNotBlank)
            ?.map { Path.of(it) }
        val fixtureDirectory = System.getProperty("rank.screenshot.dir")
        if (fixtureFiles == null && fixtureDirectory == null) return

        val files = fixtureFiles ?: Files.list(Path.of(fixtureDirectory!!)).use { stream ->
            stream
                .filter { it.toString().lowercase().endsWith(".png") }
                .sorted()
                .toList()
        }
        val requestedCount = System.getProperty("rank.screenshot.limit")?.toIntOrNull() ?: 10
        assertTrue(files.size >= requestedCount, "Expected at least $requestedCount rank screenshots")

        // The directory also contains matching/gameplay frames where the
        // rank badge is genuinely absent.  A focused ten-file list is used
        // for the positive OCR regression; it is supplied by the test
        // command from real screenshots whose badge visibly shows 10.
        val selected = files.take(requestedCount)
        val failures = selected.map { file ->
            val detection = ImageIO.read(file.toFile())?.let {
                CurrentRankDetector.detectCapturedImage(it, saveEvidence = false)
            }
            println(
                "RANK_FIXTURE file=${file.fileName} rank=${detection?.rank ?: "null"} " +
                    "ocr=${detection?.ocrText?.ifBlank { "<empty>" } ?: "<no-detection>"}",
            )
            file.fileName.toString() to detection
        }.filter { (_, detection) -> detection?.rank != 10 }

        assertTrue(
            failures.isEmpty(),
            "Rank-10 OCR failed for: " + failures.joinToString { (file, detection) ->
                "$file -> ${detection?.rank ?: "null"} (${detection?.ocrText ?: "no detection"})"
            },
        )
    }

    @Test
    fun rankVisualHintDistinguishesTwoDigitBadgeFromSingleDigitBadge() {
        val ten = BufferedImage(144, 140, BufferedImage.TYPE_INT_RGB)
        val tenGraphics = ten.createGraphics()
        tenGraphics.color = Color.WHITE
        tenGraphics.fillRect(40, 45, 10, 35)
        tenGraphics.fillRect(56, 45, 18, 35)
        tenGraphics.dispose()
        assertTrue(CurrentRankDetector.looksLikeTwoDigitRank(ten))

        val one = BufferedImage(144, 140, BufferedImage.TYPE_INT_RGB)
        val oneGraphics = one.createGraphics()
        oneGraphics.color = Color.WHITE
        oneGraphics.fillRect(53, 45, 12, 35)
        oneGraphics.dispose()
        assertFalse(CurrentRankDetector.looksLikeTwoDigitRank(one))
    }

    @Test
    fun rankRoiMatchesKnownGood1920LayoutAndExcludesPlayerName() {
        val badge = CurrentRankDetector.rankBadgeBoundsForTest(1920, 1080)
        val expanded = CurrentRankDetector.rankExpandedBoundsForTest(1920, 1080)
        val digit = CurrentRankDetector.rankDigitBoundsForTest(1920, 1080)

        assertEquals(Rectangle(23, 941, 57, 47), badge)
        assertTrue(badge.x + badge.width <= 80)
        assertTrue(expanded.x + expanded.width <= 100)
        assertTrue(digit.x + digit.width <= 70)
        assertTrue(digit.y >= 950)
    }

    @Test
    fun paddleXRankDetectionUsesSingleSidecarPass() {
        val originalSettingsProvider = OcrRuntime.settingsProvider
        val originalBridgeFactory = OcrRuntime.paddleXBridgeFactory
        val calls = mutableListOf<String>()
        val roiSizes = mutableListOf<Pair<Int, Int>>()
        try {
            OcrRuntime.settingsProvider = {
                PaddleXOcrSettings(
                    enabled = true,
                    pythonExecutable = "python",
                    modulePath = "fake-module",
                    device = "cpu",
                    modelCachePath = "",
                    timeoutMs = 1000,
                )
            }
            OcrRuntime.paddleXBridgeFactory = {
                object : OcrTextBridge {
                    override fun recognize(image: BufferedImage, desc: String): String {
                        calls += desc
                        roiSizes += image.width to image.height
                        return "10"
                    }

                    override fun healthCheck(): OcrHealth =
                        OcrHealth(true, OcrProviderKind.PADDLEX, "ok")
                }
            }

            val screen = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
            val detection = CurrentRankDetector.detectCapturedImage(screen, saveEvidence = false)

            assertEquals(10, detection?.rank)
            assertEquals(listOf("current-rank-paddlex-badge"), calls)
            assertEquals(listOf(57 to 47), roiSizes)
        } finally {
            OcrRuntime.settingsProvider = originalSettingsProvider
            OcrRuntime.paddleXBridgeFactory = originalBridgeFactory
        }
    }

    @Test
    fun `rank evidence saves diagnostic panel for resolved and unresolved OCR`() {
        val originalSettingsProvider = OcrRuntime.settingsProvider
        val originalBridgeFactory = OcrRuntime.paddleXBridgeFactory
        val originalOutput = System.getProperty("hs.script.unknown-state.dir")
        val root = Files.createTempDirectory("rank-evidence-regression-").toFile()
        try {
            System.setProperty("hs.script.unknown-state.dir", root.absolutePath)
            var ocrText = "10"
            OcrRuntime.settingsProvider = {
                PaddleXOcrSettings(
                    enabled = true,
                    pythonExecutable = "python",
                    modulePath = "fake-module",
                    device = "cpu",
                    modelCachePath = "",
                    timeoutMs = 1000,
                )
            }
            OcrRuntime.paddleXBridgeFactory = {
                object : OcrTextBridge {
                    override fun recognize(image: BufferedImage, desc: String): String = ocrText

                    override fun healthCheck(): OcrHealth =
                        OcrHealth(true, OcrProviderKind.PADDLEX, "ok")
                }
            }

            val screen = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
            val resolved = CurrentRankDetector.detectCapturedImage(screen, saveEvidence = true)
            assertEquals(10, resolved?.rank)
            ocrText = "商8"
            val resolvedLowerRank = CurrentRankDetector.detectCapturedImage(screen, saveEvidence = true)
            assertEquals(8, resolvedLowerRank?.rank)
            ocrText = ""
            val unresolved = CurrentRankDetector.detectCapturedImage(screen, saveEvidence = true)
            assertNull(unresolved?.rank)
            val files = root.walkTopDown().filter { it.isFile && it.extension == "png" }.toList()
            assertEquals(3, files.size)
            assertEquals(2, files.count { it.name.contains("RANK_RESOLVED") })
            assertTrue(files.any { it.name.contains("UNKNOWN_FAIL_CLOSED") })
            // The image-side diagnostic keeps the extracted numeric token and
            // confidence visible, while a completely empty result remains
            // unresolved and fail-closed.
            assertTrue(files.all { ImageIO.read(it).width == 1920 })
            assertTrue(files.all { it.length() > 0 })
        } finally {
            OcrRuntime.settingsProvider = originalSettingsProvider
            OcrRuntime.paddleXBridgeFactory = originalBridgeFactory
            if (originalOutput == null) {
                System.clearProperty("hs.script.unknown-state.dir")
            } else {
                System.setProperty("hs.script.unknown-state.dir", originalOutput)
            }
            root.deleteRecursively()
        }
    }

    @Test
    fun rankTierVisualClassifierDistinguishesWarmGoldFromNeutralSilver() {
        val gold = BufferedImage(144, 140, BufferedImage.TYPE_INT_RGB)
        val goldGraphics = gold.createGraphics()
        goldGraphics.color = Color(220, 165, 65)
        goldGraphics.drawRect(6, 6, 90, 100)
        goldGraphics.drawRect(10, 10, 82, 92)
        goldGraphics.dispose()
        assertEquals(CurrentRankDetector.RankTier.GOLD, CurrentRankDetector.detectTierVisual(gold))

        val silver = BufferedImage(144, 140, BufferedImage.TYPE_INT_RGB)
        val silverGraphics = silver.createGraphics()
        silverGraphics.color = Color(185, 185, 185)
        silverGraphics.drawRect(6, 6, 90, 100)
        silverGraphics.drawRect(10, 10, 82, 92)
        silverGraphics.dispose()
        assertEquals(CurrentRankDetector.RankTier.SILVER, CurrentRankDetector.detectTierVisual(silver))
    }

    @Test
    fun legendaryBadgeClassifierRequiresWarmCenterAndRedBadgeField() {
        val legendary = BufferedImage(163, 168, BufferedImage.TYPE_INT_RGB)
        val graphics = legendary.createGraphics()
        graphics.color = Color(150, 35, 28)
        graphics.fillRect(0, 0, legendary.width, legendary.height)
        graphics.color = Color(226, 171, 61)
        graphics.fillOval(35, 25, 94, 112)
        graphics.color = Color(30, 20, 15)
        graphics.fillOval(69, 61, 26, 46)
        graphics.dispose()

        val metrics = CurrentRankDetector.legendaryVisualMetrics(legendary)
        assertTrue(metrics.confirmed, metrics.toString())
        assertEquals(CurrentRankDetector.RankTier.LEGEND, CurrentRankDetector.detectTierVisual(legendary))
    }

    @Test
    fun legendaryBadgeClassifierRejectsRedOverlayAndPlainGoldBadge() {
        val redOverlay = BufferedImage(163, 168, BufferedImage.TYPE_INT_RGB)
        val redGraphics = redOverlay.createGraphics()
        redGraphics.color = Color(190, 30, 25)
        redGraphics.fillRect(0, 0, redOverlay.width, redOverlay.height)
        redGraphics.dispose()
        assertFalse(CurrentRankDetector.detectLegendaryBadgeVisual(redOverlay))

        val gold = BufferedImage(163, 168, BufferedImage.TYPE_INT_RGB)
        val goldGraphics = gold.createGraphics()
        goldGraphics.color = Color(220, 165, 65)
        goldGraphics.fillRect(0, 0, gold.width, gold.height)
        goldGraphics.color = Color(30, 30, 30)
        goldGraphics.fillRect(35, 35, 94, 98)
        goldGraphics.dispose()
        assertFalse(CurrentRankDetector.detectLegendaryBadgeVisual(gold))
    }

    @Test
    fun activeLegendaryWatchdogScreenshotUsesBroadBadgeProbeRoi() {
        val file = File(
            "C:/Users/yzjsh/Documents/Codex/2026-08-15/for-all-these-delay-short-are-2/outputs/Hearthstone Script Beta/log/unknown-states/screen-watchdog/2026-09-04/unknown-state-20260904-131032-989-screen-watchdog-normal-surrender-retry-2f2b75b1-efa0-43a3-b113-9d1844c18d05.png",
        )
        if (!file.isFile) return
        val image = ImageIO.read(file)
        val bounds = CurrentRankDetector.rankBadgeVisualBoundsForTest(image.width, image.height)
        val badge = image.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height)
        assertEquals(CurrentRankDetector.RankTier.LEGEND, CurrentRankDetector.detectTierVisual(badge))
    }

    @Test
    fun suppliedLegendaryBadgeIsUsedAsAnOfflineFixtureWhenPresent() {
        val file = File("C:/Users/yzjsh/AppData/Local/Temp/codex-clipboard-6776757e-df10-4931-a627-2ef7a2f188b3.png")
        if (!file.isFile) return
        val image = ImageIO.read(file)
        assertTrue(image != null)
        assertEquals(CurrentRankDetector.RankTier.LEGEND, CurrentRankDetector.detectTierVisual(image))

        val closeUp = File("C:/Users/yzjsh/AppData/Local/Temp/codex-clipboard-9d4f4025-4999-4665-bda1-88b1f487adee.png")
        if (closeUp.isFile) {
            val partial = ImageIO.read(closeUp)
            assertTrue(partial != null)
            // The close-up is intentionally insufficient as a standalone
            // badge: a metal fragment must not promote a red overlay.
            assertFalse(CurrentRankDetector.detectLegendaryBadgeVisual(partial))
        }
    }

    @Test
    fun legendaryDetectionPrecedesUnknownRankFallback() {
        val detection = CurrentRankDetector.Detection(
            rank = null,
            tier = CurrentRankDetector.RankTier.LEGEND,
            ocrText = "230",
            confidence = null,
            captureBounds = Rectangle(0, 0, 57, 47),
        )
        assertTrue(SurrenderPolicy.isLegendaryDetection(detection))
        assertFalse(SurrenderPolicy.isLegendaryDetection(null))
        assertFalse(
            SurrenderPolicy.isLegendaryDetection(detection.copy(tier = CurrentRankDetector.RankTier.UNKNOWN)),
        )
    }

    @Test
    fun activeRankFrameWithoutNumberOrLegendaryRequestsSurrenderInsteadOfPausing() {
        val result = SurrenderPolicy.unresolvedRankSurrenderDecision(CurrentRankDetector.RankTier.UNKNOWN)

        assertTrue(result.shouldSurrender)
        assertFalse(result.blocksAutomaticSurrender)
        assertEquals("rank-ocr-unresolved-surrender", result.ruleId)
        assertTrue(result.reason.orEmpty().contains("without-legendary"))
    }

    @Test
    fun providerFailureStillUsesTheSeparateFailClosedDecision() {
        val result = SurrenderPolicy.unresolvedRankDecision(attempts = 3)

        assertFalse(result.shouldSurrender)
        assertTrue(result.blocksAutomaticSurrender)
    }

    @Test
    fun rankResolverUsesTheTopCandidateAndOnlyEmptyInputIsUnresolved() {
        assertEquals(1, CurrentRankDetector.resolveRankCandidates(listOf("1", "", "")))
        assertEquals(1, CurrentRankDetector.resolveRankCandidates(listOf("1", "1", "")))
        assertEquals(1, CurrentRankDetector.resolveRankCandidates(listOf("1", "1", ""), visualTenHint = true))
        assertEquals(2, CurrentRankDetector.resolveRankCandidates(listOf("2", "2", "")))
        assertEquals(9, CurrentRankDetector.resolveRankCandidates(listOf("9", "9", "19")))
    }

    @Test
    fun rankResolverUsesTheHighestCountFromAConflictingTransitionFrame() {
        assertEquals(
            4,
            CurrentRankDetector.resolveRankCandidates(
                listOf("", "2", "4", "4", "", "3"),
            ),
        )
    }

    @Test
    fun rankTenDoesNotRequestSurrender() {
        assertNull(SurrenderPolicy.evaluateCurrentRank(10))
    }

    @Test
    fun ranksFiveAndTenAreSafeRegardlessOfTier() {
        for (tier in CurrentRankDetector.RankTier.values()) {
            assertNull(SurrenderPolicy.evaluateCurrentRank(rank = 5, tier = tier))
            assertNull(SurrenderPolicy.evaluateCurrentRank(rank = 10, tier = tier))
        }
    }

    @Test
    fun silverNineRequestsSurrender() {
        val result = SurrenderPolicy.evaluateCurrentRank(
            rank = 9,
            tier = CurrentRankDetector.RankTier.SILVER,
        )
        assertTrue(result != null)
        assertTrue(result!!.shouldSurrender)
    }

    @Test
    fun winRateGuardNeedsFivePlayedGamesAndSurrendersAtOrAboveFortyFivePercent() {
        assertNull(SurrenderPolicy.evaluateWinRate(SurrenderPolicy.WinRateSnapshot(games = 4, wins = 0)))
        assertNull(SurrenderPolicy.evaluateWinRate(SurrenderPolicy.WinRateSnapshot(games = 5, wins = 2)))
        val boundary = SurrenderPolicy.evaluateWinRate(SurrenderPolicy.WinRateSnapshot(games = 20, wins = 9))
        assertTrue(boundary != null)
        assertTrue(boundary!!.shouldSurrender)
        assertEquals("win-rate-at-least-45-percent", boundary.ruleId)
        val result = SurrenderPolicy.evaluateWinRate(SurrenderPolicy.WinRateSnapshot(games = 5, wins = 3))
        assertTrue(result != null)
        assertTrue(result!!.shouldSurrender)
        assertEquals("win-rate-at-least-45-percent", result.ruleId)
        assertTrue(result!!.reason.orEmpty().contains("reached-threshold=45.0%"))
    }

    @Test
    fun winRateSnapshotCountsSurrenderedResultsForTheGuardDenominator() {
        val records = listOf(
            Record(result = true, surrendered = false),
            Record(result = true, surrendered = false),
            Record(result = true, surrendered = false),
            Record(result = false, surrendered = false),
            Record(result = true, surrendered = false),
            Record(result = false, surrendered = true),
            Record(result = false, surrendered = true),
            Record(result = false, surrendered = null),
            // Legacy rows can contain a stale true result on a local
            // concession; the policy must still treat it as a loss.
            Record(result = true, surrendered = true),
        )

        val snapshot = SurrenderPolicy.winRateSnapshotForCompletedResults(records)

        assertEquals(9, snapshot.games)
        assertEquals(4, snapshot.wins)
        assertEquals(44.44444444444444, snapshot.percent)
        assertTrue(SurrenderPolicy.evaluateWinRate(snapshot) == null)
    }

    @Test
    fun rankInspectionRequiresAnActiveWarNotJustThePreMulliganPhase() {
        assertFalse(
            SurrenderPolicy.isRankInspectionEligible(
                inWar = false,
                phase = WarPhaseEnum.FILL_DECK,
            ),
        )
        assertFalse(
            SurrenderPolicy.isRankInspectionEligible(
                inWar = true,
                phase = WarPhaseEnum.FILL_DECK,
            ),
        )
        assertFalse(
            SurrenderPolicy.isRankInspectionEligible(
                inWar = true,
                phase = WarPhaseEnum.DRAWN_INIT_CARD,
            ),
        )
        assertTrue(
            SurrenderPolicy.isRankInspectionEligible(
                inWar = true,
                phase = WarPhaseEnum.REPLACE_CARD,
            ),
        )
        assertFalse(
            SurrenderPolicy.isRankInspectionEligible(
                inWar = true,
                phase = WarPhaseEnum.GAME_OVER,
            ),
        )
    }

    @Test
    fun rankInspectionStageContractBlocksStartupHubAndTransitionFrames() {
        assertFalse(SurrenderPolicy.isRankInspectionEligible(false, WarPhaseEnum.FILL_DECK))
        assertFalse(SurrenderPolicy.isRankInspectionEligible(false, WarPhaseEnum.GAME_TURN))
        assertFalse(SurrenderPolicy.isRankInspectionEligible(true, WarPhaseEnum.FILL_DECK))
        assertFalse(SurrenderPolicy.isRankInspectionEligible(true, WarPhaseEnum.DRAWN_INIT_CARD))
        assertTrue(SurrenderPolicy.isRankInspectionEligible(true, WarPhaseEnum.REPLACE_CARD))
        assertFalse(SurrenderPolicy.isRankInspectionEligible(true, WarPhaseEnum.GAME_TURN))
    }

    @Test
    fun rankBelowTenRequestsSurrender() {
        val result = SurrenderPolicy.evaluateCurrentRank(9)

        assertTrue(result != null)
        assertTrue(result!!.shouldSurrender)
        assertEquals("current-rank-is-not-target", result.ruleId)
        assertEquals("current-rank=9 target-ranks=5,10", result.reason)
    }

    @Test
    fun everyOtherConfirmedRankRequestsSurrenderRegardlessOfTier() {
        for (rank in listOf(1, 2, 3, 4, 6, 7, 8, 9)) {
            for (tier in CurrentRankDetector.RankTier.values()) {
                val result = SurrenderPolicy.evaluateCurrentRank(rank = rank, tier = tier)
                assertTrue(result != null)
                assertTrue(result!!.shouldSurrender)
                assertEquals("current-rank-is-not-target", result.ruleId)
            }
        }
    }

    @Test
    fun rankSevenIsRejectedByNumberAloneAndDoesNotDependOnTier() {
        for (tier in CurrentRankDetector.RankTier.values()) {
            val result = SurrenderPolicy.evaluateCurrentRank(rank = 7, tier = tier)
            assertTrue(result!!.shouldSurrender)
            assertEquals("current-rank=7 target-ranks=5,10", result.reason)
        }
    }

    @Test
    fun earlyRankUnknownWaitsWithoutPauseOrSurrender() {
        val first = SurrenderPolicy.classifyRankInspection(
            rank = null,
            detectionAvailable = true,
            attempt = 1,
        )
        assertEquals(RankInspectionState.WAITING_FOR_RANK, first.state)
        assertTrue(first.wait)
        assertFalse(first.pause)

        val sidecarFailure = SurrenderPolicy.classifyRankInspection(
            rank = null,
            detectionAvailable = false,
            attempt = 2,
        )
        assertEquals("provider-failure-or-capture-failure", sidecarFailure.reason)
        assertTrue(sidecarFailure.wait)
        assertFalse(sidecarFailure.pause)
    }

    @Test
    fun exhaustedRankUnknownPausesAndNeverRequestsSurrender() {
        val decision = SurrenderPolicy.classifyRankInspection(
            rank = null,
            detectionAvailable = false,
            attempt = 3,
        )
        assertEquals(RankInspectionState.BLOCKED, decision.state)
        assertTrue(decision.pause)
        assertFalse(decision.wait)
        val action = SurrenderPolicy.unresolvedRankDecision(3)
        assertFalse(action.shouldSurrender)
        assertTrue(action.blocksAutomaticSurrender)
    }

    @Test
    fun actionDispatchGateRejectsPausedOrNonWorkingState() {
        assertFalse(ActionDispatchGate.allowedForState(paused = true, working = true))
        assertFalse(ActionDispatchGate.allowedForState(paused = false, working = false))
        assertTrue(ActionDispatchGate.allowedForState(paused = false, working = true))
    }

    @Test
    fun unresolvedRankFailsClosedAndRequestsPause() {
        val previousPause = PauseStatus.isPause
        try {
            PauseStatus.isPause = false
            val result = SurrenderPolicy.blockForUnresolvedRank(attempts = 8)
            assertFalse(result.shouldSurrender)
            assertEquals("rank-ocr-unresolved", result.ruleId)
            assertTrue(PauseStatus.isPause)
        } finally {
            PauseStatus.isPause = previousPause
        }
    }

    @Test
    fun debugScreenshotRingRetainsNewestSixtyPngs() {
        val directory = Files.createTempDirectory("hs-debug-ring-test").toFile()
        try {
            repeat(65) { index ->
                val file = directory.resolve("debug-$index.png")
                file.writeBytes(byteArrayOf(1))
                file.setLastModified(index.toLong())
            }

            val retained = DebugScreenshotRing.prune(directory, 60)

            assertEquals(60, retained.size)
            assertEquals(60, directory.listFiles()!!.size)
            assertTrue(directory.resolve("debug-64.png").exists())
            assertFalse(directory.resolve("debug-4.png").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun warWithRivalHero(heroName: String): War {
        val war = War()
        war.me = Player(playerId = "1", war = war)
        war.rival = Player(playerId = "2", war = war)
        war.currentPhase = WarPhaseEnum.REPLACE_CARD
        war.rival.playArea.hero = Card(TestCardAction()).apply {
            entityName = heroName
            cardType = CardTypeEnum.HERO
            health = 30
        }
        return war
    }
}
