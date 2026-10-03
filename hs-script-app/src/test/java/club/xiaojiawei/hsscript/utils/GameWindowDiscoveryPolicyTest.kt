package club.xiaojiawei.hsscript.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameWindowDiscoveryPolicyTest {
    @Test
    fun `accepts a visible valid window owned by Hearthstone executable`() {
        assertTrue(
            GameWindowDiscoveryPolicy.isVerifiedGameWindow(
                ownerPid = 39016,
                ownerProcessName = "C:/Games/Hearthstone.exe",
                valid = true,
                visible = true,
                expectedProcessName = "Hearthstone.exe",
            ),
        )
    }

    @Test
    fun `delayed window creation is found on the next enumeration`() {
        assertNull(GameWindowDiscoveryPolicy.select(emptyList(), GAME, TITLES))
        assertEquals(
            22L,
            GameWindowDiscoveryPolicy.select(listOf(candidate(handle = 22L)), GAME, TITLES)?.handle,
        )
    }

    @Test
    fun `diagnostics select the verified HWND owner from multiple native Hearthstone PIDs`() {
        val nativePids = listOf(100L, 200L)
        val hwnd = candidate(handle = 22L, ownerPid = 200L, valid = true, visible = true)
        assertTrue(
            GameWindowDiscoveryPolicy.isVerifiedGameWindow(
                hwnd.ownerPid,
                hwnd.ownerProcessName,
                hwnd.valid,
                hwnd.visible,
                GAME,
            ),
        )

        assertEquals(
            200L,
            GameWindowDiscoveryPolicy.selectDiagnosticPid(hwnd.ownerPid, nativePids),
        )
    }

    @Test
    fun `diagnostics select a sole native PID when Java process metadata and HWND are unavailable`() {
        assertEquals(
            73_060L,
            GameWindowDiscoveryPolicy.selectDiagnosticPid(
                windowOwnerPid = null,
                nativeProcessPids = listOf(73_060L),
            ),
        )
    }

    @Test
    fun `ambiguous native process list fails closed without a verified window owner`() {
        assertNull(
            GameWindowDiscoveryPolicy.selectDiagnosticPid(
                windowOwnerPid = null,
                nativeProcessPids = listOf(73_060L, 81_155L),
            ),
        )
    }

    @Test
    fun `native PID enumeration failure requires independently verified HWND identity`() {
        assertNull(GameWindowDiscoveryPolicy.selectDiagnosticPid(null, null))
        assertEquals(73_060L, GameWindowDiscoveryPolicy.selectDiagnosticPid(73_060L, null))
    }

    @Test
    fun `name-based native injection refuses zero or multiple matching processes`() {
        assertNull(GameWindowDiscoveryPolicy.selectUniqueProcessForNameBasedInjection(null))
        assertNull(GameWindowDiscoveryPolicy.selectUniqueProcessForNameBasedInjection(emptyList()))
        assertNull(GameWindowDiscoveryPolicy.selectUniqueProcessForNameBasedInjection(listOf(73_060L, 81_155L)))
        assertEquals(73_060L, GameWindowDiscoveryPolicy.selectUniqueProcessForNameBasedInjection(listOf(73_060L)))
    }

    @Test
    fun `process creation time falls back to native query when ProcessHandle has no start instant`() {
        assertEquals(
            1_790_000_000_000L,
            GameWindowDiscoveryPolicy.selectProcessStartedAtMs(
                processHandleStartedAtMs = null,
                nativeStartedAtMs = 1_790_000_000_000L,
            ),
        )
    }

    @Test
    fun `verified game HWND must belong to the exact diagnostic PID`() {
        assertTrue(GameWindowDiscoveryPolicy.belongsToProcess(ownerPid = 46_112L, expectedPid = 46_112L))
        assertFalse(GameWindowDiscoveryPolicy.belongsToProcess(ownerPid = 46_112L, expectedPid = 50_912L))
        assertFalse(GameWindowDiscoveryPolicy.belongsToProcess(ownerPid = 0L, expectedPid = 46_112L))
    }

    @Test
    fun `rejects stale invalid hidden zero pid and foreign process windows`() {
        val rejected = listOf(
            candidate(handle = 0L),
            candidate(handle = 2L, valid = false),
            candidate(handle = 3L, visible = false),
            candidate(handle = 4L, ownerPid = 0L),
            candidate(handle = 5L, ownerProcessName = "Battle.net.exe"),
            candidate(handle = 6L, ownerProcessName = null),
        )
        assertNull(GameWindowDiscoveryPolicy.select(rejected, GAME, TITLES))
        assertFalse(GameWindowDiscoveryPolicy.isVerifiedGameWindow(0, GAME, true, true, GAME))
    }

    @Test
    fun `selects preferred main Hearthstone window among multiple candidates`() {
        val selected = GameWindowDiscoveryPolicy.select(
            listOf(
                candidate(handle = 10L, owned = true, title = "Hearthstone", clientArea = 4_000_000L),
                candidate(handle = 11L, title = "", clientArea = 2_000_000L, enumerationOrder = 1),
                candidate(handle = 12L, title = "Hearthstone", clientArea = 1_000_000L, enumerationOrder = 2),
                candidate(handle = 13L, ownerProcessName = "Other.exe", title = "Hearthstone", clientArea = 9_000_000L),
            ),
            expectedProcessName = GAME,
            preferredTitles = TITLES,
        )
        assertEquals(12L, selected?.handle)
    }

    @Test
    fun `process-name matching ignores directories but not executable identity`() {
        assertTrue(GameWindowDiscoveryPolicy.isVerifiedGameWindow(1, "D:\\Games\\Hearthstone.exe", true, true, GAME))
        assertFalse(GameWindowDiscoveryPolicy.isVerifiedGameWindow(1, "HearthstoneBeta.exe", true, true, GAME))
    }

    private fun candidate(
        handle: Long,
        ownerPid: Long = 39016L,
        ownerProcessName: String? = GAME,
        valid: Boolean = true,
        visible: Boolean = true,
        owned: Boolean = false,
        title: String? = "Hearthstone",
        clientArea: Long = 1_920_000L,
        enumerationOrder: Int = 0,
    ) = GameWindowCandidate(
        handle = handle,
        ownerPid = ownerPid,
        ownerProcessName = ownerProcessName,
        valid = valid,
        visible = visible,
        owned = owned,
        title = title,
        clientArea = clientArea,
        enumerationOrder = enumerationOrder,
    )

    private companion object {
        const val GAME = "Hearthstone.exe"
        val TITLES = setOf("Hearthstone", "炉石传说")
    }
}
