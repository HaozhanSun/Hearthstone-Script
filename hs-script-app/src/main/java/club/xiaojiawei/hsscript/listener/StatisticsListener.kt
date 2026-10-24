package club.xiaojiawei.hsscript.listener

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.statistics.Record
import club.xiaojiawei.hsscript.statistics.RecordDaoEx
import club.xiaojiawei.hsscript.statistics.SurrenderClassifier
import club.xiaojiawei.hsscript.statistics.DailyGameSummaryEvent
import club.xiaojiawei.hsscript.statistics.DailySummaryService
import club.xiaojiawei.hsscript.statistics.DailySummaryStore
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscriptbase.config.log
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * @author 肖嘉威
 * @date 2025/3/14 1:04
 */
object StatisticsListener {

    val launch: Unit by lazy {
        DailySummaryService.bootstrap(
            RecordDaoEx.RECORD_DAO.findAll().mapNotNull { record ->
                val endTime = record.endTime ?: return@mapNotNull null
                DailyGameSummaryEvent(
                    recordId = record.id,
                    result = record.result == true,
                    surrendered = record.surrendered,
                    durationMinutes = durationMinutes(record.startTime, endTime),
                    experience = record.experience ?: 0,
                    completedAt = endTime.atZone(DailySummaryStore.PACIFIC_ZONE).toInstant(),
                )
            },
        )
        WarEx.warCountProperty.addListener { _, _, _: Number ->
            WarEx.war.run {
                val startDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(startTime), ZoneId.systemDefault())
                val endDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(endTime), ZoneId.systemDefault())
                val recordDao = RecordDaoEx.RECORD_DAO
                val deckStrategy = DeckStrategyManager.currentDeckStrategy ?: return@run
                val runModeEnum = currentRunMode ?: return@run
                val surrendered = SurrenderClassifier.classify(
                    concededPlayerId = conceded,
                    ourGameId = me.gameId,
                    opponentGameId = rival.gameId,
                    surrenderRequestedByUs = WarEx.surrenderRequested ||
                            club.xiaojiawei.hsscript.status.E2ETrace.surrenderRequested,
                )
                log.info {
                    "STATISTICS_RECORD game=${WarEx.currentCompletedGameNumber ?: "UNKNOWN"} " +
                            "conceded=${conceded.ifBlank { "<blank>" }} " +
                            "ourGameId=${me.gameId.ifBlank { "<blank>" }} " +
                            "opponentGameId=${rival.gameId.ifBlank { "<blank>" }} " +
                            "requestByUs=${WarEx.surrenderRequested} " +
                            "surrenderReason=${WarEx.surrenderReason ?: "<blank>"} " +
                            "label=${surrendered ?: "UNKNOWN"}"
                }
                val inserted = recordDao.insert(
                    Record(
                        gameNumber = WarEx.currentCompletedGameNumber,
                        strategyId = deckStrategy.id(),
                        strategyName = deckStrategy.name(),
                        runMode = runModeEnum,
                        // A local concession is a completed loss.  WarEx.isWin
                        // can still contain the previous game's value when a
                        // pre-mulligan surrender is recorded.
                        result = if (surrendered == true) false else WarEx.isWin,
                        surrendered = surrendered,
                        experience = WarEx.aEXP.toInt(),
                        startTime = startDateTime,
                        endTime = endDateTime,
                    )
                )
                DailySummaryService.record(
                    DailyGameSummaryEvent(
                        recordId = inserted.id,
                        result = inserted.result == true,
                        surrendered = inserted.surrendered,
                        durationMinutes = durationMinutes(inserted.startTime, inserted.endTime),
                        experience = inserted.experience ?: 0,
                        completedAt = if (endTime > 0L) {
                            Instant.ofEpochMilli(endTime)
                        } else {
                            Instant.now()
                        },
                    ),
                )
            }
        }
    }

    private fun durationMinutes(start: LocalDateTime?, end: LocalDateTime?): Int =
        if (start == null || end == null) 0 else Duration.between(start, end).toMinutes().toInt().coerceAtLeast(0)
}
