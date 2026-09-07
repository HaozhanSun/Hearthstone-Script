package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.InitAction
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import kotlin.test.Test
import kotlin.test.assertTrue

class MonteCarloTreeNodeActionParityTest {
    @Test
    fun `a board card with attack and power exposes both actions`() {
        val war = War()
        val me = Player(playerId = "me", gameId = "game-a", war = war)
        val rival = Player(playerId = "rival", gameId = "game-b", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        war.isMyTurn = true

        val rivalHero = Card(TestDualAction()).apply {
            entityId = "rival-hero"
            cardId = "rival-hero"
            cardType = CardTypeEnum.HERO
            health = 30
            action.belongCard = this
        }
        val dual = Card(TestDualAction()).apply {
            entityId = "dual-minion"
            cardId = "dual-minion"
            cardType = CardTypeEnum.MINION
            atc = 1
            health = 3
            isLaunchpad = true
            action.belongCard = this
        }
        war.addCard(rivalHero, rival.playArea)
        war.addCard(dual, me.playArea)

        val node = MonteCarloTreeNode(
            war,
            InitAction,
            MCTSArg(
                endMillisTime = Long.MAX_VALUE,
                turnCount = 1,
                turnFactor = 0.5,
                countPerTurn = 1,
                scoreCalculator = { 0.0 },
                enableMultiThread = false,
            ),
        )

        assertTrue(node.actions.any { it is AttackAction && it.creator === dual })
        assertTrue(node.actions.any { it is PowerAction && it.creator === dual })
    }

    private class TestDualAction : CardAction(false) {
        override fun generateAttackActions(war: War, player: Player): List<AttackAction> =
            belongCard?.let { card ->
                listOf(AttackAction({}, {}, card, targetEntityId = war.rival.playArea.hero?.entityId))
            }.orEmpty()

        override fun generatePowerActions(war: War, player: Player): List<PowerAction> =
            belongCard?.let { card -> listOf(PowerAction({}, {}, card)) }.orEmpty()

        override fun getCardId(): Array<String> = emptyArray()
        override fun createNewInstance(): CardAction = TestDualAction()
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
    }
}
