package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard

/** A legacy E2E click task may be promoted only by current terminal proof. */
internal object TerminalCleanupTaskPromotionPolicy {
    fun capabilityForTick(
        e2eRun: Boolean,
        capability: MandatoryRankSurrenderGuard.TerminalCleanupCapability?,
    ): MandatoryRankSurrenderGuard.TerminalCleanupCapability? =
        capability?.takeIf { e2eRun && MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(it) }
}
