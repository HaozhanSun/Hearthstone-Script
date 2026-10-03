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
    private val playStates = linkedMapOf<String, String>()

    @Synchronized
    fun observeLine(line: String, liveAttachedSession: Boolean = true) {
        // Existing-log replay rebuilds the parser model, but old/replayed
        // terminal tails must never mint cleanup authority. The caller resets
        // this tracker for every attached/rotated Power.log; only subsequent
        // live lines from that attachment may open and complete a game.
        if (!liveAttachedSession) return
        if (line.contains("CREATE_GAME")) {
            reset()
            gameStarted = true
            currentGameGeneration = gameGeneration.incrementAndGet()
            return
        }
        if (!gameStarted) return
        PLAYSTATE.find(line)?.let { match ->
            val entity = match.groupValues[1].trim()
            val state = match.groupValues[2]
            if (entity.isNotBlank()) playStates[entity] = state
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
        opponentEntityId: String,
    ): CurrentGameSurrenderTerminalEvidence? {
        val generation = currentGameGeneration ?: return null
        if (!gameStarted || ownEntityId.isBlank() || opponentEntityId.isBlank()) return null
        return CurrentGameSurrenderTerminalEvidence(
            gameIdentity = "$generation:$ownEntityId",
            ownEntityId = ownEntityId,
            opponentEntityId = opponentEntityId,
            ownPlayState = playStates[ownEntityId],
            opponentPlayState = playStates[opponentEntityId],
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
        playStates.clear()
    }

    private companion object {
        val PLAYSTATE = Regex("Entity=(.*?)\\s+tag=PLAYSTATE value=(WON|LOST|CONCEDED)\\b")
        val TERMINAL_PLAYSTATES = setOf("WON", "LOST", "CONCEDED")
    }
}
