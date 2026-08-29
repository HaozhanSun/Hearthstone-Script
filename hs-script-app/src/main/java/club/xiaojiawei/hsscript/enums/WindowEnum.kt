package club.xiaojiawei.hsscript.enums

import club.xiaojiawei.hsscript.consts.PROGRAM_NAME
import club.xiaojiawei.hsscriptbase.config.log
import javafx.stage.Screen
import javafx.stage.StageStyle

/**
 * @author 肖嘉威
 * @date 2023/10/1 10:37
 */

private fun formatTitle(title: String): String = "$PROGRAM_NAME-$title"

@Suppress("ktlint:standard:property-naming")
val SCREEN_WIDTH = Screen.getPrimary().bounds.width

@Suppress("ktlint:standard:property-naming")
val SCREEN_HEIGHT = Screen.getPrimary().bounds.height

private const val MAIN_DEFAULT_WIDTH = 300.0
private const val MAIN_MIN_WIDTH = 220.0
private const val MAIN_RIGHT_MARGIN = 12.0

enum class WindowEnum(
    val fxmlName: String,
    val title: String = "",
    val width: Double = -1.0,
    val height: Double = -1.0,
    val x: Double = -1.0,
    val y: Double = -1.0,
    val initXY: Boolean = true,
    val cache: Boolean = true,
    val alwaysOnTop: Boolean = false,
    val initStyle: StageStyle = StageStyle.DECORATED,
) {
    SETTINGS(
        "settings/settings.fxml",
        formatTitle("设置"),
        width = 695.0,
        height = 450.0,
    ),
    INIT_SETTINGS(
        "settings/initSettings.fxml",
    ),
    ADVANCED_SETTINGS(
        "settings/advancedSettings.fxml",
    ),
    PLUGIN_SETTINGS(
        "settings/pluginSettings.fxml",
    ),
    STRATEGY_SETTINGS(
        "settings/strategySettings.fxml",
    ),
    CARD_GROUP_SETTINGS(
        "settings/cardGroupSettings.fxml",
    ),
    DEVELOPER_SETTINGS(
        "settings/developerSettings.fxml",
    ),
    ABOUT(
        "settings/about.fxml", formatTitle("项目介绍"), alwaysOnTop = true
    ),
    MAIN(
        "main.fxml",
        PROGRAM_NAME,
        // Start wide enough for the controls and log text, while keeping a
        // small margin from the screen edge.  The stage remains resizable so
        // the user can move or size it around Hearthstone's End Turn control.
        MAIN_DEFAULT_WIDTH,
        590.0,
        SCREEN_WIDTH - MAIN_DEFAULT_WIDTH - MAIN_RIGHT_MARGIN,
        (SCREEN_HEIGHT - 590.0) / 2,
        alwaysOnTop = true,
    ),
    GAME_FRAME("gameFrame.fxml", cache = false ,alwaysOnTop = true),
    TIME_SETTINGS(
        "timeSettings.fxml",
        formatTitle("工作时间设置"),
        alwaysOnTop = true,
        cache = false
    ),
    CARD_ACTION_EDITOR("cardActionEditor.fxml"), STARTUP(
        "startup.fxml",
        formatTitle("启动页"),
        558.0,
        400.0,
    ),
    VERSION_MSG(
        "versionMsg.fxml",
        formatTitle("版本说明"),
        width = 550.0,
        cache = false,
    ),
    STATISTICS(
        "statistics.fxml",
        formatTitle("数据统计"),
        cache = false,
    ),
    GAME_DATA_ANALYSIS(
        "gameDataAnalysis.fxml",
        formatTitle("游戏数据分析"),
        x = 0.0,
        y = 0.0,
        cache = false,
        alwaysOnTop = true,
    ),
    MEASURE_GAME(
        "measureGame.fxml",
        formatTitle("游戏控件测量"),
        cache = false,
        alwaysOnTop = true,
    ),
    GAME_WINDOW_MODAL(
        "gameWindowModal.fxml",
        cache = false,
        alwaysOnTop = true,
        initStyle = StageStyle.TRANSPARENT,
    ),
    GAME_WINDOW_CONTROL_MODAL(
        "gameWindowModal.fxml",
        cache = false,
        alwaysOnTop = true,
        initStyle = StageStyle.TRANSPARENT,
    ), ;

    companion object {
        fun fromString(str: String?): WindowEnum? {
            if (str.isNullOrBlank()) return null
            return try {
                WindowEnum.valueOf(str.uppercase())
            } catch (e: Exception) {
                log.error(e) { }
                null
            }
        }
    }
}
