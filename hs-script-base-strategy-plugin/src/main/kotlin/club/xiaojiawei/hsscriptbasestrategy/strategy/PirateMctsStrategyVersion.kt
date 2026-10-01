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
    // Bump whenever Pirate Warrior or Pirate Demon Hunter decision rules,
    // mode availability, or mulligan contracts change.
    const val REVISION = "2.8"

    private val buildVersion: String
        get() = VersionInfo.VERSION.removePrefix("v").substringBefore('-')

    fun displayName(deckName: String): String =
        "$deckName V$REVISION · build $buildVersion"
}
