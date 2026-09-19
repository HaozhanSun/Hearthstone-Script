package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.util.CardUtil

/**
 * Shared ordering exception for the Pirate Demon Hunter and Pirate Warrior
 * models. A live Adrenaline Fiend makes every friendly Pirate attack a
 * resource-generating step, so the minion-attack-first fence remains
 * necessary while one is on our board. Without one, the hero's attack or
 * hero power may be used before minion attacks to remove a threat safely.
 */
object PirateAttackOrderPolicy {
    const val ADRENALINE_FIEND = "VAC_927"
    const val HOZEN_ROUGHHOUSER = "VAC_938"
    const val NU_LING_NAGA = "BT_355"
    const val TREASURE_DISTRIBUTOR = "TOY_518"

    fun hasAdrenalineFiend(war: War): Boolean =
        war.me.playArea.cards.any { isAdrenalineFiend(it) && it.isAlive() }

    fun isAdrenalineFiend(card: Card): Boolean =
        card.cardId == ADRENALINE_FIEND

    fun isHozenRoughhouser(card: Card): Boolean =
        card.cardId == HOZEN_ROUGHHOUSER ||
            card.cardId == "CORE_$HOZEN_ROUGHHOUSER" ||
            card.cardId.startsWith("${HOZEN_ROUGHHOUSER}t") ||
            card.cardId.startsWith("CORE_${HOZEN_ROUGHHOUSER}t")

    fun isNuLingNaga(card: Card): Boolean =
        card.cardId == NU_LING_NAGA ||
            card.cardId.startsWith("${NU_LING_NAGA}t") ||
            card.cardId == "CORE_$NU_LING_NAGA" ||
            card.cardId.startsWith("CORE_${NU_LING_NAGA}t")

    fun isTreasureDistributor(card: Card): Boolean =
        card.cardId == TREASURE_DISTRIBUTOR ||
            card.cardId == "CORE_$TREASURE_DISTRIBUTOR" ||
            card.cardId.startsWith("${TREASURE_DISTRIBUTOR}t") ||
            card.cardId.startsWith("CORE_${TREASURE_DISTRIBUTOR}t")

    /**
     * Nu Ling Naga is the death-trigger body, so it should attack after the
     * other friendly minions have had their chance to trade. This is a hard
     * sequencing fence, but only while another friendly minion has a legal
     * generated attack; if Naga is the only attacker, it remains available so
     * the policy cannot strand the turn.
     */
    fun shouldDeferNuLingNagaAttack(action: Any, war: War): Boolean {
        if (action !is club.xiaojiawei.hsscriptcardsdk.bean.AttackAction) return false
        val naga = action.creator ?: return false
        if (naga.cardType !== CardTypeEnum.MINION || !isNuLingNaga(naga)) return false

        return war.me.playArea.cards.any { other ->
            other.entityId != naga.entityId &&
                other.cardType === CardTypeEnum.MINION &&
                other.isAlive() &&
                other.canAttack() &&
                runCatching {
                    other.action.generateAttackActions(war, war.me).isNotEmpty()
                }.getOrDefault(false)
        }
    }

    /**
     * Adrenaline Fiend must attack after every other friendly minion. Each
     * other Pirate attack is an opportunity to increase the hero's attack;
     * sending the Fiend first can also sacrifice the source of that effect
     * before those attacks happen.
     *
     * Keep the Fiend available when it is the only remaining minion attacker,
     * so this ordering fence cannot strand a turn.
     */
    fun shouldDeferAdrenalineFiendAttack(action: Any, war: War): Boolean {
        if (action !is AttackAction) return false
        val fiend = action.creator ?: return false
        if (fiend.cardType !== CardTypeEnum.MINION || !isAdrenalineFiend(fiend)) return false

        return war.me.playArea.cards.any { other ->
            other.entityId != fiend.entityId &&
                other.cardType === CardTypeEnum.MINION &&
                !isAdrenalineFiend(other) &&
                other.isAlive() &&
                other.canAttack() &&
                runCatching {
                    other.action.generateAttackActions(war, war.me).isNotEmpty()
                }.getOrDefault(false)
        }
    }

    /**
     * 粗暴的猢狲 (VAC_938) is a one-shot living Battlecry aura source. It
     * must stay alive while the other Pirates attack; otherwise sacrificing it
     * first removes the +1/+1 benefit from the remaining attacks.
     */
    fun shouldDeferHozenRoughhouserAttack(action: Any, war: War): Boolean {
        if (action !is AttackAction) return false
        val hozen = action.creator ?: return false
        if (hozen.cardType !== CardTypeEnum.MINION || !isHozenRoughhouser(hozen)) return false

        return war.me.playArea.cards.any { other ->
            other.entityId != hozen.entityId &&
                other.cardType === CardTypeEnum.MINION &&
                other.isAlive() &&
                other.canAttack() &&
                runCatching {
                    other.action.generateAttackActions(war, war.me).isNotEmpty()
                }.getOrDefault(false)
        }
    }

    /**
     * Treasure Distributor is an ongoing Pirate-enabling body. Keep it alive
     * for the other Pirate attacks first; spending it early can remove the
     * attack bonus from the rest of the combat sequence.
     */
    fun shouldDeferTreasureDistributorAttack(action: Any, war: War): Boolean {
        if (action !is AttackAction) return false
        val distributor = action.creator ?: return false
        if (distributor.cardType !== CardTypeEnum.MINION || !isTreasureDistributor(distributor)) {
            return false
        }

        return war.me.playArea.cards.any { other ->
            other.entityId != distributor.entityId &&
                other.cardType === CardTypeEnum.MINION &&
                other.isAlive() &&
                other.canAttack() &&
                runCatching {
                    other.action.generateAttackActions(war, war.me).isNotEmpty()
                }.getOrDefault(false)
        }
    }

