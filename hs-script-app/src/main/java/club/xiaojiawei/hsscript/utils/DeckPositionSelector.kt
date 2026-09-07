package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.bean.WorkTimeRule

/**
 * Resolves the deck slots used by the deck-selection screen.
 *
 * A time rule owns the deck slot for its enabled window.  The high-priority
 * switch controls whether the rule overrides the user's mode and strategy;
 * it must not make an enabled rule silently lose its deck-slot selection.
 */
object DeckPositionSelector {
    fun resolve(
        activeScheduleRule: WorkTimeRule?,
        globalDeckPositions: List<Int>,
    ): List<Int> = activeScheduleRule
        ?.deckPos
        ?.filter { it > 0 }
        ?.takeIf { it.isNotEmpty() }
        ?: globalDeckPositions.filter { it > 0 }
}
