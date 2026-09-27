package club.xiaojiawei.hsscript.dll

import club.xiaojiawei.hsscriptbase.config.log
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.ptr.IntByReference

/** Reads the executable image Windows associates with a PID, not its command line. */
internal object Win32ProcessImagePath {
    private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    private const val IMAGE_PATH_CAPACITY = 32_768

    fun query(pid: Int): String? {
        if (pid <= 0) return null
        return try {
            val process = Kernel32ProcessImage.INSTANCE.OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION,
                0,
                pid,
            ) ?: run {
                log.warn { "PROCESS_IMAGE_QUERY_FAILED pid=$pid stage=open win32Error=${Kernel32.INSTANCE.GetLastError()}" }
                return null
            }
            try {
                val imagePath = CharArray(IMAGE_PATH_CAPACITY)
                val size = IntByReference(imagePath.size)
                if (Kernel32ProcessImage.INSTANCE.QueryFullProcessImageNameW(process, 0, imagePath, size) == 0) {
                    log.warn {
                        "PROCESS_IMAGE_QUERY_FAILED pid=$pid stage=query win32Error=${Kernel32.INSTANCE.GetLastError()}"
                    }
                    null
                } else {
                    String(imagePath, 0, size.value.coerceIn(0, imagePath.size)).takeIf(String::isNotBlank)
                }
            } finally {
                Kernel32ProcessImage.INSTANCE.CloseHandle(process)
            }
        } catch (error: Throwable) {
            log.warn(error) { "PROCESS_IMAGE_QUERY_FAILED pid=$pid stage=exception" }
            null
        }
    }

    private interface Kernel32ProcessImage : StdCallLibrary {
        fun OpenProcess(desiredAccess: Int, inheritHandle: Int, processId: Int): Pointer?
        fun QueryFullProcessImageNameW(
            process: Pointer,
            flags: Int,
            imageName: CharArray,
            size: IntByReference,
        ): Int
        fun CloseHandle(handle: Pointer): Int

        companion object {
            val INSTANCE: Kernel32ProcessImage by lazy {
                Native.load("kernel32", Kernel32ProcessImage::class.java, W32APIOptions.DEFAULT_OPTIONS)
            }
        }
    }
}
