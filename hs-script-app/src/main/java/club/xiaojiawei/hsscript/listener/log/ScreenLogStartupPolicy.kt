package club.xiaojiawei.hsscript.listener.log

/**
 * Controls whether an old LoadingScreen.log state may be used at startup.
 *
 * Historical scene restoration is intentionally opt-in.  The last line in
 * an append-only Hearthstone log is not a live UI observation and restoring it
 * by default can send gameplay clicks to the deck picker or another screen.
 */
object ScreenLogStartupPolicy {

    private const val RESTORE_HISTORY_PROPERTY = "hs.script.screenlog.restore-history"

    fun shouldRestoreHistoricalState(
        propertyValue: String? = System.getProperty(RESTORE_HISTORY_PROPERTY),
    ): Boolean = propertyValue.equals("true", ignoreCase = true)
}
