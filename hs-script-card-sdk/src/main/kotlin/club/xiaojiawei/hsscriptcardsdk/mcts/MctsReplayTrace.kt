package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable, machine-readable evidence for MCTS decisions.
 *
 * This is deliberately separate from the normal application log.  Every
 * record contains a live/simulated state snapshot, the candidate/action
 * details supplied by the caller, and a reason.  It is best-effort: an IO
 * failure is reported but can never make a card action fail.
 */
object MctsReplayTrace {
    const val DEFAULT_DIRECTORY = "log/mcts-replay"
    const val MAX_RETAINED_GAMES = 50

    private val lock = Any()
    private val nextSequence = ConcurrentHashMap<String, Long>()
    private var fallbackGameKey = "session-${System.currentTimeMillis()}"

    fun record(
        war: War,
        event: String,
        reason: String,
        details: Map<String, Any?> = emptyMap(),
        rootDirectory: File? = null,
    ): Path? {
        return synchronized(lock) {
            runCatching {
                val directory = gameDirectory(war, rootDirectory)
                val key = directory.absolutePath
                val sequence = (nextSequence[key] ?: 0L) + 1L
                nextSequence[key] = sequence
                val record = linkedMapOf<String, Any?>(
                    "schemaVersion" to 1,
                    "timestamp" to Instant.now().toString(),
                    "sequence" to sequence,
                    "event" to event,
                    "reason" to reason,
                    "gameId" to gameId(war),
                    "gameStartTime" to war.startTime,
                    "warTurn" to war.warTurn,
                    "playerTurn" to war.me.turn,
                    "isMyTurn" to war.isMyTurn,
                    "phase" to war.currentPhase.name,
                    "step" to war.currentTurnStep?.name,
                    "state" to snapshot(war),
                    "details" to details,
                )
                val file = directory.resolve("decisions.jsonl").toPath()
                Files.writeString(
                    file,
                    jsonValue(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                )
                file
            }.onFailure { error ->
                log.warn(error) { "MCTS回放记录失败 event=$event reason=$reason" }
            }.getOrNull()
        }
    }

    /** Returns the durable directory for one game and applies 50-game FIFO. */
    fun gameDirectory(war: War, rootDirectory: File? = null): File {
        val root = rootDirectory ?: File(System.getProperty("hs.script.mcts-replay.dir", DEFAULT_DIRECTORY))
        if (!root.exists()) root.mkdirs()
        val directory = File(root, "game-${safeGameKey(war)}")
        if (!directory.exists()) directory.mkdirs()
        pruneGames(root)
        return directory
    }

    fun snapshot(war: War): Map<String, Any?> = linkedMapOf(
        "myMana" to war.me.usableResource,
        "myResources" to war.me.resources,
        "myUsedResources" to war.me.usedResources,
        "hand" to war.me.handArea.cards.map(::cardSnapshot),
        "board" to war.me.playArea.cards.map(::cardSnapshot),
        "hero" to war.me.playArea.hero?.let(::cardSnapshot),
        "weapon" to war.me.playArea.weapon?.let(::cardSnapshot),
        "power" to war.me.playArea.power?.let(::cardSnapshot),
        "locations" to war.me.playArea.cards.filter { it.cardType === CardTypeEnum.LOCATION }.map(::cardSnapshot),
        "rivalBoard" to war.rival.playArea.cards.map(::cardSnapshot),
        "rivalHero" to war.rival.playArea.hero?.let(::cardSnapshot),
        "rivalWeapon" to war.rival.playArea.weapon?.let(::cardSnapshot),
        "boardSlotsUsed" to war.me.playArea.cards.size,
        "boardSlotsFree" to (war.me.playArea.maxSize - war.me.playArea.cards.size).coerceAtLeast(0),
    )

    private fun pruneGames(root: File) {
        val games = root.listFiles { file -> file.isDirectory && file.name.startsWith("game-") }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            ?: return
        games.dropLast(MAX_RETAINED_GAMES).forEach { old ->
            runCatching { old.deleteRecursively() }
                .onFailure { error -> log.warn(error) { "MCTS回放旧局清理失败 path=${old.absolutePath}" } }
        }
    }

    private fun gameId(war: War): String =
        war.me.gameId.takeIf { it.isNotBlank() }
            ?: war.firstPlayerGameId.takeIf { it.isNotBlank() }
            ?: war.me.playerId.takeIf { it.isNotBlank() }
            ?: "unknown"

    private fun safeGameKey(war: War): String {
        val id = gameId(war)
        val time = war.startTime.takeIf { it > 0L }?.toString() ?: "session"
        val raw = if (id == "unknown" && time == "session") fallbackGameKey else "$id-$time"
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun cardSnapshot(card: Card): Map<String, Any?> = linkedMapOf(
        "entityId" to card.entityId,
        "cardId" to card.cardId,
        "name" to card.entityName,
        "cost" to card.cost,
        "attack" to card.atc,
        "health" to card.health,
        "damage" to card.damage,
        "type" to card.cardType.name,
        "exhausted" to card.isExhausted,
        "uncertain" to card.isUncertain,
    )

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        is String -> jsonString(value)
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            "${jsonString(key.toString())}:${jsonValue(item)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { jsonValue(it) }
        else -> jsonString(value.toString())
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
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
        append('"')
    }
}
