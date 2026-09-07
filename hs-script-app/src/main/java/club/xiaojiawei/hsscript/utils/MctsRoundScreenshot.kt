package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsReplayTrace
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO

/**
 * End-of-turn visual evidence for MCTS games.
 *
 * Screenshots live beside the JSONL replay for the game, and only the newest
 * ten round images are retained per game.  The direct [save] entry point is
 * also used by tests so FIFO behavior can be verified without a desktop.
 */
object MctsRoundScreenshot {
    const val MAX_ROUND_SCREENSHOTS = 10
    const val MAX_ACTION_SCREENSHOTS = 100

    private val timestampFormat = DateTimeFormatter.ofPattern(
        "yyyyMMdd-HHmmss-SSS",
        Locale.ROOT,
    ).withZone(ZoneId.systemDefault())
    private val captureLock = Any()
    private val capturedTurns = mutableMapOf<String, Int>()

    /** Register the app-layer Robot capture without coupling the SDK to app classes. */
    fun ensureActionCaptureInstalled() {
        MctsReplayTrace.installVisualSnapshotter { war, stage, step, phase, action, confirmation ->
            captureAction(war, stage, step, phase, action, confirmation)?.absolutePath
        }
    }

    fun capture(war: War, completedTurn: Int): File? {
        val gameKey = MctsReplayTrace.gameDirectory(war).absolutePath
        synchronized(captureLock) {
            if (capturedTurns[gameKey] == completedTurn) return null
            capturedTurns[gameKey] = completedTurn
        }

        var file: File? = null
        var failureReason: String? = null
        runCatching {
            if (GraphicsEnvironment.isHeadless()) {
                failureReason = "headless"
                return@runCatching
            }
            val bounds = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .screenDevices
                .map { it.defaultConfiguration.bounds }
                .fold(Rectangle()) { all, next -> all.union(next) }
            if (bounds.width <= 0 || bounds.height <= 0) {
                failureReason = "invalid-screen-bounds"
                return@runCatching
            }
            file = save(
                Robot().createScreenCapture(bounds),
                war,
                completedTurn,
            )
        }.onFailure { error ->
            failureReason = "${error.javaClass.simpleName}:${error.message ?: "capture-failed"}"
            log.warn(error) { "MCTS回合末截图失败 turn=$completedTurn" }
        }

        MctsReplayTrace.record(
            war,
            "round_end",
            "the MCTS turn ended and the app captured end-of-round evidence",
            mapOf(
                "completedTurn" to completedTurn,
                "screenshot" to file?.absolutePath,
                "screenshotFailure" to failureReason,
            ),
        )
        if (file != null) {
            log.info { "MCTS回合末截图 turn=$completedTurn path=${file!!.absolutePath}" }
        } else if (failureReason != null) {
            log.warn { "MCTS回合末截图未保存 turn=$completedTurn reason=$failureReason" }
        }
        return file
    }

    fun save(
        image: BufferedImage,
        war: War,
        completedTurn: Int,
        rootDirectory: File? = null,
        clock: Clock = Clock.systemDefaultZone(),
    ): File? {
        return runCatching {
            val gameDirectory = MctsReplayTrace.gameDirectory(war, rootDirectory)
            val screenshotDirectory = File(gameDirectory, "round-screenshots")
            if (!screenshotDirectory.exists() && !screenshotDirectory.mkdirs()) {
                throw IllegalStateException("cannot create ${screenshotDirectory.absolutePath}")
            }
            val stamp = timestampFormat.format(Instant.now(clock))
            val file = File(
                screenshotDirectory,
                "round-${completedTurn.toString().padStart(4, '0')}-$stamp.png",
            )
            ImageIO.write(image, "png", file)
            prune(screenshotDirectory)
            file
        }.onFailure { error ->
            log.warn(error) { "MCTS回合末截图写入失败 turn=$completedTurn" }
        }.getOrNull()
    }

    fun captureAction(
        war: War,
        stage: String,
        step: Int,
        phase: String?,
        action: String,
        confirmation: String,
    ): File? {
        var file: File? = null
        var failureReason: String? = null
        runCatching {
            if (GraphicsEnvironment.isHeadless()) {
                failureReason = "headless"
                return@runCatching
            }
            val bounds = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .screenDevices
                .map { it.defaultConfiguration.bounds }
                .fold(Rectangle()) { all, next -> all.union(next) }
            if (bounds.width <= 0 || bounds.height <= 0) {
                failureReason = "invalid-screen-bounds"
                return@runCatching
            }
            file = saveAction(
                Robot().createScreenCapture(bounds),
                war,
                stage,
                step,
                phase,
                action,
                confirmation,
            )
        }.onFailure { error ->
            failureReason = "${error.javaClass.simpleName}:${error.message ?: "capture-failed"}"
            log.warn(error) { "MCTS动作截图失败 stage=$stage step=$step" }
        }
        if (file != null) {
            log.info {
                "MCTS动作截图 stage=$stage turn=${war.me.turn} step=$step " +
                    "phase=${phase ?: "UNCLASSIFIED"} confirmation=$confirmation path=${file!!.absolutePath}"
            }
        } else if (failureReason != null) {
            log.warn { "MCTS动作截图未保存 stage=$stage turn=${war.me.turn} step=$step reason=$failureReason" }
        }
        return file
    }

    fun saveAction(
        image: BufferedImage,
        war: War,
        stage: String,
        step: Int,
        phase: String?,
        action: String,
        confirmation: String,
        rootDirectory: File? = null,
        clock: Clock = Clock.systemDefaultZone(),
    ): File? {
        return runCatching {
            val gameDirectory = MctsReplayTrace.gameDirectory(war, rootDirectory)
            val screenshotDirectory = File(gameDirectory, "action-screenshots")
            if (!screenshotDirectory.exists() && !screenshotDirectory.mkdirs()) {
                throw IllegalStateException("cannot create ${screenshotDirectory.absolutePath}")
            }
            val stamp = timestampFormat.format(Instant.now(clock))
            val safeAction = sanitize(action).take(80)
            val file = File(
                screenshotDirectory,
                "turn-${war.me.turn.toString().padStart(4, '0')}-step-${step.toString().padStart(2, '0')}" +
                    "-${sanitize(stage)}-${sanitize(phase ?: "UNCLASSIFIED")}-${sanitize(confirmation)}-$safeAction-$stamp.png",
            )
            ImageIO.write(image, "png", file)
            pruneActionScreenshots(screenshotDirectory)
            file
        }.onFailure { error ->
            log.warn(error) { "MCTS动作截图写入失败 stage=$stage step=$step" }
        }.getOrNull()
    }

    private fun prune(directory: File) {
        val images = directory.listFiles { file ->
            file.isFile && file.name.startsWith("round-") && file.extension.equals("png", true)
        }?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name }).orEmpty()
        images.dropLast(MAX_ROUND_SCREENSHOTS).forEach { old ->
            runCatching { Files.deleteIfExists(old.toPath()) }
                .onFailure { error -> log.warn(error) { "MCTS旧回合截图清理失败 path=${old.absolutePath}" } }
        }
    }

    private fun pruneActionScreenshots(directory: File) {
        val images = directory.listFiles { file ->
            file.isFile && file.extension.equals("png", true)
        }?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name }).orEmpty()
        images.dropLast(MAX_ACTION_SCREENSHOTS).forEach { old ->
            runCatching { Files.deleteIfExists(old.toPath()) }
                .onFailure { error -> log.warn(error) { "MCTS旧动作截图清理失败 path=${old.absolutePath}" } }
        }
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "unknown" }
}
