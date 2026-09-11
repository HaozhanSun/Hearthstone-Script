package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsLethalTelemetry
import kotlin.test.Test
import kotlin.test.assertTrue

/** Both released Pirate models share the same live-action lethal contract. */
class PirateMctsLethalTelemetryContractTest {
    @Test
    fun `warrior and demon hunter models agree on a current face lethal`() {
        val war = War(false)
        val me = Player(playerId = "me", gameId = "contract-game", war = war)
        val rival = Player(playerId = "rival", gameId = "rival", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        war.isMyTurn = true

        val rivalHero = card("rival-hero", CardTypeEnum.HERO, 0).apply { health = 2 }
        val attacker = card("pirate-attacker", CardTypeEnum.MINION, 3)
        war.addCard(rivalHero, rival.playArea)
        war.addCard(attacker, me.playArea)

        val attack = attacker.action.generateAttackActions(war, me)
            .single { it.targetIsHero }
        val assessment = MctsLethalTelemetry.assess(war, attack)

        assertTrue(assessment.canLethal)
        assertTrue(PirateWarriorMctsModel.isLethalAction(attack, war))
        assertTrue(PirateDemonHunterMctsExperimentModel.isLethalAction(attack, war))
    }

    private fun card(id: String, type: CardTypeEnum, attack: Int): Card = Card(FixtureFaceAction()).apply {
        entityId = id
        cardId = id
        cardType = type
        atc = attack
        health = 10
        isExhausted = false
    }

    private class FixtureFaceAction : CardAction(createDefaultAction = false) {
        override fun generateAttackActions(war: War, player: Player): List<AttackAction> =
            listOf(AttackAction({}, {}, belongCard, targetEntityId = war.rival.playArea.hero?.entityId, targetIsHero = true))

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
        override fun createNewInstance(): CardAction = this
    }
}
