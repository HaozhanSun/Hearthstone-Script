package club.xiaojiawei.hsscript.ocr

import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistentPaddleXOcrSidecarBridgeTest {
    private val bridges = mutableListOf<PersistentPaddleXOcrSidecarBridge>()

    @AfterTest
    fun closeBridges() = bridges.forEach(PersistentPaddleXOcrSidecarBridge::close)

    @Test
    fun persistentSessionIsReusedAndRequestsAreSingleFlight() {
        val starts = AtomicInteger(0)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val requests = AtomicInteger(0)
        val bridge = bridge { _ ->
            starts.incrementAndGet()
            fakeSession { payload, _ ->
                val now = active.incrementAndGet()
                maxActive.updateAndGet { maxOf(it, now) }
                Thread.sleep(30)
                active.decrementAndGet()
                requests.incrementAndGet()
                json(payload, "ok")
            }
        }

        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<String> { bridge.recognize(TestImages.onePixel(), "first") }
            val second = pool.submit<String> { bridge.recognize(TestImages.onePixel(), "second") }
            assertEquals("ok", first.get(2, TimeUnit.SECONDS))
            assertEquals("ok", second.get(2, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, starts.get())
        assertEquals(2, requests.get())
        assertEquals(1, maxActive.get())
    }

    @Test
    fun failedRequestDiscardsSessionBeforeTheNextRequest() {
        val starts = AtomicInteger(0)
        val bridge = bridge { _ ->
            if (starts.incrementAndGet() == 1) {
                fakeSession { _, _ -> throw PaddleXOcrException("bounded timeout") }
            } else {
                fakeSession { payload, _ -> json(payload, "fresh") }
            }
        }

        assertFailsWith<PaddleXOcrException> {
            bridge.recognize(TestImages.onePixel(), "timeout")
        }
        assertEquals("fresh", bridge.recognize(TestImages.onePixel(), "retry"))
        assertEquals(2, starts.get())
    }

    @Test
    fun queuedRequestCanBeCancelledWithoutStartingAnOverlappingRequest() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val starts = AtomicInteger(0)
        val bridge = bridge { _ ->
            starts.incrementAndGet()
            fakeSession { payload, _ ->
                firstStarted.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
                json(payload, "ok")
            }
        }
        val first = Thread { bridge.recognize(TestImages.onePixel(), "first") }
        first.start()
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        val queued = Thread {
            assertFailsWith<PaddleXOcrCancelledException> {
                bridge.recognize(TestImages.onePixel(), "cancelled")
            }
        }
        queued.start()
        Thread.sleep(30)
        queued.interrupt()
        queued.join(2_000)
        releaseFirst.countDown()
        first.join(2_000)
        assertTrue(!queued.isAlive)
        assertEquals(1, starts.get())
    }

    private fun bridge(factory: PaddleXOcrSidecarSessionFactory): PersistentPaddleXOcrSidecarBridge =
        PersistentPaddleXOcrSidecarBridge(settings(), factory).also(bridges::add)

    private fun fakeSession(handler: (String, Long) -> String): PaddleXOcrSidecarSession =
        object : PaddleXOcrSidecarSession {
            override fun request(payload: String, timeoutMs: Long): String = handler(payload, timeoutMs)
            override fun close() = Unit
        }

    private fun json(payload: String, text: String): String {
        val requestId = Regex("\\\"request_id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(payload)?.groupValues?.get(1) ?: "unknown"
        return "{\"schema_version\":1,\"request_id\":\"$requestId\",\"ocr_text\":\"$text\",\"texts\":[]}"
    }

    private fun settings() = PaddleXOcrSettings(true, "python", "fake-module", "cpu", "", 500)
}
