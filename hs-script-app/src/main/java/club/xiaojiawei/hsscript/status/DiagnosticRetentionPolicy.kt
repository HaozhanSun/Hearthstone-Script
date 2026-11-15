package club.xiaojiawei.hsscript.status

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Locale

internal enum class DiagnosticRootScope {
    RUNTIME_LOG_TREE,
    /** Historical enum name; applies to any channel's external app-log root. */
    EXTERNAL_BETA_LOG_TREE,
    ARTIFACT_CATEGORY,
    RUNTIME_EVIDENCE_TREE,
    RUNTIME_OCR_TREE,
    RUNTIME_DEBUG_MOUSE_TREE,
    SHARED_GAME_LOG_TREE,
    ARCHIVE_ARTIFACT_CATEGORY,
}

internal data class DiagnosticRetentionRoot(val channel: String, val path: Path, val scope: DiagnosticRootScope)

/** Explicit roots and file types that contain disposable diagnostics only. */
internal object DiagnosticRetentionPolicy {
    const val RETENTION_DAYS = 30L
    const val PERIODIC_INTERVAL_HOURS = 6L
    const val MAX_DELETIONS_PER_RUN = 25_000

    private val retainedDiagnosticExtensions = setOf(
        "log", "jsonl", "png", "jpg", "jpeg", "webp", "txt", "state", "json", "csv", "tsv",
    )
    private val runtimeDiagnosticDirectories = setOf(
        "app", "debug-screenshots", "decision-trace", "e2e-evidence", "game-results",
        "mcts-deck-profile", "mcts-replay", "mulligan", "offline-rank-two-roi",
        "rank-ocr-preprocess", "unknown-cards", "unknown-states",
    )
    private val artifactCategories = runtimeDiagnosticDirectories - "app"

    fun cutoff(now: Instant): Instant = now.minus(Duration.ofDays(RETENTION_DAYS))

    /** Strictly older than 30 days expires; exactly-at-cutoff files are retained. */
    fun isExpired(lastModified: Instant, cutoff: Instant): Boolean = lastModified.isBefore(cutoff)

    fun rootsForChannel(
        channel: String,
        runtimeRoot: Path,
        applicationLogDirectory: Path,
        artifactRoot: Path?,
        betaRuntimeLogRoot: Path? = null,
        sharedGameLogRoot: Path? = null,
    ): List<DiagnosticRetentionRoot> {
        val token = channelToken(channel)
        if (token !in SUPPORTED_CHANNELS) return emptyList()
        val runtimeLogRoot = runtimeRoot.resolve("log")
        val roots = mutableListOf(
            DiagnosticRetentionRoot(token, runtimeLogRoot, DiagnosticRootScope.RUNTIME_LOG_TREE),
            DiagnosticRetentionRoot(token, runtimeRoot.resolve("evidence"), DiagnosticRootScope.RUNTIME_EVIDENCE_TREE),
            DiagnosticRetentionRoot(token, runtimeRoot.resolve("ocr_res"), DiagnosticRootScope.RUNTIME_OCR_TREE),
            DiagnosticRetentionRoot(
                token,
                runtimeRoot.resolve("debug").resolve("mouse"),
                DiagnosticRootScope.RUNTIME_DEBUG_MOUSE_TREE,
            ),
        )
        if (sharedGameLogRoot != null) {
            roots += DiagnosticRetentionRoot(token, sharedGameLogRoot, DiagnosticRootScope.SHARED_GAME_LOG_TREE)
        }
        if (token in SUPPORTED_CHANNELS) {
            if (betaRuntimeLogRoot != null &&
                betaRuntimeLogRoot.toAbsolutePath().normalize() != runtimeLogRoot.toAbsolutePath().normalize()
            ) {
                // Before migration, historical diagnostics remain on C:. After
                // the compatibility junction exists this root is safely
                // refused, while the explicit direct destination is cleaned.
                roots += DiagnosticRetentionRoot(token, betaRuntimeLogRoot, DiagnosticRootScope.RUNTIME_LOG_TREE)
            }
            val externalLogRoot = applicationLogDirectory.parent
            if (externalLogRoot != null && !samePath(externalLogRoot, runtimeRoot)) {
                roots += DiagnosticRetentionRoot(token, externalLogRoot, DiagnosticRootScope.EXTERNAL_BETA_LOG_TREE)
            }
            if (artifactRoot != null) {
                artifactCategories.sorted().forEach { category ->
                    roots += DiagnosticRetentionRoot(
                        token,
                        artifactRoot.resolve(category),
                        DiagnosticRootScope.ARTIFACT_CATEGORY,
                    )
                }
            }
        }
        val seen = HashSet<String>()
        return roots.filter { root ->
            val normalized = root.path.toAbsolutePath().normalize()
            seen.add(normalized.toString().lowercase(Locale.ROOT))
        }
    }

