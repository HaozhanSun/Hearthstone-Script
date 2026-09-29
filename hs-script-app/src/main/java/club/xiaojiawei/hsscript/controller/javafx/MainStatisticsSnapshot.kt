package club.xiaojiawei.hsscript.controller.javafx

import java.util.Locale

/** Shared display values for the compact Control and Log pane telemetry. */
data class MainStatisticsSnapshot(
    val gameCount: String,
    val winningPercentage: String,
    val gameTime: String,
    val experience: String,
) {
    companion object {
        fun from(
            gameCount: Int,
            winCount: Int,
            hangingTimeMinutes: Int,
            experience: Int,
        ): MainStatisticsSnapshot = MainStatisticsSnapshot(
            gameCount = gameCount.toString(),
            winningPercentage = if (gameCount > 0) {
                String.format(Locale.ROOT, "%.1f%%", winCount.toDouble() / gameCount * 100.0)
            } else {
                "?"
            },
            gameTime = formatTime(hangingTimeMinutes),
            experience = experience.toString(),
        )

        private fun formatTime(time: Int): String {
            if (time == 0) return "0"
            if (time < 60) return "${time}m"
            if (time < 1440) {
                return if (time % 60 == 0) "${time / 60}h" else "${time / 60}h${time % 60}m"
            }
            return if (time % 1440 == 0) {
                "${time / 1440}d"
            } else {
                "${time / 1440}d${time % 1440 / 60}h"
            }
        }
    }
}
