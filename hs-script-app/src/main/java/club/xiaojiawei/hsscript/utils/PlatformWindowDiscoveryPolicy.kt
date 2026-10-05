package club.xiaojiawei.hsscript.utils

/** Snapshot of one top-level Battle.net candidate; native enumeration stays in GameUtil. */
internal data class PlatformWindowCandidate(
    val handle: Long,
    val className: String?,
    val title: String?,
    val ownerImageCandidate: Boolean,
    val valid: Boolean,
    val visible: Boolean,
    val clientWidth: Int,
    val clientHeight: Int,
    val enumerationOrder: Int,
) {
    val clientArea: Long
        get() = clientWidth.coerceAtLeast(0).toLong() * clientHeight.coerceAtLeast(0).toLong()
}

/** Deterministic selection from verified-image, visible top-level Battle.net candidates. */
internal object PlatformWindowDiscoveryPolicy {
    const val MIN_CLIENT_WIDTH = 320
    const val MIN_CLIENT_HEIGHT = 240

    fun select(
        candidates: List<PlatformWindowCandidate>,
        expectedClassNames: Set<String>,
        expectedTitles: Set<String>,
    ): PlatformWindowCandidate? = candidates.asSequence()
        .filter { candidate ->
            candidate.handle != 0L &&
                expectedClassNames.any { it.equals(candidate.className, ignoreCase = true) } &&
                expectedTitles.any { it.equals(candidate.title?.trim(), ignoreCase = true) } &&
                candidate.ownerImageCandidate && candidate.valid && candidate.visible &&
                candidate.clientWidth >= MIN_CLIENT_WIDTH && candidate.clientHeight >= MIN_CLIENT_HEIGHT
        }
        .sortedWith(compareByDescending<PlatformWindowCandidate> { it.clientArea }.thenBy { it.enumerationOrder })
        .firstOrNull()
}
