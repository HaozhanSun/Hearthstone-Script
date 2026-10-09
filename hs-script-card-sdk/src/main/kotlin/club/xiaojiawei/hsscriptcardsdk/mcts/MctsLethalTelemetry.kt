package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.data.CardInfoData
import club.xiaojiawei.hsscriptcardsdk.enums.CardActionEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.util.CardUtil
import kotlin.math.max

/**
 * Read-only lethal telemetry for the common MCTS controller.
 *
 * This deliberately observes the same generated attack actions that the
 * controller can dispatch.  It does not score, filter, or replace an action;
 * its only effect is a bounded evidence record immediately before dispatch.
 * That makes taunt, exhausted attackers, rush-only restrictions, and weapon
 * availability visible without creating a second rules engine.
 */
object MctsLethalTelemetry {
    data class Assessment(
        /** Raw printed/current friendly attack, regardless of readiness or target. */
        val rawFriendlyAttack: Int,
        val totalAttack: Int,
        val legalFaceMinionAttack: Int,
        val legalFaceHeroAttack: Int,
        val enemyHeroHealth: Int,
        val enemyHeroArmor: Int,
        val enemyHeroImmune: Boolean,
        val canLethal: Boolean,
        val verdict: String,
        val decision: String,
        val reason: String,
        val attackableAttackers: Int,
        val faceAttackers: Int,
        val tauntCount: Int,
        val legalFaceSpellDamage: Int,
        val opponentHeroHealImpact: Int,
        val unknownDamageEffects: List<String>,
        val currentlyAvailableUnknownEffects: List<String>,
        val maxReachableNetFaceDamage: Int,
        val possibleDamageUpperBound: String,
        val attackerEvidence: List<String>,
        val heroPowerEvidence: String,
        val handDamageEvidence: List<String>,
        val lethalScanSummary: String,
        val stateKey: String,
    )

    private const val MAX_DEDUP_KEYS = 512
    private val emittedKeys = object : LinkedHashMap<String, Boolean>(MAX_DEDUP_KEYS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > MAX_DEDUP_KEYS
    }

    /** Shared root-gate contract using the exact attack set written to telemetry. */
    fun isLethalFaceAction(action: Action, war: War): Boolean {
        if (action !is AttackAction) return false
        val rivalHeroId = war.rival.playArea.hero?.entityId
        if (!(action.targetIsHero || action.targetEntityId == rivalHeroId)) return false
        return assess(war, action).canLethal
    }

