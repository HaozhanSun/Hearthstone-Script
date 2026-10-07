package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.const.BuildChannel
import club.xiaojiawei.hsscriptbase.const.BuildInfo

/**
 * Beta-derived-channel kill switch for script-initiated concessions.
 *
 * The setting is deliberately channel-scoped: a persisted diagnostic
 * choice cannot silently alter Stable behavior.  This policy only blocks
 * automation requests.  It does not change authoritative terminal-state
 * parsing or result recording.
 */
object NeverSurrenderPolicy {

    fun enabled(): Boolean = enabledForChannel(
        BuildInfo.RELEASE_CHANNEL,
        ConfigUtil.getBoolean(ConfigEnum.NEVER_SURRENDER),
    )

    internal fun enabledForChannel(channel: String?, setting: Boolean): Boolean =
        setting && BuildChannel.isBetaDerived(channel)

    internal fun rankIsIneligible(rank: Int): Boolean =
        rank != 5 && rank != 10 && rank <= 20

    /** Rank-floor decisions are an explicit safety policy, not ordinary strategy surrender. */
    internal fun isMandatoryRankRule(ruleId: String?): Boolean =
        ruleId == "current-rank-not-5-or-10-or-legendary-20-plus" ||
            ruleId == "current-rank-is-not-silver-target" ||
            ruleId == "rank-ocr-unresolved"

    internal fun isMandatoryRankDispatch(source: String, ruleId: String?): Boolean =
        (source == "current-rank" || source == "mulligan-rank-preflight") &&
            isMandatoryRankRule(ruleId)

    /** Returns true when the caller must stop before enqueueing any surrender work. */
    fun blockSurrender(source: String, mandatoryRank: Boolean = false): Boolean {
        if (mandatoryRank) {
            log.info {
                "SURRENDER_ALLOWED reason=mandatory-rank-policy channel=${BuildChannel.identityToken(BuildInfo.RELEASE_CHANNEL)} source=$source " +
                    "dispatch=true queue=true retry=false replan=false"
            }
            return false
        }
        if (!shouldBlock(enabled(), mandatoryRank = false)) return false
        log.warn {
            "SURRENDER_BLOCKED reason=never-surrender channel=${BuildChannel.identityToken(BuildInfo.RELEASE_CHANNEL)} source=$source " +
                "dispatch=false queue=false retry=false replan=false"
        }
        return true
    }

    internal fun shouldBlock(enabled: Boolean, mandatoryRank: Boolean): Boolean =
        enabled && !mandatoryRank
}
