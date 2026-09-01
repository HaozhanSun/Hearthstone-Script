package club.xiaojiawei.hsscript.status

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `uses fallback identity when current player model is not populated`() {
        val log = Files.createTempFile("power", ".log")
        try {
            Files.writeString(
                log,
                "TAG_CHANGE Entity=KennethSun#5122 tag=PLAYSTATE value=LOST",
            )

            assertFalse(
                E2ETrace.readPowerLogResult(
                    log.toString(),
                    playerGameId = "",
                    fallbackPlayerGameId = "KennethSun#5122",
                )!!,
            )
        } finally {
            Files.deleteIfExists(log)
        }
    }

    @Test
    fun `new CREATE_GAME boundary invalidates previous game milestones`() {
        val initialSequence = E2ETrace.gameSequence
        try {
            E2ETrace.beginNewGame("test-create-game-1")
            E2ETrace.markMulliganCompleted()
            E2ETrace.markOurTurnSeen()
            E2ETrace.markOutCardStarted()
            assertTrue(E2ETrace.isValidScriptControlledGame())
            val firstSequence = E2ETrace.gameSequence

            E2ETrace.beginNewGame("test-create-game-2")

            assertEquals(firstSequence + 1, E2ETrace.gameSequence)
            assertFalse(E2ETrace.isValidScriptControlledGame())
            assertFalse(E2ETrace.mulliganCompleted)
            assertFalse(E2ETrace.ourTurnSeen)
            assertFalse(E2ETrace.outCardStarted)
        } finally {
            E2ETrace.resetForNewGame()
        }
        assertEquals(initialSequence + 2, E2ETrace.gameSequence)
    }
}
