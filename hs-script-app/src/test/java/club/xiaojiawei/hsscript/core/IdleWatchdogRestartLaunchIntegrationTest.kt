package club.xiaojiawei.hsscript.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdleWatchdogRestartLaunchIntegrationTest {
    @Test
    fun `idle watchdog restart force closes then dispatches the established starter chain`() {
        val events = mutableListOf<String>()
        IdleWatchdogRestartSequence.dispatch(
            forceCloseClient = { events += "force-close" },
            startStarterChain = { events += "starter-chain" },
        )
        assertEquals(listOf("force-close", "starter-chain"), events)

        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("src/main/java/club/xiaojiawei/hsscript/core/Core.kt")) }
        val core = Files.readString(root.resolve("src/main/java/club/xiaojiawei/hsscript/core/Core.kt"))
        val idleRestart = core.substring(core.indexOf("fun restartFromIdleWatchdog"))
        val restartBody = idleRestart.substring(idleRestart.indexOf("private fun restartInternal"))
        val sequenceCall = restartBody.indexOf("IdleWatchdogRestartSequence.dispatch(")
        val forceClose = restartBody.indexOf("GameUtil.killGame(true)", sequenceCall)
        val launchLog = restartBody.indexOf("CORE_RESTART_STARTER_CHAIN_DISPATCH", sequenceCall)
        val starterLaunch = restartBody.indexOf("StarterConfig.starter.start()", sequenceCall)
        assertTrue(sequenceCall >= 0 && forceClose > sequenceCall && launchLog > forceClose && starterLaunch > launchLog,
            "idle recovery must force-close then hand off to GameStarter/platform startup")
    }
}
