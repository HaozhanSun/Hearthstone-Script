package club.xiaojiawei.hsscript.status

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BetaLogDirectoryPolicyTest {

    @Test
    fun `Beta prefers the configured external log volume and rejects runtime volume`() {
        val resolved = BetaLogDirectoryPolicy.resolve(
            channel = "beta",
            runtimeDirectory = File("C:/BetaRuntime"),
            configuredDirectory = "C:/BetaRuntime/log",
            javaTempDirectory = "D:/HearthstoneScriptTemp/Beta/runtime",
            minimumFreeBytes = 5L,
            usableSpace = { if (it.path.startsWith("D:")) 100L else 1000L },
        )
        assertEquals(File("D:/HearthstoneScriptTemp/Beta/runtime"), resolved)
    }

    @Test
    fun `Beta refuses file logging when no separate volume has enough free space`() {
        assertNull(
            BetaLogDirectoryPolicy.resolve(
                channel = "beta",
                runtimeDirectory = File("C:/BetaRuntime"),
                configuredDirectory = "C:/BetaRuntime/log",
                javaTempDirectory = "C:/Temp/Beta",
                minimumFreeBytes = 5L,
                usableSpace = { 100L },
            ),
        )
    }

    @Test
    fun `RC keeps its runtime log directory policy`() {
        assertEquals(
            File("C:/RcRuntime/log"),
            BetaLogDirectoryPolicy.resolve(
                channel = "release-candidate",
                runtimeDirectory = File("C:/RcRuntime"),
                configuredDirectory = "D:/ignored",
                javaTempDirectory = "D:/ignored-temp",
                usableSpace = { 0L },
            ),
        )
    }
}
