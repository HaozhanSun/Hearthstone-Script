package club.xiaojiawei.hsscript.starter

import club.xiaojiawei.hsscript.enums.GameStartupModeEnum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameStartupModeSequencePolicyTest {
    @Test
    fun `unconfirmed message mode rotates back to launcher argument instead of repeating last mode`() {
        val configured = listOf(GameStartupModeEnum.PLATFORM_ARG, GameStartupModeEnum.PLATFORM_MESSAGE)

        val selected = (0..3).map {
            GameStartupModeSequencePolicy.select(configured, it, launcherWindowAvailable = true).mode
        }

        assertEquals(
            listOf(
                GameStartupModeEnum.PLATFORM_ARG,
                GameStartupModeEnum.PLATFORM_MESSAGE,
                GameStartupModeEnum.PLATFORM_ARG,
                GameStartupModeEnum.PLATFORM_MESSAGE,
            ),
            selected,
        )
    }

    @Test
    fun `single message preference gets an argument fallback after its first attempt`() {
        val configured = listOf(GameStartupModeEnum.PLATFORM_MESSAGE)

        assertEquals(
            GameStartupModeEnum.PLATFORM_MESSAGE,
            GameStartupModeSequencePolicy.select(configured, 0, launcherWindowAvailable = true).mode,
        )
        val fallback = GameStartupModeSequencePolicy.select(configured, 1, launcherWindowAvailable = true)
        assertEquals(GameStartupModeEnum.PLATFORM_ARG, fallback.mode)
        assertTrue(fallback.fallbackApplied)
    }

    @Test
    fun `message mode without a launcher window degrades to argument dispatch`() {
        val selected = GameStartupModeSequencePolicy.select(
            listOf(GameStartupModeEnum.PLATFORM_MESSAGE),
            attemptIndex = 0,
            launcherWindowAvailable = false,
        )

        assertEquals(GameStartupModeEnum.PLATFORM_ARG, selected.mode)
        assertTrue(selected.fallbackApplied)
    }

    @Test
    fun `empty and duplicate configuration is bounded and deterministic`() {
        assertEquals(
            GameStartupModeEnum.PLATFORM_ARG,
            GameStartupModeSequencePolicy.select(emptyList(), 0, launcherWindowAvailable = false).mode,
        )
        val duplicated = GameStartupModeSequencePolicy.select(
            listOf(GameStartupModeEnum.PLATFORM_ARG, GameStartupModeEnum.PLATFORM_ARG),
            attemptIndex = 17,
            launcherWindowAvailable = false,
        )
        assertEquals(GameStartupModeEnum.PLATFORM_ARG, duplicated.mode)
        assertFalse(duplicated.fallbackApplied)
    }
}
