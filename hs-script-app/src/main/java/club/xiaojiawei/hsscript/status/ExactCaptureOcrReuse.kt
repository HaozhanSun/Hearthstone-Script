package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.security.MessageDigest
import club.xiaojiawei.hsscriptbase.const.BuildChannel

/** Reuses OCR text only when a newly authorized capture is pixel-identical. */
internal class ExactCaptureOcrReuse {
    private data class Entry(val processId: Long, val windowId: String, val fingerprint: String, val text: String)

    @Volatile
    private var previous: Entry? = null

    @Synchronized
    fun lookup(processId: Long, windowId: String, fingerprint: String): String? {
        val entry = previous
        if (entry != null && entry.processId == processId && entry.windowId == windowId &&
            entry.fingerprint == fingerprint
        ) return entry.text
        previous = null
        return null
    }

    @Synchronized
    fun remember(processId: Long, windowId: String, fingerprint: String, text: String) {
        previous = if (processId > 0 && windowId.isNotBlank() && text.isNotEmpty()) {
            Entry(processId, windowId, fingerprint, text)
        } else null
    }

    @Synchronized
    fun clear() {
        previous = null
    }

    companion object {
        fun enabledForChannel(channel: String?): Boolean = BuildChannel.identityToken(channel) == "beta"

        fun fingerprint(image: BufferedImage): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(ByteBuffer.allocate(8).putInt(image.width).putInt(image.height).array())
            val row = IntArray(image.width)
            val bytes = ByteBuffer.allocate(image.width * 4)
            for (y in 0 until image.height) {
                image.getRGB(0, y, image.width, 1, row, 0, image.width)
                bytes.clear()
                row.forEach(bytes::putInt)
                digest.update(bytes.array())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
