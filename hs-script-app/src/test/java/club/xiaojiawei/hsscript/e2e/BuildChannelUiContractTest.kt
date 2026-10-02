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
    fun `main footer renders version channel and human readable Pacific artifact timestamp`() {
        assertEquals("Stable", BuildChannel.label("stable"))
        assertEquals("Beta", BuildChannel.label(" BETA "))
        assertEquals("Unknown", BuildChannel.label("nightly"))
        assertEquals("stable", BuildChannel.identityToken("stable"))
        assertEquals("beta", BuildChannel.identityToken("beta"))
        assertEquals("unknown", BuildChannel.identityToken("nightly"))
        assertEquals("hs-script-beta", BuildChannel.mainWindowTitle("hs-script", "beta"))
        assertEquals("hs-script", BuildChannel.mainWindowTitle("hs-script", "stable"))
        assertEquals("hs-script", BuildChannel.mainWindowTitle("hs-script", "unknown"))
        val artifactTimestamp = "2026-10-01 09:50:57 PDT"
        assertEquals(
            "当前版本：v4.16.194 · 渠道：Beta\n构建时间（Pacific）：$artifactTimestamp",
            formatVersionText("v4.16.194", "Beta", artifactTimestamp),
        )
        assertEquals(
            "当前版本：v4.16.194 · 渠道：Beta\n构建时间（Pacific）：$artifactTimestamp",
            formatVersionText("v4.16.194", "Beta", "  $artifactTimestamp  "),
            "second-precision artifact timestamp should remain visible and cleanly formatted",
        )
        assertEquals(
            "当前版本：v4.16.194 · 渠道：Stable\n构建时间（Pacific）：$artifactTimestamp",
            formatVersionText("v4.16.194", "Stable", artifactTimestamp),
        )
        assertEquals(
            "当前版本：${BuildInfo.VERSION} · 渠道：${BuildInfo.RELEASE_CHANNEL_LABEL}\n" +
                "构建时间（Pacific）：${BuildInfo.BUILD_TIMESTAMP_PACIFIC}",
            formatVersionText(BuildInfo.VERSION, BuildInfo.RELEASE_CHANNEL_LABEL, BuildInfo.BUILD_TIMESTAMP_PACIFIC),
        )
        assertTrue(
            Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} (PDT|PST)")
                .matches(BuildInfo.BUILD_TIMESTAMP_PACIFIC),
            "embedded build timestamp must show human-readable Pacific date, minutes, and seconds",
        )
    }

    @Test
    fun `release channel is injected through build metadata and deploy arguments`() {
        val root = repositoryRoot()
        val channel = Files.readString(root.resolve("release-channel.json"))
        assertTrue(channel.contains("\"channel\": \"beta\""))

        val buildInfoTemplate = Files.readString(root.resolve("hs-script-app/src/main/resources-filtered/build.info"))
        assertTrue(buildInfoTemplate.contains("channel=\${build-channel}"))

        val pom = Files.readString(root.resolve("pom.xml"))
        assertTrue(pom.contains("<build-channel>UNKNOWN</build-channel>"))

        val deploy = Files.readString(root.resolve("build-and-deploy.ps1"))
        assertTrue(deploy.contains("-Dbuild-channel=\$Channel"))

        val controller = Files.readString(root.resolve(
            "hs-script-app/src/main/java/club/xiaojiawei/hsscript/controller/javafx/MainController.kt",
        ))
        val windowEnum = Files.readString(root.resolve(
            "hs-script-app/src/main/java/club/xiaojiawei/hsscript/enums/WindowEnum.kt",
        ))
        assertTrue(controller.contains("BuildInfo.RELEASE_CHANNEL_LABEL"))
        assertTrue(controller.contains("BuildInfo.VERSION"))
        assertTrue(controller.contains("BuildInfo.BUILD_TIMESTAMP_PACIFIC"))
        assertTrue(controller.contains("构建时间（Pacific）"))
        assertTrue(windowEnum.contains("BuildChannel.mainWindowTitle(PROGRAM_NAME, BuildInfo.RELEASE_CHANNEL)"))
        assertTrue(Files.readString(root.resolve("hs-script-app/src/main/resources/fxml/main.fxml"))
            .contains("fx:id=\"versionText\""))

        assertTrue(buildInfoTemplate.contains("buildTimestampPacific=\${local-build-timestamp-pacific}"))
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
        assertTrue(deploy.contains("does not contain a usable SQLite cards table"))
        assertTrue(deploy.contains("CARD_DB_BACKUP="))
        assertTrue(deploy.contains("Copy-Item -LiteralPath \$stagedCardDb -Destination \$runtimeCardDb -Force"))
        assertTrue(deploy.contains("Post-deployment runtime"))
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