    /** A visible Taunt permits the hero to open combat after hand plays. */
    fun hasAttackableEnemyTaunt(war: War): Boolean =
        CardUtil.getTauntCards(war.rival.playArea.cards, true).any {
            it.cardType === CardTypeEnum.MINION && it.isAlive() && it.canBeAttacked()
        }

    /** Whether the current state can expose a legal hero attack. */
    fun hasHeroAttackAction(war: War): Boolean {
        val hero = war.me.playArea.hero ?: return false
        val weaponBacked = (war.me.playArea.weapon?.atc ?: 0) > 0 &&
            hero.canAttack(ignoreAtc = true)
        if (!hero.canAttack() && !weaponBacked) return false
        return runCatching {
            hero.action.generateAttackActions(war, war.me).isNotEmpty()
        }.getOrDefault(false)
    }

    /** Whether a usable hero-power action is currently generated. */
    fun hasUsableHeroPowerAction(war: War): Boolean {
        val power = war.me.playArea.power ?: return false
        if (war.me.usableResource < power.cost || !power.canPower()) return false
        return runCatching {
            power.action.generatePowerActions(war, war.me).isNotEmpty()
        }.getOrDefault(false)
    }

    /**
     * A weapon-backed hero attack consumes the weapon's current attack window.
     * If the hero power is also usable, it must be spent before that attack;
     * otherwise the power's attack buff cannot affect the attack that follows.
     * This is intentionally a narrow exception to the no-Fiend early-hero
     * override, which remains useful when no weapon is equipped.
     */
    fun shouldUseHeroPowerBeforeWeaponAttack(war: War): Boolean =
        (war.me.playArea.weapon?.atc ?: 0) > 0 && hasUsableHeroPowerAction(war)

    fun isHeroPowerAction(action: Any): Boolean =
        action is PowerAction && action.creator?.cardType === CardTypeEnum.HERO_POWER

    /**
     * A fresh combat re-plan must not turn a now-killable enemy minion into a
     * face attack merely because the MCTS prior prefers face damage.  This is
     * deliberately a direct-kill rule: combined-damage setup remains handled
     * by PirateHeroAttackTargetPolicy, while an already-damaged enemy is
     * removed before any other friendly minion attacks face.
     */
    fun isDirectFriendlyMinionKillAction(
        action: Any,
        war: War,
        attackDamage: (Card, War) -> Int,
    ): Boolean {
        if (action !is AttackAction || action.targetIsHero) return false
        val attacker = action.creator ?: return false
        if (attacker.cardType !== CardTypeEnum.MINION || !attacker.isAlive()) return false
        val targetId = action.targetEntityId ?: return false
        val target = war.rival.playArea.cards.firstOrNull { it.entityId == targetId }
            ?: return false
        if (target.cardType !== CardTypeEnum.MINION || !target.isAlive() || !target.canBeAttacked()) return false

        val requiredDamage = (
            target.bloodLimit() - target.damage + if (target.isDivineShield) 1 else 0
            ).coerceAtLeast(0)
        return attackDamage(attacker, war) >= requiredDamage
    }

    fun hasDirectFriendlyMinionKillAction(
        war: War,
        attackDamage: (Card, War) -> Int,
    ): Boolean = war.me.playArea.cards.any { attacker ->
        attacker.cardType === CardTypeEnum.MINION && attacker.isAlive() && attacker.canAttack() &&
            runCatching {
                attacker.action.generateAttackActions(war, war.me)
                    .any { isDirectFriendlyMinionKillAction(it, war, attackDamage) }
            }.getOrDefault(false)
    }

    /**
     * A friendly minion must not be sacrificed into an unkillable Taunt.
     * Allow the attack when the attacker itself kills the Taunt or when the
     * remaining ready minions can complete a legal combined kill this turn.
     * This is shared by both Pirate MCTS models so a Taunt cannot be treated
     * as a reason to throw away a body in one model but not the other.
     */
    fun isUnkillableTauntMinionAttack(
        action: Any,
        war: War,
        attackDamage: (Card, War) -> Int,
    ): Boolean {
        if (action !is AttackAction || action.targetIsHero) return false
        val attacker = action.creator ?: return false
        if (attacker.cardType !== CardTypeEnum.MINION || !attacker.isAlive()) return false
        val targetId = action.targetEntityId ?: return false
        val target = war.rival.playArea.cards.firstOrNull { it.entityId == targetId }
            ?: return false
        if (target.cardType !== CardTypeEnum.MINION || !target.isTaunt ||
            !target.isAlive() || !target.canBeAttacked()
        ) return false

        val requiredDamage = (
            target.bloodLimit() - target.damage + if (target.isDivineShield) 1 else 0
            ).coerceAtLeast(0)
        val attackerDamage = attackDamage(attacker, war)
        if (attackerDamage >= requiredDamage) return false

        val combinedDamage = war.me.playArea.cards
            .asSequence()
            .filter { candidate ->
                candidate.entityId != attacker.entityId &&
                    candidate.cardType === CardTypeEnum.MINION &&
                    candidate.isAlive() &&
                    candidate.canAttack() &&
                    runCatching {
                        candidate.action.generateAttackActions(war, war.me)
                            .any { it.targetEntityId == target.entityId }
                    }.getOrDefault(false)
            }
            .sumOf { attackDamage(it, war) }

        return attackerDamage + combinedDamage < requiredDamage
    }
}
