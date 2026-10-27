package club.xiaojiawei.hsscript.controller.javafx

import club.xiaojiawei.hsscript.builder.buildStarter
import club.xiaojiawei.controls.Notification
import club.xiaojiawei.controls.NotificationManager
import club.xiaojiawei.hsscript.bean.FrameData
import club.xiaojiawei.hsscript.bean.FrameReader
import club.xiaojiawei.hsscript.consts.GAME_CN_NAME
import club.xiaojiawei.hsscript.dll.CSystemDll
import club.xiaojiawei.hsscript.interfaces.StageHook
import club.xiaojiawei.hsscript.starter.AbstractStarter
import club.xiaojiawei.hsscript.starter.InjectGameStarter
import club.xiaojiawei.hsscript.starter.MissingGameStartPolicy
import club.xiaojiawei.hsscript.status.LifecycleTrace
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.go
import club.xiaojiawei.hsscript.utils.runUI
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.kt.dsl.StyleSize
import club.xiaojiawei.kt.dsl.button
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.image.ImageView
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.StackPane
import java.net.URL
import java.util.*
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author 肖嘉威
 * @date 2025/8/21 10:18
 */

class GameFrameController : Initializable, StageHook {

    @FXML
    protected lateinit var notificationManager: NotificationManager<Any>

    @FXML
    protected lateinit var gameFrameView: ImageView

    @FXML
    protected lateinit var rootPane: StackPane

    @FXML
    protected fun closeWindow() {
        rootPane.scene?.window?.hide()
    }

    private data class FrameSnapshot(
        val width: Int,
        val height: Int,
        val buffer: ByteArray,
    )

    @Volatile
    private var latestFrame: FrameSnapshot? = null

    private val frameUpdateScheduled = AtomicBoolean(false)

    fun handleFrame(frameData: FrameData) {
        val data = ByteArray(frameData.width * frameData.height * 4)
        val source = frameData.buffer.duplicate().apply {
            clear()
        }
        source.get(data)
        latestFrame = FrameSnapshot(frameData.width, frameData.height, data)
        requestFrameRender()
    }

    private fun requestFrameRender() {
        if (!frameUpdateScheduled.compareAndSet(false, true)) {
            return
        }
        runUI {
            try {
                if (!running || rootPane.scene?.window?.isShowing != true) {
                    latestFrame = null
                    return@runUI
                }
                val frame = latestFrame ?: return@runUI
                latestFrame = null
                val image = (gameFrameView.image as? WritableImage)
                    ?.takeIf { it.width.toInt() == frame.width && it.height.toInt() == frame.height }
                    ?: WritableImage(frame.width, frame.height).also {
                        gameFrameView.image = it
                    }
                image.pixelWriter.setPixels(
                    0,
                    0,
                    frame.width,
                    frame.height,
                    PixelFormat.getByteBgraInstance(),
                    frame.buffer,
                    0,
                    frame.width * 4,
                )
            } catch (e: Exception) {
                log.warn(e) { "更新捕获画面失败" }
            } finally {
                frameUpdateScheduled.set(false)
                if (latestFrame != null && running && rootPane.scene?.window?.isShowing == true) {
                    requestFrameRender()
                }
            }
        }
    }

    @Volatile
    private var running = false

    private fun execCapture() {
        InjectGameStarter().start()
        CSystemDll.INSTANCE.capture(true)
        val frameReader = FrameReader().apply {
            initialize()
        }
        running = true
        log.info { "开始捕获" }
        go {
            while (running) {
                val frame = frameReader.tryReadFrame()
                if (frame != null) {
                    handleFrame(frame)
                }
                Thread.sleep(1)
            }
            frameReader.close()
            CSystemDll.INSTANCE.capture(false)
        }
    }

    private fun startTimer(startStillAuthorized: () -> Boolean = { true }): Future<*> = go {
        (0 until 3).forEach { _ ->
            if (Thread.interrupted()) return@go
            Thread.sleep(1000)
        }
        if (!startStillAuthorized()) {
            log.warn { "GAME_START_RECOVERY_BLOCKED reason=authorization-changed-during-delay action=NONE" }
            return@go
        }
        val lastStarer = object : AbstractStarter() {
            override fun execStart() {
                if (!running) execCapture()
                else log.info { "GAME_CAPTURE_RETAINED reason=recovery-start-chain-already-capturing" }
            }
        }
        buildStarter {
            platform()
            game()
            custom(lastStarer)
        }.start()
    }

