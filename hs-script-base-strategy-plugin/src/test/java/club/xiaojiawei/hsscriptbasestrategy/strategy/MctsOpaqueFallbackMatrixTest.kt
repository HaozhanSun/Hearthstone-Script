package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.InitAction
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsCardDiagnostics
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsDecisionModel
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeNode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The global fallback contract must not depend on a deck-specific model.
 * Exercise the actual root action scan through all three current Pirate
 * Warrior, Pirate DH, and Elemental Mage models.
 */
class MctsOpaqueFallbackMatrixTest {

    private val models: List<Pair<String, MctsDecisionModel>> = listOf(
        "Pirate Warrior" to PirateWarriorMctsModel,
        "Pirate DH" to PirateDemonHunterMctsExperimentModel,
        "Elemental Mage" to ElementalMageMctsModel,
    )

    @Test
    fun `generic uncertain minion and weapon remain actionable for every model`() {
        models.forEach { (label, model) ->
            listOf(
                card("MATRIX_MINION", CardTypeEnum.MINION),
                card("MATRIX_WEAPON", CardTypeEnum.WEAPON),
            ).forEach { candidate ->
                candidate.isUncertain = true
                val war = testWar()
                war.addCard(candidate, war.me.handArea)

                assertTrue(
                    MctsCardDiagnostics.braveOpaqueFallbackAllowed(candidate),
                    "$label should allow the shared fallback for ${candidate.cardType}",
                )
                assertTrue(
                    rootHasCandidate(war, model, candidate),
                    "$label root scan should expose ${candidate.cardType} as an action",
                )
            }
        }
    }

    @Test
    fun `battlecry discover and target hinted cards remain late candidates for every model`() {
        models.forEach { (label, model) ->
            val cases = listOf(
                card("MATRIX_BATTLECRY", CardTypeEnum.MINION).apply {
                    isUncertain = true
                    isBattlecry = true
                },
                card("MATRIX_DISCOVER", CardTypeEnum.SPELL).apply {
                    isUncertain = true
                    isDiscover = true
                },
                card("MATRIX_TARGET_HINT", CardTypeEnum.SPELL).apply {
                    isUncertain = true
                    entityName = "选择一个敌方目标"
                },
            )
            cases.forEach { candidate ->
                assertTrue(
                    MctsCardDiagnostics.braveOpaqueFallbackAllowed(candidate),
                    "$label should allow one late generic initiation for ${candidate.cardId} type=${candidate.cardType} uncertain=${candidate.isUncertain} stats=${candidate.atc}/${candidate.health} reason=${MctsCardDiagnostics.opaqueFallbackBlockReason(candidate)}",
                )
                val war = testWar()
                war.addCard(candidate, war.me.handArea)
                assertTrue(
                    rootHasCandidate(war, model, candidate),
                    "$label root scan should expose one late generic attempt for ${candidate.cardId}",
                )
            }
        }
    }

    @Test
    fun `invalid snapshot fails closed for every model`() {
        models.forEach { (label, model) ->
            val candidate = card("MATRIX_INVALID", CardTypeEnum.INVALID).apply {
                isUncertain = true
                entityName = "UNKNOWN ENTITY [cardType=INVALID]"
            }
            assertFalse(MctsCardDiagnostics.braveOpaqueFallbackAllowed(candidate))
            val war = testWar()
            war.addCard(candidate, war.me.handArea)
            assertFalse(
                rootHasCandidate(war, model, candidate),
                "$label root scan must reject an invalid snapshot",
            )
        }
    }

    private fun rootHasCandidate(war: War, model: MctsDecisionModel, candidate: Card): Boolean {
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
                decisionModel = model,
                experimentalSearch = true,
            ),
        )
        return node.actions.any { it.creator?.entityId == candidate.entityId }
    }

    private fun card(id: String, type: CardTypeEnum): Card = Card(CommonUnknownCardAction()).apply {
        entityId = "$id-entity"
        cardId = id
        entityName = "UNKNOWN ENTITY [cardType=${type.name}]"
        cardType = type
        cost = 1
        health = 3
        atc = 2
        action.belongCard = this
    }

    private fun testWar(): War {
        val war = War()
        val me = Player(playerId = "me", gameId = "matrix-game", war = war)
        val rival = Player(playerId = "rival", gameId = "matrix-game", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        war.isMyTurn = true
        me.resources = 5
        return war
    }

    /** Same parser-shaped common action used by the real unresolved-card path. */
    private class CommonUnknownCardAction : CardAction(createDefaultAction = false, common = true) {
        override fun getCardId(): Array<String> = emptyArray()
        override fun execPower(): Boolean = true
        override fun execPower(card: Card): Boolean = true
        override fun execPower(index: Int): Boolean = true
        override fun execAttack(card: Card): Boolean = true
        override fun execAttackHero(): Boolean = true
        override fun execPointTo(card: Card, click: Boolean): Boolean = true
        override fun execPointTo(index: Int, click: Boolean): Boolean = true
        override fun createNewInstance(): CardAction = this
        override fun execLClick(): Boolean = true
        override fun execLaunch(): Boolean = true
        override fun execTrade(): Boolean = true
        override fun execChooseOne(index: Int): Boolean = true
        override fun execForge(): Boolean = true
    }
}
