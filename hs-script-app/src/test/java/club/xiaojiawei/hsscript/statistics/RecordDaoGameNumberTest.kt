package club.xiaojiawei.hsscript.statistics

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RecordDaoGameNumberTest {

    @Test
    fun `record round-trip preserves canonical all-match ordinal`() {
        val database = Files.createTempFile("record-game-number-", ".db")
        try {
            val dao = RecordDao(database.toString())
            val record = dao.insert(
                Record(
                    gameNumber = 133,
                    strategyId = "strategy",
                    strategyName = "strategy",
                    runMode = RunModeEnum.STANDARD,
                    result = true,
                    surrendered = false,
                    experience = 8,
                    startTime = LocalDateTime.of(2026, 9, 30, 10, 12),
                    endTime = LocalDateTime.of(2026, 9, 30, 10, 13),
                )
            )

            val loaded = dao.findById(record.id!!)
            assertNotNull(loaded)
            assertEquals(133, loaded.gameNumber)
        } finally {
            Files.deleteIfExists(database)
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-journal"))
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-wal"))
            Files.deleteIfExists(database.resolveSibling(database.fileName.toString() + "-shm"))
        }
    }
}
