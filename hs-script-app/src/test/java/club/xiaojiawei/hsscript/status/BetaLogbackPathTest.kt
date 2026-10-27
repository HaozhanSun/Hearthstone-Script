package club.xiaojiawei.hsscript.status

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.rolling.RollingFileAppender
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BetaLogbackPathTest {

    @Test
    fun `logback writes the application log beneath the selected external directory`() {
        val directory = Files.createTempDirectory("beta-logback-path-").toFile()
        val previous = System.getProperty("hs.script.log.dir")
        val context = LoggerContext()
        try {
            System.setProperty("hs.script.log.dir", directory.absolutePath)
            val config = JoranConfigurator().apply { this.context = context }
            val stream = assertNotNull(javaClass.getResourceAsStream("/logback.xml"))
            stream.use(config::doConfigure)
            val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            val fileAppender = assertNotNull(
                (root.getAppender("file_async") as ch.qos.logback.classic.AsyncAppender).getAppender("file")
                    as? RollingFileAppender<*>,
            )
            assertTrue(fileAppender.file.endsWith("hs_script.log"))
            assertTrue(fileAppender.file.startsWith(directory.absolutePath))
            context.stop()
        } finally {
            context.stop()
            if (previous == null) System.clearProperty("hs.script.log.dir")
            else System.setProperty("hs.script.log.dir", previous)
            directory.deleteRecursively()
        }
    }
}
