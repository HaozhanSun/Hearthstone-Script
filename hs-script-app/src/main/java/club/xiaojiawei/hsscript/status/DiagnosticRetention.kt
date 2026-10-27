package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.consts.LOG_PATH
import club.xiaojiawei.hsscript.consts.ROOT_PATH
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import club.xiaojiawei.hsscriptbase.config.log
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class DiagnosticRetentionRootResult(
    val root: Path,
    var examined: Int = 0,
    var deleted: Int = 0,
    var bytesDeleted: Long = 0,
    var retainedFresh: Int = 0,
    var skippedActiveOrLocked: Int = 0,
    var skippedReparse: Int = 0,
    var skippedNonDiagnostic: Int = 0,
    var errors: Int = 0,
    var missing: Boolean = false,
)

internal data class DiagnosticRetentionReport(
    val channel: String,
    val cutoff: Instant,
    val roots: List<DiagnosticRetentionRootResult>,
) {
    val deleted: Int get() = roots.sumOf { it.deleted }
    val bytesDeleted: Long get() = roots.sumOf { it.bytesDeleted }
    val errors: Int get() = roots.sumOf { it.errors }
}

/** File operations are injectable so junctions, locks, permission failures and retries are deterministic in tests. */
internal class DiagnosticRetentionCleaner(
    private val isReparsePoint: (Path) -> Boolean = ::isPlatformReparsePoint,
    private val isOpenOrLocked: (Path) -> Boolean = ::isOpenOrLocked,
    private val canRead: (Path) -> Boolean = Files::isReadable,
    private val deleteFile: (Path) -> Boolean = { Files.deleteIfExists(it) },
) {
    fun clean(
        channel: String,
        roots: List<DiagnosticRetentionRoot>,
        now: Instant,
        activeFiles: Set<Path> = emptySet(),
        maxDeletes: Int = DiagnosticRetentionPolicy.MAX_DELETIONS_PER_RUN,
    ): DiagnosticRetentionReport {
        val cutoff = DiagnosticRetentionPolicy.cutoff(now)
        val active = activeFiles.map { it.toAbsolutePath().normalize().toString().lowercase(Locale.ROOT) }.toSet()
        val results = mutableListOf<DiagnosticRetentionRootResult>()
        var remainingDeletes = maxDeletes.coerceAtLeast(0)

        roots.forEach { root ->
            val normalizedRoot = root.path.toAbsolutePath().normalize()
            val result = DiagnosticRetentionRootResult(normalizedRoot)
            results += result
            try {
                if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
                    result.missing = true
                    return@forEach
                }
                if (isPathThroughReparsePoint(normalizedRoot)) {
                    result.skippedReparse++
                    return@forEach
                }
                if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS) || !canRead(normalizedRoot)) {
                    result.errors++
                    return@forEach
                }
                Files.walkFileTree(normalizedRoot, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dir != normalizedRoot && isReparsePoint(dir)) {
                            result.skippedReparse++
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (remainingDeletes <= 0) return FileVisitResult.CONTINUE
                        if (attrs.isSymbolicLink || attrs.isOther || isReparsePoint(file)) {
                            result.skippedReparse++
                            return FileVisitResult.CONTINUE
                        }
                        if (!attrs.isRegularFile || !DiagnosticRetentionPolicy.allowsFile(root, file)) {
                            result.skippedNonDiagnostic++
                            return FileVisitResult.CONTINUE
                        }
                        result.examined++
                        val key = file.toAbsolutePath().normalize().toString().lowercase(Locale.ROOT)
                        if (key in active || isOpenOrLocked(file)) {
                            result.skippedActiveOrLocked++
                            return FileVisitResult.CONTINUE
                        }
                        val modified = attrs.lastModifiedTime().toInstant()
                        if (!DiagnosticRetentionPolicy.isExpired(modified, cutoff)) {
                            result.retainedFresh++
                            return FileVisitResult.CONTINUE
                        }
                        val size = attrs.size()
                        try {
                            if (deleteFile(file)) {
                                result.deleted++
                                result.bytesDeleted += size
                                remainingDeletes--
                            }
                        } catch (_: Exception) {
                            result.errors++
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                        if (isReparsePoint(file)) result.skippedReparse++ else result.errors++
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                        if (exc != null) result.errors++
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (_: Exception) {
                result.errors++
            }
        }
        return DiagnosticRetentionReport(DiagnosticRetentionPolicy.channelToken(channel), cutoff, results)
    }

    private fun isPathThroughReparsePoint(path: Path): Boolean {
        var current = path.root ?: return true
        if (isReparsePoint(current)) return true
        for (part in path) {
            current = current.resolve(part)
            if (isReparsePoint(current)) return true
        }
        return false
    }
}

