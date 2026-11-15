package club.xiaojiawei.hsscript.status

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BetaArtifactStorageTest {

    @Test
    fun `all supported channels route artifacts to their isolated root`() {
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
            File("D:/HearthstoneScriptArtifacts/Release Candidate/runtime/debug-screenshots"),
            BetaArtifactStorage.directoryForChannel(
                channel = "release-candidate",
                artifactRoot = "D:/HearthstoneScriptArtifacts/Release Candidate/runtime",
                category = "debug-screenshots",
                legacy = legacy,
            ),
        )
        assertEquals(
            File("D:/HearthstoneScriptArtifacts/Stable/runtime/debug-screenshots"),
            BetaArtifactStorage.directoryForChannel(
                channel = "stable",
                artifactRoot = "D:/HearthstoneScriptArtifacts/Stable/runtime",
                category = "debug-screenshots",
                legacy = legacy,
            ),
        )
        assertEquals(
            legacy,
            BetaArtifactStorage.directoryForChannel(
                channel = "unknown",
                artifactRoot = "D:/HearthstoneScriptArtifacts/Stable/runtime",
                category = "debug-screenshots",
                legacy = legacy,
            ),
        )
    }

    @Test
    fun `all supported channels fail closed for missing root disabled storage and low reserve`() {
        listOf("stable", "beta", "release-candidate").forEach { channel ->
            assertFalse(BetaArtifactStorage.canWriteForChannel(channel, false, false, 20L * 1024 * 1024 * 1024))
            assertFalse(BetaArtifactStorage.canWriteForChannel(channel, true, true, 20L * 1024 * 1024 * 1024))
            assertFalse(BetaArtifactStorage.canWriteForChannel(channel, false, true, BetaArtifactStorage.MINIMUM_FREE_BYTES - 1))
            assertTrue(BetaArtifactStorage.canWriteForChannel(channel, false, true, BetaArtifactStorage.MINIMUM_FREE_BYTES))
        }
        assertTrue(BetaArtifactStorage.canWriteForChannel("unknown", true, false, 0L))
    }
}
