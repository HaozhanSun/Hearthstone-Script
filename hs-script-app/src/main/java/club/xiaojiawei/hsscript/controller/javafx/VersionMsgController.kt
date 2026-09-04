package club.xiaojiawei.hsscript.controller.javafx

import club.xiaojiawei.hsscriptbase.const.BuildInfo
import club.xiaojiawei.md.MarkdownView
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Label
import javafx.scene.layout.AnchorPane
import javafx.scene.layout.Pane
import java.net.URL
import java.util.*


/**
 * @author 肖嘉威
 * @date 2023/10/14 12:43
 */
class VersionMsgController : Initializable {
    @FXML
    protected lateinit var versionDescription: MarkdownView

    @FXML
    protected lateinit var rootPane: Pane

    @FXML
    protected lateinit var version: Label

    override fun initialize(
        url: URL?,
        resourceBundle: ResourceBundle?,
    ) {

        version.text = "${BuildInfo.VERSION} · 渠道：${BuildInfo.RELEASE_CHANNEL_LABEL}"
        //        TODO 版本更新时修改！！！
        versionDescription.setMarkdown(
            """
            本地构建基线：${BuildInfo.UPSTREAM_BASELINE_VERSION}
            本地构建时间（Pacific）：${BuildInfo.BUILD_TIMESTAMP_PACIFIC}
            发布渠道：${BuildInfo.RELEASE_CHANNEL_LABEL}

            🔧 重构与优化
            1. 相关工具更新成64位以适配游戏
            """.trimIndent()
        )
    }

    @FXML
    protected fun closeWindow(actionEvent: ActionEvent) {
        rootPane.scene.window.hide()
    }
}
