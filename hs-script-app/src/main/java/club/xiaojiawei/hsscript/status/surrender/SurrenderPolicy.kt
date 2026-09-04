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
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.statistics.Record
import club.xiaojiawei.hsscript.statistics.RecordDaoEx
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.strategy.phase.ReplaceCardPhaseStrategy
import java.time.LocalDateTime

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

enum class RankInspectionState {
    NOT_READY,
    WAITING_FOR_RANK,
    RESOLVED,
    BLOCKED,
}

enum class OpponentHeroInspectionState {
    NOT_RESOLVED,
    WAITING_FOR_HERO,
    ORIGINAL_HERO_ALLOWED,
    SURRENDER_REQUESTED,
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
    /** True when automation must stop instead of dispatching surrender. */
    val blocksAutomaticSurrender: Boolean = false,
)

data class PersistentStreakSnapshot(
    val consecutiveSurrenders: Int,
    val consecutiveWins: Int,
)

data class PersistentStreakGuard(
    val ruleId: String,
    val reason: String,
)

internal data class RankInspectionReadDecision(
    val state: RankInspectionState,
    val wait: Boolean,
    val pause: Boolean,
    val reason: String,
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
    private const val WIN_RATE_GUARD_THRESHOLD_PERCENT = 45.0
    private const val WIN_RATE_GUARD_MIN_GAMES = 5
    /** Protect the next game after seven persisted concessions. */
    private const val MAX_CONSECUTIVE_SURRENDERS = 7
    /** Protect the next game after five persisted wins in a row. */
    private const val MAX_CONSECUTIVE_WINS = 5
    /** A transient/early rank read must not pause the first eligible frame. */
    private const val MAX_RANK_INSPECTION_ATTEMPTS = 3

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
    private var rankDetectorInvocationCount = 0
    @Volatile
    private var rankInspectionState = RankInspectionState.NOT_READY
    @Volatile
    private var opponentHeroInspectionState = OpponentHeroInspectionState.NOT_RESOLVED

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
        rankDetectorInvocationCount = 0
        rankInspectionState = RankInspectionState.NOT_READY
        opponentHeroInspectionState = OpponentHeroInspectionState.NOT_RESOLVED
    }

    /**
     * Calculate terminal-result streaks from persisted records.  Sorting by
     * end time makes the result independent of database row order and means
     * the streak survives process and machine restarts.
     *
     * Unknown/legacy surrender flags break both streaks. A win is counted
     * only when it is explicitly non-surrendered, matching the win-rate guard.
     */
    internal fun persistentStreakSnapshot(records: List<Record>): PersistentStreakSnapshot {
        var consecutiveSurrenders = 0
        var consecutiveWins = 0
        val completed = records
            .filter { it.result != null }
            .sortedWith(compareBy<Record> { it.endTime ?: LocalDateTime.MIN }.thenBy { it.id ?: Int.MIN_VALUE })
        completed.forEach { record ->
            when {
                record.surrendered == true -> {
                    consecutiveSurrenders++
                    consecutiveWins = 0
                }
                record.result == true && record.surrendered == false -> {
                    consecutiveWins++
                    consecutiveSurrenders = 0
                }
                else -> {
                    consecutiveSurrenders = 0
                    consecutiveWins = 0
                }
            }
        }
        return PersistentStreakSnapshot(consecutiveSurrenders, consecutiveWins)
    }

    internal fun evaluatePersistentStreakGuard(snapshot: PersistentStreakSnapshot): PersistentStreakGuard? = when {
        snapshot.consecutiveSurrenders >= MAX_CONSECUTIVE_SURRENDERS -> PersistentStreakGuard(
            ruleId = "consecutive-surrenders-over-seven",
            reason = "consecutive-surrenders=${snapshot.consecutiveSurrenders} threshold=$MAX_CONSECUTIVE_SURRENDERS",
        )
        snapshot.consecutiveWins >= MAX_CONSECUTIVE_WINS -> PersistentStreakGuard(
            ruleId = "consecutive-wins-over-five",
            reason = "consecutive-wins=${snapshot.consecutiveWins} threshold=$MAX_CONSECUTIVE_WINS",
        )
        else -> null
    }

    /** Pure action semantics for the durable streak guard. */
    internal fun persistentStreakDecision(snapshot: PersistentStreakSnapshot): SurrenderRuleResult? {
        val guard = evaluatePersistentStreakGuard(snapshot) ?: return null
        return if (snapshot.consecutiveSurrenders >= MAX_CONSECUTIVE_SURRENDERS) {
            SurrenderRuleResult(
                ruleId = guard.ruleId,
                matched = true,
                shouldSurrender = false,
                reason = guard.reason,
                blocksAutomaticSurrender = true,
            )
        } else {
            SurrenderRuleResult(
                ruleId = guard.ruleId,
                matched = true,
                shouldSurrender = true,
                reason = guard.reason,
            )
        }
    }

    /**
     * Never Surrender intentionally bypasses the five-win protective
     * surrender, but it must never bypass the seven-surrender fail-closed
     * block. Keeping this decision pure makes that distinction testable
     * without depending on persisted configuration.
     */
    internal fun applyNeverSurrenderStreakPolicy(
        result: SurrenderRuleResult,
        neverSurrenderEnabled: Boolean,
    ): SurrenderRuleResult? = if (
        neverSurrenderEnabled && result.shouldSurrender && !result.blocksAutomaticSurrender
    ) {
        null
    } else {
        result
    }

    /**
     * Re-read durable history before any early surrender decision. Seven
     * persisted concessions block the next automatic game and pause because
     * continuing would extend the surrender streak. Five persisted wins
     * request surrender for the next game, according to the configured
     * protection rule. The evidence includes recent durable records so a
     * result/classification regression is diagnosable.
     */
    private fun enforcePersistentStreakGuard(): SurrenderRuleResult? = runCatching {
        val strategy = DeckStrategyManager.currentDeckStrategy ?: return null
        val strategyId = strategy.id().takeIf { it.isNotBlank() } ?: return null
        val records = RecordDaoEx.RECORD_DAO.query(Record(strategyId = strategyId))
        val snapshot = persistentStreakSnapshot(records)
        val guard = evaluatePersistentStreakGuard(snapshot) ?: return null
        val evidence = records
            .filter { it.result != null }
            .sortedWith(compareBy<Record> { it.endTime ?: LocalDateTime.MIN }.thenBy { it.id ?: Int.MIN_VALUE })
            .takeLast(10)
            .joinToString(",") {
                "id=${it.id ?: "?"}:result=${it.result}:surrendered=${it.surrendered}:end=${it.endTime ?: "?"}"
            }
        val decision = persistentStreakDecision(snapshot) ?: return null
        if (decision.blocksAutomaticSurrender) {
            PauseStatus.isPause = true
            log.error {
                "PERSISTENT_STREAK_GUARD_BLOCKED strategy=$strategyId rule=${guard.ruleId} " +
                    "reason=${guard.reason} consecutiveSurrenders=${snapshot.consecutiveSurrenders} " +
                    "consecutiveWins=${snapshot.consecutiveWins} action=PAUSE " +
                    "surrenderPolicyPass=BLOCKED dispatch=false evidence=$evidence source=statistics.db"
            }
            decision
        } else {
            log.warn {
                "PERSISTENT_STREAK_GUARD_TRIGGERED strategy=$strategyId rule=${guard.ruleId} " +
                    "reason=${guard.reason} consecutiveSurrenders=${snapshot.consecutiveSurrenders} " +
                    "consecutiveWins=${snapshot.consecutiveWins} action=SURRENDER " +
                    "surrenderPolicyPass=REQUESTED evidence=$evidence source=statistics.db"
            }
            decision
        }
    }.getOrElse { error ->
        persistentStreakGuardUnavailable("statistics-read-failed", error)
    }

    private fun persistentStreakGuardUnavailable(
        reason: String,
        error: Throwable? = null,
    ): SurrenderRuleResult {
        PauseStatus.isPause = true
        if (error == null) {
            log.error {
                "PERSISTENT_STREAK_GUARD_BLOCKED rule=persistent-streak-guard-unavailable " +
                    "reason=$reason action=PAUSE surrenderPolicyPass=BLOCKED dispatch=false"
            }
        } else {
            log.error(error) {
                "PERSISTENT_STREAK_GUARD_BLOCKED rule=persistent-streak-guard-unavailable " +
                    "reason=$reason action=PAUSE surrenderPolicyPass=BLOCKED dispatch=false"
            }
        }
        return SurrenderRuleResult(
            ruleId = "persistent-streak-guard-unavailable",
            matched = true,
            shouldSurrender = false,
            reason = reason,
            blocksAutomaticSurrender = true,
        )
    }

    /**
     * Never Surrender disables the five-win protective surrender, but it must
     * not disable the independent seven-concession fail-closed pause.
     */
    private fun enforcePersistentStreakGuardForCurrentPolicy(): SurrenderRuleResult? =
        enforcePersistentStreakGuard()?.let { result ->
            val applied = applyNeverSurrenderStreakPolicy(result, NeverSurrenderPolicy.enabled())
            if (applied == null) {
                log.info {
                    "SURRENDER_POLICY_BYPASS reason=never-surrender rule=${result.ruleId} " +
                        "action=CONTINUE dispatch=false queue=false retry=false replan=false"
                }
                null
            } else {
                applied
            }
        }

    /**
     * Evaluate the rival hero as soon as the live model has a resolved hero
     * entity during the pre-mulligan phases.  Unknown/placeholder names are
     * ignored here: an early surrender is safe only after the portrait's
     * identity is positively available.
     */
    @Synchronized
    fun evaluateOpponentHeroBeforeMulligan(war: War): SurrenderRuleResult? {
        enforcePersistentStreakGuardForCurrentPolicy()?.let { return it }
        if (System.getProperty("hs.script.e2e.skip-surrender-policy") == "true") {
            return null
        }
        if (NeverSurrenderPolicy.enabled()) {
            log.info { "SURRENDER_POLICY_BYPASS reason=never-surrender stage=${SurrenderCheckStage.OPPONENT_HERO_RESOLVED.name} action=CONTINUE" }
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

        val rivalHero = war.rival.playArea.hero
        if (rivalHero == null) {
            opponentHeroInspectionState = OpponentHeroInspectionState.WAITING_FOR_HERO
            log.info {
                "SURRENDER_CHECK stage=${SurrenderCheckStage.OPPONENT_HERO_RESOLVED.name} " +
                    "rule=rival-hero-is-original-class-hero heroResolved=false action=WAIT " +
                    "reason=opponent-hero-entity-not-available"
            }
            return null
        }
        val rawHeroName = rivalHero.entityName.trim()
        val heroCardId = rivalHero.cardId.trim()
        if (!isResolvedOpponentHeroName(rawHeroName)) {
            opponentHeroInspectionState = OpponentHeroInspectionState.WAITING_FOR_HERO
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
        opponentHeroInspectionState = if (result.shouldSurrender) {
            OpponentHeroInspectionState.SURRENDER_REQUESTED
        } else {
            OpponentHeroInspectionState.ORIGINAL_HERO_ALLOWED
        }
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
     * The rank gate is the primary policy: rank 5 and rank 10 are the only
     * safe targets, regardless of tier, so every other confirmed rank
     * surrenders before mulligan. The old
     * 45% win-rate gate is a secondary insurance and is evaluated from every
     * completed result for the selected strategy, including our own
     * concessions. Otherwise a win-rate-triggered surrender would never enter
     * its own denominator and the same stale percentage would trigger forever.
     * A small/empty sample is ignored.
     */
    @Synchronized
    fun evaluateCurrentRankBeforeMulligan(): SurrenderRuleResult? {
        enforcePersistentStreakGuardForCurrentPolicy()?.let { return it }
        if (System.getProperty("hs.script.e2e.skip-surrender-policy") == "true") return null
        when (opponentHeroInspectionState) {
            OpponentHeroInspectionState.ORIGINAL_HERO_ALLOWED -> Unit
            OpponentHeroInspectionState.SURRENDER_REQUESTED -> {
                log.info {
                    "RANK_POLICY_SKIP reason=opponent-hero-surrender-already-requested " +
                        "action=SKIP rankDetector=false"
                }
                return null
            }
            OpponentHeroInspectionState.NOT_RESOLVED,
            OpponentHeroInspectionState.WAITING_FOR_HERO,
            -> {
                log.info {
                    "RANK_POLICY_WAITING_FOR_OPPONENT_HERO state=$opponentHeroInspectionState " +
                        "action=WAIT rankDetector=false"
                }
                return null
            }
        }
        // Historical Power.log replay reconstructs the in-memory model but
        // does not represent the pixels of the current game.  In particular,
        // rank OCR during replay can inspect a matchmaking/mulligan frame and
        // must never produce a destructive surrender decision.
        if (PowerLogListener.replayingExistingLog) {
            log.debug { "RANK_POLICY_SKIP reason=historical-power-log-replay" }
            return null
        }
        if (!ReplaceCardPhaseStrategy.isRankInspectionReady()) {
            rankInspectionState = RankInspectionState.NOT_READY
            log.info {
                "RANK_POLICY_WAITING_FOR_RANK reason=mulligan-input-not-confirmed " +
                    "phase=${WAR.currentPhase.name} inWar=${WarEx.inWar} " +
                    "action=WAIT provider=NONE"
            }
            return null
        }
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

        rankDetectorInvocationCount++
        val detection = CurrentRankDetector.detect(
            trigger = "rank-policy-${phase.name}",
            phase = phase.name,
        )
        if (isLegendaryDetection(detection)) {
            rankCheckCompleted = true
            rankInspectionState = RankInspectionState.RESOLVED
            log.info {
                "RANK_POLICY_CONTINUE stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                    "rank=LEGENDARY tier=${detection?.tier?.name ?: "UNKNOWN"} reason=legendary-badge-confirmed " +
                    "surrender=false pause=false"
            }
            return null
        }
        val rank = detection?.rank
        if (rank == null) {
            if (detection != null) {
                rankCheckCompleted = true
                rankInspectionState = RankInspectionState.RESOLVED
                val result = unresolvedRankSurrenderDecision(detection.tier)
                log.warn {
                    "SURRENDER_POLICY_TRIGGERED stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                        "rule=${result.ruleId} reason=${result.reason} " +
                        "detectionAvailable=true tier=${detection.tier.name} action=SURRENDER pause=false"
                }
                return result
            }
            val readDecision = classifyRankInspection(
                rank = null,
                detectionAvailable = false,
                attempt = rankInspectionAttempts,
            )
            if (readDecision.wait) {
                rankInspectionState = readDecision.state
                log.warn {
                    "RANK_POLICY_WAITING_FOR_RANK stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                        "attempt=$rankInspectionAttempts maxAttempts=$MAX_RANK_INSPECTION_ATTEMPTS " +
                        "providerResult=${readDecision.reason} " +
                        "action=WAIT pause=false surrender=false"
                }
                return null
            }
            evaluateWinRateGuard()?.let { result ->
                rankCheckCompleted = true
                log.warn {
                    "WIN_RATE_POLICY_TRIGGERED stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                        "rule=${result.ruleId} reason=${result.reason} fallback=rank-unresolved"
                }
                return result
            }
            rankCheckCompleted = true
            return blockForUnresolvedRank(rankInspectionAttempts)
        }

        rankCheckCompleted = true
        rankInspectionState = RankInspectionState.RESOLVED
        if (NeverSurrenderPolicy.enabled()) {
            if (NeverSurrenderPolicy.rankIsIneligible(rank)) {
                rankInspectionState = RankInspectionState.BLOCKED
                PauseStatus.isPause = true
                log.error {
                    "RANK_POLICY_BLOCKED stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                        "rank=$rank tier=${detection.tier.name} reason=never-surrender-rank-ineligible " +
                        "action=PAUSE surrender=false dispatch=false"
                }
                return SurrenderRuleResult(
                    ruleId = "rank-is-not-target-never-surrender",
                    matched = true,
                    shouldSurrender = false,
                    reason = "current-rank=$rank target-ranks=5,10 never-surrender=true",
                    blocksAutomaticSurrender = true,
                )
            }
            log.info {
                "SURRENDER_POLICY_BYPASS stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                    "rank=$rank tier=${detection.tier.name} reason=never-surrender action=CONTINUE"
            }
            return null
        }
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

    @Suppress("UNUSED_PARAMETER")
    internal fun evaluateCurrentRank(
        rank: Int,
        tier: CurrentRankDetector.RankTier = CurrentRankDetector.RankTier.UNKNOWN,
    ): SurrenderRuleResult? {
        if (rank !in 1..10 || rank == 5 || rank == 10) return null
        return SurrenderRuleResult(
            ruleId = "current-rank-is-not-target",
            matched = false,
            shouldSurrender = true,
            reason = "current-rank=$rank target-ranks=5,10",
        )
    }

    /** Legendary is a confirmed non-numeric terminal rank, not UNKNOWN. */
    internal fun isLegendaryDetection(detection: CurrentRankDetector.Detection?): Boolean =
        detection?.rank == null && detection?.tier == CurrentRankDetector.RankTier.LEGEND

    internal fun unresolvedRankDecision(attempts: Int): SurrenderRuleResult =
        SurrenderRuleResult(
            ruleId = "rank-ocr-unresolved",
            matched = false,
            shouldSurrender = false,
            reason = "rank-ocr-unresolved attempts=$attempts",
            blocksAutomaticSurrender = true,
        )

    /**
     * An active rank frame with no valid number and no Legendary badge is
     * unsafe to play through.  Keep the decision explicit: surrender rather
     * than silently continue; provider/capture failure (detection == null)
     * still uses the separate fail-closed retry/pause path above.
     */
    internal fun unresolvedRankSurrenderDecision(tier: CurrentRankDetector.RankTier): SurrenderRuleResult =
        SurrenderRuleResult(
            ruleId = "rank-ocr-unresolved-surrender",
            matched = true,
            shouldSurrender = true,
            reason = "rank-unresolved-without-legendary tier=${tier.name} target-ranks=5,10",
        )

    internal fun classifyRankInspection(
        rank: Int?,
        detectionAvailable: Boolean,
        attempt: Int,
        maxAttempts: Int = MAX_RANK_INSPECTION_ATTEMPTS,
    ): RankInspectionReadDecision {
        if (rank != null) {
            return RankInspectionReadDecision(
                state = RankInspectionState.RESOLVED,
                wait = false,
                pause = false,
                reason = "rank-resolved",
            )
        }
        if (attempt < maxAttempts) {
            return RankInspectionReadDecision(
                state = RankInspectionState.WAITING_FOR_RANK,
                wait = true,
                pause = false,
                reason = if (detectionAvailable) "empty-or-unmapped" else "provider-failure-or-capture-failure",
            )
        }
        return RankInspectionReadDecision(
            state = RankInspectionState.BLOCKED,
            wait = false,
            pause = true,
            reason = if (detectionAvailable) "empty-or-unmapped" else "provider-failure-or-capture-failure",
        )
    }

    internal fun blockForUnresolvedRank(attempts: Int): SurrenderRuleResult {
        val result = unresolvedRankDecision(attempts)
        rankInspectionState = RankInspectionState.BLOCKED
        PauseStatus.isPause = true
        log.error {
            "RANK_POLICY_BLOCKED stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                "rule=${result.ruleId} reason=${result.reason} action=PAUSE ocrFailure=true"
        }
        return result
    }

    data class WinRateSnapshot(
        val games: Int,
        val wins: Int,
    ) {
        val percent: Double
                get() = if (games <= 0) 0.0 else wins * 100.0 / games
    }

    /**
     * Build the guard's all-completed-results snapshot without database access.
     *
     * A local concession is always a loss for this policy, even if a stale
     * WarEx.isWin value was left over from the previous game when the
     * concession was recorded.  This also repairs the denominator for legacy
     * rows written before the listener normalized surrendered results.
     */
    internal fun winRateSnapshotForCompletedResults(records: List<Record>): WinRateSnapshot {
        val completed = records.filter { it.result != null }
        return WinRateSnapshot(
            games = completed.size,
            wins = completed.count { it.result == true && it.surrendered != true },
        )
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

    /**
     * Read all completed results for the active strategy.  The statistics UI
     * can separately report non-surrendered games; this policy must count a
     * local concession as a completed loss so its own guard can decay.
     */
    private fun evaluateWinRateGuard(): SurrenderRuleResult? = runCatching {
        val strategy = DeckStrategyManager.currentDeckStrategy ?: return null
        val strategyId = strategy.id().takeIf { it.isNotBlank() } ?: return null
        val records = RecordDaoEx.RECORD_DAO.query(Record(strategyId = strategyId))
        val completed = records.filter { it.result != null }
        val played = completed.count { it.surrendered == false }
        val surrendered = completed.count { it.surrendered == true }
        val unknownSurrender = completed.count { it.surrendered == null }
        val snapshot = winRateSnapshotForCompletedResults(records)
        val result = evaluateWinRate(snapshot)
        if (result == null) {
            log.info {
                "WIN_RATE_POLICY_CLEAR strategy=$strategyId games=${snapshot.games} " +
                    "wins=${snapshot.wins} rate=${"%.2f".format(java.util.Locale.ROOT, snapshot.percent)}% " +
                    "played=$played surrendered=$surrendered unknownSurrender=$unknownSurrender " +
                    "basis=all-completed-results " +
                    "threshold=${WIN_RATE_GUARD_THRESHOLD_PERCENT}% minGames=$WIN_RATE_GUARD_MIN_GAMES"
            }
        } else {
            log.info {
                "WIN_RATE_POLICY_SNAPSHOT strategy=$strategyId games=${snapshot.games} " +
                    "wins=${snapshot.wins} rate=${"%.2f".format(java.util.Locale.ROOT, snapshot.percent)}% " +
                    "played=$played surrendered=$surrendered unknownSurrender=$unknownSurrender " +
                    "basis=all-completed-results"
            }
        }
        result
    }.getOrElse { error ->
        log.warn(error) { "WIN_RATE_POLICY_UNAVAILABLE reason=statistics-read-failed" }
        null
    }

    private val PRE_MULLIGAN_PHASES = setOf(WarPhaseEnum.REPLACE_CARD)

    /**
     * Rank OCR is destructive because a resolved rank below ten immediately
     * concedes.  A phase name alone is not proof that a real game exists:
     * during deck selection, matchmaking, and initial entity creation the
     * parser can still be left at FILL_DECK while Mode/WarEx have already
     * switched to GAMEPLAY.  Only the interactive mulligan page exposes the
     * stable rank HUD, and it must also have an active WarEx lifecycle flag, so
     * transition-screen HUD numbers cannot become a surrender decision.
     */
    internal fun isRankInspectionEligible(inWar: Boolean, phase: WarPhaseEnum): Boolean =
        inWar && phase in PRE_MULLIGAN_PHASES

    internal fun currentRankInspectionState(): RankInspectionState = rankInspectionState

    internal fun currentOpponentHeroInspectionState(): OpponentHeroInspectionState =
        opponentHeroInspectionState

    internal fun rankDetectorInvocationCountForTest(): Int = rankDetectorInvocationCount

    /**
     * Evaluate all turn-start rules and return the first surrender request.
     * The hero identity is read from the live card entity.  A blank or
     * placeholder hero name is deliberately treated as ineligible: the
     * requirement is to continue only when the original hero is positively
     * identified.
     */
    fun evaluateTurnStart(war: War): SurrenderRuleResult? {
        enforcePersistentStreakGuardForCurrentPolicy()?.let { return it }
        // Test-only escape hatch for the real-input E2E harness. Normal runs
        // never set this property, so the production eligibility rules remain
        // unchanged; the harness must be able to reach card-play/attack turns
        // even when matchmaking supplies an ineligible or late OCR name.
        if (System.getProperty("hs.script.e2e.skip-surrender-policy") == "true") {
            log.info { "E2E_TEST_ONLY surrender policy bypassed for card-play/attack verification" }
            return null
        }
        if (NeverSurrenderPolicy.enabled()) {
            log.info { "SURRENDER_POLICY_BYPASS reason=never-surrender stage=${SurrenderCheckStage.TURN_START.name} action=CONTINUE" }
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
