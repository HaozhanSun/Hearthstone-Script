package club.xiaojiawei.hsscript.config

import club.xiaojiawei.hsscript.initializer.AbstractInitializer
import club.xiaojiawei.hsscript.initializer.BaseInitializer
import club.xiaojiawei.hsscript.initializer.DriverInitializer
import club.xiaojiawei.hsscript.initializer.GameLogInitializer
import club.xiaojiawei.hsscript.initializer.GamePathInitializer
import club.xiaojiawei.hsscript.initializer.PluginInitializer
import club.xiaojiawei.hsscript.initializer.ServiceInitializer

/**
 * Starter的责任链配置
 * @author 肖嘉威
 * @date 2023/7/5 14:48
 */
object InitializerConfig {

    val initializer: AbstractInitializer = BaseInitializer()

    init {
        initializer
            .setNextInitializer(GamePathInitializer())
            .setNextInitializer(GameLogInitializer())
            .setNextInitializer(PluginInitializer())
            .setNextInitializer(DriverInitializer())
            .setNextInitializer(ServiceInitializer())
    }
}
