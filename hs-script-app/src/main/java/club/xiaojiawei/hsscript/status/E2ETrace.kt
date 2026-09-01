package club.xiaojiawei.hsscript.status

import java.io.File
import java.io.RandomAccessFile
import club.xiaojiawei.hsscriptbase.config.log

/**
 * Process-local milestones used only by the automated E2E runner.
 *
 * A game-result line alone is not proof that the current process controlled
 * the game: a watchdog restart can observe the tail of an already-running
 * game. These flags make the result marker require the normal-game path.
 */
object E2ETrace {
    private val enabled = System.getProperty("hs.script.e2e") == "true"
    private val stateFile: File? = if (enabled) {
        File("log", "e2e-milestones-${System.getProperty("hs.script.e2e.run-id", "unknown")}.state")
            .also { it.parentFile?.mkdirs() }
    } else {
        null
    }

    @Volatile
    var mulliganCompleted: Boolean = false

    @Volatile
    var ourTurnSeen: Boolean = false

    @Volatile
    var outCardStarted: Boolean = false

    /** Set only when this JVM requests surrender for the current live game. */
    @Volatile
    var surrenderRequested: Boolean = false

    @Volatile
    var resultRecorded: Boolean = false

    @Volatile
    var winRecorded: Boolean = false

    /** Monotonic CREATE_GAME boundary within this JVM/run lineage. */
    @Volatile
    var gameSequence: Long = 0

    init {
        restore()
    }

    private fun persist() {
        if (!enabled) return
        runCatching {
            stateFile?.writeText(
                buildString {
                    appendLine("game-sequence=$gameSequence")
                    if (mulliganCompleted) appendLine("mulligan")
                    if (ourTurnSeen) appendLine("our-turn")
                    if (outCardStarted) appendLine("out-card")
                    if (winRecorded) appendLine("win")
                }
            )
        }
    }

    private fun restore() {
        if (!enabled) return
        runCatching {
            // A state file is useful as a forensic breadcrumb, but it is not
            // proof that a newly-started JVM controls the current game.
            // E2E readiness binds the process to a fresh CREATE_GAME; discard
            // state from an older process before that boundary.
            stateFile?.takeIf { it.isFile }?.let { staleState ->
                log.warn {
                    "E2E_TRACE_STALE_STATE_DISCARDED path=${staleState.path} " +
                        "reason=new-jvm-requires-fresh-create-game"
                }
                staleState.delete()
            }
        }
    }

    fun markMulliganCompleted() {
        if (gameSequence == 0L) return
        mulliganCompleted = true
        persist()
        logMilestone("mulligan")
    }

    fun markOurTurnSeen() {
        if (gameSequence == 0L) return
        ourTurnSeen = true
        persist()
        logMilestone("our-turn")
    }

    fun markOutCardStarted() {
        if (gameSequence == 0L) return
        outCardStarted = true
        persist()
        logMilestone("out-card")
    }

    fun markSurrenderRequested() {
        if (gameSequence == 0L) return
        surrenderRequested = true
        logMilestone("surrender-requested")
    }

    /** Start a new E2E game lineage at the authoritative CREATE_GAME line. */
    @Synchronized
    fun beginNewGame(source: String = "CREATE_GAME") {
        gameSequence += 1
        resetFlagsForCurrentGame()
        runCatching { stateFile?.delete() }
        persist()
        log.info {
            "E2E_GAME_BOUNDARY sequence=$gameSequence source=$source " +
                "milestones-cleared=true"
        }
    }

    /** Clear current-game evidence without advancing the CREATE_GAME lineage. */
    @Synchronized
    fun resetForNewGame() {
        resetFlagsForCurrentGame()
        runCatching { stateFile?.delete() }
    }

    private fun resetFlagsForCurrentGame() {
        mulliganCompleted = false
        ourTurnSeen = false
        outCardStarted = false
        surrenderRequested = false
        resultRecorded = false
        winRecorded = false
    }

    /**
     * Records a game result for E2E. A win is always terminal. When the
     * runner explicitly requires a win, a loss is logged but does not let the
     * watchdog declare success or stop the process.
     */
    fun recordResult(gameWon: Boolean): Boolean {
        if (gameWon) {
            winRecorded = true
            resultRecorded = true
            persist()
            return true
        }
        resultRecorded = System.getProperty("hs.script.e2e.win-required") != "true"
        return resultRecorded
    }

    fun isValidScriptControlledGame(): Boolean =
        gameSequence > 0L && mulliganCompleted && ourTurnSeen && outCardStarted

    fun milestoneSummary(): String =
        "game=$gameSequence mulligan=$mulliganCompleted " +
            "ourTurn=$ourTurnSeen outCard=$outCardStarted " +
            "surrender=$surrenderRequested"

    private fun logMilestone(name: String) {
        if (enabled) {
            log.info {
                "E2E_MILESTONE game=$gameSequence milestone=$name accepted=true " +
                    milestoneSummary()
            }
        }
    }

    /**
     * Read the authoritative PLAYSTATE from the tail of Power.log.  The
     * phase listener can enter GAME_OVER before the final PLAYSTATE tag has
     * reached the in-memory model, especially after a watchdog replay.
     */
    fun readPowerLogResult(
        logPath: String?,
        playerGameId: String,
        fallbackPlayerGameId: String? = null,
    ): Boolean? {
        // This parser is also used by the normal result handler.  E2E mode
        // controls milestone persistence, not whether Power.log is the
        // authoritative source of the terminal result.
        val playerIdentity = playerGameId.ifBlank { fallbackPlayerGameId?.trim().orEmpty() }
        if (logPath.isNullOrBlank() || playerIdentity.isBlank()) return null
        return runCatching {
            RandomAccessFile(logPath, "r").use { file ->
                val start = (file.length() - 128 * 1024L).coerceAtLeast(0L)
                file.seek(start)
                val remaining = (file.length() - start).coerceAtMost(128 * 1024L).toInt()
                val bytes = ByteArray(remaining)
                file.readFully(bytes)
                val text = bytes.toString(Charsets.UTF_8)
                val playerWon = "Entity=$playerIdentity tag=PLAYSTATE value=WON"
                val playerLost = "Entity=$playerIdentity tag=PLAYSTATE value=LOST"
                val playerConceded = "Entity=$playerIdentity tag=PLAYSTATE value=CONCEDED"
                // The tail can contain several games.  Comparing contains()
                // in a fixed order lets an older WON marker override a newer
                // CONCEDED/LOST marker, which mislabeled surrender results.
                val latestResult = listOf(
                    "WON" to text.lastIndexOf(playerWon),
                    "LOST" to text.lastIndexOf(playerLost),
                    "CONCEDED" to text.lastIndexOf(playerConceded),
                ).filter { it.second >= 0 }.maxByOrNull { it.second }?.first
                when (latestResult) {
                    "WON" -> true
                    "LOST", "CONCEDED" -> false
                    else -> null
                }
            }
        }.getOrNull()
    }
}
