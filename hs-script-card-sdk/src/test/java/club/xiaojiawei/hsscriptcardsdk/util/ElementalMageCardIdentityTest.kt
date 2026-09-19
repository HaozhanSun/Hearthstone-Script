package club.xiaojiawei.hsscriptcardsdk.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Evidence test for the cards observed in the current Elemental Mage run. */
class ElementalMageCardIdentityTest {
    @Test
    fun `current elemental cards are database-known even when parser lacks interceptor`() {
        val expected = listOf(
            "TTN_095" to "流水档案管理员",
            "DEEP_034" to "页岩蛛",
            "TOY_370" to "三芯诡烛",
            "DMF_100" to "甜点飓风",
            "CORE_UNG_809" to "火羽精灵",
            "GDB_302" to "吸积炽焰",
            "TOY_000" to "焦油泥浆怪",
            "WW_424" to "溢流熔岩",
            "EX1_015" to "工程师学徒",
        ).map { (cardId, name) -> CardIdentityRef(cardId, name) }

        val result = CardIdentityCoverage.inspect(
            expected,
            resolver = { cardId -> CardDBUtil.queryCardById(cardId).firstOrNull() },
        )

        assertTrue(result.missing.isEmpty(), result.diagnosticMessage())
        assertEquals(expected.size, result.resolved.size)
        expected.forEach { ref ->
            val databaseCard = CardDBUtil.queryCardById(ref.cardId).firstOrNull()
            assertEquals(ref.expectedName, databaseCard?.name, ref.cardId)
            assertEquals(CardIdentitySource.DATABASE, CardIdentityCatalog.resolve(ref.cardId, databaseCard)?.source)
        }
    }
}
