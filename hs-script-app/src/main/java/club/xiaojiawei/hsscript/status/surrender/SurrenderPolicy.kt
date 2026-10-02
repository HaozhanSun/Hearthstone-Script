package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.bean.isValid
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscript.status.DebugScreenshotRing
import club.xiaojiawei.hsscript.status.Mode
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.statistics.Record
import club.xiaojiawei.hsscript.statistics.RecordDaoEx
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscript.strategy.phase.ReplaceCardPhaseStrategy
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.SpecialCardEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscriptcardsdk.enums.ZoneEnum
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
    OPPONENT_CARD_PLAYED,
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
    /** True when this particular surrender request must be blocked. */
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

/**
 * A revealed opponent card that is an immediate surrender signal.
 *
 * Keep this registry data-driven: adding a future card should only require a
 * new entry (with its stable IDs and localized names), not another policy
 * branch.  IDs are authoritative; names provide a diagnostic/replay fallback.
 */
internal data class DirectSurrenderCardDefinition(
    val key: String,
    val cardIds: Set<String>,
    val localizedNames: Set<String>,
    val playedZones: Set<ZoneEnum>,
)

internal data class RankInspectionReadDecision(
    val state: RankInspectionState,
    val wait: Boolean,
    val pause: Boolean,
    val reason: String,
)

internal data class RankInspectionGraceDecision(
    val probeAllowed: Boolean,
    val remainingMs: Long,
)

