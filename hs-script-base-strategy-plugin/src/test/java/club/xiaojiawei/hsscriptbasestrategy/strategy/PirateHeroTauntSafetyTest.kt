package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.TurnOverAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PirateHeroTauntSafetyTest {

    @Test
    fun `unkillable taunt blocks hero collision and face`() {
        val war = testWar()
        val hero = card("HERO", "hero", CardTypeEnum.HERO, 3, 30)
        val taunt = card("TAUNT_9_5", "taunt", CardTypeEnum.MINION, 9, 5).apply { isTaunt = true }
        war.addCard(hero, war.me.playArea)
        war.addCard(taunt, war.rival.playArea)

        val collision = AttackAction({}, {}, hero, targetEntityId = taunt.entityId)
        val face = AttackAction({}, {}, hero, targetEntityId = war.rival.playArea.hero!!.entityId, targetIsHero = true)

        assertTrue(PirateHeroAttackTargetPolicy.isHeroAttackBlockedByUnkillableTaunt(collision, war))
        assertFalse(PirateHeroAttackTargetPolicy.isLegal(collision, war))
        assertFalse(PirateHeroAttackTargetPolicy.isLegal(face, war))
        assertFalse(PirateDemonHunterMctsExperimentModel.isActionLegal(collision, war))
        assertFalse(PirateWarriorMctsModel.isActionLegal(collision, war))
    }

    @Test
    fun `demon hunter plus one power is deferred when taunt remains unkillable`() {
        val war = testWar()
        val hero = card("HERO", "hero", CardTypeEnum.HERO, 3, 30)
        val taunt = card("TAUNT_9_5", "taunt", CardTypeEnum.MINION, 9, 5).apply { isTaunt = true }
        val power = card(
            PirateHeroAttackTargetPolicy.DEMON_HUNTER_HERO_POWER,
            "power",
            CardTypeEnum.HERO_POWER,
            0,
            1,
        )
        war.addCard(hero, war.me.playArea)
        war.addCard(taunt, war.rival.playArea)
        war.addCard(power, war.me.playArea)

        val action = PowerAction({}, {}, power)
        assertTrue(PirateHeroAttackTargetPolicy.shouldBlockHeroPowerAgainstUnkillableTaunt(action, war))
        assertFalse(PirateDemonHunterMctsExperimentModel.isActionLegal(action, war))
        assertTrue(
            PirateDemonHunterMctsExperimentModel.actionFilterReason(action, war)
                ?.startsWith("HERO_POWER_DEFERRED") == true,
        )
    }

    @Test
    fun `demon hunter plus one power remains legal when it kills taunt`() {
        val war = testWar()
        val hero = card("HERO", "hero", CardTypeEnum.HERO, 4, 30)
        val taunt = card("TAUNT_9_5", "taunt", CardTypeEnum.MINION, 9, 5).apply { isTaunt = true }
        val power = card(
            PirateHeroAttackTargetPolicy.DEMON_HUNTER_HERO_POWER,
            "power",
            CardTypeEnum.HERO_POWER,
            0,
            1,
        )
        war.addCard(hero, war.me.playArea)
        war.addCard(taunt, war.rival.playArea)
        war.addCard(power, war.me.playArea)

        val action = PowerAction({}, {}, power)
        assertFalse(PirateHeroAttackTargetPolicy.shouldBlockHeroPowerAgainstUnkillableTaunt(action, war))
        assertTrue(PirateDemonHunterMctsExperimentModel.isActionLegal(action, war))
    }

    @Test
    fun `demon hunter can end turn when taunt blocks its only hero attack`() {
        val war = testWar().apply { me.resources = 0 }
        val hero = card("HERO", "hero", CardTypeEnum.HERO, 3, 30)
        val taunt = card("TAUNT_9_5", "taunt", CardTypeEnum.MINION, 9, 5).apply { isTaunt = true }
        war.addCard(hero, war.me.playArea)
        war.addCard(taunt, war.rival.playArea)

        assertTrue(PirateDemonHunterMctsExperimentModel.isActionLegal(TurnOverAction, war))
    }

    @Test
    fun `both pirate models block a minion sacrifice into an unkillable taunt`() {
        val warriorWar = testWar()
        val warriorAttacker = card("WARRIOR_ATTACKER", "warrior-attacker", CardTypeEnum.MINION, 3, 2)
        val warriorTaunt = card("TAUNT_9_5", "warrior-taunt", CardTypeEnum.MINION, 9, 5).apply { isTaunt = true }
        warriorWar.addCard(warriorAttacker, warriorWar.me.playArea)
        warriorWar.addCard(warriorTaunt, warriorWar.rival.playArea)

        val demonHunterWar = testWar()
        val demonHunterAttacker = card("DH_ATTACKER", "dh-attacker", CardTypeEnum.MINION, 3, 2)
        val demonHunterTaunt = card("TAUNT_9_5", "dh-taunt", CardTypeEnum.MINION, 9, 5).apply { isTaunt = true }
        demonHunterWar.addCard(demonHunterAttacker, demonHunterWar.me.playArea)
        demonHunterWar.addCard(demonHunterTaunt, demonHunterWar.rival.playArea)

        val warriorAttack = AttackAction({}, {}, warriorAttacker, targetEntityId = warriorTaunt.entityId)
        val demonHunterAttack = AttackAction({}, {}, demonHunterAttacker, targetEntityId = demonHunterTaunt.entityId)

        assertFalse(PirateWarriorMctsModel.isActionLegal(warriorAttack, warriorWar))
        assertFalse(PirateDemonHunterMctsExperimentModel.isActionLegal(demonHunterAttack, demonHunterWar))
        assertTrue(
            PirateWarriorMctsModel.actionFilterReason(warriorAttack, warriorWar)
                ?.startsWith("TAUNT_ATTACK_BLOCKED") == true,
        )
        assertTrue(
            PirateDemonHunterMctsExperimentModel.actionFilterReason(demonHunterAttack, demonHunterWar)
                ?.startsWith("TAUNT_ATTACK_BLOCKED") == true,
        )
    }

    @Test
    fun `combined friendly attacks may still complete a taunt kill`() {
        val war = testWar()
        val first = card("FIRST_ATTACKER", "first-attacker", CardTypeEnum.MINION, 3, 2)
        val second = card("SECOND_ATTACKER", "second-attacker", CardTypeEnum.MINION, 2, 2)
        val taunt = card("TAUNT_5_5", "taunt", CardTypeEnum.MINION, 5, 5).apply { isTaunt = true }
        war.addCard(first, war.me.playArea)
        war.addCard(second, war.me.playArea)
        war.addCard(taunt, war.rival.playArea)

        val attack = AttackAction({}, {}, first, targetEntityId = taunt.entityId)
        assertTrue(PirateWarriorMctsModel.isActionLegal(attack, war))
        assertTrue(PirateDemonHunterMctsExperimentModel.isActionLegal(attack, war))
    }

    private fun testWar(): War {
        val war = War()
        val me = Player(playerId = "me", war = war)
        val rival = Player(playerId = "rival", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        war.isMyTurn = true
        me.resources = 1
        war.addCard(card("RIVAL_HERO", "rival-hero", CardTypeEnum.HERO, 0, 30), rival.playArea)
        return war
    }

    private fun card(cardId: String, entityId: String, type: CardTypeEnum, attack: Int, health: Int): Card =
        Card(TestCardAction()).apply {
            this.cardId = cardId
            this.entityId = entityId
            entityName = cardId
            cardType = type
            cardRace = if (type === CardTypeEnum.MINION) CardRaceEnum.PIRATE else CardRaceEnum.UNKNOWN
            cost = 0
            atc = attack
            this.health = health
            isExhausted = false
            action.belongCard = this
        }
}
