package club.xiaojiawei.hsscript.status

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugScreenshotRingTest {

    @Test
    fun `retention enforces total byte cap from oldest first`() {
        val directory = Files.createTempDirectory("debug-ring-bytes-").toFile()
        try {
            listOf(4, 3, 2).forEachIndexed { index, size ->
                val file = directory.resolve("debug-$index.png")
                file.writeBytes(ByteArray(size))
                file.setLastModified(index.toLong())
            }

            val retained = DebugScreenshotRing.prune(directory, maxFiles = 10, maxBytes = 5)

            assertEquals(listOf("debug-2.png", "debug-1.png"), retained.map { it.name })
            assertFalse(directory.resolve("debug-0.png").exists())
            assertEquals(5L, retained.sumOf { it.length() })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `named final victory evidence is protected from ring eviction`() {
        val directory = Files.createTempDirectory("debug-ring-protected-").toFile()
        try {
            val protected = directory.resolve("debug-final-victory.png")
            protected.writeBytes(ByteArray(100))
            protected.setLastModified(1L)
            val newest = directory.resolve("debug-newest.png")
            newest.writeBytes(ByteArray(1))
            newest.setLastModified(2L)

            val retained = DebugScreenshotRing.prune(directory, maxFiles = 10, maxBytes = 1)

            assertTrue(protected.exists())
            assertTrue(newest.exists())
            assertEquals(2, retained.size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
