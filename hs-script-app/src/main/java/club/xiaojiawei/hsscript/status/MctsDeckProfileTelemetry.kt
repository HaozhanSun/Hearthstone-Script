package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.Deck
import club.xiaojiawei.hsscript.bean.DeckDecoder
import club.xiaojiawei.hsscript.listener.log.DeckLogListener
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbasestrategy.strategy.PirateDemonHunterMctsExperimentModel
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsReplayTrace
import club.xiaojiawei.hsscriptcardsdk.util.CardDBUtil
import club.xiaojiawei.hsscriptstrategysdk.deck.MCTSDeckStrategy
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Records the complete deck actually reported by Hearthstone's Decks.log for
 * every Pirate DH MCTS game.  The selected strategy's deckCode is empty by
 * design, so the client log is the authoritative source for a user-edited
 * list.  This is intentionally independent of the hand parser: a card that
 * has not been drawn can still be detected as a new/untuned card.
 */
object MctsDeckProfileTelemetry {
    const val DEFAULT_DIRECTORY = "log/mcts-deck-profile"

    private val recordedProfiles = ConcurrentHashMap.newKeySet<String>()

    data class DeckCardRecord(
        val dbfId: Int,
        val cardId: String,
        val name: String,
        val count: Int,
        val text: String,
        val manuallyTuned: Boolean,
    )

    /** Pure classifier kept public so it can be tested without a live client. */
    fun classifyUnknownCardIds(cardIds: Collection<String>): List<String> =
        cardIds.filter { !PirateDemonHunterMctsExperimentModel.isKnownTunedCardId(it) }.distinct()

