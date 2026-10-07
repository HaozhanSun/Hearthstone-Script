package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class PowerLogActiveMatchProbeTest {
    @Test
    fun `empty power log is no-match but mulligan and terminal markers are distinguished`() {
        val empty = File.createTempFile("empty-power", ".log")
        try {
            assertEquals(
                PowerLogActiveMatchProbe.State.NO_MATCH,
                PowerLogActiveMatchProbe.inspect(empty, empty.lastModified() - 1L).state,
            )
        } finally { empty.delete() }
        assertEquals(
            PowerLogActiveMatchProbe.State.ACTIVE_MATCH,
            PowerLogActiveMatchProbe.assess(sequenceOf("CREATE_GAME", "tag=MULLIGAN_STATE value=INPUT")).state,
        )
        assertEquals(
            PowerLogActiveMatchProbe.State.TERMINAL,
            PowerLogActiveMatchProbe.assess(sequenceOf("CREATE_GAME", "tag=PLAYSTATE value=WON")).state,
        )
    }

    @Test
    fun `latest create game scopes active marker and read failures fail closed`() {
        assertEquals(
            PowerLogActiveMatchProbe.State.UNREADABLE,
            PowerLogActiveMatchProbe.assess(
                sequenceOf("CREATE_GAME", "tag=STEP value=MAIN_ACTION", "CREATE_GAME", "tag=STEP value=BEGIN_MULLIGAN"),
            ).state,
        )
        assertEquals(
            PowerLogActiveMatchProbe.State.UNREADABLE,
            PowerLogActiveMatchProbe.inspect(File(System.getProperty("java.io.tmpdir"))).state,
        )
    }
}
