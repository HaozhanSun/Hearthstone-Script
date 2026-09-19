package club.xiaojiawei.hsscriptcardsdk.diagnostics

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.area.Area
import club.xiaojiawei.hsscriptcardsdk.bean.area.HandArea
import club.xiaojiawei.hsscriptcardsdk.bean.area.PlayArea
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent, low-noise evidence for cards that did not resolve to an action.
 *
 * This is deliberately separate from the user-visible diagnostic log.  The
 * existing CARD_ACTION_UNRECOGNIZED messages stay intact; this collector adds
 * one append-only JSONL record per observation so later reports can distinguish
 * an actionable card in our hand from a provisional entity-discovery event.
 */
enum class UnknownCardSourceZone {
    HAND,
    BOARD,
    ENTITY_DISCOVERY,
    GENERATED,
    HISTORICAL_BACKFILL,
    UNKNOWN,
}

object UnknownCardCollector {

    private val lock = Any()
    private val announcedFiles = ConcurrentHashMap.newKeySet<Path>()

    @Volatile
    private var configuredFile: Path? = null

    fun record(
        cardId: String,
        cardName: String?,
        reason: String,
        action: String,
        sourceZone: UnknownCardSourceZone,
        phase: String,
        identitySource: String? = null,
        route: String = "UNKNOWN",
        safeAction: String = action,
    ) {
        if (cardId.isBlank()) return
        val file = collectorFile()
        val readableName = cardName?.takeUnless { it.isBlank() } ?: "未知卡牌($cardId)"
        val record = buildString {
            append('{')
            appendJsonField("observedAt", Instant.now().toString())
            append(',')
            appendJsonField("cardId", cardId)
            append(',')
            appendJsonField("cardName", readableName)
            append(',')
            appendJsonField("reason", reason)
            append(',')
            appendJsonField("action", action)
            append(',')
            appendJsonField("sourceZone", sourceZone.name)
            append(',')
            appendJsonField("phase", phase)
            append(',')
            appendJsonField("route", route)
            append(',')
            appendJsonField("safeAction", safeAction)
            if (!identitySource.isNullOrBlank()) {
                append(',')
                appendJsonField("identitySource", identitySource)
            }
            append('}')
        }
        synchronized(lock) {
            try {
                file.parent?.let(Files::createDirectories)
                Files.writeString(
                    file,
                    record + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    CREATE,
                    APPEND,
                )
                if (announcedFiles.add(file)) {
                    log.info { "UNKNOWN_CARD_COLLECTOR_READY file=$file format=jsonl" }
                }
            } catch (exception: Exception) {
                // Collector failure must never block card parsing or a turn.
                log.warn(exception) { "UNKNOWN_CARD_COLLECTOR_WRITE_FAILED file=$file" }
            }
        }
    }

    fun collectorFile(): Path {
        configuredFile?.let { return it }
        val configured = System.getProperty("hs.script.unknown-card-collector.file")
            ?.takeUnless { it.isBlank() }
        return configured?.let(Path::of)
            ?: Path.of(
                System.getProperty("user.dir"),
                "log",
                "unknown-cards",
                "unknown-card-events-${LocalDate.now()}.jsonl",
            )
    }

    /** Used by deterministic offline tests without touching the live runtime. */
    fun configureForTests(file: Path) {
        synchronized(lock) {
            configuredFile = file
            announcedFiles.clear()
        }
    }

    /** Restores the normal runtime path after an offline test. */
    fun resetConfiguration() {
        synchronized(lock) {
            configuredFile = null
            announcedFiles.clear()
        }
    }

    fun sourceZone(area: Area): UnknownCardSourceZone = when (area) {
        is HandArea -> UnknownCardSourceZone.HAND
        is PlayArea -> UnknownCardSourceZone.BOARD
        else -> UnknownCardSourceZone.UNKNOWN
    }

    private fun StringBuilder.appendJsonField(name: String, value: String) {
        append('"')
        append(escapeJson(name))
        append("\":\"")
        append(escapeJson(value))
        append('"')
    }

    private fun escapeJson(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}
