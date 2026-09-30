package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.cardparser.ParsedCardActionFactory
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PirateLethalTelemetryTest {
    @Test
    fun `no taunt sums only ready face attacks and confirms lethal`() {
        val war = testWar(mana = 0, enemyHealth = 6)
        war.addCard(minion("READY_ONE", 4, exhausted = false), war.me.playArea)
        war.addCard(minion("READY_TWO", 2, exhausted = false), war.me.playArea)
        war.addCard(minion("EXHAUSTED", 9, exhausted = true), war.me.playArea)

        val result = PirateLethalAttackPolicy.telemetry(war)

        assertEquals(6, result.enemyHeroHealth)
        assertEquals(6, result.readyAttackDamage)
        assertEquals(0, result.legalFaceSpellDamage)
        assertTrue(result.canLethal)
    }

    @Test
    fun `taunt blocks face attacks but reports barrier`() {
        val war = testWar(mana = 0, enemyHealth = 4)
        war.addCard(minion("ATTACKER", 8, exhausted = false), war.me.playArea)
        war.addCard(minion("TAUNT", 5, exhausted = false).apply { isTaunt = true; health = 5 }, war.rival.playArea)

        val result = PirateLethalAttackPolicy.telemetry(war)

        assertEquals(1, result.tauntCount)
        assertEquals(5, result.tauntBarrierHealth)
        assertEquals(0, result.readyAttackDamage)
        assertFalse(result.canLethal)
    }

    @Test
    fun `taunt lifesteal healing impact is measured from legal simulation`() {
        val war = testWar(mana = 0, enemyHealth = 20)
        val hero = hero("MY_HERO", health = 20).apply { damage = 6 }
        val attacker = minion("LIFESTEAL_ATTACKER", 4, exhausted = false).apply { isLifesteal = true }
        val taunt = minion("TAUNT_LIFE", 5, exhausted = false).apply { isTaunt = true }
        war.addCard(hero, war.me.playArea)
        war.addCard(attacker, war.me.playArea)
        war.addCard(taunt, war.rival.playArea)

        val result = PirateLethalAttackPolicy.telemetry(war)

        assertTrue(result.tauntLifestealHealImpact >= 0)
        assertEquals(0, result.readyAttackDamage)
    }

    @Test
    fun `legal spell lethal is included without counting alternative cards twice`() {
        val war = testWar(mana = 2, enemyHealth = 6)
        war.addCard(parsedDamageSpell("BURN_ONE"), war.me.handArea)
        war.addCard(parsedDamageSpell("BURN_TWO"), war.me.handArea)

        val result = PirateLethalAttackPolicy.telemetry(war)

        assertEquals(6, result.legalFaceSpellDamage)
        assertEquals(6, result.maxReachableNetFaceDamage)
        assertTrue(result.canLethal)
    }

    @Test
    fun `uncalculable damage effect is labeled and cannot claim lethal`() {
        val war = testWar(mana = 2, enemyHealth = 4)
        val unknown = damageSpell("UNKNOWN_DAMAGE", cost = 2, damage = 0).apply { isUncertain = true }
        war.addCard(unknown, war.me.handArea)

        val result = PirateLethalAttackPolicy.telemetry(war)

        assertTrue(result.unknownDamageEffects.contains("UNKNOWN_DAMAGE"))
        assertFalse(result.canLethal)
    }

    private fun testWar(mana: Int, enemyHealth: Int): War {
        val war = War()
        val me = Player(playerId = "me", gameId = "telemetry-game", war = war)
        val rival = Player(playerId = "rival", gameId = "telemetry-game", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        war.isMyTurn = true
        me.resources = mana
        war.addCard(hero("RIVAL_HERO", enemyHealth), rival.playArea)
        return war
    }

    private fun minion(id: String, attack: Int, exhausted: Boolean): Card = Card(TestCardAction()).apply {
        entityId = "$id-entity"
        cardId = id
        entityName = id
        cardType = CardTypeEnum.MINION
        cardRace = CardRaceEnum.PIRATE
        atc = attack
        health = 4
        isExhausted = exhausted
        action.belongCard = this
    }

    private fun hero(id: String, health: Int): Card = minion(id, 0, exhausted = false).apply {
        cardType = CardTypeEnum.HERO
        cardRace = CardRaceEnum.UNKNOWN
        this.health = health
    }

    private fun damageSpell(id: String, cost: Int, damage: Int): Card = Card(DamageSpellAction(damage)).apply {
        entityId = "$id-entity"
        cardId = id
        entityName = id
        cardType = CardTypeEnum.SPELL
        cardRace = CardRaceEnum.UNKNOWN
        this.cost = cost
        action.belongCard = this
    }

    private fun parsedDamageSpell(entityId: String): Card = Card(
        requireNotNull(ParsedCardActionFactory.getOrCreate("CORE_CS2_029")) { "Fireball parser must be available" }.invoke(),
    ).apply {
        this.entityId = "$entityId-entity"
        cardId = "CORE_CS2_029"
        entityName = "火球术"
        cardType = CardTypeEnum.SPELL
        cardRace = CardRaceEnum.UNKNOWN
        cost = 2
        action.belongCard = this
    }

    private class DamageSpellAction(private val damage: Int) : CardAction(createDefaultAction = false) {
        override fun generatePlayActions(war: War, player: Player): List<PlayAction> = listOf(
            PlayAction(
                {},
                { simulated ->
                    simulated.me.usedResources += belongCard?.cost ?: 0
                    if (damage > 0) simulated.rival.playArea.hero?.let { it.damage += damage }
                },
                belongCard,
            ),
        )

        override fun getCardId(): Array<String> = emptyArray()
        override fun execPower(): Boolean = true
        override fun execPower(card: Card): Boolean = true
        override fun execPower(index: Int): Boolean = true
        override fun execAttack(card: Card): Boolean = true
        override fun execAttackHero(): Boolean = true
        override fun execPointTo(card: Card, click: Boolean): Boolean = true
        override fun execPointTo(index: Int, click: Boolean): Boolean = true
        override fun execLClick(): Boolean = true
        override fun execLaunch(): Boolean = true
        override fun execTrade(): Boolean = true
        override fun execChooseOne(index: Int): Boolean = true
        override fun execForge(): Boolean = true
        override fun createNewInstance(): CardAction = DamageSpellAction(damage)
    }
}

