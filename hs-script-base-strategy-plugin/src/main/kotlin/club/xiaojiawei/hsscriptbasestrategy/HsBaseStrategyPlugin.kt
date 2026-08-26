package club.xiaojiawei.hsscriptbasestrategy

import club.xiaojiawei.hsscriptstrategysdk.StrategyPlugin

/**
 * @author 肖嘉威
 * @date 2024/9/8 14:57
 */
class HsBaseStrategyPlugin : StrategyPlugin {
    override fun version(): String = VersionInfo.VERSION

    override fun author(): String = "XiaoJiawei"

    override fun description(): String =
        """
        捆绑
        """.trimIndent()

    override fun id(): String = "xjw-base-plugin"

    override fun name(): String = "基础"

    override fun homeUrl(): String = "https://github.com/xjw580/Hearthstone-Script"

    /**
     * 使用的卡牌SDK版本
     */
    override fun cardSDKVersion(): String? = if (VersionInfo.CARD_SDK_VERSION_USED.endsWith("}")) null else VersionInfo.CARD_SDK_VERSION_USED

    /**
     * 使用的策略SDK版本
     */
    override fun strategySDKVersion(): String? = if (VersionInfo.STRATEGY_SDK_VERSION_USED.endsWith("}")) null else VersionInfo.STRATEGY_SDK_VERSION_USED
}