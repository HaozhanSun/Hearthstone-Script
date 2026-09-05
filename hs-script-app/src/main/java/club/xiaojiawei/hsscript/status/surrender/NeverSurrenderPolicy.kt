package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.const.BuildChannel
import club.xiaojiawei.hsscriptbase.const.BuildInfo

/**
 * Beta-only kill switch for script-initiated concessions.
 *
 * The setting is deliberately channel-scoped: a persisted Beta diagnostic
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
        setting && BuildChannel.identityToken(channel) == "beta"

    internal fun rankIsIneligible(rank: Int): Boolean =
        rank in 1..10 && rank != 5 && rank != 10

    /** Returns true when the caller must stop before enqueueing any surrender work. */
    fun blockSurrender(source: String): Boolean {
        if (!enabled()) return false
        log.warn {
            "SURRENDER_BLOCKED reason=never-surrender channel=beta source=$source " +
                "dispatch=false queue=false retry=false replan=false"
        }
        return true
    }
}
