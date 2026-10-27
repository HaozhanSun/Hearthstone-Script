package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.const.BuildChannel

/** Allows one Beta-only result reconciliation when a complete current-game Power.log tail outlives the phase model. */
internal object BetaTerminalResultReconciliationPolicy {
    fun shouldReconcile(
        channel: String,
        phase: String,
        activeGame: Boolean,
        completeCurrentGameTerminal: Boolean,
        resultAlreadyHandled: Boolean,
    ): Boolean = BuildChannel.identityToken(channel) == "beta" &&
        phase == "FILL_DECK" &&
        !activeGame &&
        completeCurrentGameTerminal &&
        !resultAlreadyHandled
}
