package club.xiaojiawei.hsscript.starter

import club.xiaojiawei.hsscript.enums.GameStartupModeEnum

/**
 * Rotate startup mechanisms after an unaccepted dispatch. A launcher command
 * or queued window message is only an attempt; GameStarter still requires an
 * observed Hearthstone process/window before it accepts the handoff.
 */
internal object GameStartupModeSequencePolicy {
    data class Selection(
        val mode: GameStartupModeEnum,
        val configuredMode: GameStartupModeEnum,
        val attempt: Int,
        val fallbackApplied: Boolean,
    )

    fun select(
        configuredModes: List<GameStartupModeEnum>,
        attemptIndex: Int,
        launcherWindowAvailable: Boolean,
    ): Selection {
        val configured = configuredModes.distinct().ifEmpty { listOf(GameStartupModeEnum.PLATFORM_ARG) }
        // A one-item message configuration otherwise repeats a window click
        // forever. Add the non-interactive launcher command as its bounded
        // fallback without changing the user's preferred first mechanism.
        val sequence = if (configured == listOf(GameStartupModeEnum.PLATFORM_MESSAGE)) {
            listOf(GameStartupModeEnum.PLATFORM_MESSAGE, GameStartupModeEnum.PLATFORM_ARG)
        } else {
            configured
        }
        val index = Math.floorMod(attemptIndex.coerceAtLeast(0), sequence.size)
        val configuredMode = sequence[index]
        val selectedMode = if (
            configuredMode == GameStartupModeEnum.PLATFORM_MESSAGE && !launcherWindowAvailable
        ) {
            GameStartupModeEnum.PLATFORM_ARG
        } else {
            configuredMode
        }
        return Selection(
            mode = selectedMode,
            configuredMode = configuredMode,
            attempt = attemptIndex.coerceAtLeast(0) + 1,
            fallbackApplied = selectedMode != configuredMode || configuredMode !in configuredModes,
        )
    }
}
