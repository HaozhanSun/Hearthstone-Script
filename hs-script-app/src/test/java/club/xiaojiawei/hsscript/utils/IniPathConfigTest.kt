package club.xiaojiawei.hsscript.utils

import org.ini4j.Config
import org.ini4j.Ini
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.io.StringWriter

class IniPathConfigTest {
    @Test
    fun `windows paddlex paths survive ini round trip`() {
        val value = "C:/Users/yzjsh/Documents/Codex/Hearthstone Script Release Candidate/resources/paddlex-vision/src"
        val config = Config().apply { isEscape = false }
        val written = Ini().apply {
            this.config = config
            put("dev", "PADDLEX_OCR_MODULE_PATH", value)
        }.let { ini ->
            StringWriter().also { writer -> ini.store(writer) }.toString()
        }

        val loaded = Ini(StringReader(written)).apply { this.config = config }
        assertEquals(value, loaded.get("dev", "PADDLEX_OCR_MODULE_PATH"))
    }
}
