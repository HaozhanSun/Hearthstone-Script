package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameUtilSurrenderGuardTest {

    @Test
    fun rejectsUnknownModeWithoutActiveWar() {
        assertFalse(GameUtil.isSurrenderStateConfirmed(null, false))
        assertFalse(GameUtil.isSurrenderStateConfirmed(ModeEnum.HUB, false))
    }

    @Test
    fun allowsGameplayOrActiveWarForPreMulliganSurrender() {
        assertTrue(GameUtil.isSurrenderStateConfirmed(ModeEnum.GAMEPLAY, false))
        assertTrue(GameUtil.isSurrenderStateConfirmed(null, true))
    }
}
