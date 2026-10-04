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
    /**
     * Account identity explicitly correlated with WAR's local player in an
     * earlier game from this same Power.log session. PlayerID is game-local;
     * GameAccountId is the stable bridge across CREATE_GAME boundaries.
     */
    private var trustedLocalGameAccountId: String? = null
    private val playerAccountIds = linkedMapOf<String, MutableSet<String>>()
    private val playerNames = linkedMapOf<String, MutableSet<String>>()
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
            resetCurrentGame()
            gameStarted = true
            currentGameGeneration = gameGeneration.incrementAndGet()
            currentCreateGameTimestamp = timestamp
            return
        }
        if (!gameStarted) return
        PLAYER_ACCOUNT.find(line)?.let { match ->
            val playerId = match.groupValues[1]
            val accountId = "${match.groupValues[2]}:${match.groupValues[3]}"
            playerAccountIds.getOrPut(playerId) { linkedSetOf() }.add(accountId)
        }
        PLAYER_NAME.find(line)?.let { match ->
            val playerId = match.groupValues[1]
            val name = match.groupValues[2].trim()
            if (name.isNotBlank()) playerNames.getOrPut(playerId) { linkedSetOf() }.add(name)
        }
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
    fun currentGameIdentity(ownEntityId: String): String? {
        val resolvedOwnEntityId = resolveOwnEntityId(ownEntityId) ?: return null
        return currentGameGeneration
            ?.takeIf { gameStarted }
            ?.let { "$it:$resolvedOwnEntityId" }
    }

    @Synchronized
    fun currentGameSurrenderEvidence(
        ownEntityId: String,
        opponentEntityId: String?,
    ): CurrentGameSurrenderTerminalEvidence? {
        val generation = currentGameGeneration ?: return null
        val resolvedOwnEntityId = resolveOwnEntityId(ownEntityId) ?: return null
        if (!gameStarted) return null
        val resolvedOpponentEntityId = opponentEntityId?.takeIf { it.isNotBlank() }
        return CurrentGameSurrenderTerminalEvidence(
            gameIdentity = "$generation:$resolvedOwnEntityId",
            ownEntityId = resolvedOwnEntityId,
            opponentEntityId = resolvedOpponentEntityId,
            ownPlayState = playStates[resolvedOwnEntityId],
            opponentPlayState = resolvedOpponentEntityId?.let(playStates::get),
            ownConceded = resolvedOwnEntityId in concededPlayers,
            finalGameOver = finalGameOverObserved,
            complete = completeStateObserved,
        )
    }

    private fun resolveOwnEntityId(ownEntityId: String): String? {
        val suppliedOwnId = ownEntityId.takeIf { it.isNotBlank() }
        val accountBoundOwnId = resolveTrustedLocalEntityId()
        if (suppliedOwnId != null) {
            // A live WAR mapping is authoritative, but it must not contradict
            // an account binding already established for this Power.log
            // session. Contradiction means ownership is ambiguous: fail closed.
            if (accountBoundOwnId != null && accountBoundOwnId != suppliedOwnId) return null
            rememberTrustedLocalAccount(suppliedOwnId)
            return suppliedOwnId
        }
        return accountBoundOwnId
    }

    private fun rememberTrustedLocalAccount(ownEntityId: String) {
        val playerIds = playerNames
            .filterValues { names -> ownEntityId in names }
            .keys
        if (playerIds.size != 1) return
        val accountIds = playerAccountIds[playerIds.single()].orEmpty()
        if (accountIds.size != 1) return
        val candidate = accountIds.single()
        if (trustedLocalGameAccountId == null || trustedLocalGameAccountId == candidate) {
            trustedLocalGameAccountId = candidate
        }
    }

    private fun resolveTrustedLocalEntityId(): String? {
        val trustedAccountId = trustedLocalGameAccountId ?: return null
        val playerIds = playerAccountIds
            .filterValues { accountIds -> trustedAccountId in accountIds }
            .keys
        if (playerIds.size != 1) return null
        val names = playerNames[playerIds.single()].orEmpty()
            .filter(::isUsablePlayerName)
            .distinct()
        return names.singleOrNull()
    }

    private fun isUsablePlayerName(name: String): Boolean =
        name.isNotBlank() &&
            !name.contains("UNKNOWN", ignoreCase = true) &&
            !name.contains("HUMAN PLAYER", ignoreCase = true)

    @Synchronized
    fun reset() {
        resetCurrentGame()
        trustedLocalGameAccountId = null
    }

    private fun resetCurrentGame() {
        gameStarted = false
        terminalPlayStateObserved = false
        finalGameOverObserved = false
        completeStateObserved = false
        currentGameGeneration = null
        currentCreateGameTimestamp = null
        playerAccountIds.clear()
        playerNames.clear()
        playStates.clear()
        concededPlayers.clear()
    }

    private companion object {
        val CREATE_GAME_TIMESTAMP = Regex("^D\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d+)")
        val PLAYER_ACCOUNT = Regex("Player EntityID=\\d+ PlayerID=(\\d+) GameAccountId=\\[hi=(\\d+) lo=(\\d+)\\]")
        val PLAYER_NAME = Regex("PlayerID=(\\d+), PlayerName=(.+?)\\s*$")
        val PLAYSTATE = Regex("Entity=(.*?)\\s+tag=PLAYSTATE value=(WON|LOST|CONCEDED)\\b")
        val TERMINAL_PLAYSTATES = setOf("WON", "LOST", "CONCEDED")
    }
}
