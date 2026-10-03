package club.xiaojiawei.hsscript.status.surrender

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
            return
        }
        if (!gameStarted) return
        if (TERMINAL_PLAYSTATE.containsMatchIn(line)) terminalPlayStateObserved = true
        if (line.contains("tag=STEP value=FINAL_GAMEOVER")) finalGameOverObserved = true
        if (line.contains("tag=STATE value=COMPLETE")) completeStateObserved = true
    }

    fun hasCompleteTerminalEvidence(): Boolean =
        gameStarted && terminalPlayStateObserved && finalGameOverObserved && completeStateObserved

    @Synchronized
    fun reset() {
        gameStarted = false
        terminalPlayStateObserved = false
        finalGameOverObserved = false
        completeStateObserved = false
    }

    private companion object {
        val TERMINAL_PLAYSTATE = Regex("tag=PLAYSTATE value=(WON|LOST|CONCEDED)\\b")
    }
}
