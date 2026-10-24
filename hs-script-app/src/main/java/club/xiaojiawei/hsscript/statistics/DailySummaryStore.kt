package club.xiaojiawei.hsscript.statistics

import club.xiaojiawei.hsscript.consts.DATA_DIR
import club.xiaojiawei.hsscriptbase.config.log
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

/** Metrics for all completed games attributed to one local calendar day. */
data class DailySummary(
    val date: LocalDate,
    val totalGames: Int = 0,
    val realGames: Int = 0,
    val wins: Int = 0,
    val playDurationMinutes: Int = 0,
    val experience: Int = 0,
    internal val recordIds: Set<Int> = emptySet(),
)

/** A completed game contribution used by the persistent daily summary. */
data class DailyGameSummaryEvent(
    val recordId: Int?,
    val result: Boolean,
    val surrendered: Boolean?,
    val durationMinutes: Int,
    val experience: Int,
    val completedAt: Instant,
)

/**
 * Small file-backed store for the metrics shown in the main control/log panes.
 * The file lives below the channel runtime's data directory, so Beta and
 * Stable never share daily totals.
 */
class DailySummaryStore(
    private val path: Path = DATA_DIR.resolve("daily-summary.properties"),
    private val zoneId: ZoneId = PACIFIC_ZONE,
    private val clock: Clock = Clock.system(zoneId),
) {
    private val summaries = linkedMapOf<LocalDate, DailySummary>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        load()
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    @Synchronized
    fun current(): DailySummary = current(LocalDate.now(clock))

    @Synchronized
    fun current(date: LocalDate): DailySummary = summaries[date] ?: DailySummary(date)

    /**
     * Reconcile records already present in the authoritative SQLite store.
     * Record ids make this safe to repeat after a crash between DB insertion
     * and summary persistence: missing contributions are added once, while
     * already applied contributions are ignored.
     */
    @Synchronized
    fun bootstrap(date: LocalDate, events: Iterable<DailyGameSummaryEvent>) {
        var summary = summaries[date] ?: DailySummary(date)
        var changed = !summaries.containsKey(date)
        for (event in events) {
            if (event.recordId != null && event.completedAt.atZone(zoneId).toLocalDate().equals(date) &&
                event.recordId !in summary.recordIds
            ) {
                summary = summary.add(event)
                changed = true
            }
        }
        if (changed) {
            summaries[date] = summary
            persist()
        }
    }

    @Synchronized
    fun record(event: DailyGameSummaryEvent): DailySummary {
        val date = event.completedAt.atZone(zoneId).toLocalDate()
        val current = summaries[date] ?: DailySummary(date)
        if (event.recordId != null && event.recordId in current.recordIds) return current
        val updated = current.add(event)
        summaries[date] = updated
        persist()
        notifyListeners()
        return updated
    }

    private fun DailySummary.add(event: DailyGameSummaryEvent): DailySummary {
        val realGame = event.surrendered != true
        return copy(
            totalGames = totalGames + 1,
            realGames = realGames + if (realGame) 1 else 0,
            wins = wins + if (realGame && event.result) 1 else 0,
            playDurationMinutes = playDurationMinutes + event.durationMinutes.coerceAtLeast(0),
            experience = experience + event.experience.coerceAtLeast(0),
            recordIds = if (event.recordId == null) recordIds else recordIds + event.recordId,
        )
    }

    private fun load() {
        if (!Files.isRegularFile(path)) return
        val properties = Properties()
        runCatching {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use(properties::load)
            val dates = properties.stringPropertyNames()
                .mapNotNull { key ->
                    if (!key.startsWith(DATE_PREFIX) || !key.endsWith(".totalGames")) return@mapNotNull null
                    key.removePrefix(DATE_PREFIX).removeSuffix(".totalGames")
                        .let { runCatching { LocalDate.parse(it) }.getOrNull() }
                }
            for (date in dates) {
                summaries[date] = DailySummary(
                    date = date,
                    totalGames = properties.intValue(date, "totalGames"),
                    realGames = properties.intValue(date, "realGames"),
                    wins = properties.intValue(date, "wins"),
                    playDurationMinutes = properties.intValue(date, "playDurationMinutes"),
                    experience = properties.intValue(date, "experience"),
                    recordIds = properties.stringPropertyNames()
                        .firstOrNull { it == "$DATE_PREFIX$date.recordIds" }
                        ?.let { properties.getProperty(it).split(',').mapNotNull(String::toIntOrNull).toSet() }
                        ?: emptySet(),
                )
            }
        }.onFailure { error ->
            log.warn(error) { "DAILY_SUMMARY_LOAD_FAILED path=${path.toAbsolutePath()}" }
            summaries.clear()
        }
    }

    private fun Properties.intValue(date: LocalDate, field: String): Int =
        getProperty("$DATE_PREFIX$date.$field")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun persist() {
        runCatching {
            path.parent?.let(Files::createDirectories)
            val temporary = path.resolveSibling("${path.fileName}.tmp-${ProcessHandle.current().pid()}")
            val properties = Properties()
            properties.setProperty("schema", "1")
            summaries.forEach { (date, summary) ->
                properties.setProperty("$DATE_PREFIX$date.totalGames", summary.totalGames.toString())
                properties.setProperty("$DATE_PREFIX$date.realGames", summary.realGames.toString())
                properties.setProperty("$DATE_PREFIX$date.wins", summary.wins.toString())
                properties.setProperty("$DATE_PREFIX$date.playDurationMinutes", summary.playDurationMinutes.toString())
                properties.setProperty("$DATE_PREFIX$date.experience", summary.experience.toString())
                if (summary.recordIds.isNotEmpty()) {
                    properties.setProperty("$DATE_PREFIX$date.recordIds", summary.recordIds.sorted().joinToString(","))
                }
            }
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                properties.store(writer, "Hearthstone Script daily summaries")
            }
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { error ->
            log.error(error) { "DAILY_SUMMARY_PERSIST_FAILED path=${path.toAbsolutePath()}" }
        }
    }

    private fun notifyListeners() {
        listeners.forEach { listener -> runCatching(listener).onFailure { error -> log.warn(error) { "DAILY_SUMMARY_LISTENER_FAILED" } } }
    }

    companion object {
        val PACIFIC_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
        private const val DATE_PREFIX = "date."
    }
}

object DailySummaryService {
    private val store = DailySummaryStore()

    fun addListener(listener: () -> Unit) = store.addListener(listener)

    fun current(): DailySummary = store.current()

    fun bootstrap(events: Iterable<DailyGameSummaryEvent>) =
        store.bootstrap(LocalDate.now(DailySummaryStore.PACIFIC_ZONE), events)

    fun record(event: DailyGameSummaryEvent): DailySummary = store.record(event)

}
