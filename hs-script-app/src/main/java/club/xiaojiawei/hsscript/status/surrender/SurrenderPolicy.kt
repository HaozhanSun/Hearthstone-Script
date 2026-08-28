package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.bean.isValid
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscript.status.DebugScreenshotRing
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.statistics.Record
import club.xiaojiawei.hsscript.statistics.RecordDaoEx
import club.xiaojiawei.hsscript.status.DeckStrategyManager

/**
 * The point at which a surrender rule is evaluated.
 *
 * Keeping the stage explicit makes it possible to add later checks (for
 * example, matchmaking or mulligan checks) without scattering more direct
 * GameUtil.surrender() calls through phase strategies.
 */
enum class SurrenderCheckStage {
    OPPONENT_HERO_RESOLVED,
    CURRENT_RANK_RESOLVED,
    TURN_START,
}

data class SurrenderRuleContext(
    val stage: SurrenderCheckStage,
    val rivalHeroNameRaw: String,
    val rivalHeroName: String,
    val rivalHeroNameResolved: Boolean,
    val rivalHeroCardId: String,
    val rivalPlayerName: String,
    val rivalHealth: Int?,
    val rivalArmor: Int?,
)

data class SurrenderRuleResult(
    val ruleId: String,
    val matched: Boolean,
    val shouldSurrender: Boolean,
    val reason: String? = null,
)

private data class SurrenderRule(
    val id: String,
    val evaluate: (SurrenderRuleContext) -> SurrenderRuleResult,
)

/**
 * Central registry for rules that decide whether the current live game is
 * eligible to continue.  Rules are ordered: the first rule that requests a
 * surrender wins, while every rule still emits a structured diagnostic line.
 */
object SurrenderPolicy {

    /**
     * Surrender coordinates are only safe after the client has positively
     * identified an active game. During startup/recovery Mode can briefly be
     * null; an active War is also sufficient for pre-mulligan phases.
     */
    internal fun hasConfirmedGameState(mode: ModeEnum?, inWar: Boolean): Boolean =
        mode === ModeEnum.GAMEPLAY || inWar

    private const val NAME_RESOLUTION_TIMEOUT_MS = 3_000L
    private const val NAME_RESOLUTION_POLL_MS = 100L
    private const val RANK_RETRY_INTERVAL_MS = 750L
    private const val MAX_RANK_INSPECTION_ATTEMPTS = 8
    private const val WIN_RATE_GUARD_THRESHOLD_PERCENT = 45.0
    private const val WIN_RATE_GUARD_MIN_GAMES = 5

    /**
     * Early opponent-hero checks run once per resolved identity.  Keeping the
     * state here prevents a burst of Power.log entity updates from scheduling
     * several surrender flows for the same game.
     */
    private var lastPreMulliganHeroName = ""
    private var earlySurrenderTriggered = false
    private var rankCheckCompleted = false
    private var rankInspectionAttempts = 0
    private var lastRankInspectionAt = 0L
    private var lastHeroEvidenceKey = ""

    /**
     * The ten original constructed-game hero portraits.  The value comes
     * from the localized hero entity name in Power.log, not from the rival's
     * account name. Matching is exact so a non-original skin such as
     * "死亡猎手雷克萨" cannot pass merely because it contains the base name.
     */
    private val allowedOriginalHeroNames = setOf(
        "加尔鲁什",
        "加尔鲁什·地狱咆哮",
        "萨尔",
        "瓦莉拉",
        "瓦莉拉·萨古纳尔",
        "乌瑟尔",
        "乌瑟尔·光明使者",
        "雷克萨",
        "玛法里奥",
        "玛法里奥·怒风",
        "古尔丹",
        "吉安娜",
        "吉安娜·普罗德摩尔",
        "安度因",
        "安度因·乌瑞恩",
        "伊利丹",
        "伊利丹·怒风",
        "巫妖王",
        "The Lich King",
        "Garrosh",
        "Thrall",
        "Valeera",
        "Uther",
        "Rexxar",
        "Malfurion",
        "Gul'dan",
        "Guldan",
        "Jaina",
        "Anduin",
        "Illidan",
    )

