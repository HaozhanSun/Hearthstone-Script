package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.enums.MouseControlModeEnum
import club.xiaojiawei.hsscript.starter.GameStartupHandoffPolicy
import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformMessageStartupDispatchTest {

    @Test
    fun `startup message mode uses legacy mouse dispatch for both clicks in order`() {
        val events = mutableListOf<String>()
        val upper = Point(145, 849)
        val lower = Point(145, 869)

        PlatformMessageStartupDispatch.dispatch(
            upperClick = upper,
            lowerClick = lower,
            hwnd = null,
            click = { point, hwnd, mode ->
                events += "click:${point.x},${point.y}:$hwnd:$mode"
            },
            delay = { events += "delay" },
        )

        assertEquals(
            listOf(
                "click:145,849:null:${MouseControlModeEnum.MESSAGE.code}",
                "delay",
                "click:145,869:null:${MouseControlModeEnum.MESSAGE.code}",
            ),
            events,
        )

        // A completed helper call only proves that the script attempted both
        // inputs. Without a game process/window, the startup handshake stays
        // pending and must proceed through the normal retry path.
        val noTargetAccepted = GameStartupHandoffPolicy.observe(
            state = GameStartupHandoffPolicy.State(),
            processAlive = false,
            windowFound = false,
            nowMs = 10_000L,
        )
        assertEquals(GameStartupHandoffPolicy.Decision.WAIT, noTargetAccepted.decision)
    }
}
