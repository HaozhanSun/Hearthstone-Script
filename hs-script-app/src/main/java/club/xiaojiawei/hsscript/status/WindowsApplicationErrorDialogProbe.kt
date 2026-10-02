package club.xiaojiawei.hsscript.status

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.Pointer

/** Read-only Win32 probe for the exact Hearthstone Application Error caption. */
internal object WindowsApplicationErrorDialogProbe {
    internal data class WindowEvidence(
        val hwnd: Long,
        val className: String,
        val title: String,
        val body: String,
        val hostPid: Long,
        val ownerPid: Long?,
        val firstSeenAtMs: Long,
    ) {
        fun asPolicyEvidence() = BetaStartupFailureRecoveryPolicy.DialogEvidence(
            hwnd = hwnd,
            title = title,
            body = body,
            hostPid = hostPid,
            ownerPid = ownerPid,
            firstSeenAtMs = firstSeenAtMs,
        )
    }

    internal data class Candidate(
        val hwnd: Long,
        val className: String,
        val title: String,
        val body: String,
        val hostPid: Long,
        val ownerPid: Long?,
    )

    private val firstSeenByHwnd = mutableMapOf<Long, Long>()

    internal fun isTargetDialog(candidate: Candidate): Boolean =
        candidate.className == "#32770" &&
            candidate.title.trim() in EXACT_CAPTIONS &&
            candidate.body.contains("0x80000003", ignoreCase = true)

    @Synchronized
    fun observe(candidates: List<Candidate>, nowMs: Long): WindowEvidence? {
        val observedHandles = candidates.mapTo(mutableSetOf()) { it.hwnd }
        firstSeenByHwnd.keys.retainAll(observedHandles)
        return candidates.firstOrNull(::isTargetDialog)?.let { candidate ->
            val firstSeen = firstSeenByHwnd.getOrPut(candidate.hwnd) { nowMs }
            WindowEvidence(
                hwnd = candidate.hwnd,
                className = candidate.className,
                title = candidate.title.trim(),
                body = candidate.body,
                hostPid = candidate.hostPid,
                ownerPid = candidate.ownerPid,
                firstSeenAtMs = firstSeen,
            )
        }
    }

    fun detect(nowMs: Long = System.currentTimeMillis()): WindowEvidence? = runCatching {
        val candidates = mutableListOf<Candidate>()
        User32.INSTANCE.EnumWindows(WinUser.WNDENUMPROC { hwnd, _ ->
            val title = windowText(hwnd)
            val className = windowClass(hwnd)
            if (className == "#32770" && title in EXACT_CAPTIONS) {
                val hostPid = processId(hwnd)
                val owner = User32.INSTANCE.GetWindow(hwnd, WinDef.DWORD(WinUser.GW_OWNER.toLong()))
                val ownerPid = owner?.let(::processId)?.takeIf { it > 0L }
                val body = childWindowText(hwnd)
                candidates += Candidate(
                    hwnd = Pointer.nativeValue(hwnd.pointer),
                    className = className,
                    title = title,
                    body = body,
                    hostPid = hostPid,
                    ownerPid = ownerPid,
                )
            }
            true
        }, null)
        observe(candidates, nowMs)
    }.getOrNull()

    private fun childWindowText(parent: WinDef.HWND): String {
        val texts = mutableListOf<String>()
        User32.INSTANCE.EnumChildWindows(parent, WinUser.WNDENUMPROC { hwnd, _ ->
            windowText(hwnd).takeIf(String::isNotBlank)?.let(texts::add)
            true
        }, null)
        return texts.joinToString(" ")
    }

    private fun processId(hwnd: WinDef.HWND): Long {
        val pid = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid)
        return pid.value.toLong()
    }

    private fun windowText(hwnd: WinDef.HWND): String =
        CharArray(1024).also { User32.INSTANCE.GetWindowText(hwnd, it, it.size) }
            .concatToString().trimEnd('\u0000').trim()

    private fun windowClass(hwnd: WinDef.HWND): String =
        CharArray(256).also { User32.INSTANCE.GetClassName(hwnd, it, it.size) }
            .concatToString().trimEnd('\u0000').trim()

    private val EXACT_CAPTIONS = setOf(
        "Hearthstone.exe - Application Error",
        "炉石传说: Hearthstone.exe - Application Error",
    )
}
