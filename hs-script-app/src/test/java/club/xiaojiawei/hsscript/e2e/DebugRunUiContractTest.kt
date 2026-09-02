package club.xiaojiawei.hsscript.e2e

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Prevents the DebugRun implementation from compiling while its main-screen
 * control is silently dropped from the packaged application resources.
 */
class DebugRunUiContractTest {
    @Test
    fun `debug run UI is wired to the non-persistent thirty minute lease`() {
        val moduleRoot = Path.of("hs-script-app")
        val fxml = moduleRoot.resolve(Path.of("src", "main", "resources", "fxml", "main.fxml"))
        assertTrue(Files.isRegularFile(fxml), "main.fxml must remain checked in")
        val fxmlText = Files.readString(fxml)

        assertTrue(fxmlText.contains("fx:id=\"debugRunModeCheckBox\""))
        assertTrue(fxmlText.contains("config=\"DEBUG_RUN_MODE\""))
        assertTrue(fxmlText.contains("onAction=\"#toggleDebugRun\""))
        assertTrue(fxmlText.contains("fx:id=\"debugRunStatus\""))

        val config = moduleRoot.resolve(Path.of(
            "src",
            "main",
            "java",
            "club",
            "xiaojiawei",
            "hsscript",
            "enums",
            "ConfigEnum.kt",
        ))
        assertTrue(Files.isRegularFile(config), "ConfigEnum.kt must remain checked in")
        val configText = Files.readString(config)
        assertTrue(configText.contains("DEBUG_RUN_MODE"))
        assertTrue(configText.contains("defaultValueInitializer = { FALSE_STR }"))

        val lease = moduleRoot.resolve(Path.of(
            "src",
            "main",
            "java",
            "club",
            "xiaojiawei",
            "hsscript",
            "status",
            "DebugRunLease.kt",
        ))
        assertTrue(Files.isRegularFile(lease), "DebugRunLease.kt must remain checked in")
        val leaseText = Files.readString(lease)
        assertTrue(leaseText.contains("MAX_DURATION_MILLIS"))
        assertTrue(leaseText.contains("effectiveCanWork"))
    }
}
