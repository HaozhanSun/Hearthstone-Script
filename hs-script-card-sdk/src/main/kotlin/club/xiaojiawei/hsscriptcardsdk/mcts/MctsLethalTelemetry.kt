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
        val totalAttack: Int,
        val enemyHeroHealth: Int,
        val canLethal: Boolean,
        val decision: String,
        val reason: String,
        val attackableAttackers: Int,
        val faceAttackers: Int,
        val tauntCount: Int,
        val legalFaceSpellDamage: Int,
        val opponentHeroHealImpact: Int,
        val unknownDamageEffects: List<String>,
        val maxReachableNetFaceDamage: Int,
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

        val totalAttack = faceAttackers.sumOf { card ->
            if (card.cardType === CardTypeEnum.HERO) heroAttack else card.atc.coerceAtLeast(0)
        }
        val enemyHeroHealth = rivalHero?.let { (it.bloodLimit() - it.damage).coerceAtLeast(0) } ?: 0
        val spellResult = spellDamage(war)
        val tauntHeal = opponentHeroHealFromTaunt(war)
        val unknown = (spellResult.unknownEffects + tauntHeal.unknownEffects).distinct()
        val maxReachable = (totalAttack + spellResult.damage - tauntHeal.heal).coerceAtLeast(0)
        val canLethal = rivalHero != null && maxReachable >= enemyHeroHealth && unknown.isEmpty()
        val decision = when (selectedAction) {
            is AttackAction -> if (isFace(selectedAction)) "HERO" else "TRADE"
            null -> "NONE"
            else -> "OTHER"
        }
        val reason = when {
            canLethal && selectedAction is AttackAction && decision == "HERO" -> "lethal-face-route-selected"
            canLethal && selectedAction is AttackAction && decision == "TRADE" -> "lethal-route-available-trade-selected"
            canLethal -> "lethal-route-available-non-attack-selected"
            unknown.isNotEmpty() -> "unknown-damage-effects-not-claimed"
            faceAttackers.isEmpty() && tauntCount > 0 -> "taunt-blocks-face"
            faceAttackers.isEmpty() -> "no-current-face-attack"
            else -> "current-face-damage-below-health"
        }
        return Assessment(
            totalAttack = totalAttack,
            enemyHeroHealth = enemyHeroHealth,
            canLethal = canLethal,
            decision = decision,
            reason = reason,
            attackableAttackers = attackableAttackers,
            faceAttackers = faceAttackers.size,
            tauntCount = tauntCount,
            legalFaceSpellDamage = spellResult.damage,
            opponentHeroHealImpact = tauntHeal.heal,
            unknownDamageEffects = unknown,
            maxReachableNetFaceDamage = maxReachable,
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
                    "legalFaceSpellDamage" to assessment.legalFaceSpellDamage,
                    "opponentHeroHealImpact" to assessment.opponentHeroHealImpact,
                    "unknownDamageEffects" to assessment.unknownDamageEffects,
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

    private data class SpellDamageResult(val damage: Int, val unknownEffects: List<String>)
    private data class OpponentHeroHealResult(val heal: Int, val unknownEffects: List<String>)

    private fun spellDamage(war: War): SpellDamageResult {
        val candidates = mutableListOf<Pair<Int, Int>>()
        val unknown = mutableListOf<String>()
        war.me.handArea.cards.forEach { card ->
            if (card.cost < 0 || card.cost > war.me.usableResource) return@forEach
            val damageText = CardUtil.getCardText(card.cardId)?.replace("$", "")?.let(CardUtil::getDamageValue)
            if (card.cardType !== CardTypeEnum.SPELL && damageText == null) return@forEach
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
            if (damage != null) candidates += card.cost to damage
            else if (damageText != null && parsedRivalEffect) candidates += card.cost to damageText
            else if (damageText != null || card.isUncertain) unknown += card.cardId
        }
        val maxMana = war.me.usableResource.coerceAtLeast(0)
        val dp = IntArray(maxMana + 1)
        candidates.forEach { (cost, damage) ->
            for (mana in maxMana downTo cost) dp[mana] = max(dp[mana], dp[mana - cost] + damage)
        }
        return SpellDamageResult(dp.maxOrNull() ?: 0, unknown.distinct())
    }

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
