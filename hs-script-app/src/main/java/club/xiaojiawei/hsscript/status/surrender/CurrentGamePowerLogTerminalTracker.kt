package club.xiaojiawei.hsscript.status.surrender

import java.util.concurrent.atomic.AtomicLong

/** Tracks terminal PLAYSTATE evidence only within the latest Power.log game. */
internal class CurrentGamePowerLogTerminalTracker {
    @Volatile
    private var gameStarted = false
    @Volatile
    private var terminalPlayStateObserved = false
    @Volatile
    private var finalGameOverObserved = false
    @Volatile
    private var completeStateObserved = false
    private val gameGeneration = AtomicLong(0L)
    @Volatile
    private var currentGameGeneration: Long? = null
    @Volatile
    private var currentCreateGameTimestamp: String? = null
    private val playStates = linkedMapOf<String, String>()
    private val concededPlayers = linkedSetOf<String>()

    @Synchronized
    fun observeLine(line: String, currentSessionEvidence: Boolean = true) {
        // Historical replay is not terminal authority. The one exception is a
        // replay that starts at the latest unfinished CREATE_GAME in the
        // already-verified current-session Power.log: that segment is the
        // current live match, and its replayed prefix must seed ownership so
        // later live terminal lines can be correlated to the same game.
        if (!currentSessionEvidence) return
        if (line.contains("CREATE_GAME")) {
            val timestamp = CREATE_GAME_TIMESTAMP.find(line)?.groupValues?.get(1)
            // Hearthstone writes CREATE_GAME once for GameState and again in
            // the nested PowerTaskList dump with the same timestamp. Treat
            // those as one game boundary, not two generations.
            if (gameStarted && timestamp != null && timestamp == currentCreateGameTimestamp) return
            reset()
            gameStarted = true
            currentGameGeneration = gameGeneration.incrementAndGet()
            currentCreateGameTimestamp = timestamp
            return
        }
        if (!gameStarted) return
        PLAYSTATE.find(line)?.let { match ->
            val entity = match.groupValues[1].trim()
            val state = match.groupValues[2]
            if (entity.isNotBlank()) {
                playStates[entity] = state
                if (state == "CONCEDED") concededPlayers += entity
            }
            if (state in TERMINAL_PLAYSTATES) terminalPlayStateObserved = true
        }
        if (line.contains("tag=STEP value=FINAL_GAMEOVER")) finalGameOverObserved = true
        if (line.contains("tag=STATE value=COMPLETE")) completeStateObserved = true
    }

    fun hasCompleteTerminalEvidence(): Boolean =
        gameStarted && terminalPlayStateObserved && finalGameOverObserved && completeStateObserved

    @Synchronized
    fun currentGameIdentity(ownEntityId: String): String? = currentGameGeneration
        ?.takeIf { gameStarted && ownEntityId.isNotBlank() }
        ?.let { "$it:$ownEntityId" }

    @Synchronized
    fun currentGameSurrenderEvidence(
        ownEntityId: String,
        opponentEntityId: String?,
    ): CurrentGameSurrenderTerminalEvidence? {
        val generation = currentGameGeneration ?: return null
        if (!gameStarted || ownEntityId.isBlank()) return null
        val resolvedOpponentEntityId = opponentEntityId?.takeIf { it.isNotBlank() }
        return CurrentGameSurrenderTerminalEvidence(
            gameIdentity = "$generation:$ownEntityId",
            ownEntityId = ownEntityId,
            opponentEntityId = resolvedOpponentEntityId,
            ownPlayState = playStates[ownEntityId],
            opponentPlayState = resolvedOpponentEntityId?.let(playStates::get),
            ownConceded = ownEntityId in concededPlayers,
            finalGameOver = finalGameOverObserved,
            complete = completeStateObserved,
        )
    }

    @Synchronized
    fun reset() {
        gameStarted = false
        terminalPlayStateObserved = false
        finalGameOverObserved = false
        completeStateObserved = false
        currentGameGeneration = null
        currentCreateGameTimestamp = null
        playStates.clear()
        concededPlayers.clear()
    }

    private companion object {
        val CREATE_GAME_TIMESTAMP = Regex("^D\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d+)")
        val PLAYSTATE = Regex("Entity=(.*?)\\s+tag=PLAYSTATE value=(WON|LOST|CONCEDED)\\b")
        val TERMINAL_PLAYSTATES = setOf("WON", "LOST", "CONCEDED")
    }
}