    // The base class hero IDs are more stable than localized names. HERO_11
    // is the default Death Knight portrait whose entity name is 巫妖王.
    private val allowedOriginalHeroCardIds = (1..11).map { "HERO_${it.toString().padStart(2, '0')}" }.toSet()

    private val turnStartRules: List<SurrenderRule> = listOf(
        SurrenderRule("rival-hero-is-original-class-hero") { context ->
            if (!context.rivalHeroNameResolved) {
                SurrenderRuleResult(
                        ruleId = "rival-hero-is-original-class-hero",
                        matched = false,
                        shouldSurrender = false,
                        reason = "opponent-hero-name-not-resolved",
                )
            } else {
                val matched = allowedOriginalHeroNames.any { heroName ->
                    context.rivalHeroName.equals(heroName, ignoreCase = true)
                }
                SurrenderRuleResult(
                    ruleId = "rival-hero-is-original-class-hero",
                    matched = matched,
                    shouldSurrender = !matched,
                    reason = if (matched) {
                        "opponent-hero-is-original-class-hero"
                    } else {
                        "opponent-hero-is-not-original-class-hero"
                    },
                )
            }
        },
    )

    /**
     * Reset the early identity guard when CREATE_GAME/TURN=1 starts a new
     * game.  This is intentionally separate from the rule definitions because
     * the same policy object lives for the lifetime of the application.
     */
    @Synchronized
    fun resetForNewGame() {
        lastPreMulliganHeroName = ""
        earlySurrenderTriggered = false
        rankCheckCompleted = false
        rankInspectionAttempts = 0
        lastRankInspectionAt = 0L
        lastHeroEvidenceKey = ""
    }

