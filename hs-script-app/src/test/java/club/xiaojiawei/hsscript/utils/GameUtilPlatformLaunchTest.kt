package club.xiaojiawei.hsscript.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GameUtilPlatformLaunchTest {

    @Test
    fun `platform path with spaces remains one executable argument`() {
        val path = "C:\\Users\\tester\\OneDrive - Duke University\\Battle.net\\Battle.net.exe"

        assertEquals(
            listOf(path, "--exec=launch WTCG"),
            GameUtil.buildPlatformCommand(path, launchGame = true),
        )
    }

    @Test
    fun `launching platform without game uses only executable path`() {
        val path = "C:\\Program Files\\Battle.net\\Battle.net.exe"

        assertEquals(
            listOf(path),
            GameUtil.buildPlatformCommand(path, launchGame = false),
        )
    }

    @Test
    fun `blank platform path is rejected before process creation`() {
        assertFailsWith<IllegalArgumentException> {
            GameUtil.buildPlatformCommand("  ", launchGame = true)
        }
    }
}
