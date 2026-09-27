package club.xiaojiawei.hsscript.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowMessageClickPolicyTest {
    @Test
    fun `startup message target must be owned by Battle net executable`() {
        assertTrue(
            WindowMessageClickPolicy.isExpectedOwner(
                "Battle.net.exe",
                "C:\\Users\\tester\\Battle.net\\Battle.net.exe",
            ),
        )
        assertFalse(WindowMessageClickPolicy.isExpectedOwner("Battle.net.exe", "C:\\Apps\\Codex.exe"))
        assertFalse(WindowMessageClickPolicy.isExpectedOwner("Battle.net.exe", null))
    }

    @Test
    fun `client point packing preserves x and y for LPARAM`() {
        val packed = WindowMessageClickPolicy.packClientPoint(145, 849)

        assertEquals((849L shl 16) or 145L, packed)
        assertTrue(WindowMessageClickPolicy.isInsideClient(145, 849, 1920, 1080))
    }

    @Test
    fun `negative oversized and out of client coordinates are rejected`() {
        assertNull(WindowMessageClickPolicy.packClientPoint(-1, 20))
        assertNull(WindowMessageClickPolicy.packClientPoint(20, 65_536))
        assertFalse(WindowMessageClickPolicy.isInsideClient(0, 0, 0, 10))
        assertFalse(WindowMessageClickPolicy.isInsideClient(10, 0, 10, 10))
        assertFalse(WindowMessageClickPolicy.isInsideClient(0, 10, 10, 10))
    }
}
