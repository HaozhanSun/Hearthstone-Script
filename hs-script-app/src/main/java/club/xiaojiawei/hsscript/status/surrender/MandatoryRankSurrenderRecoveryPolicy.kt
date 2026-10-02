package club.xiaojiawei.hsscript.status.surrender

/** Controls when a mandatory rank surrender may leave its recovery-only state. */
internal object MandatoryRankSurrenderRecoveryPolicy {
    internal fun shouldWaitForMoreEvidence(mandatoryRank: Boolean, screenConfirmed: Boolean): Boolean =
        mandatoryRank && !screenConfirmed
}
