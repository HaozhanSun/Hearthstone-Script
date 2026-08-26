package club.xiaojiawei.hsscriptcardsdk.data

import club.xiaojiawei.hsscriptcardsdk.enums.CardActionEnum
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * @author 肖嘉威
 * @date 2026/2/7 21:51
 */
class CardInfoDataTest {

    @Test
    fun testParsePlayAction(){
        assertTrue("【海盗火炮2】卡牌描述解析出错") { CardInfoData.parsePlayAction("LETL_813_01") == listOf(CardActionEnum.POINT_RIVAL) }
        assertTrue("【奉献】卡牌描述解析出错") { CardInfoData.parsePlayAction("CORE_CS2_093") == listOf(CardActionEnum.NO_POINT) }
        assertTrue("【火球术】卡牌描述解析出错") { CardInfoData.parsePlayAction("CORE_CS2_029") == listOf(CardActionEnum.POINT_RIVAL) }
        assertTrue("【安戈洛宣传单】卡牌描述解析出错") { CardInfoData.parsePlayAction("WORK_050") == listOf(CardActionEnum.NO_POINT) }
        assertTrue("【工匠光环】卡牌描述解析出错") { CardInfoData.parsePlayAction("TOY_808") == listOf(CardActionEnum.NO_POINT) }
        assertTrue("【嘉沃顿的故事】卡牌描述解析出错") { CardInfoData.parsePlayAction("TLC_444") == listOf(CardActionEnum.POINT_MY_MINION) }
        assertTrue("【淹没的地图】卡牌描述解析出错") { CardInfoData.parsePlayAction("TLC_442") == listOf(CardActionEnum.NO_POINT) }
        assertTrue("【污手街供货商】卡牌描述解析出错") { CardInfoData.parsePlayAction("CORE_CFM_753") == listOf(CardActionEnum.NO_POINT) }
        assertTrue("【强光护卫】卡牌描述解析出错") { CardInfoData.parsePlayAction("TIME_015") == listOf(CardActionEnum.NO_POINT) }
        assertTrue("【忙碌机器人】卡牌描述解析出错") { CardInfoData.parsePlayAction("WORK_002") == listOf(CardActionEnum.POINT_MY_MINION) }

    }
}