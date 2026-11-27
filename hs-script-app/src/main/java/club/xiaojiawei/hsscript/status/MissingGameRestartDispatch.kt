package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.const.BuildChannel

/**
 * Testable boundary for the Beta missing-client restart. The caller supplies
 * the already-authorized runtime checks and the serialized restart primitive;
 * generation rearming is deliberately driven only by the restart completion
 * callback, never by an intent or permit log.
 */
internal object MissingGameRestartDispatch {
    enum class Result { BLOCKED, RESTART_REQUESTED }

    fun dispatch(
        channel: String?,
        stillAuthorized: () -> Boolean,
        gameAlive: () -> Boolean,
        requestRestart: (afterClientTerminated: () -> Unit) -> Boolean,
        onRestartCompleted: () -> Unit,
    ): Result {
        if (BuildChannel.identityToken(channel) != "beta") return Result.BLOCKED
        if (runCatching { !stillAuthorized() }.getOrDefault(true) ||
            runCatching { gameAlive() }.getOrDefault(true)
        ) {
            return Result.BLOCKED
        }
        val accepted = runCatching {
            requestRestart { onRestartCompleted() }
        }.getOrDefault(false)
        return if (accepted) Result.RESTART_REQUESTED else Result.BLOCKED
    }
}
