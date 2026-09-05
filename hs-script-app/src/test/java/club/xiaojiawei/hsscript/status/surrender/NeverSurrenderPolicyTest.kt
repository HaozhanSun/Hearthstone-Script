package club.xiaojiawei.hsscript.status.surrender

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class NeverSurrenderPolicyTest {

    @Test
    fun `setting is effective only for beta channel`() {
        assertTrue(NeverSurrenderPolicy.enabledForChannel("beta", true))
        assertTrue(NeverSurrenderPolicy.enabledForChannel(" BETA ", true))
        assertFalse(NeverSurrenderPolicy.enabledForChannel("stable", true))
        assertFalse(NeverSurrenderPolicy.enabledForChannel("beta", false))
        assertFalse(NeverSurrenderPolicy.enabledForChannel("unknown", true))
    }

    @Test
    fun `rank seven remains fail closed instead of being played when never surrender is enabled`() {
        assertTrue(NeverSurrenderPolicy.rankIsIneligible(7))
        assertFalse(NeverSurrenderPolicy.rankIsIneligible(5))
        assertFalse(NeverSurrenderPolicy.rankIsIneligible(10))
        assertTrue(SurrenderPolicy.evaluateCurrentRank(7)!!.shouldSurrender)
    }

    @Test
    fun `streak guard thresholds retain seven-concession pause and five-win surrender semantics`() {
        val surrenderGuard = SurrenderPolicy.evaluatePersistentStreakGuard(
            PersistentStreakSnapshot(consecutiveSurrenders = 7, consecutiveWins = 0),
        )
        val winGuard = SurrenderPolicy.evaluatePersistentStreakGuard(
            PersistentStreakSnapshot(consecutiveSurrenders = 0, consecutiveWins = 5),
        )
        assertTrue(surrenderGuard?.reason?.contains("threshold=7") == true)
        assertTrue(winGuard?.reason?.contains("threshold=5") == true)
        assertFalse(SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 7, consecutiveWins = 0),
        )?.shouldSurrender == true)
        assertTrue(SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 0, consecutiveWins = 5),
        )?.shouldSurrender == true)
    }

    @Test
    fun `never surrender cannot bypass seven-concession fail-closed block`() {
        val decision = SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 7, consecutiveWins = 0),
        )!!

        assertFalse(decision.shouldSurrender)
        assertTrue(decision.blocksAutomaticSurrender)
        assertTrue(
            SurrenderPolicy.applyNeverSurrenderStreakPolicy(decision, neverSurrenderEnabled = true)
                ?.blocksAutomaticSurrender == true,
        )
    }

    @Test
    fun `beta setting is visible and uses the persisted config switch`() {
        val root = repositoryRoot()
        val fxml = Files.readString(root.resolve("hs-script-app/src/main/resources/fxml/settings/strategySettings.fxml"))
        val configEnum = Files.readString(root.resolve("hs-script-app/src/main/java/club/xiaojiawei/hsscript/enums/ConfigEnum.kt"))
        val gameUtil = Files.readString(root.resolve("hs-script-app/src/main/java/club/xiaojiawei/hsscript/utils/GameUtil.kt"))
        assertTrue(fxml.contains("Never Surrender（永不投降）"))
        assertTrue(fxml.contains("beta=\"true\""))
        assertTrue(fxml.contains("config=\"NEVER_SURRENDER\""))
        assertTrue(fxml.contains("对方英雄非原皮投降"))
        assertTrue(fxml.contains("config=\"OPPONENT_HERO_NON_ORIGINAL_SURRENDER\""))
        val surrenderSectionStart = fxml.indexOf("text=\"投降\"")
        val toggleLabel = fxml.indexOf("对方英雄非原皮投降")
        val surrenderSectionEnd = fxml.indexOf("</TitledPane>", surrenderSectionStart)
        assertTrue(surrenderSectionStart >= 0)
        assertTrue(toggleLabel > surrenderSectionStart && toggleLabel < surrenderSectionEnd)
        assertTrue(configEnum.contains("NEVER_SURRENDER"))
        assertTrue(configEnum.contains("OPPONENT_HERO_NON_ORIGINAL_SURRENDER"))
        assertTrue(gameUtil.contains("NeverSurrenderPolicy.blockSurrender"))
    }

    private fun repositoryRoot(): Path {
        val current = Path.of("").toAbsolutePath().normalize()
        return sequenceOf(current, current.parent)
            .first { Files.isRegularFile(it.resolve("release-channel.json")) }
    }
}
