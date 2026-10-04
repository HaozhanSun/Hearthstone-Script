package club.xiaojiawei.hsscript.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class AppRuntimeChannelDetectorTest {

    @Test
    fun `release candidate metadata identifies an isolated beta-derived runtime`() {
        assertEquals(
            AppRuntimeChannel.RELEASE_CANDIDATE,
            AppRuntimeChannelDetector.fromMetadata("{\"channel\":\"release-candidate\"}"),
        )
        assertEquals(
            AppRuntimeChannel.BETA,
            AppRuntimeChannelDetector.fromMetadata("{\"channel\":\"beta\"}"),
        )
        assertEquals(
            AppRuntimeChannel.STABLE,
            AppRuntimeChannelDetector.fromMetadata("{\"channel\":\"stable\"}"),
        )
        assertEquals(AppRuntimeChannel.UNKNOWN, AppRuntimeChannelDetector.fromMetadata(null))
    }
}