    /**
     * Calculate only currently legal face-damage opportunities.  A minion or
     * hero that can attack only a taunt is still attackable, but contributes
     * zero to [totalAttack] because its face target is not currently legal.
     */
    fun assess(war: War, selectedAction: Action? = null): Assessment {
        val rivalHero = war.rival.playArea.hero
        val rivalHeroId = rivalHero?.entityId
        val tauntCount = war.rival.playArea.cards.count { it.isTaunt && it.canBeAttacked() }
        val faceAttackers = mutableListOf<Card>()
        var attackableAttackers = 0

        fun isFace(action: AttackAction): Boolean =
            action.targetIsHero || action.targetEntityId == rivalHeroId

        war.me.playArea.cards
            .filter { it.cardType === CardTypeEnum.MINION && it.canAttack() }
            .forEach { card ->
                val actions = runCatching { card.action.generateAttackActions(war, war.me) }
                    .getOrDefault(emptyList())
                if (actions.isNotEmpty()) attackableAttackers++
                if (actions.any(::isFace)) faceAttackers += card
            }

        val hero = war.me.playArea.hero
        val weaponAttack = war.me.playArea.weapon?.atc?.coerceAtLeast(0) ?: 0
        val heroAttack = maxOf(hero?.atc ?: 0, weaponAttack)
        if (hero != null && heroAttack > 0 &&
            (hero.canAttack() || hero.canAttack(ignoreAtc = true))
        ) {
            val actions = runCatching { hero.action.generateAttackActions(war, war.me) }
                .getOrDefault(emptyList())
            if (actions.isNotEmpty()) attackableAttackers++
            if (actions.any(::isFace)) faceAttackers += hero
        }

        val legalFaceMinionAttack = faceAttackers.filter { it.cardType === CardTypeEnum.MINION }
            .sumOf { it.atc.coerceAtLeast(0) }
        val legalFaceHeroAttack = if (faceAttackers.any { it.cardType === CardTypeEnum.HERO }) heroAttack else 0
        val totalAttack = legalFaceMinionAttack + legalFaceHeroAttack
        val rawFriendlyAttack = war.me.playArea.cards
            .filter { it.cardType === CardTypeEnum.MINION && it.isAlive() }
            .sumOf { it.atc.coerceAtLeast(0) } + max(hero?.atc ?: 0, weaponAttack).coerceAtLeast(0)
        val enemyHeroArmor = rivalHero?.let { (it.armor - it.damage).coerceAtLeast(0) } ?: 0
        val enemyHeroImmune = rivalHero?.isImmune == true
        val enemyHeroHealthOnly = rivalHero?.let {
            (it.health - (it.damage - it.armor).coerceAtLeast(0)).coerceAtLeast(0)
        } ?: 0
        val enemyHeroHealth = rivalHero?.let { (it.bloodLimit() - it.damage).coerceAtLeast(0) } ?: 0
        val spellResult = spellDamage(war)
        val powerResult = heroPowerDamage(war)
        val tauntHeal = opponentHeroHealFromTaunt(war)
        val unknown = (spellResult.unknownEffects + tauntHeal.unknownEffects).distinct()
        val availableUnknown = (spellResult.availableUnknownEffects + tauntHeal.unknownEffects).distinct()
        // The taunt-heal value is diagnostic for a possible taunt attack. It
        // is not part of the route being counted here: with an active Taunt,
        // totalAttack contains no face attacks, so a direct spell route must
        // not pay a hypothetical retaliation-heal cost.
        val knownAbilityDamage = bestManaBoundDamage(
            war.me.usableResource,
            spellResult.damageOptions + listOfNotNull(powerResult.damageOption),
        )
        val guaranteedDamage = (totalAttack + knownAbilityDamage).coerceAtLeast(0)
        val canLethal = rivalHero != null && !enemyHeroImmune && guaranteedDamage >= enemyHeroHealth
        val verdict = when {
            canLethal -> "GUARANTEED_LETHAL"
            enemyHeroImmune -> "NOT_LETHAL_IMMUNE"
            availableUnknown.isNotEmpty() -> "UNRESOLVED"
            else -> "NOT_LETHAL"
        }
        val maxReachable = guaranteedDamage
        val decision = when (selectedAction) {
            is AttackAction -> if (isFace(selectedAction)) "HERO" else "TRADE"
            null -> "NONE"
            else -> "OTHER"
        }
        val reason = when {
            canLethal && selectedAction is AttackAction && decision == "HERO" -> "lethal-face-route-selected"
            canLethal && selectedAction is AttackAction && decision == "TRADE" -> "lethal-route-available-trade-selected"
            canLethal && selectedAction != null -> "lethal-route-available-non-attack-selected"
            canLethal -> "known-guaranteed-damage-proves-lethal"
            availableUnknown.isNotEmpty() -> "available-unknown-effects-leave-verdict-unresolved"
            enemyHeroImmune -> "enemy-hero-immune"
            faceAttackers.isEmpty() && tauntCount > 0 -> "taunt-blocks-face"
            faceAttackers.isEmpty() -> "no-current-face-attack"
            else -> "current-face-damage-below-health"
        }
        val attackerEvidence = attackerEvidence(war, rivalHeroId)
        val handEvidence = spellResult.evidence
        val possibleDamageUpperBound = if (availableUnknown.isNotEmpty()) "unknown" else guaranteedDamage.toString()
        val summary = buildLethalScanSummary(
            war = war,
            rawAttack = rawFriendlyAttack,
            legalMinionAttack = legalFaceMinionAttack,
            legalHeroAttack = legalFaceHeroAttack,
            attackers = attackerEvidence,
            heroPower = powerResult.evidence,
            hand = handEvidence,
            enemyHealth = enemyHeroHealth,
            enemyHealthOnly = enemyHeroHealthOnly,
            enemyArmor = enemyHeroArmor,
            enemyImmune = enemyHeroImmune,
            tauntCount = tauntCount,
            guaranteed = guaranteedDamage,
            upperBound = possibleDamageUpperBound,
            verdict = verdict,
        )
        return Assessment(
            rawFriendlyAttack = rawFriendlyAttack,
            totalAttack = totalAttack,
            legalFaceMinionAttack = legalFaceMinionAttack,
            legalFaceHeroAttack = legalFaceHeroAttack,
            enemyHeroHealth = enemyHeroHealth,
            enemyHeroArmor = enemyHeroArmor,
            enemyHeroImmune = enemyHeroImmune,
            canLethal = canLethal,
            verdict = verdict,
            decision = decision,
            reason = reason,
            attackableAttackers = attackableAttackers,
            faceAttackers = faceAttackers.size,
            tauntCount = tauntCount,
            legalFaceSpellDamage = spellResult.damage,
            opponentHeroHealImpact = tauntHeal.heal,
            unknownDamageEffects = unknown,
            currentlyAvailableUnknownEffects = availableUnknown,
            maxReachableNetFaceDamage = maxReachable,
            possibleDamageUpperBound = possibleDamageUpperBound,
            attackerEvidence = attackerEvidence,
            heroPowerEvidence = powerResult.evidence,
            handDamageEvidence = handEvidence,
            lethalScanSummary = summary,
            stateKey = stateKey(war, faceAttackers),
        )
    }

