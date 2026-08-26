package club.xiaojiawei.hsscriptbasecard

import club.xiaojiawei.hsscriptcardsdk.CardPlugin
import club.xiaojiawei.hsscriptpluginsdk.config.PluginScope
import club.xiaojiawei.hsscriptbase.util.SystemUtil
import javafx.event.EventHandler
import javafx.scene.Cursor
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Label
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox

/**
 * @author 肖嘉威
 * @date 2024/9/8 14:57
 */
class HsBaseCardPlugin : CardPlugin {
    override fun version(): String = VersionInfo.VERSION

    override fun author(): String = "XiaoJiawei"

    override fun graphicDescription(): Pane? {
        val deckCode = "AAECAQcC4+YG5eYGDp6fBJ+fBLSfBIagBIigBImgBI7UBJDUBJzUBJ/UBKPUBLT4BbX4Bd3zBgAA"
        val vBox =
            VBox().apply {
                spacing = 5.0
                children.addAll(
                    Label("包含以下卡组中卡牌的适配：(点击卡组代码复制)"),
                    Label("沙包战：").apply {
                        contentDisplay = ContentDisplay.RIGHT
                        graphic =
                            Label(deckCode).apply {
                                cursor = Cursor.HAND
                            }
                        onMouseClicked =
                            EventHandler {
                                SystemUtil.pasteTextToClipboard(deckCode)
                            }
                    },
                )
            }
        return vBox
    }

    override fun description(): String =
        """
        捆绑。包含对基础英雄技能的适配
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

    override fun pluginScope(): Array<String> {
        return PluginScope.PUBLIC
    }
}