    /**
     * Evaluate the rival hero as soon as the live model has a resolved hero
     * entity during the pre-mulligan phases.  Unknown/placeholder names are
     * ignored here: an early surrender is safe only after the portrait's
     * identity is positively available.
     */
    @Synchronized
    fun evaluateOpponentHeroBeforeMulligan(war: War): SurrenderRuleResult? {
        if (System.getProperty("hs.script.e2e.skip-surrender-policy") == "true") {
            return null
        }
        if (war.currentPhase !in setOf(
                WarPhaseEnum.FILL_DECK,
                WarPhaseEnum.DRAWN_INIT_CARD,
                WarPhaseEnum.REPLACE_CARD,
            )
        ) {
            return null
        }
        // Player mapping determines which hero is actually the opponent. Do
        // not inspect the default UNKNOWN_PLAYER placeholder.
        if (!war.me.isValid() || !war.rival.isValid()) return null

        val rivalHero = war.rival.playArea.hero ?: return null
        val rawHeroName = rivalHero.entityName.trim()
        val heroCardId = rivalHero.cardId.trim()
        if (!isResolvedOpponentHeroName(rawHeroName)) {
            captureHeroEvidence(
                stage = SurrenderCheckStage.OPPONENT_HERO_RESOLVED,
                rawName = rawHeroName,
                normalizedName = normalizeOpponentHeroName(rawHeroName),
                cardId = heroCardId,
                reason = "opponent-hero-name-not-resolved",
            )
            log.warn {
                "SURRENDER_CHECK stage=${SurrenderCheckStage.OPPONENT_HERO_RESOLVED.name} " +
                    "rule=rival-hero-is-original-class-hero rivalHeroRaw=${rawHeroName.ifBlank { "<blank>" }} " +
                    "rivalHero=${normalizeOpponentHeroName(rawHeroName).ifBlank { "<blank>" }} " +
                    "cardId=${heroCardId.ifBlank { "<blank>" }} heroResolved=false action=WAIT " +
                    "reason=opponent-hero-name-not-resolved"
            }
            return null
        }

        val normalizedHeroName = normalizeOpponentHeroName(rawHeroName)
        if (normalizedHeroName.equals(lastPreMulliganHeroName, ignoreCase = true)) {
            return null
        }
        lastPreMulliganHeroName = normalizedHeroName

        val result = evaluateOpponentHero(rawHeroName, heroCardId)
        val context = SurrenderRuleContext(
            stage = SurrenderCheckStage.OPPONENT_HERO_RESOLVED,
            rivalHeroNameRaw = rawHeroName,
            rivalHeroName = normalizedHeroName,
            rivalHeroNameResolved = true,
            rivalHeroCardId = heroCardId,
            rivalPlayerName = war.rival.gameId.trim(),
            rivalHealth = rivalHero.health - rivalHero.damage,
            rivalArmor = rivalHero.armor,
        )
        log.info {
            "SURRENDER_CHECK stage=${context.stage.name} rule=${result.ruleId} " +
                "rivalHeroRaw=${context.rivalHeroNameRaw} " +
                "rivalHero=${context.rivalHeroName} heroResolved=true " +
                "cardId=${context.rivalHeroCardId.ifBlank { "<blank>" }} " +
                "rivalPlayer=${context.rivalPlayerName.ifBlank { "<blank>" }} " +
                "matched=${result.matched} action=" +
                "${if (result.shouldSurrender) "SURRENDER" else "CONTINUE"} " +
                "reason=${result.reason ?: "none"}"
        }

        if (!result.shouldSurrender || earlySurrenderTriggered) return null

        captureHeroEvidence(
            stage = context.stage,
            rawName = context.rivalHeroNameRaw,
            normalizedName = context.rivalHeroName,
            cardId = context.rivalHeroCardId,
            reason = result.reason ?: "policy-requested-surrender",
        )

        earlySurrenderTriggered = true
        log.warn {
            "SURRENDER_POLICY_TRIGGERED stage=${context.stage.name} " +
                "rule=${result.ruleId} rivalHero=${context.rivalHeroName} " +
                "rivalPlayer=${context.rivalPlayerName.ifBlank { "<blank>" }} " +
                "timing=before-mulligan"
        }
        return result
    }

    /**
     * The rank gate is the primary policy: Silver 10 is the floor, so Silver
     * 9..1 and every tier above Silver surrender before mulligan. The old
     * 45% win-rate gate is a secondary insurance and is evaluated from known
     * non-surrendered games for the selected strategy. It cannot replace a
     * rank decision, and a small/empty sample is ignored.
     */
    @Synchronized
    fun evaluateCurrentRankBeforeMulligan(): SurrenderRuleResult? {
        if (System.getProperty("hs.script.e2e.skip-surrender-policy") == "true") return null
        val phase = WAR.currentPhase
        if (!isRankInspectionEligible(WarEx.inWar, phase)) {
            if (phase == WarPhaseEnum.FILL_DECK || !WarEx.inWar) {
                log.debug {
                    "RANK_POLICY_SKIP reason=rank-screen-not-ready inWar=${WarEx.inWar} phase=${phase.name}"
                }
            }
            return null
        }
        if (rankCheckCompleted) return null

        val now = System.currentTimeMillis()
        if (now - lastRankInspectionAt < RANK_RETRY_INTERVAL_MS) return null
        lastRankInspectionAt = now
        rankInspectionAttempts++

        val detection = CurrentRankDetector.detect()
        val rank = detection?.rank
        if (rank == null) {
            evaluateWinRateGuard()?.let { result ->
                rankCheckCompleted = true
                log.warn {
                    "WIN_RATE_POLICY_TRIGGERED stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                        "rule=${result.ruleId} reason=${result.reason} fallback=rank-unresolved"
                }
                return result
            }
            if (rankInspectionAttempts >= MAX_RANK_INSPECTION_ATTEMPTS) {
                rankCheckCompleted = true
                log.warn {
                    "RANK_POLICY_CONTINUE reason=rank-ocr-unresolved " +
                        "attempts=$rankInspectionAttempts"
                }
            }
            return null
        }

        rankCheckCompleted = true
        val result = evaluateCurrentRank(rank, detection.tier) ?: run {
            evaluateWinRateGuard()?.let { winRateResult ->
                rankCheckCompleted = true
                log.warn {
                    "WIN_RATE_POLICY_TRIGGERED stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                        "rule=${winRateResult.ruleId} reason=${winRateResult.reason} " +
                        "rank=$rank tier=${detection.tier.name}"
                }
                return winRateResult
            }
            log.info {
                "RANK_POLICY_CONTINUE stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                    "rank=$rank tier=${detection.tier.name} reason=rank-is-safe-and-win-rate-guard-clear"
            }
            return null
        }
        log.warn {
            "SURRENDER_POLICY_TRIGGERED stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
            "rank=$rank tier=${detection.tier.name} rule=${result.ruleId} reason=${result.reason}"
        }
        return result
    }

