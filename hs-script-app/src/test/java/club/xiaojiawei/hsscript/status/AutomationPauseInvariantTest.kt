package club.xiaojiawei.hsscript.status

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationPauseInvariantTest {
    @Test
    fun `automation recovery and failure paths never request a pause`() {
        val root = moduleRoot()
        val productionFiles = listOf(
            "core/Core.kt",
            "starter/AbstractStarter.kt",
            "listener/log/AbstractLogListener.kt",
            "status/BetaScreenRecoveryService.kt",
            "status/TournamentModeConfirmation.kt",
            "strategy/mode/FriendlyModeStrategy.kt",
            "strategy/mode/TournamentModeStrategy.kt",
            "strategy/phase/GameOverPhaseStrategy.kt",
        ).map { root.resolve(Path.of("src", "main", "java", "club", "xiaojiawei", "hsscript", it)) }

        productionFiles.forEach { path ->
            val source = Files.readString(path)
            assertFalse(source.contains("setAutomaticPause(true)"), "Automatic pause request remains in $path")
            assertFalse(source.contains("asyncSetPause(true)"), "Automatic pause request remains in $path")
            assertFalse(source.contains("PauseStatus.isPause = true"), "Automatic pause request remains in $path")
        }

        val pauseStatus = Files.readString(
            root.resolve(Path.of("src", "main", "java", "club", "xiaojiawei", "hsscript", "status", "PauseStatus.kt")),
        )
        val hotkeyListener = Files.readString(
            root.resolve(Path.of("src", "main", "java", "club", "xiaojiawei", "hsscript", "listener", "GlobalHotkeyListener.kt")),
        )
        assertTrue(pauseStatus.contains("fun setManualPause(paused: Boolean)"))
        assertTrue(hotkeyListener.contains("PauseStatus.setManualPause(paused)"), "F2 must retain manual pause authority")
    }

    private fun moduleRoot(): Path =
        listOf(Path.of("."), Path.of("hs-script-app"))
            .first { Files.isRegularFile(it.resolve(Path.of("src", "main", "java", "club", "xiaojiawei", "hsscript", "status", "PauseStatus.kt"))) }
}
