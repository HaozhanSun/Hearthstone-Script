package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.data.CardInfoData
import club.xiaojiawei.hsscriptcardsdk.enums.CardActionEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.PirateDamageAuraPolicy
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsReplayTrace
import club.xiaojiawei.hsscriptcardsdk.util.CardUtil
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Shared first-pass lethal calculation for the two Pirate MCTS models.
 *
 * The calculation only counts attack actions that the normal action generator
 * currently exposes against the opposing hero. That makes taunt, rush-only
 * attacks, exhausted attackers, immune heroes, and other target restrictions
 * part of the calculation instead of treating printed attack as automatically
 * face damage.
 */
object PirateLethalAttackPolicy {
    private val lastLoggedLethalState = AtomicReference<String?>(null)

    data class Telemetry(
        val enemyHeroHealth: Int,
        val readyAttackDamage: Int,
        val legalFaceSpellDamage: Int,
        val tauntCount: Int,
        val tauntBarrierHealth: Int,
        val opponentHeroHealImpact: Int,
        val unknownDamageEffects: List<String>,
        val maxReachableNetFaceDamage: Int,
        val canLethal: Boolean,
        val reason: String,
    ) {
        fun details(): Map<String, Any?> = mapOf(
            "enemyHeroHealth" to enemyHeroHealth,
            "readyAttackDamage" to readyAttackDamage,
            "legalFaceSpellDamage" to legalFaceSpellDamage,
            "tauntCount" to tauntCount,
            "tauntBarrierHealth" to tauntBarrierHealth,
            "opponentHeroHealImpact" to opponentHeroHealImpact,
            "unknownDamageEffects" to unknownDamageEffects,
            "maxReachableNetFaceDamage" to maxReachableNetFaceDamage,
            "canLethal" to canLethal,
        )
    }

    /**
     * Explainable, bounded endgame calculation for telemetry only. It uses
     * the same generated actions that the MCTS root can execute, simulates
     * each candidate on a clone, and uses a small 0/1 mana knapsack so
     * mutually-exclusive target choices are not summed optimistically.
     */
    fun telemetry(war: War): Telemetry {
        val rivalHero = war.rival.playArea.hero
            ?: return Telemetry(0, 0, 0, 0, 0, 0, emptyList(), 0, false, "missing-enemy-hero")
        val enemyHealth = rivalHero.blood().coerceAtLeast(0)
        val taunts = CardUtil.getTauntCards(war.rival.playArea.cards, false)
        val attackActions = attackActions(war)
        val faceActions = attackActions.filter { isFaceAction(it, war) && isLegalFaceAction(it, war) }
        val readyAttackDamage = faceActions.sumOf { attackDamage(it, war) }
        val tauntHealing = opponentHeroHealFromTaunt(war, attackActions, taunts)
        val spellResult = spellDamage(war)
        val unknownEffects = (spellResult.unknownEffects + tauntHealing.unknownEffects).distinct()
        // Taunt-clearing attack sequences are intentionally not projected here:
        // only legal face attacks are counted, so a taunt can never be silently
        // cleared and followed by an optimistic face attack in the same estimate.
        val maxDamage = (readyAttackDamage + spellResult.damage - tauntHealing.heal).coerceAtLeast(0)
        val lethal = enemyHealth > 0 && maxDamage >= enemyHealth && unknownEffects.isEmpty()
        val reason = when {
            lethal -> "reachable-face-damage-confirmed"
            enemyHealth <= 0 -> "enemy-hero-already-dead"
            unknownEffects.isNotEmpty() -> "unknown-damage-effects-not-claimed"
            taunts.isNotEmpty() && readyAttackDamage == 0 -> "taunt-blocks-face-attacks"
            maxDamage == 0 -> "no-reachable-face-damage"
            else -> "reachable-damage-below-health"
        }
        return Telemetry(
            enemyHealth,
            readyAttackDamage,
            spellResult.damage,
            taunts.size,
            taunts.sumOf { it.blood().coerceAtLeast(0) },
            tauntHealing.heal,
            unknownEffects,
            maxDamage,
            lethal,
            reason,
        )
    }