    /**
     * Observe the selected deck once per game. Returns true when a profile was
     * recorded, false when the deck log is not ready or this game was already
     * recorded.
     */
    fun observe(war: War, strategy: MCTSDeckStrategy): Boolean {
        val strategyName = strategy.name()
        if (!isPirateMctsStrategy(strategyName)) return false

        val deck = currentPirateDeck() ?: run {
            log.info { "MCTS_DECK_PROFILE_DEFERRED reason=Decks.log-not-ready strategy=$strategyName" }
            return false
        }
        if (deck.code.isBlank()) {
            log.warn { "MCTS_DECK_PROFILE_DEFERRED reason=selected-deck-has-no-code name=${deck.name} id=${deck.id}" }
            return false
        }

        val deckInfo = runCatching { DeckDecoder().decode(deck.code) }.getOrElse { error ->
            log.warn(error) { "MCTS_DECK_PROFILE_DECODE_FAILED name=${deck.name} id=${deck.id}" }
            return false
        }
        val dbCards = CardDBUtil.queryCardsByDbfIds(deckInfo.cards.map { it.dbfId })
        val cards = deckInfo.cards.map { entry ->
            val dbCard = dbCards[entry.dbfId]
            val cardId = dbCard?.cardId.orEmpty()
            DeckCardRecord(
                dbfId = entry.dbfId,
                cardId = cardId.ifBlank { "DBF_${entry.dbfId}" },
                name = dbCard?.name.orEmpty().ifBlank { "未知卡牌(${entry.dbfId})" },
                count = entry.count,
                text = dbCard?.text.orEmpty(),
                manuallyTuned = PirateDemonHunterMctsExperimentModel.isKnownTunedCardId(cardId),
            )
        }
        val unknown = cards.filterNot { it.manuallyTuned }
        val currentCardIds = cards.map { it.cardId }.toSet()
        val knownTunedCardsAbsent = PirateDemonHunterMctsExperimentModel.knownTunedCardIds
            .filterNot { knownId -> currentCardIds.any { currentId ->
                PirateDemonHunterMctsExperimentModel.isKnownTunedCardId(currentId) &&
                    (currentId == knownId || currentId.startsWith(knownId) ||
                        currentId.removePrefix("CORE_") == knownId.removePrefix("CORE_"))
            } }
            .sorted()
        val gameKey = "${war.me.gameId}:${war.startTime}:${deck.id}:${deck.code}"
        if (!recordedProfiles.add(gameKey)) return false

        val root = File(System.getProperty("hs.script.mcts-deck-profile.dir", DEFAULT_DIRECTORY))
        val details = linkedMapOf<String, Any?>(
            "strategy" to strategyName,
            "deckName" to deck.name,
            "deckId" to deck.id,
            "deckCode" to deck.code,
            "format" to deckInfo.format.name,
            "heroes" to deckInfo.heroes,
            "cardCount" to cards.sumOf { it.count },
            "uniqueCardCount" to cards.size,
            "knownTunedCardCount" to cards.count { it.manuallyTuned },
            "unknownOrUntunedCardCount" to unknown.size,
            "knownTunedCardsAbsent" to knownTunedCardsAbsent,
            "cards" to cards.map { card ->
                linkedMapOf<String, Any?>(
                    "dbfId" to card.dbfId,
                    "cardId" to card.cardId,
                    "name" to card.name,
                    "count" to card.count,
                    "text" to card.text,
                    "manualTuning" to if (card.manuallyTuned) "KNOWN" else "UNSET",
                    "priorityStatus" to if (card.manuallyTuned) "KNOWN" else "UNSET",
                    "playStyleStatus" to if (card.manuallyTuned) "KNOWN" else "UNSET",
                )
            },
        )
        MctsReplayTrace.record(
            war = war,
            event = "deck_profile",
            reason = "authoritative current deck snapshot decoded from Hearthstone Decks.log",
            details = details,
            rootDirectory = root,
        )

        unknown.forEach { card ->
            MctsReplayTrace.record(
                war = war,
                event = "new_deck_card",
                reason = "card is present in the current deck but has no hand-reviewed Pirate DH MCTS tuning rule",
                details = mapOf(
                    "strategy" to strategyName,
                    "deckName" to deck.name,
                    "deckId" to deck.id,
                    "dbfId" to card.dbfId,
                    "cardId" to card.cardId,
                    "name" to card.name,
                    "count" to card.count,
                    "text" to card.text,
                    "manualTuning" to "UNSET",
                    "priorityStatus" to "UNSET",
                    "playStyleStatus" to "UNSET",
                    "actionPolicy" to "generic-parser-or-opaque-fallback-until-reviewed",
                ),
                rootDirectory = root,
            )
        }

        val cardSummary = cards.joinToString(" | ") { "${it.cardId}:${it.name}x${it.count}" }
        log.info {
            "MCTS_DECK_PROFILE_RECORDED strategy=$strategyName deck=${deck.name} " +
                "id=${deck.id} cards=${cards.sumOf { it.count }} unique=${cards.size} " +
                "unknownOrUntuned=${unknown.size} directory=${root.absolutePath} " +
                "cards=$cardSummary"
        }
        if (unknown.isNotEmpty()) {
            val summary = unknown.joinToString("、") { "${it.name}/${it.cardId}" }
            log.warn {
                "MCTS_NEW_DECK_CARD count=${unknown.size} manualTuning=UNSET " +
                    "priorityStatus=UNSET playStyleStatus=UNSET cards=$summary " +
                    "profileDirectory=${root.absolutePath}"
            }
            // This uses the existing Script notification path. It is forced
            // because silently missing a newly inserted card is more harmful
            // than the one-time notification for this deck/game fingerprint.
            SystemUtil.notice(
                "海盗瞎 MCTS 检测到新卡/未人工调优卡：$summary。" +
                    " 已记录完整卡组和卡牌文本，请先审阅优先级与打出风格。",
                title = "MCTS 卡组变更",
                forceNotify = true,
            )
        }
        return true
    }

    private fun isPirateMctsStrategy(strategyName: String): Boolean =
        strategyName.contains("海盗瞎") && strategyName.contains("MCTS", ignoreCase = true)

    private fun currentPirateDeck(): Deck? {
        val decks = synchronized(DeckLogListener.DECKS) { DeckLogListener.DECKS.toList() }
        return selectCurrentDeck(DeckLogListener.selectedGameDeck(), decks)
    }

    /** Prefer the per-match selection over the cached deck-list response. */
    internal fun selectCurrentDeck(selectedGameDeck: Deck?, decks: List<Deck>): Deck? {
        return selectedGameDeck?.takeIf { it.code.isNotBlank() }
            ?: decks.firstOrNull { it.name.contains("海盗瞎") || it.name.contains("海盗") }
            ?: decks.firstOrNull { it.code.isNotBlank() }
    }
}
