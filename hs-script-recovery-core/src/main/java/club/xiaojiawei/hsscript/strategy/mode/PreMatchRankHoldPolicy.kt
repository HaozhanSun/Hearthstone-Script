package club.xiaojiawei.hsscript.strategy.mode

/** Pure hold policy for a fresh pre-match rank denial. */
object PreMatchRankHoldPolicy {
    fun shouldEnterHold(
        rankAuthorized: Boolean,
        working: Boolean,
        paused: Boolean,
        mandatoryRankSurrenderPending: Boolean,
    ): Boolean = working && !paused && !mandatoryRankSurrenderPending && !rankAuthorized
}
