package club.xiaojiawei.hsscript.listener.log

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbstractLogListenerTest {

    @Test
    fun `existing idle log is usable without another file being locked`() {
        val directory = Files.createTempDirectory("hs-log-listener-")
        try {
            val decksLog = directory.resolve("Decks.log")
            decksLog.writeText("Finding Game With Deck\n")
            val listener = TestListener("Decks.log")

            assertEquals(decksLog.toFile().absolutePath, listener.resolveDiskLogFile(directory.toFile())?.absolutePath)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing requested log does not get invented in an idle directory`() {
        val directory = Files.createTempDirectory("hs-log-listener-")
        try {
            directory.resolve("Power.log").writeText("GAME_START\n")
            val listener = TestListener("Decks.log")

            assertNull(listener.resolveDiskLogFile(directory.toFile()))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `listener recognizes a new timestamped session log as a rotation`() {
        val listener = TestListener("Power.log")

        assertTrue(listener.shouldRotateDiskLog("D:\\Hearthstone\\Logs\\Hearthstone_2026_09_13_15_25_30\\Power.log", "D:\\Hearthstone\\Logs\\Hearthstone_2026_09_13_16_42_23\\Power.log"))
        assertFalse(listener.shouldRotateDiskLog("D:\\Hearthstone\\Logs\\Hearthstone_2026_09_13_16_42_23\\Power.log", "D:\\Hearthstone\\Logs\\Hearthstone_2026_09_13_16_42_23\\Power.log"))
    }

    @Test
    fun `terminal old session rotates to a fresh empty Power log without treating old bytes as current`() {
        val root = Files.createTempDirectory("hs-power-log-session-")
        try {
            val oldSession = Files.createDirectories(root.resolve("Hearthstone_2026_09_26_10_41_43"))
            val newSession = Files.createDirectories(root.resolve("Hearthstone_2026_09_26_11_47_52"))
            val oldPowerLog = oldSession.resolve("Power.log")
            oldPowerLog.writeText("PLAYSTATE=LOST\\nPLAYSTATE=WON\\n")
            val newPowerLog = Files.createFile(newSession.resolve("Power.log"))
            val listener = TestListener("Power.log")

            assertTrue(listener.shouldRotateDiskLog(oldPowerLog.toString(), newPowerLog.toString()))
            assertEquals(0L, Files.size(newPowerLog))
            assertTrue(Files.size(oldPowerLog) > 0L)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class TestListener(name: String) : AbstractLogListener(name, 0, 1, TimeUnit.SECONDS) {
        override fun dealOldLog() = Unit
        override fun dealNewLog() = Unit
    }
}
