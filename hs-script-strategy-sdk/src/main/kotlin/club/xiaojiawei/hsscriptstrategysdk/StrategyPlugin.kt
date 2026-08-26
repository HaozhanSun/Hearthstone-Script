package club.xiaojiawei.hsscriptstrategysdk

import club.xiaojiawei.hsscriptpluginsdk.Plugin

/**
 * @author 肖嘉威
 * @date 2024/9/22 19:18
 */
interface StrategyPlugin : Plugin {
    companion object {
        /**
         * 最低兼容版本
         */
        const val MINIMUM_COMPATIBLE_VERSION = "1.0.1"
    }
}