    @FXML
    protected fun startCapture() {
        val gameAlive = GameUtil.isAliveOfGame()
        val decision = MissingGameStartPolicy.decide(
            gameAlive = gameAlive,
            paused = PauseStatus.isPause,
            activeGame = WarEx.inWar || WarEx.war.currentPhase == WarPhaseEnum.REPLACE_CARD,
            terminal = WarEx.war.currentPhase == WarPhaseEnum.GAME_OVER || GameUtil.isTerminalGameState(),
            activeOrRecentQueue = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                LifecycleTrace.hasRecentMatchmakingInputIntent(),
        )
        when (decision) {
            MissingGameStartPolicy.Decision.BLOCK_PAUSED -> {
                log.warn { "GAME_START_RECOVERY_BLOCKED reason=paused action=NONE" }
                return
            }
            MissingGameStartPolicy.Decision.BLOCK_ACTIVE_OR_AMBIGUOUS -> {
                log.warn {
                    "GAME_START_RECOVERY_BLOCKED reason=active-or-ambiguous-session " +
                        "gameAlive=$gameAlive inWar=${WarEx.inWar} phase=${WarEx.war.currentPhase.name} " +
                        "recentQueue=${LifecycleTrace.hasRecentMatchmakingInputIntent()} action=NONE"
                }
                return
            }
            MissingGameStartPolicy.Decision.ATTACH_EXISTING_GAME -> {
                if (running) return
                execCapture()
            }
            MissingGameStartPolicy.Decision.START_CONFIGURED_CHAIN -> {
                val dispatch = MissingGameStartPolicy.dispatch(
                    decision = decision,
                    startStillAuthorized = {
                        MissingGameStartPolicy.decide(
                            gameAlive = GameUtil.isAliveOfGame(),
                            paused = PauseStatus.isPause,
                            activeGame = WarEx.inWar || WarEx.war.currentPhase == WarPhaseEnum.REPLACE_CARD,
                            terminal = WarEx.war.currentPhase == WarPhaseEnum.GAME_OVER || GameUtil.isTerminalGameState(),
                            activeOrRecentQueue = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                                LifecycleTrace.hasRecentMatchmakingInputIntent(),
                        ) == MissingGameStartPolicy.Decision.START_CONFIGURED_CHAIN
                    },
                    attachExistingGame = {},
                    startConfiguredChain = {
                        log.warn {
                            "GAME_START_RECOVERY_REQUESTED reason=operator-start-game-process-missing " +
                                "captureAlreadyRunning=$running activeQueue=false activeGame=false dispatch=CONFIGURED_STARTER_CHAIN"
                        }
                        val future = startTimer {
                            MissingGameStartPolicy.decide(
                                gameAlive = GameUtil.isAliveOfGame(),
                                paused = PauseStatus.isPause,
                                activeGame = WarEx.inWar || WarEx.war.currentPhase == WarPhaseEnum.REPLACE_CARD,
                                terminal = WarEx.war.currentPhase == WarPhaseEnum.GAME_OVER || GameUtil.isTerminalGameState(),
                                activeOrRecentQueue = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                                    LifecycleTrace.hasRecentMatchmakingInputIntent(),
                            ) == MissingGameStartPolicy.Decision.START_CONFIGURED_CHAIN
                        }
                        var notification: Notification<Any>? = null
                        notification = notificationManager.showInfo(
                            "${GAME_CN_NAME}没有打开，3秒后自动打开",
                            button {
                                +"取消"
                                styleNormal(StyleSize.SMALL)
                                onAction {
                                    future.cancel(true)
                                    notification?.hide()
                                }
                            },
                            3
                        )
                    },
                )
                if (dispatch == MissingGameStartPolicy.DispatchResult.BLOCKED) {
                    log.warn { "GAME_START_RECOVERY_BLOCKED reason=authorization-changed-before-schedule action=NONE" }
                }
            }
        }
    }

    @FXML
    protected fun stopCapture() {
        if (!running) return
        running = false
        latestFrame = null
        log.info { "停止捕获" }
    }

    override fun initialize(p0: URL?, p1: ResourceBundle?) {
    }

    override fun onHiding() {
        stopCapture()
    }

    override fun onShown() {
        startCapture()
    }
}
//
//fun main() {
//    val instance: ITesseract = Tesseract()
//    instance.setDatapath(Path.of(TESS_DATA_PATH).toString())
//    instance.setLanguage("chi_sim")
//    instance.setVariable("tessedit_char_whitelist", "0123456789/");
//    val result = instance.doOCR(File("C:\\Users\\28671\\Downloads\\1.png")).replace("\\s".toRegex(), "")
//    println(result)
//}
