package club.xiaojiawei.hsscript.e2e

import club.xiaojiawei.hsscript.controller.javafx.formatVersionText
import club.xiaojiawei.hsscript.utils.ExistingInstanceSignal
import club.xiaojiawei.hsscriptbase.const.BuildChannel
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildChannelUiContractTest {

    @Test
    fun `version text renders the injected channel label`() {
        assertEquals("Stable", BuildChannel.label("stable"))
        assertEquals("Beta", BuildChannel.label(" BETA "))
        assertEquals("Unknown", BuildChannel.label("nightly"))
        assertEquals("stable", BuildChannel.identityToken("stable"))
        assertEquals("beta", BuildChannel.identityToken("beta"))
        assertEquals("unknown", BuildChannel.identityToken("nightly"))
        assertEquals(
            "当前版本：v4.16.194 · 渠道：Beta",
            formatVersionText("v4.16.194", "Beta"),
        )
        assertEquals(
            "当前版本：v4.16.194 · 渠道：Stable",
            formatVersionText("v4.16.194", "Stable"),
        )
    }

    @Test
    fun `release channel is injected through build metadata and deploy arguments`() {
        val root = repositoryRoot()
        val channel = Files.readString(root.resolve("release-channel.json"))
        assertTrue(channel.contains("\"channel\": \"beta\""))

        val buildInfo = Files.readString(root.resolve("hs-script-app/src/main/resources-filtered/build.info"))
        assertTrue(buildInfo.contains("channel=\${build-channel}"))

        val pom = Files.readString(root.resolve("pom.xml"))
        assertTrue(pom.contains("<build-channel>UNKNOWN</build-channel>"))

        val deploy = Files.readString(root.resolve("build-and-deploy.ps1"))
        assertTrue(deploy.contains("-Dbuild-channel=\$Channel"))

        val controller = Files.readString(root.resolve(
            "hs-script-app/src/main/java/club/xiaojiawei/hsscript/controller/javafx/MainController.kt",
        ))
        assertTrue(controller.contains("BuildInfo.RELEASE_CHANNEL_LABEL"))
    }

    @Test
    fun `beta hidden-window lifecycle has a labeled tray and single-instance show signal`() {
        val root = repositoryRoot()
        val mainApplication = Files.readString(root.resolve(
            "hs-script-app/src/main/java/club/xiaojiawei/hsscript/MainApplication.kt",
        ))
        val systemUtil = Files.readString(root.resolve(
            "hs-script-app/src/main/java/club/xiaojiawei/hsscript/utils/SystemUtil.kt",
        ))
        val main = Files.readString(root.resolve(
            "hs-script-app/src/main/java/club/xiaojiawei/hsscript/Main.kt",
        ))
        val instanceSignal = Files.readString(root.resolve(
            "hs-script-app/src/main/java/club/xiaojiawei/hsscript/utils/ExistingInstanceSignal.kt",
        ))
        val assembly = Files.readString(root.resolve("hs-script-app/assembly.xml"))

        assertTrue(mainApplication.contains("BETA_TRAY_INIT mode=AWT"))
        assertTrue(mainApplication.contains("BETA_TRAY_READY mode=AWT"))
        assertTrue(mainApplication.contains("显示窗口（\${BuildInfo.RELEASE_CHANNEL_LABEL}）"))
        assertTrue(mainApplication.contains("WindowUtil.showStage(WindowEnum.MAIN)"))
        assertTrue(mainApplication.contains("shutdownSoft()"))
        assertTrue(mainApplication.contains("setSystemTray()"))
        assertTrue(systemUtil.contains("fun addTrayWithLabel"))
        assertTrue(systemUtil.contains("TrayIcon(image, displayName"))
        assertTrue(systemUtil.contains("TRAY_ALREADY_INITIALIZED"))
        assertTrue(main.contains("programLockNameForChannel"))
        assertTrue(main.contains("ExistingInstanceSignal.requestShowMain()"))
        assertTrue(instanceSignal.contains("requestPathForChannel"))
        assertTrue(assembly.contains("<directory>\${project.parent.basedir}</directory>"))
        assertTrue(assembly.contains("<include>*.db</include>"))
        val deploy = Files.readString(root.resolve("build-and-deploy.ps1"))
        assertTrue(deploy.contains("Assembled deployment is missing hs_cards.db"))
        assertTrue(deploy.contains("Assembled deployment contains an empty hs_cards.db"))
        assertEquals(
            "hs-script-show-main.beta.request",
            ExistingInstanceSignal.requestPathForChannel("Beta").fileName.toString(),
        )
    }

    private fun repositoryRoot(): Path {
        val current = Path.of("").toAbsolutePath().normalize()
        return sequenceOf(current, current.parent)
            .filterNotNull()
            .first { Files.isRegularFile(it.resolve("release-channel.json")) }
    }
}