/** Keep late callbacks from moving one game's rank lifecycle backwards. */
internal fun monotonicRankInspectionState(
    current: RankInspectionState,
    next: RankInspectionState,
): RankInspectionState {
    val currentIsTerminal = current == RankInspectionState.RESOLVED ||
        current == RankInspectionState.BLOCKED
    val regresses = current == RankInspectionState.WAITING_FOR_RANK &&
        next == RankInspectionState.NOT_READY
    return if (current != next && (currentIsTerminal || regresses)) current else next
}

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
    internal const val INITIAL_RANK_INSPECTION_GRACE_MS = 7_000L
    private const val WIN_RATE_GUARD_THRESHOLD_PERCENT = 45.0
    private const val WIN_RATE_GUARD_MIN_GAMES = 5
    /** Protect the next game after seven persisted concessions. */
    private const val MAX_CONSECUTIVE_SURRENDERS = 7
    /** Protect the next game after five persisted wins in a row. */
    private const val MAX_CONSECUTIVE_WINS = 5
    /** A transient/early rank read must not pause the first eligible frame. */
    private const val MAX_RANK_INSPECTION_ATTEMPTS = 3
    /** A live opponent at exactly 40 health is an unconditional skip target. */
    private const val DIRECT_SURRENDER_HEALTH = 40

    /**
     * Early opponent-hero checks run once per resolved identity.  Keeping the
     * state here prevents a burst of Power.log entity updates from scheduling
     * several surrender flows for the same game.
     */
    private var lastPreMulliganHeroName = ""
    private var earlySurrenderTriggered = false
    @Volatile
    private var rankCheckCompleted = false
    @Volatile
    private var rankContinueAuthorized = false
    private var rankCompletionDiagnosticLogged = false
    private var rankCompletionDiagnosticCount = 0
    private var rankInspectionAttempts = 0
    private var lastRankInspectionAt = 0L
    private var rankInspectionEligibleAt = 0L
    private var lastHeroEvidenceKey = ""
    private var directSurrenderCardTriggered = false
    private var rankDetectorInvocationCount = 0
    /** Cache the stable per-game streak decision; Power.log can emit bursts. */
    private var persistentStreakGuardCacheReady = false
    private var persistentStreakGuardCacheStrategyId = ""
    private var persistentStreakGuardCacheDecision: SurrenderRuleResult? = null
    private var lastPersistentStreakContinueLogKey = ""
    /** Survives game-state resets so replay/reset bursts cannot re-spam logs. */
    private var lastPersistentStreakDecisionLogKey = ""
    @Volatile
    private var rankInspectionState = RankInspectionState.NOT_READY
    @Volatile
    private var opponentHeroInspectionState = OpponentHeroInspectionState.NOT_RESOLVED

    /** A late Power.log callback cannot make a resolved rank look pending. */
    private fun setRankInspectionState(next: RankInspectionState) {
        rankInspectionState = monotonicRankInspectionState(rankInspectionState, next)
    }

    /**
     * Cards revealed in the opponent's Power.log-derived zones that should
     * immediately use the normal unified surrender executor.
     */
    internal val directSurrenderCardRegistry: List<DirectSurrenderCardDefinition> = listOf(
        DirectSurrenderCardDefinition(
            key = "demon-seed",
            cardIds = setOf(SpecialCardEnum.THE_DEMON_SEED.cardId),
            localizedNames = setOf(SpecialCardEnum.THE_DEMON_SEED.comment, "The Demon Seed"),
            playedZones = setOf(ZoneEnum.PLAY, ZoneEnum.SECRET, ZoneEnum.SETASIDE),
        ),
        DirectSurrenderCardDefinition(
            key = "darkbishop-benedictus",
            cardIds = setOf(
                SpecialCardEnum.DARKBISHOP_BENEDICTUS.cardId,
                "CORE_${SpecialCardEnum.DARKBISHOP_BENEDICTUS.cardId}",
            ),
            localizedNames = setOf(
                SpecialCardEnum.DARKBISHOP_BENEDICTUS.comment,
                "Darkbishop Benedictus",
                "Dark Bishop Benedictus",
            ),
            // SW_448 is revealed in the opponent DECK zone when its
            // START_OF_GAME_KEYWORD effect is emitted. Do not generalize
            // DECK matching to ordinary cards; only this explicit rule may
            // use that zone as a surrender signal.
            playedZones = setOf(ZoneEnum.DECK),
        ),
    )

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
        SurrenderRule("opponent-health-is-40") { context ->
            val matched = context.rivalHealth == DIRECT_SURRENDER_HEALTH
            SurrenderRuleResult(
                ruleId = "opponent-health-is-40",
                matched = matched,
                shouldSurrender = matched,
                reason = if (matched) {
                    "opponent-health-is-40 source=Power.log"
                } else {
                    "opponent-health-is-not-40"
                },
            )
        },
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
        rankContinueAuthorized = false
        rankCompletionDiagnosticLogged = false
        rankCompletionDiagnosticCount = 0
        rankInspectionAttempts = 0
        lastRankInspectionAt = 0L
        rankInspectionEligibleAt = 0L
        lastHeroEvidenceKey = ""
        directSurrenderCardTriggered = false
        rankDetectorInvocationCount = 0
        persistentStreakGuardCacheReady = false
        persistentStreakGuardCacheStrategyId = ""
        persistentStreakGuardCacheDecision = null
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
     * persisted concessions block the next automatic surrender request, but
     * never pause the runtime. The caller may still apply a higher-priority
     * rule, such as the non-original-opponent-hero rule, and normal play must
     * continue when no surrender is dispatched. Five persisted wins request
     * surrender for the next game, according to the configured protection
     * rule. The evidence includes recent durable records so a
     * result/classification regression is diagnosable.
     */
    @Synchronized
    private fun enforcePersistentStreakGuard(): SurrenderRuleResult? {
        val strategy = DeckStrategyManager.currentDeckStrategy ?: return null
        val strategyId = strategy.id().takeIf { it.isNotBlank() } ?: return null
        if (persistentStreakGuardCacheReady && persistentStreakGuardCacheStrategyId == strategyId) {
            return persistentStreakGuardCacheDecision
        }

        val decision = runCatching {
            val records = RecordDaoEx.RECORD_DAO.query(Record(strategyId = strategyId))
            val snapshot = persistentStreakSnapshot(records)
            val guard = evaluatePersistentStreakGuard(snapshot) ?: return@runCatching null
            val evidence = records
                .filter { it.result != null }
                .sortedWith(compareBy<Record> { it.endTime ?: LocalDateTime.MIN }.thenBy { it.id ?: Int.MIN_VALUE })
                .takeLast(10)
                .joinToString(",") {
                    "id=${it.id ?: "?"}:result=${it.result}:surrendered=${it.surrendered}:end=${it.endTime ?: "?"}"
                }
            val result = persistentStreakDecision(snapshot) ?: return@runCatching null
            val logKey = "$strategyId|${result.ruleId}|${result.reason}"
            if (logKey != lastPersistentStreakDecisionLogKey) {
                lastPersistentStreakDecisionLogKey = logKey
                if (result.blocksAutomaticSurrender) {
                    log.warn {
                        "PERSISTENT_STREAK_GUARD_BLOCKED strategy=$strategyId rule=${guard.ruleId} " +
                            "reason=${guard.reason} consecutiveSurrenders=${snapshot.consecutiveSurrenders} " +
                            "consecutiveWins=${snapshot.consecutiveWins} action=BLOCK_SURRENDER " +
                            "surrenderPolicyPass=BLOCKED dispatch=false pause=false evidence=$evidence source=statistics.db"
                    }
                } else {
                    log.warn {
                        "PERSISTENT_STREAK_GUARD_TRIGGERED strategy=$strategyId rule=${guard.ruleId} " +
                            "reason=${guard.reason} consecutiveSurrenders=${snapshot.consecutiveSurrenders} " +
                            "consecutiveWins=${snapshot.consecutiveWins} action=SURRENDER " +
                            "surrenderPolicyPass=REQUESTED evidence=$evidence source=statistics.db"
                    }
                }
            }
            result
        }.getOrElse { error ->
            persistentStreakGuardUnavailable("statistics-read-failed", error)
        }
        persistentStreakGuardCacheReady = true
        persistentStreakGuardCacheStrategyId = strategyId
        persistentStreakGuardCacheDecision = decision
        return decision
    }

    @Synchronized
    private fun logPersistentStreakContinueOnce(kind: String, decision: SurrenderRuleResult) {
        // The same durable decision is observed at several lifecycle
        // boundaries (opponent hero, rank preflight, and turn start).  The
        // stage is presentation context, not a new decision; including it in
        // the key lets a Power.log burst alternate stages and spam the UI.
        val key = "${decision.ruleId}|${decision.reason}"
        if (key == lastPersistentStreakContinueLogKey) return
        lastPersistentStreakContinueLogKey = key
        when (kind) {
            "opponent-hero" -> log.info {
                "PERSISTENT_STREAK_GUARD_DEFERRED stage=${SurrenderCheckStage.OPPONENT_HERO_RESOLVED.name} " +
                    "rule=${decision.ruleId} reason=opponent-hero-rule-has-priority action=CONTINUE"
            }
            "rank" -> log.info {
                "RANK_POLICY_CONTINUE stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                    "reason=${decision.reason} rankDetector=false surrender=false pause=false"
            }
            "turn" -> log.info {
                "TURN_POLICY_CONTINUE reason=${decision.reason} " +
                    "rule=${decision.ruleId} surrender=false pause=false"
            }
        }
    }

    private fun persistentStreakGuardUnavailable(
        reason: String,
        error: Throwable? = null,
    ): SurrenderRuleResult {
        if (error == null) {
            log.error {
                "PERSISTENT_STREAK_GUARD_BLOCKED rule=persistent-streak-guard-unavailable " +
                    "reason=$reason action=BLOCK_SURRENDER surrenderPolicyPass=BLOCKED " +
                    "dispatch=false pause=false"
            }
        } else {
            log.error(error) {
                "PERSISTENT_STREAK_GUARD_BLOCKED rule=persistent-streak-guard-unavailable " +
                    "reason=$reason action=BLOCK_SURRENDER surrenderPolicyPass=BLOCKED " +
                    "dispatch=false pause=false"
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
     * not disable the independent seven-concession surrender block. Neither
     * branch is allowed to pause the runtime.
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

    /** Keep every generic surrender policy subordinate to a verified eligible rank. */
    private fun skipGenericSurrenderForEligibleRank(stage: SurrenderCheckStage): Boolean {
        if (!rankContinueAuthorized) return false
        log.info {
            "SURRENDER_POLICY_SKIPPED reason=verified-rank-eligibility stage=${stage.name} " +
                "action=CONTINUE surrender=false dispatch=false retry=false"
        }
        return true
    }

    /** Common executor guard for direct GameUtil.surrender callers and stale requests. */
    internal fun surrenderDispatchBlockReason(mandatoryRank: Boolean): String? = when {
        rankContinueAuthorized -> "verified-rank-eligibility"
        !rankCheckCompleted && !mandatoryRank -> "rank-eligibility-not-resolved"
        else -> null
    }

    /**
     * Evaluate the rival hero as soon as the live model has a resolved hero
     * entity during the pre-mulligan phases.  Unknown/placeholder names are
     * ignored here: an early surrender is safe only after the portrait's
     * identity is positively available.
     */
    @Synchronized
    fun evaluateOpponentHeroBeforeMulligan(war: War): SurrenderRuleResult? {
        if (skipGenericSurrenderForEligibleRank(SurrenderCheckStage.OPPONENT_HERO_RESOLVED)) return null
        enforcePersistentStreakGuardForCurrentPolicy()?.let { streakDecision ->
            if (!streakDecision.blocksAutomaticSurrender) return streakDecision
            logPersistentStreakContinueOnce("opponent-hero", streakDecision)
        }
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

        val currentHealth = rivalHero.health - rivalHero.damage
        if (currentHealth == DIRECT_SURRENDER_HEALTH) {
            val rawHeroName = rivalHero.entityName.trim()
            val normalizedHeroName = normalizeOpponentHeroName(rawHeroName)
            val result = SurrenderRuleResult(
                ruleId = "opponent-health-is-40",
                matched = true,
                shouldSurrender = true,
                reason = "opponent-health-is-40 source=Power.log",
            )
            opponentHeroInspectionState = OpponentHeroInspectionState.SURRENDER_REQUESTED
            log.info {
                "SURRENDER_CHECK stage=${SurrenderCheckStage.OPPONENT_HERO_RESOLVED.name} " +
                    "rule=${result.ruleId} rivalHeroRaw=${rawHeroName.ifBlank { "<blank>" }} " +
                    "rivalHero=${normalizedHeroName.ifBlank { "<blank>" }} heroResolved=${isResolvedOpponentHeroName(rawHeroName)} " +
                    "rivalHealth=$currentHealth initialMaxHealth=${rivalHero.health} " +
                    "matched=true action=SURRENDER reason=${result.reason}"
            }
            if (earlySurrenderTriggered) return null
            earlySurrenderTriggered = true
            captureHeroEvidence(
                stage = SurrenderCheckStage.OPPONENT_HERO_RESOLVED,
                rawName = rawHeroName,
                normalizedName = normalizedHeroName,
                cardId = rivalHero.cardId.trim(),
                reason = result.reason ?: "opponent-health-is-40",
            )
            log.warn {
                "SURRENDER_POLICY_TRIGGERED stage=${SurrenderCheckStage.OPPONENT_HERO_RESOLVED.name} " +
                    "rule=${result.ruleId} rivalHero=${normalizedHeroName.ifBlank { "<blank>" }} " +
                    "reason=${result.reason} timing=before-mulligan"
            }
            return result
        }

        // Power.log can reveal Prince Renathal as an opponent hand entity
        // (REV_018).  This is stronger evidence than OCR and is available
        // before the mulligan decision.  The initial max-health fallback is
        // deliberately limited to this pre-mulligan path: a later health
        // increase must not be mistaken for the deck rule.
        val renathalEvidence = opponentRenathalEvidence(rivalHero, war.rival.handArea.cards)
        if (renathalEvidence != null) {
            val rawHeroName = rivalHero.entityName.trim()
            val normalizedHeroName = normalizeOpponentHeroName(rawHeroName)
            val result = SurrenderRuleResult(
                ruleId = "opponent-prince-renathal",
                matched = true,
                shouldSurrender = true,
                reason = renathalEvidence,
            )
            opponentHeroInspectionState = OpponentHeroInspectionState.SURRENDER_REQUESTED
            log.info {
                "SURRENDER_CHECK stage=${SurrenderCheckStage.OPPONENT_HERO_RESOLVED.name} " +
                    "rule=${result.ruleId} rivalHeroRaw=${rawHeroName.ifBlank { "<blank>" }} " +
                    "rivalHero=${normalizedHeroName.ifBlank { "<blank>" }} heroResolved=${isResolvedOpponentHeroName(rawHeroName)} " +
                    "rivalHealth=$currentHealth initialMaxHealth=${rivalHero.health} " +
                    "matched=true action=SURRENDER reason=${result.reason}"
            }
            if (earlySurrenderTriggered) return null
            earlySurrenderTriggered = true
            captureHeroEvidence(
                stage = SurrenderCheckStage.OPPONENT_HERO_RESOLVED,
                rawName = rawHeroName,
                normalizedName = normalizedHeroName,
                cardId = rivalHero.cardId.trim(),
                reason = result.reason ?: "opponent-prince-renathal",
            )
            log.warn {
                "SURRENDER_POLICY_TRIGGERED stage=${SurrenderCheckStage.OPPONENT_HERO_RESOLVED.name} " +
                    "rule=${result.ruleId} rivalHero=${normalizedHeroName.ifBlank { "<blank>" }} " +
                    "reason=${result.reason} timing=before-mulligan"
            }
            return result
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

        val result = applyOpponentHeroSurrenderSetting(
            evaluateOpponentHero(rawHeroName, heroCardId),
            opponentHeroNonOriginalSurrenderEnabled(),
        )
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
                "reason=${result.reason ?: "policy-requested-surrender"} " +
                "timing=before-mulligan"
        }
        return result
    }

    /** Read the persisted setting at decision time so UI changes apply to the next check. */
    internal fun opponentHeroNonOriginalSurrenderEnabled(): Boolean =
        ConfigUtil.getBoolean(ConfigEnum.OPPONENT_HERO_NON_ORIGINAL_SURRENDER)

    /**
     * Return the strongest local Power.log evidence that the opponent chose
     * Prince Renathal.  The card ID is preferred; an untouched hero with an
     * initial 40-health maximum is the fail-safe fallback for logs where the
     * hand entity was not exposed.  This function is called only during the
     * pre-mulligan window, before ordinary in-game health changes are possible.
     */
    internal fun opponentRenathalEvidence(
        rivalHero: Card?,
        rivalHand: Collection<Card>,
    ): String? {
        val renathalCardId = SpecialCardEnum.PRINCE_RENATHAL.cardId
        val revealedCard = rivalHand.firstOrNull { card ->
            val cardId = card.cardId.trim().uppercase()
            cardId == renathalCardId || cardId == "CORE_$renathalCardId"
        }
        if (revealedCard != null) {
            return "opponent-prince-renathal-card-seen source=Power.log cardId=${revealedCard.cardId.trim()}"
        }
        if (rivalHero != null && rivalHero.health >= 40 && rivalHero.damage == 0) {
            return "opponent-prince-renathal-initial-health source=Power.log initialMaxHealth=${rivalHero.health}"
        }
        return null
    }

    /**
     * Disable only the non-original-opponent-hero rule. Hero detection still
     * runs and the caller still advances through the normal rank/strategy
     * gates, so this cannot bypass unrelated surrender rules.
     */
    internal fun applyOpponentHeroSurrenderSetting(
        result: SurrenderRuleResult,
        enabled: Boolean,
    ): SurrenderRuleResult = if (enabled) {
        result
    } else {
        result.copy(
            matched = false,
            shouldSurrender = false,
            reason = "opponent-hero-original-check-disabled",
        )
    }

    /**
     * The rank gate is the primary policy: fresh, positively verified numeric
     * ranks 5, 10, or above 20 are eligible to continue, independent of tier
     * classifier output. A streak or win-rate surrender suggestion cannot
     * override verified rank authorization; uncertain rank evidence still
     * follows the bounded fail-closed path.
     */
    @Synchronized
    fun evaluateCurrentRankBeforeMulligan(): SurrenderRuleResult? {
        // The rank preflight is complete after the first allowed, blocked, or
        // surrendering decision. Later Power.log bursts must not re-enter the
        // streak guard or replay the same continuation decision.
        if (rankCheckCompleted) {
            if (!rankCompletionDiagnosticLogged) {
                rankCompletionDiagnosticLogged = true
                rankCompletionDiagnosticCount++
                log.info {
                    "RANK_POLICY_INSPECT_SKIPPED reason=rank-check-completed " +
                        "rankContinueAuthorized=$rankContinueAuthorized attempts=$rankInspectionAttempts"
                }
            }
            return null
        }
        // Defer both streak outcomes until rank evidence is checked. A
        // verified allowed rank is the authoritative gate for whether to
        // play; neither a win-streak suggestion nor a surrender-streak block
        // may turn that eligible rank into a surrender.
        val persistentStreakDecision = enforcePersistentStreakGuardForCurrentPolicy()
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
                    "RANK_POLICY_PROBE_WITHOUT_OPPONENT_HERO state=$opponentHeroInspectionState " +
                        "action=CONTINUE_RANK_PROBE rankDetector=true"
                }
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
        // Once a final result exists, late phase callbacks must be silent and
        // must not emit a new pending/not-ready state for this game.
        if (rankCheckCompleted) return null
        if (!ReplaceCardPhaseStrategy.isRankInspectionReady()) {
            setRankInspectionState(RankInspectionState.NOT_READY)
            log.info {
                "RANK_POLICY_WAITING_FOR_RANK reason=mulligan-input-not-confirmed " +
                    "readiness=${ReplaceCardPhaseStrategy.rankInspectionReadinessDiagnostic()} " +
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
        val now = System.currentTimeMillis()
        if (rankInspectionEligibleAt == 0L) {
            rankInspectionEligibleAt = now
        }
        val grace = rankInspectionGraceDecision(rankInspectionEligibleAt, now)
        if (!grace.probeAllowed) {
            setRankInspectionState(RankInspectionState.WAITING_FOR_RANK)
            log.info {
                "RANK_POLICY_WAITING_FOR_INITIAL_GRACE trigger=game-entry-mulligan " +
                    "eligibleAt=$rankInspectionEligibleAt delayMs=$INITIAL_RANK_INSPECTION_GRACE_MS " +
                    "remainingMs=${grace.remainingMs} action=WAIT rankDetector=false"
            }
            return null
        }
        if (now - lastRankInspectionAt < RANK_RETRY_INTERVAL_MS) return null
        lastRankInspectionAt = now
        rankInspectionAttempts++

        rankDetectorInvocationCount++
        val detection = CurrentRankDetector.detect(
            trigger = "rank-policy-${phase.name}",
            phase = phase.name,
        )
        return evaluateMulliganRankEvidence(
            detection = detection,
            actualMode = Mode.currMode?.name,
            inWar = WarEx.inWar,
            nowMs = System.currentTimeMillis(),
            persistentStreakDecision = persistentStreakDecision,
            winRateDecisionProvider = ::evaluateWinRateGuard,
        )
    }

    /**
     * Apply the same rank/evidence decision used by the asynchronous mulligan
     * preflight. Kept injectable so tests can drive the real policy from OCR
     * evidence without capturing the desktop or calling its authorization
     * latch directly.
     */
    internal fun evaluateMulliganRankEvidence(
        detection: CurrentRankDetector.Detection?,
        actualMode: String?,
        inWar: Boolean,
        nowMs: Long,
        persistentStreakDecision: SurrenderRuleResult?,
        winRateDecisionProvider: () -> SurrenderRuleResult? = { null },
    ): SurrenderRuleResult? {
        val authorization = RankEligibilityPolicy.evaluate(
            detection = detection,
            expectedMode = ModeEnum.GAMEPLAY.name,
            actualMode = actualMode,
            expectedInWar = true,
            inWar = inWar,
            nowMs = nowMs,
        )
        log.info {
            "RANK_ELIGIBILITY_CHECK stage=MULLIGAN provider=${detection?.provider ?: "NONE"} " +
                "rank=${detection?.rank ?: "UNKNOWN"} tier=${detection?.tier?.name ?: "UNKNOWN"} " +
                "confidence=${detection?.confidence ?: "unavailable"} agreement=${detection?.agreementCount ?: 0} " +
                "mode=${actualMode ?: "NONE"} decision=${if (authorization.eligible) "ALLOW" else "DENY"} " +
                "reason=${authorization.reason}"
        }
        if (authorization.eligible) {
            val rank = detection!!.rank!!
            val winRateDecision = winRateDecisionProvider()
            authorizeEligibleMulliganRank(rank, detection.tier, authorization.reason, persistentStreakDecision)
            if (winRateDecision != null) {
                log.info {
                    "WIN_RATE_POLICY_BYPASS reason=verified-rank-eligibility " +
                        "rule=${winRateDecision.ruleId} action=CONTINUE rank=$rank"
                }
            }
            return null
        }
        if (authorization.reason == "rank-not-5-or-10") {
            rankCheckCompleted = true
            setRankInspectionState(RankInspectionState.RESOLVED)
            val result = evaluateCurrentRank(detection?.rank ?: 0, detection?.tier ?: CurrentRankDetector.RankTier.UNKNOWN)
            log.warn {
                "RANK_POLICY_TRIGGERED stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                    "rank=${detection?.rank ?: "LEGENDARY"} tier=${detection?.tier?.name ?: "UNKNOWN"} " +
                    "action=SURRENDER reason=${result?.reason ?: authorization.reason}"
            }
            return result
        }
        // Provider failures, low confidence, stale frames, mode mismatch, and
        // unresolved/canceled OCR are not numeric-rank decisions. Retry them
        // within the existing bound, then fail closed explicitly.
        val readDecision = classifyRankInspection(
            rank = null,
            detectionAvailable = detection != null,
            attempt = rankInspectionAttempts,
        )
        if (readDecision.wait) {
            setRankInspectionState(readDecision.state)
            log.debug {
                "RANK_POLICY_WAITING_FOR_RANK stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                    "attempt=$rankInspectionAttempts maxAttempts=$MAX_RANK_INSPECTION_ATTEMPTS " +
                    "providerResult=${authorization.reason} retry=${readDecision.reason} " +
                    "action=WAIT pause=false surrender=false"
            }
            return null
        }
        rankCheckCompleted = true
        setRankInspectionState(RankInspectionState.RESOLVED)
        val result = unresolvedRankDecision(rankInspectionAttempts)
        log.warn {
            "RANK_POLICY_TRIGGERED stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                "rank=${detection?.rank ?: "UNKNOWN"} tier=${detection?.tier?.name ?: "UNKNOWN"} " +
                "action=SURRENDER reason=${authorization.reason} final=${result.reason}"
        }
        return result
    }

    internal fun evaluateCurrentRank(
        rank: Int,
        tier: CurrentRankDetector.RankTier = CurrentRankDetector.RankTier.UNKNOWN,
    ): SurrenderRuleResult? {
        // Exact numeric targets are authoritative even if the independent
        // tier classifier mistakes their badge artwork for Legendary.
        if (rank == 5 || rank == 10) return null
        return SurrenderRuleResult(
            ruleId = "current-rank-not-5-or-10",
            matched = false,
            shouldSurrender = true,
            reason = "current-rank=$rank tier=${tier.name} target-ranks=5,10",
        )
    }

    /** Shared by the live evidence evaluator and continuation hand-off. */
    internal fun authorizeEligibleMulliganRank(
        rank: Int,
        tier: CurrentRankDetector.RankTier,
        reason: String,
        persistentStreakDecision: SurrenderRuleResult? = null,
    ) {
        rankCheckCompleted = true
        setRankInspectionState(RankInspectionState.RESOLVED)
        rankContinueAuthorized = true
        persistentStreakDecision?.let { streakDecision ->
            if (streakDecision.blocksAutomaticSurrender) {
                logPersistentStreakContinueOnce("rank", streakDecision)
            } else {
                log.info {
                    "SURRENDER_POLICY_BYPASS reason=verified-rank-eligibility " +
                        "rule=${streakDecision.ruleId} action=CONTINUE rank=$rank"
                }
            }
        }
        log.info {
            "RANK_POLICY_CONTINUE stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                "rank=$rank tier=${tier.name} reason=verified-eligibility-$reason " +
                "streakGuard=OVERRIDDEN_FOR_ELIGIBLE_RANK"
        }
    }

    /** Classification helper only; Legendary remains outside the allowed 5/10 ranks. */
    internal fun isLegendaryDetection(detection: CurrentRankDetector.Detection?): Boolean =
        detection?.rank?.let { it > 20 } == true

    internal fun unresolvedRankDecision(attempts: Int): SurrenderRuleResult =
        SurrenderRuleResult(
            ruleId = "rank-ocr-unresolved",
            matched = false,
            shouldSurrender = true,
            reason = "rank-ocr-unresolved attempts=$attempts",
            blocksAutomaticSurrender = false,
        )

    /**
     * An active rank frame with no valid number is retried while bounded, then
     * becomes an explicit fail-closed surrender decision. Unknown is never a
     * playable continuation.
     */
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
            pause = false,
            reason = if (detectionAvailable) "empty-or-unmapped" else "provider-failure-or-capture-failure",
        )
    }

    internal fun rankInspectionGraceDecision(eligibleAt: Long, now: Long): RankInspectionGraceDecision {
        val remaining = if (eligibleAt <= 0L) {
            INITIAL_RANK_INSPECTION_GRACE_MS
        } else {
            (INITIAL_RANK_INSPECTION_GRACE_MS - (now - eligibleAt)).coerceAtLeast(0L)
        }
        return RankInspectionGraceDecision(
            probeAllowed = remaining == 0L,
            remainingMs = remaining,
        )
    }

    internal fun blockForUnresolvedRank(attempts: Int): SurrenderRuleResult {
        val result = unresolvedRankDecision(attempts)
        setRankInspectionState(RankInspectionState.RESOLVED)
        log.warn {
            "RANK_POLICY_BLOCKED stage=${SurrenderCheckStage.CURRENT_RANK_RESOLVED.name} " +
                "rule=${result.ruleId} reason=${result.reason} action=SURRENDER " +
                "surrender=true pause=false ocrFailure=true"
        }
        return result
    }

    /** True when a rank read already produced a final safe or unsafe result. */
    internal fun currentRankCheckCompleted(): Boolean = rankCheckCompleted

    /** True only after this game's numeric rank was positively authorized. */
    internal fun currentRankContinueAuthorized(): Boolean = rankContinueAuthorized

    internal fun rankInspectionAttemptsForTest(): Int = rankInspectionAttempts

    internal fun rankCompletionDiagnosticCountForTest(): Int = rankCompletionDiagnosticCount

    /** Narrow seam for proving lifecycle reset of a latched preflight state. */
    internal fun forceRankInspectionLatchForTest(
        completed: Boolean,
        authorized: Boolean,
        attempts: Int,
    ) {
        rankCheckCompleted = completed
        rankContinueAuthorized = authorized
        rankInspectionAttempts = attempts
        rankCompletionDiagnosticLogged = false
        rankCompletionDiagnosticCount = 0
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
        if (skipGenericSurrenderForEligibleRank(SurrenderCheckStage.TURN_START)) return null
        enforcePersistentStreakGuardForCurrentPolicy()?.let { streakDecision ->
            if (!streakDecision.blocksAutomaticSurrender) return streakDecision
            logPersistentStreakContinueOnce("turn", streakDecision)
            return null
        }
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
            // Health and armor are separate Hearthstone values. The first
            // turn-start rule below treats exactly 40 current health as a
            // direct surrender signal; all other health values remain
            // available for diagnostics without changing rank/hero policy.
            rivalHealth = rivalHero?.let { it.health - it.damage },
            rivalArmor = rivalHero?.armor,
        )

        for (rule in turnStartRules) {
            // Keep this live-turn path consistent with the pre-mulligan path.
            // The settings toggle is read at decision time so changing it in
            // the UI applies without restarting the policy object.
            val evaluated = rule.evaluate(context)
            // The skin/class toggle is intentionally scoped only to the
            // non-original-hero rule.  A confirmed 40-health opponent is an
            // independent direct-surrender condition and must still fire
            // when that UI toggle is off.
            val result = if (rule.id == "opponent-health-is-40") {
                evaluated
            } else {
                applyOpponentHeroSurrenderSetting(
                    evaluated,
                    opponentHeroNonOriginalSurrenderEnabled(),
                )
            }
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
                        "rivalPlayer=${context.rivalPlayerName.ifBlank { "<blank>" }} " +
                        "reason=${result.reason ?: "policy-requested-surrender"}"
                }
                return result
            }
        }
        return null
    }

    /**
     * Inspect only the opponent's Power.log-derived active/revealed zones. A
     * card in hand or graveyard is historical evidence, not proof of a newly
     * played card, so those zones are deliberately excluded. DECK is included
     * only because the card registry can explicitly opt a start-of-game
     * reveal into that zone; ordinary cards cannot match there accidentally.
     */
    @Synchronized
    internal fun evaluateOpponentPlayedCard(war: War): SurrenderRuleResult? {
        if (skipGenericSurrenderForEligibleRank(SurrenderCheckStage.OPPONENT_CARD_PLAYED)) return null
        if (System.getProperty("hs.script.e2e.skip-surrender-policy") == "true") return null
        if (!war.me.isValid() || !war.rival.isValid()) return null
        val cardsByZone = sequenceOf(
            ZoneEnum.PLAY to war.rival.playArea.cards.asSequence(),
            ZoneEnum.SECRET to war.rival.secretArea.cards.asSequence(),
            ZoneEnum.SETASIDE to war.rival.setasideArea.cards.asSequence(),
            ZoneEnum.DECK to war.rival.deckArea.cards.asSequence(),
        )
        for ((zone, cards) in cardsByZone) {
            for (card in cards) {
                val definition = directSurrenderCardRegistry.firstOrNull { entry ->
                    zone in entry.playedZones && (
                        entry.cardIds.any { it.equals(card.cardId.trim(), ignoreCase = true) } ||
                            entry.localizedNames.any {
                                it.equals(normalizeOpponentCardName(card.entityName), ignoreCase = true)
                            }
                        )
                } ?: continue
                val matchedById = definition.cardIds.any { it.equals(card.cardId.trim(), ignoreCase = true) }
                val matchedByName = definition.localizedNames.any {
                    it.equals(normalizeOpponentCardName(card.entityName), ignoreCase = true)
                }
                val result = SurrenderRuleResult(
                    ruleId = "opponent-played-card-${definition.key}",
                    matched = true,
                    shouldSurrender = true,
                    reason = "opponent-card=${definition.localizedNames.firstOrNull() ?: definition.key} " +
                        "cardId=${card.cardId.ifBlank { "<blank>" }} zone=${zone.name} " +
                        "match=${if (matchedById) "card-id" else if (matchedByName) "localized-name" else "registry"}",
                )
                log.warn {
                    "SURRENDER_CHECK stage=${SurrenderCheckStage.OPPONENT_CARD_PLAYED.name} " +
                        "rule=${result.ruleId} opponentCard=${card.entityName.ifBlank { definition.localizedNames.firstOrNull() ?: definition.key }} " +
                        "cardId=${card.cardId.ifBlank { "<blank>" }} zone=${zone.name} " +
                        "matched=true action=SURRENDER reason=${result.reason}"
                }
                return result
            }
        }
        return null
    }

    /** Prevent repeated Power.log callbacks from dispatching the same rule. */
    @Synchronized
    internal fun markOpponentCardSurrenderTriggered() {
        directSurrenderCardTriggered = true
    }

    @Synchronized
    internal fun opponentCardSurrenderWasTriggered(): Boolean = directSurrenderCardTriggered

    private fun normalizeOpponentCardName(rawName: String): String = rawName.trim()

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
