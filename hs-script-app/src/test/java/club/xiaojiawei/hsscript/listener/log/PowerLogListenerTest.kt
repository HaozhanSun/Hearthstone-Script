package club.xiaojiawei.hsscript.listener.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

class PowerLogListenerTest {

    @Test
    fun `active create game without terminal playstate is replayable`() {
        assertTrue(
            PowerLogListener.hasUnfinishedGame(
                sequenceOf(
                    "CREATE_GAME",
                    "TAG_CHANGE Entity=GameEntity tag=STEP value=MAIN_ACTION",
                ),
            ),
        )
    }

    @Test
    fun `completed game is not replayed`() {
        assertFalse(
            PowerLogListener.hasUnfinishedGame(
                sequenceOf(
                    "CREATE_GAME",
                    "TAG_CHANGE Entity=1 tag=PLAYSTATE value=PLAYING",
                    "TAG_CHANGE Entity=1 tag=PLAYSTATE value=WON",
                ),
            ),
        )
    }

    @Test
    fun `a later create game reopens replay after an earlier completed game`() {
        assertTrue(
            PowerLogListener.hasUnfinishedGame(
                sequenceOf(
                    "CREATE_GAME",
                    "TAG_CHANGE Entity=1 tag=PLAYSTATE value=WON",
                    "CREATE_GAME",
                    "TAG_CHANGE Entity=1 tag=PLAYSTATE value=PLAYING",
                ),
            ),
        )
    }

    @Test
    fun `recovery starts at the newest unfinished game instead of byte zero`() {
        val log = Files.createTempFile("power-recovery", ".log")
        try {
            Files.writeString(
                log,
                "CREATE_GAME old\n" +
                    "TAG_CHANGE Entity=1 tag=PLAYSTATE value=WON\n" +
                    "CREATE_GAME current\n" +
                    "TAG_CHANGE Entity=1 tag=PLAYSTATE value=PLAYING\n",
            )
            val expected = "CREATE_GAME old\n".toByteArray(Charsets.UTF_8).size.toLong() +
                "TAG_CHANGE Entity=1 tag=PLAYSTATE value=WON\n".toByteArray(Charsets.UTF_8).size
            assertEquals(expected, PowerLogListener.unfinishedGameStartOffset(log.toString()))
        } finally {
            Files.deleteIfExists(log)
        }
    }
}
