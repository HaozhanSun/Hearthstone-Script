package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
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
    const val NU_LING_NAGA = "BT_355"

    fun hasAdrenalineFiend(war: War): Boolean =
        war.me.playArea.cards.any { isAdrenalineFiend(it) && it.isAlive() }

    fun isAdrenalineFiend(card: Card): Boolean =
        card.cardId == ADRENALINE_FIEND

    fun isNuLingNaga(card: Card): Boolean =
        card.cardId == NU_LING_NAGA ||
            card.cardId.startsWith("${NU_LING_NAGA}t") ||
            card.cardId == "CORE_$NU_LING_NAGA" ||
            card.cardId.startsWith("CORE_${NU_LING_NAGA}t")

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
}