    /** Emit one record for a state/decision pair; repeated rescans are silent. */
    fun recordBeforeAttackDecision(
        war: War,
        strategy: String,
        step: Int,
        selectedAction: Action?,
    ): Assessment {
        val assessment = assess(war, selectedAction)
        val key = "${gameKey(war)}|${assessment.stateKey}|${assessment.decision}|$strategy"
        val shouldEmit = synchronized(emittedKeys) {
            if (emittedKeys.containsKey(key)) false else {
                emittedKeys[key] = true
                true
            }
        }
        if (shouldEmit) {
            MctsReplayTrace.record(
                war,
                "mcts_lethal_telemetry",
                assessment.reason,
                mapOf(
                    "strategy" to strategy,
                    "step" to step,
                    "totalAttack" to assessment.totalAttack,
                    "rawFriendlyAttack" to assessment.rawFriendlyAttack,
                    "legalFaceMinionAttack" to assessment.legalFaceMinionAttack,
                    "legalFaceHeroAttack" to assessment.legalFaceHeroAttack,
                    "legalFaceSpellDamage" to assessment.legalFaceSpellDamage,
                    "enemyHeroArmor" to assessment.enemyHeroArmor,
                    "enemyHeroImmune" to assessment.enemyHeroImmune,
                    "verdict" to assessment.verdict,
                    "possibleDamageUpperBound" to assessment.possibleDamageUpperBound,
                    "attackerEvidence" to assessment.attackerEvidence,
                    "heroPowerEvidence" to assessment.heroPowerEvidence,
                    "handDamageEvidence" to assessment.handDamageEvidence,
                    "lethalScanSummary" to assessment.lethalScanSummary,
                    "opponentHeroHealImpact" to assessment.opponentHeroHealImpact,
                    "unknownDamageEffects" to assessment.unknownDamageEffects,
                    "currentlyAvailableUnknownEffects" to assessment.currentlyAvailableUnknownEffects,
                    "maxReachableNetFaceDamage" to assessment.maxReachableNetFaceDamage,
                    "enemyHeroHealth" to assessment.enemyHeroHealth,
                    "canLethal" to assessment.canLethal,
                    "decision" to assessment.decision,
                    "attackableAttackers" to assessment.attackableAttackers,
                    "faceAttackers" to assessment.faceAttackers,
                    "tauntCount" to assessment.tauntCount,
                    "selectedAction" to selectedAction?.let(::describeAction),
                    "stateKey" to assessment.stateKey,
                ),
            )
            log.info {
                "MCTS_LETHAL_TELEMETRY strategy=$strategy step=$step " +
                "totalAttack=${assessment.totalAttack} " +
                    "legalFaceSpellDamage=${assessment.legalFaceSpellDamage} " +
                    "opponentHeroHealImpact=${assessment.opponentHeroHealImpact} " +
                    "maxReachableNetFaceDamage=${assessment.maxReachableNetFaceDamage} " +
                    "enemyHeroHealth=${assessment.enemyHeroHealth} " +
                    "canLethal=${assessment.canLethal} decision=${assessment.decision} " +
                    "reason=${assessment.reason}"
            }
            log.info { assessment.lethalScanSummary }
            if (assessment.canLethal) {
                log.info {
                    "已经可以斩杀 totalAttack=${assessment.totalAttack} " +
                        "enemyHeroHealth=${assessment.enemyHeroHealth} " +
                        "strategy=$strategy step=$step"
                }
            }
        }
        return assessment
    }

