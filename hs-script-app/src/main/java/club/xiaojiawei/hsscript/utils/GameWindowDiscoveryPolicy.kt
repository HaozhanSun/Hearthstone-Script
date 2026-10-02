package club.xiaojiawei.hsscript.utils

/** A read-only top-level HWND candidate collected from EnumWindows. */
internal data class GameWindowCandidate(
    val handle: Long,
    val ownerPid: Long,
    val ownerProcessName: String?,
    val valid: Boolean,
    val visible: Boolean,
    val owned: Boolean,
    val title: String?,
    val clientArea: Long,
    val enumerationOrder: Int,
)

/**
 * Reproduces the stable title-first/process-owner fallback without trusting a
 * process-name scan's arbitrary first PID. Every accepted HWND must be valid,
 * visible, and owned by a live process whose executable is Hearthstone.exe.
 */
internal object GameWindowDiscoveryPolicy {
    fun belongsToProcess(ownerPid: Long, expectedPid: Long): Boolean =
        ownerPid > 0L && expectedPid > 0L && ownerPid == expectedPid

    /** Prefer the verified HWND's process when multiple Hearthstone clients exist. */
    fun selectDiagnosticPid(windowOwnerPid: Long?, discoveredProcessPids: List<Long>): Long? {
        val candidates = discoveredProcessPids.filter { it > 0L }
        return windowOwnerPid?.takeIf { it > 0L && it in candidates } ?: candidates.firstOrNull()
    }

    fun isVerifiedGameWindow(
        ownerPid: Long,
        ownerProcessName: String?,
        valid: Boolean,
        visible: Boolean,
        expectedProcessName: String,
    ): Boolean =
        ownerPid > 0L &&
            valid &&
            visible &&
            executableName(ownerProcessName).equals(executableName(expectedProcessName), ignoreCase = true)

    fun select(
        candidates: List<GameWindowCandidate>,
        expectedProcessName: String,
        preferredTitles: Set<String>,
    ): GameWindowCandidate? = candidates
        .asSequence()
        .filter { candidate ->
            candidate.handle != 0L &&
                isVerifiedGameWindow(
                    ownerPid = candidate.ownerPid,
                    ownerProcessName = candidate.ownerProcessName,
                    valid = candidate.valid,
                    visible = candidate.visible,
                    expectedProcessName = expectedProcessName,
                )
        }
        .sortedWith(
            compareBy<GameWindowCandidate> { it.owned }
                .thenByDescending { candidate ->
                    preferredTitles.any { it.equals(candidate.title?.trim(), ignoreCase = true) }
                }
                .thenByDescending { it.clientArea.coerceAtLeast(0L) }
                .thenBy { it.enumerationOrder },
        )
        .firstOrNull()

    private fun executableName(value: String?): String =
        value.orEmpty().substringAfterLast('\\').substringAfterLast('/')
}
