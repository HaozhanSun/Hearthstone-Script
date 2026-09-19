package club.xiaojiawei.hsscriptcardsdk.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CardIdentityCoverageTest {
    @Test
    fun `database contains Captain Crowley identity and card facts`() {
        val card = CardDBUtil.queryCardById("CAP_106").firstOrNull()

        assertTrue(card != null)
        assertEquals("克罗雷船长", card?.name)
        assertEquals(127019, card?.dbfId)
        assertEquals(5, card?.cost)
        assertEquals(4, card?.attack)
        assertEquals(5, card?.health)
    }

    @Test
    fun `pirate warrior profile reports remaining database gaps with Mandarin names`() {
        val result = CardIdentityCoverage.inspect(
            PIRATE_WARRIOR_PROFILE,
            resolver = { cardId -> CardDBUtil.queryCardById(cardId).firstOrNull() },
        )

        assertEquals(
            setOf("CAP_104", "CAP_105", "CAP_107"),
            result.missing.map { it.cardId }.toSet(),
        )
        assertEquals(23, result.resolved.size)
        assertTrue(result.diagnosticMessage().contains("炸药工程师(CAP_104)"))
        assertTrue(result.diagnosticMessage().contains("钩手拖曳(CAP_105)"))
        assertTrue(result.diagnosticMessage().contains("火炮长(CAP_107)"))
        assertTrue(result.resolved.any { it.cardId == "CAP_106" && it.expectedName == "克罗雷船长" })
    }

    @Test
    fun `coverage distinguishes parser gap from database gap`() {
        val result = CardIdentityCoverage.inspect(
            listOf(CardIdentityRef("SW_028", "开进码头")),
            resolver = { "database-row" },
            actionResolver = { null },
        )

        assertEquals(listOf("SW_028"), result.actionUnavailable.map { it.cardId })
        assertTrue(result.diagnosticMessage().contains("描述解析器无可执行拦截器"))
        assertTrue(result.diagnosticMessage().contains("开进码头(SW_028)"))
    }

    @Test
    fun `incomplete audit fails closed with actionable card identity`() {
        val result = CardIdentityCoverage.inspect(
            listOf(CardIdentityRef("CAP_107", "火炮长")),
            resolver = { null },
        )

        val failure = assertFailsWith<IllegalStateException> {
            CardIdentityCoverage.requireComplete(result)
        }
        assertTrue(failure.message.orEmpty().contains("牌库缺少卡牌记录"))
        assertTrue(failure.message.orEmpty().contains("火炮长(CAP_107)"))
    }

    @Test
    fun `pirate warrior profile resolves every card name through database or verified catalog`() {
        val identities = PIRATE_WARRIOR_PROFILE.map { ref ->
            CardIdentityCatalog.resolve(ref.cardId, CardDBUtil.queryCardById(ref.cardId).firstOrNull())
        }

        assertTrue(identities.all { it != null && it.name.isNotBlank() })
        assertEquals(
            setOf("CAP_104", "CAP_105", "CAP_107"),
            identities.filter { it?.source == CardIdentitySource.VERIFIED_CATALOG }
                .mapNotNull { it?.cardId }
                .toSet(),
        )
        assertEquals("炸药工程师", CardIdentityCatalog.lookup("CAP_104")?.name)
        assertEquals("钩手拖曳", CardIdentityCatalog.lookup("CAP_105")?.name)
        assertEquals("火炮手", CardIdentityCatalog.lookup("CAP_107t")?.name)
    }

    private companion object {
        // Distinct IDs from the current Pirate Warrior deck profile. Copies
        // do not change identity coverage and are intentionally omitted.
        val PIRATE_WARRIOR_PROFILE = listOf(
            CardIdentityRef("TOY_518", "宝藏经销商"),
            CardIdentityRef("SW_028", "开进码头"),
            CardIdentityRef("OG_312", "恩佐斯的副官"),
            CardIdentityRef("SW_027", "海上威胁"),
            CardIdentityRef("CFM_637", "海盗帕奇斯"),
            CardIdentityRef("CAP_107", "火炮长"),
            CardIdentityRef("DRG_024", "空中悍匪"),
            CardIdentityRef("ETC_372", "掌声雷动"),
            CardIdentityRef("BT_124", "海盗藏品"),
            CardIdentityRef("SW_029", "港口匪徒"),
            CardIdentityRef("CAP_104", "炸药工程师"),
            CardIdentityRef("DRG_056", "空降歹徒"),
            CardIdentityRef("GVG_075", "船载火炮"),
            CardIdentityRef("VAC_430", "血帆征兵员"),
            CardIdentityRef("WW_348", "误炸"),
            CardIdentityRef("CAP_105", "钩手拖曳"),
            CardIdentityRef("NEW1_027", "南海船长"),
            CardIdentityRef("VAC_440", "海关执法者"),
            CardIdentityRef("DRG_025", "海盗之锚"),
            CardIdentityRef("VAC_938", "粗暴的猢狲"),
            CardIdentityRef("CORE_NX2_028", "钩拳-3000型"),
            CardIdentityRef("CORE_REV_018", "雷纳索斯王子"),
            CardIdentityRef("BAR_844", "前锋战斧"),
            CardIdentityRef("YOD_032", "狂暴邪翼蝠"),
            CardIdentityRef("CAP_106", "克罗雷船长"),
            CardIdentityRef("VAC_924", "武器寄存员"),
        )
    }
}