    fun recordTelemetry(war: War, stage: String = "TURN_START") {
        val result = telemetry(war)
        MctsReplayTrace.record(
            war,
            "MCTS_LETHAL_TELEMETRY",
            result.reason,
            result.details() + ("stage" to stage),
        )
        if (result.canLethal) {
            log.info {
                "MCTS_LETHAL_TELEMETRY enemyHeroHealth=${result.enemyHeroHealth} " +
                    "readyAttackDamage=${result.readyAttackDamage} " +
                    "legalFaceSpellDamage=${result.legalFaceSpellDamage} " +
                    "maxReachableNetFaceDamage=${result.maxReachableNetFaceDamage} " +
                    "tauntCount=${result.tauntCount} decision=LETHAL reason=${result.reason}"
            }
        }
    }

    fun isLethalFaceAction(action: Action, war: War): Boolean {
        if (action !is AttackAction || !isFaceAction(action, war)) return false
        // A taunt means a generated face action is not a legal lethal route,
        // even if the parser has momentarily exposed that target.
        if (CardUtil.getTauntCards(war.rival.playArea.cards, false).isNotEmpty()) return false
        val rivalHero = war.rival.playArea.hero ?: return false
        val remainingLife = (rivalHero.bloodLimit() - rivalHero.damage).coerceAtLeast(0)
        val totalFaceAttack = legalFaceDamage(war)
        val lethal = totalFaceAttack >= remainingLife
        if (!lethal) {
            lastLoggedLethalState.set(null)
            return false
        }
        val stateKey = "${war.me.turn}|$totalFaceAttack|$remainingLife"
        if (lastLoggedLethalState.getAndSet(stateKey) != stateKey) {
            log.info {
                "海盗MCTS斩杀检查：总场攻=$totalFaceAttack，敌方英雄血量=$remainingLife，可斩杀"
            }
        }
        return true
    }

    fun legalFaceDamage(war: War): Int {
        val me = war.me
        val minionDamage = me.playArea.cards
            .asSequence()
            .filter { it.cardType === CardTypeEnum.MINION && it.canAttack() }
            .filter { card ->
                runCatching { card.action.generateAttackActions(war, me) }
                    .getOrDefault(emptyList())
                    .any { isFaceAction(it, war) }
            }
            .sumOf { PirateDamageAuraPolicy.outgoingDamage(it, it.atc, war) }

        val heroDamage = me.playArea.hero?.let { hero ->
            val currentAttack = PirateHeroAttackTargetPolicy.effectiveHeroAttack(hero, war)
            if (currentAttack <= 0) return@let 0

            val canAttack = hero.canAttack() || hero.canAttack(ignoreAtc = true)
            if (!canAttack) return@let 0
            val hasFaceAction = runCatching { hero.action.generateAttackActions(war, me) }
                .getOrDefault(emptyList())
                .any { isFaceAction(it, war) }
            if (hasFaceAction) currentAttack else 0
        } ?: 0

        return minionDamage + heroDamage
    }

    private data class SpellDamageResult(val damage: Int, val unknownEffects: List<String>)

    private data class SpellCandidate(val cardId: String, val cost: Int, val damage: Int)

