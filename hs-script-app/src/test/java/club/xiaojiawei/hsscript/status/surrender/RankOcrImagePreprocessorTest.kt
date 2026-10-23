package club.xiaojiawei.hsscript.status.surrender

import java.awt.image.BufferedImage
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RankOcrImagePreprocessorTest {
    @Test
    fun `four times lanczos rank crop is deterministic`() {
        assertEquals("LANCZOS3", RankOcrImagePreprocessor.ALGORITHM)
        val source = BufferedImage(45, 34, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until height) for (x in 0 until width) {
                setRGB(x, y, if ((x + y) % 3 == 0) 0xffffffff.toInt() else 0xff202020.toInt())
            }
        }
        val first = RankOcrImagePreprocessor.upscale(source, 4)
        val second = RankOcrImagePreprocessor.upscale(source, 4)
        assertEquals(180, first.width)
        assertEquals(136, first.height)
        assertEquals(hash(first), hash(second))
        assertNotEquals(hash(source), hash(first))
    }

    private fun hash(image: BufferedImage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y)
            digest.update((rgb ushr 16).toByte())
            digest.update((rgb ushr 8).toByte())
            digest.update(rgb.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
