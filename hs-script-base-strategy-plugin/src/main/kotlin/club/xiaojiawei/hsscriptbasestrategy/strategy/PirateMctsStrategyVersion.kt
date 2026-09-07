package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbasestrategy.VersionInfo

/**
 * Visible release identity for the two maintained Pirate MCTS strategies.
 *
 * The semantic revision is bumped when the strategy rules change. The build
 * suffix is derived from the application release, so a newly deployed
 * strategy cannot remain indistinguishable in the dropdown after a rebuild.
 */
object PirateMctsStrategyVersion {
    const val REVISION = "1.2"

    private val buildVersion: String
        get() = VersionInfo.VERSION.removePrefix("v").substringBefore('-')

    fun displayName(deckName: String): String =
        "$deckName V$REVISION · build $buildVersion"
}