    /** Test-only reset that also makes isolated replay tests deterministic. */
    internal fun clearDedupForTests() = synchronized(emittedKeys) { emittedKeys.clear() }

    private data class SpellDamageResult(
        val damage: Int,
        val damageOptions: List<Pair<Int, Int>>,
        val unknownEffects: List<String>,
        val availableUnknownEffects: List<String>,
        val evidence: List<String>,
    )
    private data class OpponentHeroHealResult(val heal: Int, val unknownEffects: List<String>)
    private data class HeroPowerResult(
        val damage: Int,
        val cost: Int,
        val evidence: String,
        val damageOption: Pair<Int, Int>?,
    )

    private fun spellDamage(war: War): SpellDamageResult {
        val candidates = mutableListOf<Pair<Int, Int>>()
        val unknown = mutableListOf<String>()
        val availableUnknown = mutableListOf<String>()
        val evidence = mutableListOf<String>()
        war.me.handArea.cards.forEach { card ->
            val damageText = CardUtil.getCardText(card.cardId)?.replace("$", "")?.let(CardUtil::getDamageValue)
            if (card.cardType !== CardTypeEnum.SPELL && damageText == null) return@forEach
            val affordable = card.cost in 0..war.me.usableResource
            val actions = runCatching { card.action.generatePlayActions(war, war.me) }.getOrDefault(emptyList())
            val damage = actions.mapIndexedNotNull { index, _ ->
                val simulated = runCatching { war.clone() }.getOrNull() ?: return@mapIndexedNotNull null
                val clonedCard = simulated.me.handArea.findByEntityId(card.entityId)
                    ?: return@mapIndexedNotNull null
                val clonedAction = runCatching {
                    clonedCard.action.generatePlayActions(simulated, simulated.me).getOrNull(index)
                }.getOrNull() ?: return@mapIndexedNotNull null
                runCatching { clonedAction.simulate.accept(simulated) }.getOrNull()
                    ?: return@mapIndexedNotNull null
                val before = war.rival.playArea.hero?.blood() ?: return@mapIndexedNotNull null
                val after = simulated.rival.playArea.hero?.blood() ?: return@mapIndexedNotNull null
                (before - after).takeIf { it > 0 }
            }.maxOrNull()
            val parsedRivalEffect = CardInfoData.parsePlayAction(card.cardId).contains(CardActionEnum.POINT_RIVAL)
            val knownDamage = when {
                damage != null -> damage
                damageText != null && parsedRivalEffect -> damageText
                else -> null
            }
            val availability = if (!affordable) "unavailable:cost-${card.cost}-mana-${war.me.usableResource}"
                else if (actions.isEmpty()) "unavailable:no-generated-play-action"
                else "available"
            if (knownDamage != null) {
                if (affordable) candidates += card.cost to knownDamage
                evidence += "${card.cardId}/${card.entityName} cost=${card.cost} $availability knownDamage=$knownDamage"
            } else if (card.cardType === CardTypeEnum.SPELL || card.isUncertain || damageText != null) {
                val reason = when {
                    card.isUncertain -> "unknown:uncertain-card-or-missing-interceptor"
                    damageText != null && !parsedRivalEffect -> "unknown:damage-text-without-rival-target-parser"
                    else -> "unknown:missing-damage-parser-or-interceptor"
                }
                unknown += "${card.cardId}:${card.entityName}:$reason"
                if (affordable) availableUnknown += "${card.cardId}:${card.entityName}:$reason"
                evidence += "${card.cardId}/${card.entityName} cost=${card.cost} $availability $reason"
            }
        }
        val maxMana = war.me.usableResource.coerceAtLeast(0)
        return SpellDamageResult(
            damage = bestManaBoundDamage(maxMana, candidates),
            damageOptions = candidates,
            unknownEffects = unknown.distinct(),
            availableUnknownEffects = availableUnknown.distinct(),
            evidence = evidence,
        )
    }