    internal fun evaluateCurrentRank(
        rank: Int,
        tier: CurrentRankDetector.RankTier = CurrentRankDetector.RankTier.UNKNOWN,
    ): SurrenderRuleResult? {
        if (tier.order > CurrentRankDetector.RankTier.SILVER.order) {
            return SurrenderRuleResult(
                ruleId = "current-tier-above-silver-10",
                matched = false,
                shouldSurrender = true,
                reason = "current-tier=${tier.name.lowercase()}-rank=$rank",
            )
        }
        if (tier === CurrentRankDetector.RankTier.BRONZE) return null
        if (rank !in 1..10 || rank == 10) return null
        return SurrenderRuleResult(
            ruleId = "current-rank-is-not-10",
            matched = false,
            shouldSurrender = true,
            reason = "current-rank=$rank",
        )
    }

    data class WinRateSnapshot(
        val games: Int,
        val wins: Int,
    ) {
        val percent: Double
            get() = if (games <= 0) 0.0 else wins * 100.0 / games
    }

    /** Pure policy helper kept package-visible so threshold behavior is testable. */
    internal fun evaluateWinRate(snapshot: WinRateSnapshot): SurrenderRuleResult? {
        if (snapshot.games < WIN_RATE_GUARD_MIN_GAMES) return null
        // Historical runtime behavior is a ceiling guard: once the completed,
        // non-surrendered win rate reaches 45%, prepare to surrender.  Keep the
        // boundary inclusive so 9/20 is treated exactly like the old policy.
        if (snapshot.percent < WIN_RATE_GUARD_THRESHOLD_PERCENT) return null
        return SurrenderRuleResult(
            ruleId = "win-rate-at-least-45-percent",
            matched = false,
            shouldSurrender = true,
            reason = "win-rate=${"%.2f".format(java.util.Locale.ROOT, snapshot.percent)}% " +
                "reached-threshold=${WIN_RATE_GUARD_THRESHOLD_PERCENT}% " +
                "wins=${snapshot.wins}/${snapshot.games}",
        )
    }

    /** Read only completed, explicitly non-surrendered games for the active strategy. */
    private fun evaluateWinRateGuard(): SurrenderRuleResult? = runCatching {
        val strategy = DeckStrategyManager.currentDeckStrategy ?: return null
        val strategyId = strategy.id().takeIf { it.isNotBlank() } ?: return null
        val records = RecordDaoEx.RECORD_DAO.query(Record(strategyId = strategyId))
        val played = records.filter { it.surrendered == false && it.result != null }
        val snapshot = WinRateSnapshot(
            games = played.size,
            wins = played.count { it.result == true },
        )
        val result = evaluateWinRate(snapshot)
        if (result == null) {
            log.info {
                "WIN_RATE_POLICY_CLEAR strategy=$strategyId games=${snapshot.games} " +
                    "wins=${snapshot.wins} rate=${"%.2f".format(java.util.Locale.ROOT, snapshot.percent)}% " +
                    "threshold=${WIN_RATE_GUARD_THRESHOLD_PERCENT}% minGames=$WIN_RATE_GUARD_MIN_GAMES"
            }
        }
        result
    }.getOrElse { error ->
        log.warn(error) { "WIN_RATE_POLICY_UNAVAILABLE reason=statistics-read-failed" }
        null
    }

