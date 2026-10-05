package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.starter.GameStartupHandoffPolicy
import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlatformWindowDiscoveryPolicyTest {
    private val classes = setOf("Chrome_WidgetWin_0", "Chrome_WidgetWin_1")
    private val titles = setOf("Battle.net", "战网")

    @Test
    fun `largest verified visible top-level home window wins over small same-title widgets`() {
        val selected = PlatformWindowDiscoveryPolicy.select(
            candidates = listOf(
                candidate(handle = 10, width = 160, height = 28, order = 0),
                candidate(handle = 20, width = 1600, height = 999, order = 1, className = "Chrome_WidgetWin_1"),
                candidate(handle = 30, width = 1280, height = 800, order = 2),
            ),
            expectedClassNames = classes,
            expectedTitles = titles,
        )

        assertEquals(20L, assertNotNull(selected).handle)
        assertEquals(Point(176, 891), PlatformMessageStartupDispatch.startButtonPoint(1600, 999))
    }

    @Test
    fun `small hidden invalid wrong-owner or unrelated windows cannot be selected`() {
        val rejected = listOf(
            candidate(handle = 1, width = 160, height = 28),
            candidate(handle = 2, width = 1600, height = 999, visible = false),
            candidate(handle = 3, width = 1600, height = 999, valid = false),
            candidate(handle = 4, width = 1600, height = 999, ownerImageCandidate = false),
            candidate(handle = 5, width = 1600, height = 999, title = "Other"),
            candidate(handle = 6, width = 1600, height = 999, className = "OtherWindow"),
            candidate(handle = 0, width = 1600, height = 999),
        )

        assertNull(PlatformWindowDiscoveryPolicy.select(rejected, classes, titles))
        assertNull(PlatformMessageStartupDispatch.startButtonPoint(160, 28))
    }

    @Test
    fun `startup message queueing is distinct from finding an eligible Battle net window`() {
        val point = assertNotNull(PlatformMessageStartupDispatch.startButtonPoint(1600, 999))
        var dispatched: Point? = null
        val queued = PlatformMessageStartupDispatch.dispatch(point, hwnd = null) { actualPoint, _ ->
            dispatched = actualPoint
            true
        }
        assertEquals(point, dispatched)
        assertEquals(true, queued)
        assertNull(PlatformWindowDiscoveryPolicy.select(emptyList(), classes, titles))
        val waiting = GameStartupHandoffPolicy.observe(
            state = GameStartupHandoffPolicy.State(),
            processAlive = false,
            windowFound = false,
            nowMs = 10_000,
        )
        assertEquals(GameStartupHandoffPolicy.Decision.WAIT, waiting.decision)
        val firstWindow = GameStartupHandoffPolicy.observe(
            waiting.state,
            processAlive = true,
            windowFound = true,
            nowMs = 11_000,
        )
        val accepted = GameStartupHandoffPolicy.observe(
            firstWindow.state,
            processAlive = true,
            windowFound = true,
            nowMs = 11_100,
        )
        assertEquals(GameStartupHandoffPolicy.Decision.HANDOFF, accepted.decision)
        assertFalse(
            PlatformMessageStartupDispatch.dispatch(point, hwnd = null) { _, _ -> false },
            "a failed PostMessage must stay a failed dispatch",
        )
    }

    private fun candidate(
        handle: Long,
        width: Int,
        height: Int,
        order: Int = 0,
        className: String = "Chrome_WidgetWin_0",
        title: String = "Battle.net",
        ownerImageCandidate: Boolean = true,
        valid: Boolean = true,
        visible: Boolean = true,
    ) = PlatformWindowCandidate(
        handle = handle,
        className = className,
        title = title,
        ownerImageCandidate = ownerImageCandidate,
        valid = valid,
        visible = visible,
        clientWidth = width,
        clientHeight = height,
        enumerationOrder = order,
    )
}