    private fun heroPowerDamage(war: War): HeroPowerResult {
        val power = war.me.playArea.power ?: return HeroPowerResult(0, 0, "unavailable:no-hero-power", null)
        val actions = runCatching { power.action.generatePowerActions(war, war.me) }.getOrDefault(emptyList())
        val available = power.canPower() && power.cost in 0..war.me.usableResource && actions.isNotEmpty()
        val damage = if (!available) 0 else actions.mapNotNull { action ->
            val before = war.rival.playArea.hero?.blood() ?: return@mapNotNull null
            val simulated = runCatching { war.clone() }.getOrNull() ?: return@mapNotNull null
            val clonedPower = simulated.me.playArea.power ?: return@mapNotNull null
            val index = actions.indexOf(action)
            val clonedAction = runCatching {
                clonedPower.action.generatePowerActions(simulated, simulated.me).getOrNull(index)
            }.getOrNull() ?: return@mapNotNull null
            runCatching { clonedAction.simulate.accept(simulated) }.getOrNull() ?: return@mapNotNull null
            ((before - (simulated.rival.playArea.hero?.blood() ?: before)).coerceAtLeast(0))
        }.maxOrNull() ?: 0
        val damageKnown = !available || damage > 0 || actions.isNotEmpty()
        val damageLabel = when {
            !available -> "0"
            damage > 0 -> damage.toString()
            damageKnown -> "0 (no simulated enemy-hero damage)"
            else -> "unknown"
        }
        return HeroPowerResult(
            damage = damage,
            cost = power.cost,
            evidence = "cost=${power.cost} available=$available damage=$damageLabel",
            damageOption = if (available && damage > 0) power.cost to damage else null,
        )
    }

    private fun bestManaBoundDamage(mana: Int, options: List<Pair<Int, Int>>): Int {
        val dp = IntArray(mana.coerceAtLeast(0) + 1)
        options.forEach { (cost, damage) ->
            if (cost < 0 || cost > mana) return@forEach
            for (remaining in mana downTo cost) {
                dp[remaining] = max(dp[remaining], dp[remaining - cost] + damage)
            }
        }
        return dp.maxOrNull() ?: 0
    }

    private fun attackerEvidence(war: War, rivalHeroId: String?): List<String> = buildList {
        war.me.playArea.cards.filter { it.cardType === CardTypeEnum.MINION }.forEach { card ->
            val actions = if (card.canAttack()) {
                runCatching { card.action.generateAttackActions(war, war.me) }.getOrDefault(emptyList())
            } else emptyList()
            val face = actions.any { it.targetIsHero || it.targetEntityId == rivalHeroId }
            val reason = when {
                face -> "face-legal"
                card.isExhausted -> "exhausted"
                card.isFrozen -> "frozen"
                card.isCantAttack -> "cannot-attack"
                card.isDormantAwakenConditionEnchant -> "dormant"
                card.atc <= 0 -> "zero-attack"
                war.rival.playArea.hero?.isImmune == true -> "enemy-hero-immune"
                actions.isNotEmpty() -> if (card.isAttackableByRush) "rush-target-only" else "only-non-face-targets (taunt/target-rule)"
                else -> "no-generated-attack-action"
            }
            add("${card.cardId}/${card.entityName} atk=${card.atc} ready=${!card.isExhausted && !card.isFrozen && !card.isCantAttack} attacks=${card.attackCount} face=$face reason=$reason")
        }
        war.me.playArea.hero?.let { hero ->
            val weaponAttack = war.me.playArea.weapon?.atc?.coerceAtLeast(0) ?: 0
            val attack = max(hero.atc, weaponAttack).coerceAtLeast(0)
            val actions = if (hero.canAttack() || hero.canAttack(ignoreAtc = true)) {
                runCatching { hero.action.generateAttackActions(war, war.me) }.getOrDefault(emptyList())
            } else emptyList()
            val face = actions.any { it.targetIsHero || it.targetEntityId == rivalHeroId }
            val reason = when {
                face -> "face-legal"
                hero.isFrozen -> "frozen"
                hero.isExhausted -> "exhausted"
                hero.isCantAttack -> "cannot-attack"
                attack == 0 -> "zero-attack-no-weapon"
                war.rival.playArea.hero?.isImmune == true -> "enemy-hero-immune"
                else -> "no-generated-face-action (taunt/target-rule)"
            }
            add("HERO/weapon atk=$attack ready=${!hero.isExhausted && !hero.isFrozen && !hero.isCantAttack} attacks=${hero.attackCount} face=$face reason=$reason")
        }
    }