    private val PRE_MULLIGAN_PHASES = setOf(
        WarPhaseEnum.DRAWN_INIT_CARD,
        WarPhaseEnum.REPLACE_CARD,
    )

    /**
     * Rank OCR is destructive because a resolved rank below ten immediately
     * concedes.  A phase name alone is not proof that a real game exists:
     * during deck selection, matchmaking, and initial entity creation the
     * parser can still be left at FILL_DECK while Mode/WarEx have already
     * switched to GAMEPLAY.  Only the initial-hand phases expose the stable
     * rank HUD, and they must also have an active WarEx lifecycle flag, so
     * transition-screen HUD numbers cannot become a surrender decision.
     */
    internal fun isRankInspectionEligible(inWar: Boolean, phase: WarPhaseEnum): Boolean =
        inWar && phase in PRE_MULLIGAN_PHASES

    /**
     * Evaluate all turn-start rules and return the first surrender request.
     * The hero identity is read from the live card entity.  A blank or
     * placeholder hero name is deliberately treated as ineligible: the
     * requirement is to continue only when the original hero is positively
     * identified.
     */
    fun evaluateTurnStart(war: War): SurrenderRuleResult? {
        // Test-only escape hatch for the real-input E2E harness. Normal runs
        // never set this property, so the production eligibility rules remain
        // unchanged; the harness must be able to reach card-play/attack turns
        // even when matchmaking supplies an ineligible or late OCR name.
        if (System.getProperty("hs.script.e2e.skip-surrender-policy") == "true") {
            log.info { "E2E_TEST_ONLY surrender policy bypassed for card-play/attack verification" }
            return null
        }
        val rivalHero = war.rival.playArea.hero
        val rawHeroName = awaitOpponentHeroName(rivalHero)
        val normalizedHeroName = normalizeOpponentHeroName(rawHeroName)
        val heroNameResolved = isResolvedOpponentHeroName(rawHeroName)
        val context = SurrenderRuleContext(
            stage = SurrenderCheckStage.TURN_START,
            rivalHeroNameRaw = rawHeroName,
            rivalHeroName = normalizedHeroName,
            rivalHeroNameResolved = heroNameResolved,
            rivalHeroCardId = rivalHero?.cardId?.trim().orEmpty(),
            rivalPlayerName = war.rival.gameId.trim(),
            // Health and armor are separate Hearthstone values. Keep both in
            // the diagnostic context, but do not use health as a surrender
            // shortcut: rank and hero identity are the only policy gates.
            rivalHealth = rivalHero?.let { it.health - it.damage },
            rivalArmor = rivalHero?.armor,
        )

        for (rule in turnStartRules) {
            val result = rule.evaluate(context)
            log.info {
                "SURRENDER_CHECK stage=${context.stage.name} rule=${result.ruleId} " +
                    "rivalHeroRaw=${context.rivalHeroNameRaw.ifBlank { "<blank>" }} " +
                    "rivalHero=${context.rivalHeroName.ifBlank { "<blank>" }} " +
                    "heroResolved=${context.rivalHeroNameResolved} " +
                    "cardId=${context.rivalHeroCardId.ifBlank { "<blank>" }} " +
                    "rivalPlayer=${context.rivalPlayerName.ifBlank { "<blank>" }} " +
                    "rivalHealth=${context.rivalHealth ?: "UNKNOWN"} " +
                    "rivalArmor=${context.rivalArmor ?: "UNKNOWN"} " +
                    "matched=${result.matched} action=" +
                    "${if (result.shouldSurrender) "SURRENDER" else "CONTINUE"} " +
                    "reason=${result.reason ?: "none"}"
            }
            if (result.shouldSurrender) {
                captureHeroEvidence(
                    stage = context.stage,
                    rawName = context.rivalHeroNameRaw,
                    normalizedName = context.rivalHeroName,
                    cardId = context.rivalHeroCardId,
                    reason = result.reason ?: "policy-requested-surrender",
                )
                log.warn {
                    "SURRENDER_POLICY_TRIGGERED stage=${context.stage.name} " +
                        "rule=${result.ruleId} rivalHero=${context.rivalHeroName.ifBlank { "<blank>" }} " +
                        "rivalPlayer=${context.rivalPlayerName.ifBlank { "<blank>" }}"
                }
                return result
            }
        }
        return null
    }