/** Startup plus once-daily background cleanup; all traversal stays off startup/UI threads. */
object DiagnosticRetentionService {
    private val started = AtomicBoolean(false)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diagnostic-retention").apply { isDaemon = true }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        log.info {
            "DIAGNOSTIC_RETENTION_SCHEDULED channel=${DiagnosticRetentionPolicy.channelToken(BuildInfo.RELEASE_CHANNEL)} " +
                "startup=immediate intervalHours=${DiagnosticRetentionPolicy.PERIODIC_INTERVAL_HOURS} " +
                "retentionDays=${DiagnosticRetentionPolicy.RETENTION_DAYS}"
        }
        executor.scheduleWithFixedDelay(
            {
                runCatching { runConfiguredCleanup() }
                    .onFailure { error -> log.warn(error) { "DIAGNOSTIC_RETENTION_RUN_FAILED action=RETRY_NEXT_INTERVAL" } }
            },
            0L,
            DiagnosticRetentionPolicy.PERIODIC_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
    }

    internal fun runConfiguredCleanup(now: Instant = Instant.now()): DiagnosticRetentionReport {
        val channel = BuildInfo.RELEASE_CHANNEL
        val runtime = Path.of(ROOT_PATH)
        val logDirectory = Path.of(LOG_PATH)
        val artifactRoot = System.getProperty("hs.script.artifact-root")
            ?.takeIf(String::isNotBlank)?.let(Path::of)
        val betaRuntimeLogRoot = System.getProperty("hs.script.beta-runtime-log-root")
            ?.takeIf(String::isNotBlank)?.let(Path::of)
        val roots = DiagnosticRetentionPolicy.rootsForChannel(
            channel, runtime, logDirectory, artifactRoot, betaRuntimeLogRoot,
        )
        val externalLogRoot = logDirectory.parent
        val active = buildSet {
            add(logDirectory.resolve("hs_script.log"))
            add(runtime.resolve("log").resolve("hs_script.log"))
            if (externalLogRoot != null) {
                add(externalLogRoot.resolve("guardian-state.json"))
                add(externalLogRoot.resolve("launcher-selection.log"))
            }
            System.getProperty("hs.script.guardian-state.path")?.takeIf(String::isNotBlank)?.let { add(Path.of(it)) }
            if (System.getProperty("hs.script.e2e") == "true") {
                val runId = System.getProperty("hs.script.e2e.run-id", "unknown")
                add(runtime.resolve("log").resolve("e2e-milestones-$runId.state"))
            }
        }
        return DiagnosticRetentionCleaner().clean(channel, roots, now, active).also(::logReport)
    }

    private fun logReport(report: DiagnosticRetentionReport) {
        report.roots.forEach { root ->
            log.info {
                "DIAGNOSTIC_RETENTION_ROOT channel=${report.channel} path=${root.root} cutoff=${report.cutoff} " +
                    "missing=${root.missing} examined=${root.examined} deleted=${root.deleted} " +
                    "bytesDeleted=${root.bytesDeleted} retainedFresh=${root.retainedFresh} " +
                    "skippedActiveOrLocked=${root.skippedActiveOrLocked} skippedReparse=${root.skippedReparse} " +
                    "skippedNonDiagnostic=${root.skippedNonDiagnostic} errors=${root.errors}"
            }
        }
        log.info {
            "DIAGNOSTIC_RETENTION_SUMMARY channel=${report.channel} cutoff=${report.cutoff} " +
                "roots=${report.roots.size} deleted=${report.deleted} bytesDeleted=${report.bytesDeleted} errors=${report.errors}"
        }
    }
}

private fun isOpenOrLocked(path: Path): Boolean = try {
    FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (lock == null) true else {
            lock.release()
            false
        }
    }
} catch (_: Exception) {
    true
}

private fun isPlatformReparsePoint(path: Path): Boolean {
    if (Files.isSymbolicLink(path)) return true
    val basic = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
        .getOrNull() ?: return false
    if (basic.isSymbolicLink || basic.isOther) return true
    if (!Platform.isWindows()) return false
    val attributes = runCatching { Kernel32.INSTANCE.GetFileAttributes(path.toString()) }.getOrDefault(-1)
    return attributes != -1 && (attributes and FILE_ATTRIBUTE_REPARSE_POINT) != 0
}

private const val FILE_ATTRIBUTE_REPARSE_POINT = 0x400
