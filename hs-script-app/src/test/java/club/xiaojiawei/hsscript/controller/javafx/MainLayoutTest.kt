package club.xiaojiawei.hsscript.controller.javafx

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class MainLayoutTest {

    @Test
    fun `log pane places shared telemetry above a resizable log stream`() {
        val fxml = Files.readString(Path.of("src/main/resources/fxml/main.fxml"))
        val logPaneStart = fxml.indexOf("<TitledPane text=\"日志\"")
        assertTrue(logPaneStart >= 0)
        val logPane = fxml.substring(logPaneStart)
        val telemetryStart = logPane.indexOf("fx:id=\"logStatisticsPane\"")
        val scrollStart = logPane.indexOf("fx:id=\"logScrollPane\"")

        assertTrue(telemetryStart >= 0)
        assertTrue(scrollStart > telemetryStart)
        assertTrue(logPane.contains("VBox.vgrow=\"ALWAYS\""))
        assertTrue(logPane.contains("fitToWidth=\"true\""))
        assertTrue(logPane.contains("hbarPolicy=\"NEVER\""))
        assertTrue(logPane.contains("总对局数："))
        assertTrue(logPane.contains("实战局数（未投降）："))
        assertTrue(logPane.contains("实战胜率："))
        assertTrue(logPane.contains("fx:id=\"logTotalGameCount\""))
        assertTrue(logPane.contains("fx:id=\"logPlayedGameCount\""))
        assertTrue(logPane.contains("fx:id=\"logWinningPercentage\""))
        assertTrue(logPane.contains("fx:id=\"logGameTime\""))
        assertTrue(logPane.contains("fx:id=\"logExp\""))
    }
}