    private fun spellDamage(war: War): SpellDamageResult {
        val candidates = mutableListOf<SpellCandidate>()
        val unknown = mutableListOf<String>()
        for (card in war.me.handArea.cards) {
            if (card.cost < 0 || card.cost > war.me.usableResource) continue
            val damageText = CardUtil.getCardText(card.cardId)
                ?.replace("$", "")
                ?.let(CardUtil::getDamageValue)
            if (card.cardType !== CardTypeEnum.SPELL && damageText == null) continue
            val actions = runCatching { card.action.generatePlayActions(war, war.me) }.getOrDefault(emptyList())
            val damage = actions.mapIndexedNotNull { index, _ ->
                val simulated = war.clone()
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
            if (damage != null) {
                candidates += SpellCandidate(card.entityId, card.cost, damage)
            } else if (damageText != null && parsedRivalEffect) {
                // CardInfoData is authoritative about the rival-target route;
                // use the parsed damage amount when a clone cannot expose a
                // hero object (for example in a partial replay snapshot).
                // This remains bounded to one copy/card.
                candidates += SpellCandidate(card.entityId, card.cost, damageText)
            } else if (damageText != null || card.isUncertain) {
                unknown += card.cardId
            }
        }
        if (candidates.isEmpty()) return SpellDamageResult(0, unknown.distinct())
        val bestByCard = candidates.groupBy { it.cardId }.values.mapNotNull { it.maxByOrNull(SpellCandidate::damage) }
        val maxMana = war.me.usableResource.coerceAtLeast(0)
        val dp = IntArray(maxMana + 1)
        bestByCard.forEach { candidate ->
            for (mana in maxMana downTo candidate.cost) {
                dp[mana] = max(dp[mana], dp[mana - candidate.cost] + candidate.damage)
            }
        }
        return SpellDamageResult(dp.maxOrNull() ?: 0, unknown.distinct())
    }

    private fun attackActions(war: War): List<AttackAction> = buildList {
        war.me.playArea.cards.filter { it.canAttack() }.forEach { card ->
            addAll(runCatching { card.action.generateAttackActions(war, war.me) }.getOrDefault(emptyList()))
        }
        war.me.playArea.hero?.takeIf { it.canAttack() }?.let { hero ->
            addAll(runCatching { hero.action.generateAttackActions(war, war.me) }.getOrDefault(emptyList()))
        }
    }

    private fun attackDamage(action: AttackAction, war: War): Int {
        val creator = action.creator ?: return 0
        return if (creator.cardType === CardTypeEnum.HERO) {
            war.me.playArea.weapon?.atc?.coerceAtLeast(0) ?: creator.atc.coerceAtLeast(0)
        } else creator.atc.coerceAtLeast(0)
    }

    private fun isLegalFaceAction(action: AttackAction, war: War): Boolean =
        CardUtil.getTauntCards(war.rival.playArea.cards, false).isEmpty()

    private data class OpponentHeroHealResult(val heal: Int, val unknownEffects: List<String>)

    /**
     * A lifesteal taunt heals its own hero from the retaliation damage it
     * deals, not from our attacker's lifesteal flag.  The generic attack
     * simulator currently models the combat damage but does not apply that
     * opposing-hero heal, so apply only that bounded effect on the clone used
     * for telemetry.  If the action cannot be replayed on a clone, report the
     * effect as unknown instead of claiming lethal optimistically.
     */
    private fun opponentHeroHealFromTaunt(
        war: War,
        actions: List<AttackAction>,
        taunts: List<club.xiaojiawei.hsscriptcardsdk.bean.Card>,
    ): OpponentHeroHealResult {
        if (taunts.isEmpty()) return OpponentHeroHealResult(0, emptyList())
        val candidates = actions.filter { action -> taunts.any { it.entityId == action.targetEntityId } }
        if (candidates.isEmpty()) return OpponentHeroHealResult(0, emptyList())
        val impacts = mutableListOf<Int>()
        val unknown = mutableListOf<String>()
        candidates.forEach { action ->
            val taunt = taunts.firstOrNull { it.entityId == action.targetEntityId }
                ?: return@forEach
            if (!taunt.isLifesteal) return@forEach
            val creator = action.creator
            val beforeAttackerDamage = creator?.damage ?: 0
            val simulated = runCatching { war.clone().also { action.simulate.accept(it) } }.getOrNull()
            if (simulated == null) {
                unknown += "opponent-taunt-lifesteal:${taunt.cardId}:clone-failed"
                return@forEach
            }
            val simulatedAttacker = creator?.entityId?.let { simulated.cardMap[it] }
            val retaliation = ((simulatedAttacker?.damage ?: beforeAttackerDamage) - beforeAttackerDamage).coerceAtLeast(0)
            val enemyHero = simulated.rival.playArea.hero
            if (enemyHero == null) {
                unknown += "opponent-taunt-lifesteal:${taunt.cardId}:missing-enemy-hero"
                return@forEach
            }
            val beforeHeroDamage = enemyHero.damage
            // Lifesteal restores hero health; damage is the model's inverse
            // representation of missing health.
            enemyHero.damage = (beforeHeroDamage - retaliation).coerceAtLeast(0)
            impacts += (enemyHero.blood() - (war.rival.playArea.hero?.blood() ?: enemyHero.blood())).coerceAtLeast(0)
        }
        return OpponentHeroHealResult(impacts.maxOrNull() ?: 0, unknown.distinct())
    }

    private fun isFaceAction(action: AttackAction, war: War): Boolean {
        val rivalHeroId = war.rival.playArea.hero?.entityId
        return action.targetIsHero || action.targetEntityId == rivalHeroId
    }
}
