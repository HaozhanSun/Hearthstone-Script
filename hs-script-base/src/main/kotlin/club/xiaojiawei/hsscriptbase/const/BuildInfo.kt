package club.xiaojiawei.hsscriptbase.const

import club.xiaojiawei.hsscriptbase.config.log
import java.util.*

/**
 * @author 肖嘉威 xjw580@qq.com
 * @date 2024/9/24 9:53
 */
object BuildInfo {

    val VERSION: String

    val UPSTREAM_BASELINE_VERSION: String

    val BUILD_TIMESTAMP_PACIFIC: String

    val ARTIFACT_ID: String

    val SOFT_RUN_MODE: SoftRunMode

    init {
        val properties = Properties()
        try {
            BuildInfo::class.java.classLoader.getResourceAsStream("build.info").use { resourceStream ->
                if (resourceStream == null) {
                    log.error { "build.info file is not found in the classpath." }
                } else {
                    properties.load(resourceStream)

                }
            }
        } catch (e: Exception) {
            log.warn(e) { "无法读取版本号" }
        }
        VERSION = properties.getProperty("version", "UNKNOWN")
        UPSTREAM_BASELINE_VERSION = properties.getProperty("upstreamBaselineVersion", "UNKNOWN")
        BUILD_TIMESTAMP_PACIFIC = properties.getProperty("buildTimestampPacific", "UNKNOWN")
        ARTIFACT_ID = properties.getProperty("artifactId", "UNKNOWN")
        SOFT_RUN_MODE = if (Objects.requireNonNull(javaClass.getResource(""))
                .protocol == "file"
        ) {
            SoftRunMode.FILE
        } else {
            SoftRunMode.fromString(properties.getProperty("softRunMode", SoftRunMode.JAR.name))
        }
    }


}

enum class SoftRunMode {
    JAR,
    NATIVE,
    FILE
    ;

    companion object {
        fun fromString(value: String): SoftRunMode {
            return try {
                valueOf(value.uppercase(Locale.ROOT))
            } catch (e: Exception) {
                JAR
            }
        }
    }
}
