package club.xiaojiawei.hsscript.utils

/** Pure bounds/packing helpers for client-coordinate Win32 mouse messages. */
internal object WindowMessageClickPolicy {
    fun executableName(command: String?): String? = command
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { java.nio.file.Path.of(it).fileName?.toString() }.getOrNull() }

    fun isExpectedOwner(expectedExecutable: String, actualCommand: String?): Boolean {
        val actualExecutable = executableName(actualCommand)
        return actualExecutable != null && actualExecutable.equals(expectedExecutable, ignoreCase = true)
    }

    fun isInsideClient(x: Int, y: Int, width: Int, height: Int): Boolean =
        x >= 0 && y >= 0 && x < width && y < height && x <= 0xffff && y <= 0xffff

    fun packClientPoint(x: Int, y: Int): Long? {
        if (x !in 0..0xffff || y !in 0..0xffff) return null
        return ((y.toLong() and 0xffffL) shl 16) or (x.toLong() and 0xffffL)
    }
}
