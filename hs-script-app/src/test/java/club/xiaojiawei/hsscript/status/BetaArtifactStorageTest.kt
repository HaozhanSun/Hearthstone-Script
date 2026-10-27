package club.xiaojiawei.hsscript.status

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BetaArtifactStorageTest {

    @Test
    fun `Beta artifacts route to the isolated root while other channels keep legacy paths`() {
        val legacy = File("C:/runtime/log/debug-screenshots")
        assertEquals(
            File("D:/HearthstoneScriptArtifacts/Beta/runtime/debug-screenshots"),
            BetaArtifactStorage.directoryForChannel(
                channel = "beta",
                artifactRoot = "D:/HearthstoneScriptArtifacts/Beta/runtime",
                category = "debug-screenshots",
                legacy = legacy,
            ),
        )
        assertEquals(
            legacy,
            BetaArtifactStorage.directoryForChannel(
                channel = "release-candidate",
                artifactRoot = "D:/HearthstoneScriptArtifacts/Beta/runtime",
                category = "debug-screenshots",
                legacy = legacy,
            ),
        )
    }

    @Test
    fun `Beta storage fails closed for missing root disabled storage and low reserve`() {
        assertFalse(BetaArtifactStorage.canWriteForChannel("beta", false, false, 20L * 1024 * 1024 * 1024))
        assertFalse(BetaArtifactStorage.canWriteForChannel("beta", true, true, 20L * 1024 * 1024 * 1024))
        assertFalse(BetaArtifactStorage.canWriteForChannel("beta", false, true, BetaArtifactStorage.MINIMUM_FREE_BYTES - 1))
        assertTrue(BetaArtifactStorage.canWriteForChannel("beta", false, true, BetaArtifactStorage.MINIMUM_FREE_BYTES))
        assertTrue(BetaArtifactStorage.canWriteForChannel("release-candidate", true, false, 0L))
    }
}
