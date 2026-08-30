package club.xiaojiawei.hsscript.ocr

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaddleXOcrSidecarBridgeTest {

    @Test
    fun parsesOcrOnlyJsonFromSidecarStdout() {
        val bridge = bridgeWithRunner {
            SidecarProcessResult(
                exitCode = 0,
                stdout = "sidecar log\n{\"schema_version\":1,\"ocr_text\":\"寻找对手\",\"objects\":[],\"texts\":[],\"relations\":[]}\n",
                stderr = "",
            )
        }

        assertEquals("寻找对手", bridge.recognize(TestImages.onePixel(), "json-ok"))
    }

    @Test
    fun throwsWhenSidecarExitsNonZero() {
        val bridge = bridgeWithRunner {
            SidecarProcessResult(exitCode = 2, stdout = "", stderr = "missing paddlex")
        }

        val error = assertFailsWith<PaddleXOcrException> {
            bridge.recognize(TestImages.onePixel(), "json-fail")
        }
        assertTrue(error.message.orEmpty().contains("exit=2"))
    }

    @Test
    fun throwsWhenSidecarReturnsInvalidJson() {
        val bridge = bridgeWithRunner {
            SidecarProcessResult(exitCode = 0, stdout = "not json", stderr = "")
        }

        val error = assertFailsWith<PaddleXOcrException> {
            bridge.recognize(TestImages.onePixel(), "json-invalid")
        }
        assertTrue(error.message.orEmpty().contains("no JSON"))
    }

    @Test
    fun healthCheckReportsImportFailure() {
        val modulePath = Files.createTempDirectory("paddlex-module-test")
        try {
            val bridge = PaddleXOcrSidecarBridge(
                settings(modulePath.toString()),
                SidecarProcessRunner { _, _, _, _ ->
                    SidecarProcessResult(exitCode = 1, stdout = "", stderr = "No module named paddlex")
                },
            )

            val health = bridge.healthCheck()

            assertEquals(OcrProviderKind.PADDLEX, health.provider)
            assertTrue(!health.ok)
            assertTrue(health.details.contains("No module named paddlex"))
        } finally {
            modulePath.deleteIfExists()
        }
    }

    @Test
    fun forwardsConfiguredModelCacheToSidecarEnvironment() {
        val cachePath = "C:\\model-cache"
        var seenEnvironment = emptyMap<String, String>()
        val bridge = PaddleXOcrSidecarBridge(
            settings("fake-module").copy(modelCachePath = cachePath),
            SidecarProcessRunner { _, _, environment, _ ->
                seenEnvironment = environment
                SidecarProcessResult(
                    exitCode = 0,
                    stdout = "{\"schema_version\":1,\"ocr_text\":\"ok\"}",
                    stderr = "",
                )
            },
        )

        assertEquals("ok", bridge.recognize(TestImages.onePixel(), "cache-env"))
        assertEquals(cachePath, seenEnvironment["PADDLE_HOME"])
        assertEquals(cachePath, seenEnvironment["PADDLEX_HOME"])
    }

    private fun bridgeWithRunner(result: () -> SidecarProcessResult): PaddleXOcrSidecarBridge =
        PaddleXOcrSidecarBridge(
            settings("fake-module"),
            SidecarProcessRunner { _, _, _, _ -> result() },
        )

    private fun settings(modulePath: String): PaddleXOcrSettings =
        PaddleXOcrSettings(
            enabled = true,
            pythonExecutable = "python",
            modulePath = modulePath,
            device = "cpu",
            modelCachePath = "",
            timeoutMs = 1000,
        )
}