    /**
     * Enumerates only timestamped C-volume preservation batches and known
     * channel/category descendants. The archive root itself is never scanned.
     */
    fun archiveRootsForChannel(channel: String, archiveRoot: Path?): List<DiagnosticRetentionRoot> {
        val token = channelToken(channel)
        if (token !in SUPPORTED_CHANNELS || archiveRoot == null) return emptyList()
        val channelDirectory = when (token) {
            "beta" -> "Beta"
            "release-candidate" -> "ReleaseCandidate"
            "stable" -> "Stable"
            else -> return emptyList()
        }
        val normalizedArchiveRoot = archiveRoot.toAbsolutePath().normalize()
        val batches = runCatching {
            if (!Files.isDirectory(normalizedArchiveRoot, LinkOption.NOFOLLOW_LINKS)) return@runCatching emptyList<Path>()
            Files.list(normalizedArchiveRoot).use { entries ->
                entries.filter { path ->
                    !Files.isSymbolicLink(path) &&
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                        ARCHIVE_BATCH_PATTERN.matches(path.fileName.toString())
                }.toList()
            }
        }.getOrDefault(emptyList())
        return batches.flatMap { batch ->
            val channelRoot = batch.resolve(channelDirectory)
            artifactCategories.sorted().map { category ->
                DiagnosticRetentionRoot(
                    token,
                    channelRoot.resolve(category),
                    DiagnosticRootScope.ARCHIVE_ARTIFACT_CATEGORY,
                )
            }
        }
    }

    fun allowsFile(root: DiagnosticRetentionRoot, file: Path): Boolean {
        val normalizedRoot = root.path.toAbsolutePath().normalize()
        val normalizedFile = file.toAbsolutePath().normalize()
        if (!normalizedFile.startsWith(normalizedRoot) || normalizedFile == normalizedRoot) return false
        val relative = normalizedRoot.relativize(normalizedFile)
        if (relative.nameCount == 0 || relative.any { it.toString() == ".." }) return false
        val extension = normalizedFile.fileName?.toString()?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT).orEmpty()
        if (extension !in retainedDiagnosticExtensions) return false
        val fileName = normalizedFile.fileName?.toString()?.lowercase(Locale.ROOT).orEmpty()
        if (fileName in setOf("config.json", "settings.json", "user-data.json", "statistics.db", "deployment-manifest.json") ||
            fileName.endsWith(".rollback") || fileName.contains("rollback-backup")
        ) return false

        return when (root.scope) {
            DiagnosticRootScope.RUNTIME_LOG_TREE ->
                relative.nameCount == 1 || relative.getName(0).toString().lowercase(Locale.ROOT) in runtimeDiagnosticDirectories
            DiagnosticRootScope.EXTERNAL_BETA_LOG_TREE -> when {
                relative.nameCount == 1 -> extension == "log"
                else -> relative.getName(0).toString().equals("app", ignoreCase = true) && relative.nameCount == 2 &&
                    extension == "log"
            }
            DiagnosticRootScope.ARTIFACT_CATEGORY -> true
            DiagnosticRootScope.RUNTIME_EVIDENCE_TREE -> true
            DiagnosticRootScope.RUNTIME_OCR_TREE -> extension == "png"
            DiagnosticRootScope.RUNTIME_DEBUG_MOUSE_TREE -> extension in setOf("csv", "png")
            DiagnosticRootScope.SHARED_GAME_LOG_TREE -> extension == "log"
            DiagnosticRootScope.ARCHIVE_ARTIFACT_CATEGORY -> true
        }
    }

    fun channelToken(channel: String): String = when (
        channel.trim().lowercase(Locale.ROOT).replace(Regex("[ _]+"), "-")
    ) {
        "rc", "releasecandidate", "release-candidate" -> "release-candidate"
        "stable", "release" -> "stable"
        "beta" -> "beta"
        else -> "unknown"
    }

    private val SUPPORTED_CHANNELS = setOf("beta", "release-candidate", "stable")
    private val ARCHIVE_BATCH_PATTERN = Regex("C-volume-reclaim-[0-9]{8}-[0-9]{6}")

    private fun samePath(left: Path, right: Path): Boolean =
        left.toAbsolutePath().normalize().toString().equals(
            right.toAbsolutePath().normalize().toString(),
            ignoreCase = true,
        )
}
