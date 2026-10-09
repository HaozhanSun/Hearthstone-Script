package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbasestrategy.VersionInfo

/** Visible version identity for the Elemental Mage MCTS strategy. */
object ElementalMageMctsStrategyVersion {
    const val REVISION = "1.5"

    private val buildVersion: String
        get() = VersionInfo.VERSION.removePrefix("v").substringBefore('-')

    fun displayName(): String = "元素法 V$REVISION · build $buildVersion"
}
