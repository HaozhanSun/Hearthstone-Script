package club.xiaojiawei.hsscript.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BetaStartupPathPolicyTest {

    @Test
    fun `recovered Battle net path with spaces is passed as one valid absolute executable`() {
        val game = "D:\\Hearthstone"
        val platform = "C:\\Users\\yzjsh\\OneDrive - Duke University\\Desktop\\炉石美服\\Battle.net\\Battle.net.exe"
        val result = BetaStartupPathPolicy.resolve(
            gamePath = game,
            platformPath = platform,
            isGameDirectory = { it == game },
            isPlatformExecutable = { it == platform },
        )
        assertNull(result.rejection)
        assertEquals(BetaStartupPathPolicy.ResolvedPaths(game, platform), result.paths)
        assertEquals(
            listOf(platform, "--game=hs_beta", "--gamepath=$game", "-uid", "hs_beta"),
            GameUtil.buildPlatformCommand(platform, true, AppRuntimeChannel.BETA, game),
        )
    }

    @Test
    fun `drive relative game path is rooted only when rooted directory exists`() {
        val result = BetaStartupPathPolicy.resolve(
            gamePath = "D:Hearthstone",
            platformPath = "C:\\Battle.net\\Battle.net.exe",
            isGameDirectory = { it == "D:\\Hearthstone" },
            isPlatformExecutable = { it == "C:\\Battle.net\\Battle.net.exe" },
        )
        assertNull(result.rejection)
        assertEquals("D:\\Hearthstone", result.paths?.gamePath)
    }

    @Test
    fun `collapsed platform path and missing binary are explicitly rejected`() {
        val collapsed = BetaStartupPathPolicy.resolve(
            gamePath = "D:Hearthstone",
            platformPath = "C:UsersyzjshOneDrive - Duke UniversityDesktop炉石美服Battle.netBattle.net.exe",
            isGameDirectory = { false },
            isPlatformExecutable = { false },
        )
        assertEquals(BetaStartupPathPolicy.Rejection("GAME_PATH", "not-absolute-windows-path"), collapsed.rejection)

        val missingBinary = BetaStartupPathPolicy.resolve(
            gamePath = "D:\\Hearthstone",
            platformPath = "C:\\Battle.net\\Battle.net.exe",
            isGameDirectory = { true },
            isPlatformExecutable = { false },
        )
        assertEquals(BetaStartupPathPolicy.Rejection("PLATFORM_PATH", "executable-not-found"), missingBinary.rejection)
    }

    @Test
    fun `Beta platform argument failure stops startup retries while other modes and channels preserve behavior`() {
        assertTrue(BetaStartupPathPolicy.shouldStopRetry("beta", "PLATFORM_ARG", dispatchSucceeded = false))
        assertFalse(BetaStartupPathPolicy.shouldStopRetry("beta", "PLATFORM_ARG", dispatchSucceeded = true))
        assertFalse(BetaStartupPathPolicy.shouldStopRetry("beta", "PLATFORM_MESSAGE", dispatchSucceeded = false))
        assertFalse(BetaStartupPathPolicy.shouldStopRetry("release-candidate", "PLATFORM_ARG", dispatchSucceeded = false))
        assertFalse(BetaStartupPathPolicy.shouldStopRetry("stable", "PLATFORM_ARG", dispatchSucceeded = false))
    }
}