    private fun buildLethalScanSummary(
        war: War, rawAttack: Int, legalMinionAttack: Int, legalHeroAttack: Int,
        attackers: List<String>, heroPower: String, hand: List<String>, enemyHealth: Int,
        enemyHealthOnly: Int, enemyArmor: Int, enemyImmune: Boolean, tauntCount: Int,
        guaranteed: Int, upperBound: String, verdict: String,
    ): String = buildString {
        appendLine("MCTS_LETHAL_SCAN game=${war.me.gameId.ifBlank { "unknown" }} turn=${war.me.turn} warTurn=${war.warTurn}")
        appendLine("  Friendly attack: raw=$rawAttack | legal-face minions=$legalMinionAttack | hero=$legalHeroAttack")
        appendLine("  Enemy: health=$enemyHealthOnly armor=$enemyArmor effective=$enemyHealth immune=$enemyImmune taunts=$tauntCount")
        appendLine("  Hero power: $heroPower")
        appendLine("  Known/unknown hand damage:")
        if (hand.isEmpty()) appendLine("    (none)") else hand.forEach { appendLine("    $it") }
        appendLine("  Friendly minions:")
        if (attackers.isEmpty()) appendLine("    (none)") else attackers.forEach { appendLine("    $it") }
        appendLine("  Damage range: guaranteed=$guaranteed upper=$upperBound | verdict=$verdict")
    }.trimEnd()

    private fun opponentHeroHealFromTaunt(war: War): OpponentHeroHealResult {
        val taunts = war.rival.playArea.cards.filter { it.isTaunt && it.canBeAttacked() && it.isLifesteal }
        if (taunts.isEmpty()) return OpponentHeroHealResult(0, emptyList())
        val unknown = mutableListOf<String>()
        val impacts = mutableListOf<Int>()
        val rivalHeroBefore = war.rival.playArea.hero?.blood() ?: return OpponentHeroHealResult(0, listOf("opponent-taunt-lifesteal:missing-enemy-hero"))
        war.me.playArea.cards.filter { it.canAttack() }.forEach { attacker ->
            val action = runCatching { attacker.action.generateAttackActions(war, war.me) }
                .getOrDefault(emptyList())
                .firstOrNull { candidate -> taunts.any { it.entityId == candidate.targetEntityId } }
                ?: return@forEach
            val beforeDamage = attacker.damage
            val simulated = runCatching { war.clone().also { action.simulate.accept(it) } }.getOrNull()
            if (simulated == null) {
                unknown += "opponent-taunt-lifesteal:clone-failed"
                return@forEach
            }
            val clonedAttacker = simulated.cardMap[attacker.entityId]
            val retaliation = ((clonedAttacker?.damage ?: beforeDamage) - beforeDamage).coerceAtLeast(0)
            val clonedHero = simulated.rival.playArea.hero
                ?: return@forEach
            clonedHero.damage = (clonedHero.damage - retaliation).coerceAtLeast(0)
            impacts += (clonedHero.blood() - rivalHeroBefore).coerceAtLeast(0)
        }
        return OpponentHeroHealResult(impacts.maxOrNull() ?: 0, unknown.distinct())
    }

    private fun gameKey(war: War): String =
        war.me.gameId.takeIf { it.isNotBlank() } ?: war.startTime.toString()

    private fun stateKey(war: War, faceAttackers: List<Card>): String {
        val attackers = war.me.playArea.cards.joinToString(",") {
            "${it.entityId}:${it.cardId}:${it.atc}:${it.damage}:${it.isExhausted}:${it.attackCount}"
        }
        val hero = war.me.playArea.hero?.let { "${it.entityId}:${it.atc}:${it.damage}:${it.isExhausted}" }.orEmpty()
        val weapon = war.me.playArea.weapon?.let { "${it.entityId}:${it.atc}:${it.durability}:${it.damage}" }.orEmpty()
        val rival = war.rival.playArea.hero?.let { "${it.entityId}:${it.bloodLimit()}:${it.damage}" }.orEmpty()
        val face = faceAttackers.joinToString(",") { it.entityId }
        val taunts = war.rival.playArea.cards.filter { it.isTaunt && it.canBeAttacked() }
            .joinToString(",") { "${it.entityId}:${it.health}:${it.damage}" }
        return "turn=${war.warTurn};attackers=$attackers;hero=$hero;weapon=$weapon;rival=$rival;face=$face;taunts=$taunts"
    }

    private fun describeAction(action: Action): Map<String, Any?> = mapOf(
        "type" to action::class.java.simpleName,
        "creatorId" to action.creator?.entityId,
        "cardId" to action.creator?.cardId,
        "targetEntityId" to (action as? AttackAction)?.targetEntityId,
        "targetIsHero" to (action as? AttackAction)?.targetIsHero,
    )
}
