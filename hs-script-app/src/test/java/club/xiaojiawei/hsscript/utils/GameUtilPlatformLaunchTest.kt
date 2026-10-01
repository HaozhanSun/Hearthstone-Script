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
    fun `Beta startup selects Beta app and configured game directory`() {
        val platform = "C:\\Program Files\\Battle.net\\Battle.net.exe"
        val game = "D:\\Hearthstone"

        assertEquals(
            listOf(platform, "--game=hs_beta", "--gamepath=$game", "-uid", "hs_beta"),
            GameUtil.buildPlatformCommand(platform, true, AppRuntimeChannel.BETA, game),
        )
    }

    @Test
    fun `Stable startup retains the established WTCG command`() {
        val platform = "C:\\Program Files\\Battle.net\\Battle.net.exe"

        assertEquals(
            listOf(platform, "--exec=launch WTCG"),
            GameUtil.buildPlatformCommand(platform, true, AppRuntimeChannel.STABLE, "D:\\Hearthstone"),
        )
    }

    @Test
    fun `blank platform path is rejected before process creation`() {
        assertFailsWith<IllegalArgumentException> {
            GameUtil.buildPlatformCommand("  ", launchGame = true)
        }
    }
}
