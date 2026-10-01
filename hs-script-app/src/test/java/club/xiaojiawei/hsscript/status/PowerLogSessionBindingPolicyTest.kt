package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.consts.GAME_WAR_LOG_NAME
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class PowerLogSessionBindingPolicyTest {
    @Test
    fun `accepts readable nonempty Power log modified during current game process`() {
        val root = Files.createTempDirectory("hs-game-logs")
        val session = Files.createDirectory(root.resolve("Hearthstone_2026_09_30_23_10_50"))
        val powerLog = Files.createFile(session.resolve(GAME_WAR_LOG_NAME))

        assertTrue(
            PowerLogSessionBindingPolicy.isCurrentSession(
                powerLogPath = powerLog.toString(),
                gameLogsRoot = root.toString(),
                length = 200_000L,
                lastModifiedMs = 1_000_000L,
                processStartedAtMs = 995_000L,
            ),
        )
    }

    @Test
    fun `rejects empty stale outside-root and sentinel candidates`() {
        val root = Files.createTempDirectory("hs-game-logs")
        val session = Files.createDirectory(root.resolve("Hearthstone_old"))
        val powerLog = Files.createFile(session.resolve(GAME_WAR_LOG_NAME))
        val outsideRoot = Files.createTempDirectory("hs-outside-logs")
        val outsideLog = Files.createFile(outsideRoot.resolve(GAME_WAR_LOG_NAME))

        assertFalse(candidate(powerLog.toString(), root.toString(), length = 0L))
        assertFalse(candidate(powerLog.toString(), root.toString(), lastModifiedMs = 1L, processStartedAtMs = 100_000L))
        assertFalse(candidate(outsideLog.toString(), root.toString()))
        assertFalse(candidate(null, root.toString()))
        assertFalse(candidate(powerLog.toString(), root.toString(), processStartedAtMs = null))
    }

    private fun candidate(
        path: String?,
        root: String,
        length: Long = 100L,
        lastModifiedMs: Long = 100_000L,
        processStartedAtMs: Long? = 100_000L,
    ) = PowerLogSessionBindingPolicy.isCurrentSession(
        powerLogPath = path,
        gameLogsRoot = root,
        length = length,
        lastModifiedMs = lastModifiedMs,
        processStartedAtMs = processStartedAtMs,
    )
}
