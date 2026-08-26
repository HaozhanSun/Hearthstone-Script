package club.xiaojiawei.hsscript.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnEndActionGuardTest {

    @Test
    fun `blocks end turn when any of the three action classes remains`() {
        assertTrue(
            TurnEndActionGuard.shouldBlockEndTurn(
                TurnEndActionGuard.TurnEndObservation(attackableMinions = 1, playableHandCards = 0, attackableHero = false),
            ),
        )
        assertTrue(
            TurnEndActionGuard.shouldBlockEndTurn(
                TurnEndActionGuard.TurnEndObservation(attackableMinions = 0, playableHandCards = 1, attackableHero = false),
            ),
        )
        assertTrue(
            TurnEndActionGuard.shouldBlockEndTurn(
                TurnEndActionGuard.TurnEndObservation(attackableMinions = 0, playableHandCards = 0, attackableHero = true),
            ),
        )
        assertTrue(
            TurnEndActionGuard.shouldBlockEndTurn(
                TurnEndActionGuard.TurnEndObservation(
                    attackableMinions = 0,
                    playableHandCards = 0,
                    attackableHero = false,
                    playableHeroPower = true,
                ),
            ),
        )
        assertFalse(
            TurnEndActionGuard.shouldBlockEndTurn(
                TurnEndActionGuard.TurnEndObservation(attackableMinions = 0, playableHandCards = 0, attackableHero = false),
            ),
        )
    }

    @Test
    fun `recognizes mana-playable cards while excluding board-blocked permanents`() {
        assertTrue(TurnEndActionGuard.isHandCardPlayable(CardTypeEnum.SPELL, 3, 3, boardFull = true))
        assertTrue(TurnEndActionGuard.isHandCardPlayable(CardTypeEnum.MINION, 2, 3, boardFull = false))
        assertFalse(TurnEndActionGuard.isHandCardPlayable(CardTypeEnum.MINION, 2, 3, boardFull = true))
        assertFalse(TurnEndActionGuard.isHandCardPlayable(CardTypeEnum.SPELL, 4, 3, boardFull = false))
    }

    @Test
    fun `weapon attack is retained when hero attack stat has not merged yet`() {
        assertTrue(TurnEndActionGuard.hasWeaponBackedHeroAttack(weaponAttack = 2, heroCanAttackIgnoringAttack = true))
        assertFalse(TurnEndActionGuard.hasWeaponBackedHeroAttack(weaponAttack = 0, heroCanAttackIgnoringAttack = true))
        assertFalse(TurnEndActionGuard.hasWeaponBackedHeroAttack(weaponAttack = 2, heroCanAttackIgnoringAttack = false))
    }

    @Test
    fun `hero attack target is the live taunt before the rival hero`() {
        val taunt = Card(TestCardAction()).apply {
            entityId = "taunt"
            cardType = CardTypeEnum.MINION
            health = 3
            isTaunt = true
        }
        val rivalHero = Card(TestCardAction()).apply {
            entityId = "rival-hero"
            cardType = CardTypeEnum.HERO
            health = 30
        }

        assertEquals(
            taunt,
            TurnEndActionGuard.chooseHeroAttackTarget(listOf(taunt), rivalHero),
        )

        taunt.isStealth = true
        assertEquals(
            rivalHero,
            TurnEndActionGuard.chooseHeroAttackTarget(listOf(taunt), rivalHero),
        )
    }

    @Test
    fun `hero power is playable only when canPower and mana are available`() {
        assertTrue(TurnEndActionGuard.isHeroPowerPlayable(powerCost = 1, usableMana = 1, canPower = true))
        assertTrue(TurnEndActionGuard.isHeroPowerPlayable(powerCost = 0, usableMana = 0, canPower = true))
        assertFalse(TurnEndActionGuard.isHeroPowerPlayable(powerCost = 2, usableMana = 1, canPower = true))
        assertFalse(TurnEndActionGuard.isHeroPowerPlayable(powerCost = 1, usableMana = 1, canPower = false))
    }

    @Test
    fun `coin is reserved for a non-coin card it unlocks`() {
        val war = War(false)
        val player = Player(playerId = "me", war = war).apply {
            resources = 1
        }
        war.me = player
        val coin = Card(TestCardAction()).apply {
            entityId = "coin"
            cardType = CardTypeEnum.SPELL
            cost = 0
            isCoinCard = true
        }
        val twoManaMinion = Card(TestCardAction()).apply {
            entityId = "minion"
            cardType = CardTypeEnum.MINION
            cost = 2
        }
        war.addCard(coin, player.handArea)
        war.addCard(twoManaMinion, player.handArea)

        assertTrue(TurnEndActionGuard.hasCoinPayoff(player))

        player.handArea.removeByEntityId(twoManaMinion.entityId)
        assertFalse(TurnEndActionGuard.hasCoinPayoff(player))
    }

    @Test
    fun `end turn color guard blocks yellow and allows green`() {
        val greenSamples = List(5) {
            TurnEndActionGuard.EndTurnColorSample(red = 50, green = 190, blue = 80)
        }
        val yellowSamples = List(5) {
            TurnEndActionGuard.EndTurnColorSample(red = 225, green = 170, blue = 45)
        }

        assertEquals(
            TurnEndActionGuard.EndTurnButtonColor.GREEN,
            TurnEndActionGuard.classifyEndTurnButtonColor(greenSamples),
        )
        assertEquals(
            TurnEndActionGuard.EndTurnButtonColor.YELLOW,
            TurnEndActionGuard.classifyEndTurnButtonColor(yellowSamples),
        )
        assertFalse(
            TurnEndActionGuard.allowsEndTurnForColor(TurnEndActionGuard.EndTurnButtonColor.YELLOW),
        )
        assertTrue(
            TurnEndActionGuard.allowsEndTurnForColor(TurnEndActionGuard.EndTurnButtonColor.GREEN),
        )
        assertFalse(
            TurnEndActionGuard.allowsEndTurnForColor(TurnEndActionGuard.EndTurnButtonColor.UNKNOWN),
        )
        assertFalse(
            TurnEndActionGuard.shouldAllowClearStateFallback(
                TurnEndActionGuard.EndTurnButtonColor.YELLOW,
                clearYellowRetries = 1,
            ),
        )
        assertTrue(
            TurnEndActionGuard.shouldAllowClearStateFallback(
                TurnEndActionGuard.EndTurnButtonColor.YELLOW,
                clearYellowRetries = 2,
            ),
        )
        assertFalse(
            TurnEndActionGuard.shouldAllowClearStateFallback(
                TurnEndActionGuard.EndTurnButtonColor.UNKNOWN,
                clearYellowRetries = 99,
            ),
        )
    }

    @Test
    fun `hand action is not confirmed while the same card remains in hand`() {
        assertFalse(
            TurnEndActionGuard.handCardActionConfirmed(
                entityId = "three-mana-minion",
                remainingHandEntityIds = setOf("three-mana-minion", "other-card"),
            ),
        )
        assertTrue(
            TurnEndActionGuard.handCardActionConfirmed(
                entityId = "three-mana-minion",
                remainingHandEntityIds = setOf("other-card"),
            ),
        )
    }
}
