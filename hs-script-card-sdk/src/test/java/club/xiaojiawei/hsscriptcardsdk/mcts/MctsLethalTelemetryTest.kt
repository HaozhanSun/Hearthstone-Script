package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.cardparser.ParsedCardActionFactory
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.util.CardUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MctsLethalTelemetryTest {
    @Test
    fun `no attackable units reports no lethal`() {
        val war = war()
        val assessment = MctsLethalTelemetry.assess(war)

        assertEquals(0, assessment.totalAttack)
        assertEquals(30, assessment.enemyHeroHealth)
        assertFalse(assessment.canLethal)
        assertEquals("no-current-face-attack", assessment.reason)
    }

    @Test
    fun `counts current minion and weapon backed hero attack`() {
        val war = war(enemyHealth = 8)
        val minion = faceAttacker("pirate", 5)
        war.addCard(minion, war.me.playArea)
        val weapon = card("weapon", CardTypeEnum.WEAPON, attack = 3).apply { durability = 2 }
        war.addCard(weapon, war.me.playArea)
        val hero = faceAttacker("hero", 0, CardTypeEnum.HERO).apply { atc = 0; health = 30 }
        war.addCard(hero, war.me.playArea)

        val assessment = MctsLethalTelemetry.assess(war, hero.action.generateAttackActions(war, war.me).single())

        assertEquals(8, assessment.totalAttack)
        assertEquals(2, assessment.attackableAttackers)
        assertEquals(2, assessment.faceAttackers)
        assertTrue(assessment.canLethal)
        assertEquals("HERO", assessment.decision)
    }

    @Test
    fun `exhausted minion and already spent attacker are excluded`() {
        val war = war(enemyHealth = 3)
        val ready = faceAttacker("ready", 3)
        val exhausted = faceAttacker("exhausted", 9).apply { isExhausted = true }
        war.addCard(ready, war.me.playArea)
        war.addCard(exhausted, war.me.playArea)

        val assessment = MctsLethalTelemetry.assess(war)

        assertEquals(3, assessment.totalAttack)
        assertEquals(1, assessment.attackableAttackers)
        assertEquals(1, assessment.faceAttackers)
        assertTrue(assessment.canLethal)
    }

    @Test
    fun `taunt blocks face damage and trade is reported distinctly`() {
        val war = war(enemyHealth = 1)
        val taunt = card("taunt", CardTypeEnum.MINION, attack = 1).apply {
            health = 5
            isTaunt = true
        }
        war.addCard(taunt, war.rival.playArea)
        val attacker = tradeOnlyAttacker("attacker", taunt.entityId, 7)
        war.addCard(attacker, war.me.playArea)

        val trade = attacker.action.generateAttackActions(war, war.me).single()
        val assessment = MctsLethalTelemetry.assess(war, trade)

        assertEquals(0, assessment.totalAttack)
        assertEquals(1, assessment.attackableAttackers)
        assertEquals(0, assessment.faceAttackers)
        assertFalse(assessment.canLethal)
        assertEquals("TRADE", assessment.decision)
        assertEquals("taunt-blocks-face", assessment.reason)
    }

    @Test
    fun `exact health and overkill both count as lethal`() {
        val exact = war(enemyHealth = 4).apply {
            addCard(faceAttacker("exact", 4), me.playArea)
        }
        val overkill = war(enemyHealth = 4).apply {
            addCard(faceAttacker("one", 1), me.playArea)
            addCard(faceAttacker("three", 3), me.playArea)
        }

        assertTrue(MctsLethalTelemetry.assess(exact).canLethal)
        assertEquals(4, MctsLethalTelemetry.assess(exact).totalAttack)
        assertTrue(MctsLethalTelemetry.assess(overkill).canLethal)
        assertEquals(4, MctsLethalTelemetry.assess(overkill).totalAttack)
    }

    @Test
    fun `dedup emits once for same state and again after a state change`() {
        MctsLethalTelemetry.clearDedupForTests()
        val war = war(gameId = "dedup-game", enemyHealth = 2).apply {
            addCard(faceAttacker("dedup-attacker", 2), me.playArea)
        }
        val root = java.nio.file.Files.createTempDirectory("mcts-lethal-telemetry-").toFile()
        val previousRoot = System.getProperty("hs.script.mcts-replay.dir")
        try {
            System.setProperty("hs.script.mcts-replay.dir", root.absolutePath)
            val first = MctsLethalTelemetry.recordBeforeAttackDecision(war, "Pirate Warrior", 1, null)
            val second = MctsLethalTelemetry.recordBeforeAttackDecision(war, "Pirate Warrior", 1, null)
            assertTrue(first.canLethal)
            assertEquals(first.stateKey, second.stateKey)

            val file = root.resolve("game-${war.me.gameId}-${war.startTime}/decisions.jsonl")
            val text = file.readText()
            assertTrue(text.contains("mcts_lethal_telemetry"))
            assertTrue(text.contains("\"totalAttack\":2"))
            assertTrue(text.contains("\"enemyHeroHealth\":2"))
            assertEquals(1, text.lineSequence().count { it.contains("mcts_lethal_telemetry") })
        } finally {
            if (previousRoot == null) System.clearProperty("hs.script.mcts-replay.dir")
            else System.setProperty("hs.script.mcts-replay.dir", previousRoot)
            root.deleteRecursively()
        }
    }

    @Test
    fun `offline dispatch flow records the pre-action snapshot without changing the action`() {
        MctsLethalTelemetry.clearDedupForTests()
        val war = war(gameId = "offline-flow", enemyHealth = 3)
        val attacker = faceAttacker("offline-attacker", 3)
        war.addCard(attacker, war.me.playArea)
        val attack = attacker.action.generateAttackActions(war, war.me).single()
        val root = java.nio.file.Files.createTempDirectory("mcts-lethal-flow-").toFile()
        val previousRoot = System.getProperty("hs.script.mcts-replay.dir")
        try {
            System.setProperty("hs.script.mcts-replay.dir", root.absolutePath)
            val creatorBefore = attack.creator?.entityId
            val targetBefore = attack.targetEntityId
            val snapshotBefore = MctsReplayTrace.snapshot(war)
            MctsLethalTelemetry.recordBeforeAttackDecision(war, "Pirate DH", 3, attack)
            // Fixture execution is deliberately a no-op, matching a replayed
            // Power.log action while preserving the selected action object.
            attack.exec.accept(war)
            val file = root.resolve("game-${war.me.gameId}-${war.startTime}/decisions.jsonl")
            val text = file.readText()

            assertEquals(creatorBefore, attack.creator?.entityId)
            assertEquals(targetBefore, attack.targetEntityId)
            assertEquals(3, (snapshotBefore["rivalHero"] as Map<*, *>) ["health"])
            assertTrue(text.contains("\"strategy\":\"Pirate DH\""))
            assertTrue(text.contains("\"decision\":\"HERO\""))
            assertTrue(text.contains("\"canLethal\":true"))
            assertEquals(1, text.lineSequence().count { it.contains("mcts_lethal_telemetry") })
        } finally {
            if (previousRoot == null) System.clearProperty("hs.script.mcts-replay.dir")
            else System.setProperty("hs.script.mcts-replay.dir", previousRoot)
            root.deleteRecursively()
        }
    }

    @Test
    fun `elemental mage live observation records health versus max reachable damage`() {
        MctsLethalTelemetry.clearDedupForTests()
        val war = war(gameId = "elemental-lethal", enemyHealth = 4).apply {
            addCard(faceAttacker("elemental-attacker", 4), me.playArea)
        }
        val root = java.nio.file.Files.createTempDirectory("mcts-elemental-lethal-").toFile()
        val previousRoot = System.getProperty("hs.script.mcts-replay.dir")
        try {
            System.setProperty("hs.script.mcts-replay.dir", root.absolutePath)
            val assessment = MctsLethalTelemetry.recordBeforeAttackDecision(
                war = war,
                strategy = "元素法 V1.3",
                step = 3,
                selectedAction = null,
            )
            val file = root.resolve("game-${war.me.gameId}-${war.startTime}/decisions.jsonl")
            val text = file.readText()
            assertEquals(4, assessment.enemyHeroHealth)
            assertEquals(4, assessment.totalAttack)
            assertTrue(assessment.canLethal)
            assertTrue(text.contains("\"strategy\":\"元素法 V1.3\""))
            assertTrue(text.contains("\"totalAttack\":4"))
            assertTrue(text.contains("\"enemyHeroHealth\":4"))
        } finally {
            if (previousRoot == null) System.clearProperty("hs.script.mcts-replay.dir")
            else System.setProperty("hs.script.mcts-replay.dir", previousRoot)
            root.deleteRecursively()
        }
    }

    @Test
    fun `shared telemetry includes legal face spell damage`() {
        val war = war(enemyHealth = 6)
        val fireball = Card(
            requireNotNull(ParsedCardActionFactory.getOrCreate("CORE_CS2_029"))
                .invoke(),
        ).apply {
            entityId = "fireball-entity"
            cardId = "CORE_CS2_029"
            cardType = CardTypeEnum.SPELL
            cost = 2
            action.belongCard = this
        }
        war.me.resources = 2
        war.addCard(card("lifesteal-taunt-for-spell", CardTypeEnum.MINION, 5).apply {
            health = 5
            isTaunt = true
            isLifesteal = true
        }, war.rival.playArea)
        war.addCard(fireball, war.me.handArea)

        val assessment = MctsLethalTelemetry.assess(war)

        assertEquals(6, assessment.legalFaceSpellDamage)
        assertEquals(6, assessment.maxReachableNetFaceDamage)
        assertTrue(assessment.canLethal)
    }

    @Test
    fun `shared telemetry reports enemy lifesteal taunt healing`() {
        val war = war(enemyHealth = 20)
        war.rival.playArea.hero?.let { it.damage = 6 }
        val taunt = card("lifesteal-taunt", CardTypeEnum.MINION, 5).apply {
            health = 6
            isTaunt = true
            isLifesteal = true
        }
        val attacker = Card(CombatAction(taunt.entityId)).apply {
            entityId = "combat-attacker"
            cardId = "combat-attacker"
            cardType = CardTypeEnum.MINION
            atc = 4
            health = 10
            isExhausted = false
        }
        attacker.action.belongCard = attacker
        war.addCard(taunt, war.rival.playArea)
        war.addCard(attacker, war.me.playArea)

        val assessment = MctsLethalTelemetry.assess(war)

        assertEquals(5, assessment.opponentHeroHealImpact)
        assertEquals(0, assessment.maxReachableNetFaceDamage)
        assertFalse(assessment.canLethal)
    }

    @Test
    fun `shared telemetry refuses unknown damage effect`() {
        val war = war(enemyHealth = 1)
        val unknown = card("unknown-damage", CardTypeEnum.SPELL, 0).apply {
            cost = 1
            isUncertain = true
        }
        war.me.resources = 1
        war.addCard(unknown, war.me.handArea)

        val assessment = MctsLethalTelemetry.assess(war)

        assertTrue(assessment.unknownDamageEffects.any { it.contains("unknown-damage") })
        assertFalse(assessment.canLethal)
        assertEquals("UNRESOLVED", assessment.verdict)
        assertTrue(assessment.lethalScanSummary.contains("unknown-damage"))
    }

    @Test
    fun `known face attacks prove lethal even when an optional hand effect is unknown`() {
        val war = war(enemyHealth = 3)
        listOf(4, 4, 2, 3, 9, 2).forEachIndexed { index, attack ->
            war.addCard(faceAttacker("attacker-$index", attack), war.me.playArea)
        }
        // Historical turn 7: seven mana, six face attackers for 24, enemy at
        // 3 effective health, no Taunt, unknown TOY_370 in hand and a legal
        // but non-damage TTN_475 (4 mana). The old all-or-nothing gate denied
        // the known lethal because of TOY_370 and selected TTN_475 first.
        val unknown = card("TOY_370", CardTypeEnum.SPELL, 0).apply {
            entityName = "三芯诡烛"
            cost = 4
        }
        val prematureMinion = card("TTN_475", CardTypeEnum.MINION, 0).apply { cost = 4 }
        war.me.resources = 7
        war.addCard(unknown, war.me.handArea)
        war.addCard(prematureMinion, war.me.handArea)

        val assessment = MctsLethalTelemetry.assess(war)

        assertEquals(24, assessment.totalAttack)
        assertEquals(24, assessment.rawFriendlyAttack)
        assertTrue(assessment.unknownDamageEffects.any { it.contains("TOY_370") })
        assertTrue(assessment.canLethal)
        assertEquals("GUARANTEED_LETHAL", assessment.verdict)
        assertTrue(assessment.lethalScanSummary.contains("raw=24"))
        assertTrue(assessment.lethalScanSummary.contains("legal-face minions=24"))
        assertTrue(assessment.lethalScanSummary.contains("TOY_370/三芯诡烛 cost=4"))
    }

    @Test
    fun `known face attacks plus unknown effects remain unresolved below lethal threshold`() {
        val war = war(enemyHealth = 30)
        war.addCard(faceAttacker("attacker", 4), war.me.playArea)
        val unknown = card("opaque-spell-2", CardTypeEnum.SPELL, 0).apply {
            cost = 1
            isUncertain = true
        }
        war.me.resources = 1
        war.addCard(unknown, war.me.handArea)

        val assessment = MctsLethalTelemetry.assess(war)

        assertFalse(assessment.canLethal)
        assertEquals("UNRESOLVED", assessment.verdict)
        assertEquals("unknown", assessment.possibleDamageUpperBound)
    }

    @Test
    fun `telemetry separates raw attack from legal face attack and reports immune hero`() {
        val war = war(enemyHealth = 3)
        val asleep = faceAttacker("asleep", 9).apply { isExhausted = true }
        val ready = faceAttacker("ready", 2)
        war.addCard(asleep, war.me.playArea)
        war.addCard(ready, war.me.playArea)

        val assessment = MctsLethalTelemetry.assess(war)

        assertEquals(11, assessment.rawFriendlyAttack)
        assertEquals(2, assessment.totalAttack)
        assertTrue(assessment.attackerEvidence.any { it.contains("asleep") && it.contains("reason=exhausted") })
        assertTrue(assessment.lethalScanSummary.contains("effective=3"))

        war.rival.playArea.hero?.isImmune = true
        val immune = MctsLethalTelemetry.assess(war)
        assertFalse(immune.canLethal)
        assertEquals("NOT_LETHAL_IMMUNE", immune.verdict)
    }

    private fun war(gameId: String = "lethal-game", enemyHealth: Int = 30): War = War(false).apply {
        startTime = 123L
        me = Player(playerId = "me", gameId = gameId, war = this)
        rival = Player(playerId = "rival", gameId = "rival", war = this)
        player1 = me
        player2 = rival
        currentPlayer = me
        isMyTurn = true
        addCard(card("rival-hero", CardTypeEnum.HERO, 0).apply { health = enemyHealth }, rival.playArea)
    }

    private fun faceAttacker(id: String, attack: Int, type: CardTypeEnum = CardTypeEnum.MINION): Card =
        Card(faceAttackAction()).apply {
            entityId = id
            cardId = id
            cardType = type
            atc = attack
            health = 10
            isExhausted = false
        }

    private fun tradeOnlyAttacker(id: String, targetId: String, attack: Int): Card =
        Card(FixtureAction(face = false, targetId = targetId)).apply {
            entityId = id
            cardId = id
            cardType = CardTypeEnum.MINION
            atc = attack
            health = 10
            isExhausted = false
        }

    private fun card(id: String, type: CardTypeEnum, attack: Int): Card = Card(FixtureAction(face = false)).apply {
        entityId = id
        cardId = id
        cardType = type
        atc = attack
        health = 10
    }

    private fun faceAttackAction(): CardAction = FixtureAction(face = true)

    private open class FixtureAction(
        private val face: Boolean,
        private val targetId: String? = null,
    ) : CardAction(createDefaultAction = false) {
        override fun generateAttackActions(war: War, player: Player): List<AttackAction> =
            if (face || targetId != null) {
                listOf(
                    AttackAction(
                        {},
                        {},
                        belongCard,
                        targetEntityId = targetId ?: war.rival.playArea.hero?.entityId,
                        targetIsHero = face,
                    ),
                )
            } else emptyList()

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

    private class CombatAction(private val targetId: String) : FixtureAction(face = false, targetId = targetId) {
        override fun generateAttackActions(war: War, player: Player): List<AttackAction> = listOf(
            AttackAction(
                {},
                { simulated ->
                    val attacker = belongCard?.entityId?.let { simulated.cardMap[it] }
                    val target = simulated.cardMap[targetId]
                    if (attacker != null && target != null) CardUtil.simulateAttack(simulated, attacker, target)
                },
                belongCard,
                targetEntityId = targetId,
            ),
        )

        override fun createNewInstance(): CardAction = CombatAction(targetId)
    }
}
