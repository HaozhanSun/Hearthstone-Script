package club.xiaojiawei.hsscript.listener.log

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
