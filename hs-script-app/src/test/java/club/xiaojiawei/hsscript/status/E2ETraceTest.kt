package club.xiaojiawei.hsscript.status

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class E2ETraceTest {

    @Test
    fun `reads the latest terminal playstate for the current player`() {
        val log = Files.createTempFile("power", ".log")
        try {
            Files.writeString(
                log,
                """
                TAG_CHANGE Entity=KennethSun#5122 tag=PLAYSTATE value=WON
                TAG_CHANGE Entity=KennethSun#5122 tag=PLAYSTATE value=PLAYING
                TAG_CHANGE Entity=KennethSun#5122 tag=PLAYSTATE value=LOST
                """.trimIndent(),
            )

            assertFalse(E2ETrace.readPowerLogResult(log.toString(), "KennethSun#5122")!!)
        } finally {
            Files.deleteIfExists(log)
        }
    }

    @Test
    fun `does not accept another player terminal state`() {
        val log = Files.createTempFile("power", ".log")
        try {
            Files.writeString(
                log,
                "TAG_CHANGE Entity=Opponent#1 tag=PLAYSTATE value=WON",
            )

            assertTrue(E2ETrace.readPowerLogResult(log.toString(), "KennethSun#5122") == null)
        } finally {
            Files.deleteIfExists(log)
        }
    }
}
