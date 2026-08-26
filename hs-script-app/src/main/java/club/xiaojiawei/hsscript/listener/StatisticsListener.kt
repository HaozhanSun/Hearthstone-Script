package club.xiaojiawei.hsscript.listener

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.statistics.Record
import club.xiaojiawei.hsscript.statistics.RecordDaoEx
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * @author 肖嘉威
 * @date 2025/3/14 1:04
 */
object StatisticsListener {

    val launch: Unit by lazy {
        WarEx.warCountProperty.addListener { _, _, _: Number ->
            WarEx.war.run {
                val startDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(startTime), ZoneId.systemDefault())
                val endDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(endTime), ZoneId.systemDefault())
                val recordDao = RecordDaoEx.RECORD_DAO
                val deckStrategy = DeckStrategyManager.currentDeckStrategy ?: return@run
                val runModeEnum = currentRunMode ?: return@run
                recordDao.insert(
                    Record(
                        strategyId = deckStrategy.id(),
                        strategyName = deckStrategy.name(),
                        runMode = runModeEnum,
                        result = WarEx.isWin,
                        // PLAYSTATE writes the conceding player's game id. Only count
                        // our own concession as a surrendered round; an opponent
                        // conceding is still a played win for us.
                        surrendered = WarEx.war.conceded.isNotBlank() &&
                                WarEx.war.conceded == WarEx.war.me.gameId,
                        experience = WarEx.aEXP.toInt(),
                        startTime = startDateTime,
                        endTime = endDateTime,
                    )
                )
            }
        }
    }
}
