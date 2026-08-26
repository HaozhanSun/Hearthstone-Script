package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.util.CardDBUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes one JSON object per line for every hard-coded strategy decision.
 *
 * The normal application log is useful for operational events, but it is not
 * a reliable decision ledger: it is noisy, and it does not preserve the hand
 * snapshot or the reason a candidate was selected. This writer keeps that
 * information in a separate, inspectable file without adding the full hand
 * dump to the UI log.
 */
internal object DecisionTrace {

    private val lock = Any()
    private val cardNameCache = ConcurrentHashMap<String, String>()
    private val knownDisplayNames = mapOf(
        "TOY_518" to "宝藏经销商",
        "GVG_075" to "船载火炮",
    )

    private var activeGameKey: String? = null
    private var activeFile: Path? = null
    private var sequence = 0L
    private var fallbackGameKey = "session-${System.currentTimeMillis()}"

    data class Result(
        val sequence: Long,
        val file: Path,
    )

    fun record(
        war: War,
        event: String,
        reason: String,
        candidate: Card? = null,
        hand: List<Card>? = null,
        rule: String? = null,
        priority: Int? = null,
        outcome: String? = null,
        action: String? = null,
        relatedSequence: Long? = null,
    ): Result {
        synchronized(lock) {
            val gameKey = gameKey(war)
            var announceFile: Path? = null
            if (gameKey != activeGameKey || activeFile == null) {
                activeGameKey = gameKey
                activeFile = try {
                    tracePath(gameKey)
                } catch (error: Exception) {
                    // Tracing must never become a new reason for a game crash.
                    log.warn(error) { "海盗恶魔猎手：创建决策追踪目录失败 gameKey=$gameKey" }
                    return Result(sequence, Paths.get("log", "decision-trace-unavailable.jsonl"))
                }
                sequence = 0L
                announceFile = activeFile
            }

            val currentFile = activeFile!!
            val currentSequence = ++sequence
            val record = linkedMapOf<String, Any?>(
                "schemaVersion" to 1,
                "timestamp" to Instant.now().toString(),
                "sequence" to currentSequence,
                "event" to event,
                "gameId" to gameId(war),
                "gameStartTime" to war.startTime,
                "warTurn" to war.warTurn,
                "playerTurn" to war.me.turn,
                "step" to war.currentTurnStep?.name,
                "usableResource" to war.me.usableResource,
                "boardSize" to war.me.playArea.cards.size,
                "rule" to rule,
                "priority" to priority,
                "outcome" to outcome,
                "action" to action,
                "reason" to reason,
                "relatedSequence" to relatedSequence,
                "candidate" to candidate?.let(::cardRecord),
                "hand" to (hand ?: war.me.handArea.cards.toList()).map(::cardRecord),
            )

            try {
                Files.writeString(
                    currentFile,
                    jsonValue(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                )
            } catch (error: Exception) {
                // A tracing failure must never stop the game strategy.
                log.warn(error) { "海盗恶魔猎手：写入决策追踪失败 file=$currentFile" }
            }

            announceFile?.let { file ->
                log.info { "海盗恶魔猎手：决策追踪文件=${file.toAbsolutePath()}" }
            }
            return Result(currentSequence, currentFile)
        }
    }

    fun displayName(card: Card): String {
        knownDisplayNames.entries.firstOrNull { (id, _) ->
            card.cardId == id || card.cardId.contains(id)
        }?.let { return it.value }

        card.getFormatEntityName().takeIf { it.isNotBlank() }?.let { return it }

        val cardId = card.cardId
        if (cardId.isBlank()) return "未知卡牌"
        return cardNameCache.computeIfAbsent(cardId) {
            try {
                CardDBUtil.queryCardById(it).firstOrNull()?.name?.takeIf(String::isNotBlank)
                    ?: "未知卡牌($it)"
            } catch (error: Exception) {
                log.warn(error) { "海盗恶魔猎手：查询卡牌显示名失败 cardId=$it" }
                "未知卡牌($it)"
            }
        }
    }

    private fun gameId(war: War): String =
        war.me.gameId.takeIf { it.isNotBlank() }
            ?: war.firstPlayerGameId.takeIf { it.isNotBlank() }
            ?: war.me.playerId.takeIf { it.isNotBlank() }
            ?: ""

    private fun gameKey(war: War): String {
        val id = gameId(war)
        if (id.isBlank() && war.startTime <= 0L) return fallbackGameKey
        return "${id.ifBlank { "unknown" }}-${war.startTime.takeIf { it > 0L } ?: "session"}"
    }

    private fun tracePath(gameKey: String): Path {
        val directory = Paths.get("log", "decision-trace")
        Files.createDirectories(directory)
        val safeKey = gameKey.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return directory.resolve("game-$safeKey.jsonl")
    }

    private fun cardRecord(card: Card): Map<String, Any?> = linkedMapOf(
        "displayName" to displayName(card),
        "cardId" to card.cardId,
        "entityId" to card.entityId,
        "entityName" to card.entityName,
        "cost" to card.cost,
        "attack" to card.atc,
        "health" to card.health,
        "type" to card.cardType.name,
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
