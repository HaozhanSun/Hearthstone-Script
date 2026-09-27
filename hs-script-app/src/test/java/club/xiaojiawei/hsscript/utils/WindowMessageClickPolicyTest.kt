package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.dll.Win32ProcessImagePath
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class WindowMessageClickPolicyTest {
    @Test
    fun `startup owner validation uses actual image path instead of command line`() {
        val misleadingCommand = "C:\\Users\\tester\\AppData\\Local\\Temp\\temp_a6289c43bb612c7b933dbbff6b9cfd03.exe"
        assertTrue(
            WindowMessageClickPolicy.isExpectedImage(
                "Battle.net.exe",
                "C:\\Users\\tester\\Battle.net\\Battle.net.exe",
            ),
        )
        assertFalse(WindowMessageClickPolicy.isExpectedImage("Battle.net.exe", misleadingCommand))
        assertFalse(WindowMessageClickPolicy.isExpectedImage("Battle.net.exe", "C:\\Apps\\Codex.exe"))
        assertFalse(WindowMessageClickPolicy.isExpectedImage("Battle.net.exe", null))
        assertTrue(
            WindowMessageClickPolicy.isExpectedImage(
                "Battle.net.exe",
                "\\\\?\\C:\\Users\\tester\\Battle.net\\Battle.net.exe",
            ),
        )
    }

    @Test
    fun `Win32 image query reads this process executable and rejects invalid pid`() {
        assumeTrue(System.getProperty("os.name").contains("Windows", ignoreCase = true))

        val imagePath = assertNotNull(Win32ProcessImagePath.query(ProcessHandle.current().pid().toInt()))
        val imageName = Path.of(imagePath).fileName.toString()
        assertTrue(imageName.equals("java.exe", ignoreCase = true) || imageName.equals("javaw.exe", ignoreCase = true))
        assertNull(Win32ProcessImagePath.query(0))
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
