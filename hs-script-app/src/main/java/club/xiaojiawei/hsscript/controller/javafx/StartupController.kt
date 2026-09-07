package club.xiaojiawei.hsscript.controller.javafx

import club.xiaojiawei.hsscript.consts.PROGRAM_NAME
import club.xiaojiawei.hsscript.enums.WindowEnum
import club.xiaojiawei.hsscript.utils.WindowUtil.hideStage
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.ProgressBar
import javafx.scene.text.Text
import java.net.URL
import java.util.*

/**
 * @author 肖嘉威
 * @date 2023/10/14 12:43
 */
class StartupController : Initializable {
    @FXML
    protected lateinit var progressBar: ProgressBar

    @FXML
    protected lateinit var tip: Text

    override fun initialize(url: URL?, resourceBundle: ResourceBundle?) {
        staticProgressBar = progressBar
        staticTip = tip
        begin()
    }

    companion object {
        private var staticProgressBar: ProgressBar? = null
        private var staticTip: Text? = null

        fun begin() = update(0.04, "$PROGRAM_NAME：正在启动…")

        fun update(progress: Double, message: String) {
            Platform.runLater {
                staticProgressBar?.progress = progress.coerceIn(0.0, 1.0)
                staticTip?.text = message
            }
        }

        fun failed(message: String) {
            update(0.0, "$PROGRAM_NAME：启动失败 · $message")
        }

        /**
         * 完成进度条并隐藏此窗口
         */
        fun complete() {
            Platform.runLater {
                staticProgressBar?.progress = 1.0
                staticTip?.text = "$PROGRAM_NAME：启动完成"
                hideStage(WindowEnum.STARTUP)
            }
        }
    }
}
