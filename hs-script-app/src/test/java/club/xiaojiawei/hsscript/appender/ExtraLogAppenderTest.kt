package club.xiaojiawei.hsscript.appender

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.LoggingEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtraLogAppenderTest {
    @Test
    fun `lethal scan table is routed into the existing Logger display queue`() {
        val table = """MCTS_LETHAL_SCAN game=test turn=7 warTurn=13
            Friendly attack: raw=30 | legal-face minions=24 | hero=0
            Damage range: guaranteed=24 upper=unknown | verdict=GUARANTEED_LETHAL""".trimIndent()
        val event = LoggingEvent().apply {
            loggerName = "MctsLethalTelemetryTest"
            level = Level.INFO
            message = table
            timeStamp = System.currentTimeMillis()
        }
        val appender = ExtraLogAppender()
        appender.start()
        ExtraLogAppender.logQueue.clear()

        try {
            appender.doAppend(event)
            val displayed = ExtraLogAppender.logQueue.poll()

            assertTrue(displayed != null)
            assertEquals(table, displayed.formattedMessage)
        } finally {
            appender.stop()
            ExtraLogAppender.logQueue.clear()
        }
    }
}