    internal fun evaluateOpponentHeroName(rawName: String): SurrenderRuleResult {
        return evaluateOpponentHero(rawName, "")
    }

    internal fun evaluateOpponentHero(rawName: String, cardId: String): SurrenderRuleResult {
        val normalizedName = normalizeOpponentHeroName(rawName)
        val resolved = isResolvedOpponentHeroName(rawName)
        val matchedByCardId = cardId.trim().uppercase() in allowedOriginalHeroCardIds
        val matchedByName = allowedOriginalHeroNames.any { heroName ->
            normalizedName.equals(heroName, ignoreCase = true)
        }
        val matched = resolved && (matchedByCardId || matchedByName)
        return SurrenderRuleResult(
            ruleId = "rival-hero-is-original-class-hero",
            matched = matched,
            shouldSurrender = resolved && !matched,
            reason = when {
                !resolved -> "opponent-hero-name-not-resolved"
                matched -> "opponent-hero-is-original-class-hero"
                else -> "opponent-hero-is-not-original-class-hero"
            },
        )
    }

    private fun normalizeOpponentHeroName(rawName: String): String =
        rawName.replace(Regex("#\\d+$"), "").trim()

    private fun awaitOpponentHeroName(hero: Card?): String {
        var rawName = hero?.entityName?.trim().orEmpty()
        if (isResolvedOpponentHeroName(rawName)) return rawName

        val deadline = System.nanoTime() + NAME_RESOLUTION_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(NAME_RESOLUTION_POLL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            // The hero entity is updated in-place by Power.log processing.
            rawName = hero?.entityName?.trim().orEmpty()
            if (isResolvedOpponentHeroName(rawName)) return rawName
        }
        return rawName
    }

    private fun isResolvedOpponentHeroName(rawName: String): Boolean =
        rawName.isNotBlank() &&
            !rawName.equals("UNKNOWN", ignoreCase = true) &&
            !rawName.contains("UNKNOWN HUMAN PLAYER", ignoreCase = true) &&
            !rawName.startsWith("UNKNOWN ENTITY", ignoreCase = true)

    private fun captureHeroEvidence(
        stage: SurrenderCheckStage,
        rawName: String,
        normalizedName: String,
        cardId: String,
        reason: String,
    ) {
        val key = "${stage.name}|$rawName|$normalizedName|$cardId|$reason"
        if (key == lastHeroEvidenceKey) return
        lastHeroEvidenceKey = key
        DebugScreenshotRing.capture(
            event = "opponent-hero-detection",
            reason = "stage=${stage.name};raw=${rawName.ifBlank { "<blank>" }};" +
                "normalized=${normalizedName.ifBlank { "<blank>" }};cardId=${cardId.ifBlank { "<blank>" }};" +
                "decision=$reason",
        )
    }
}
