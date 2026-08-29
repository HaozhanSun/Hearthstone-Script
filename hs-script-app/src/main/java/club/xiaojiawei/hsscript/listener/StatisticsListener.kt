package club.xiaojiawei.hsscript.listener

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.statistics.Record
import club.xiaojiawei.hsscript.statistics.RecordDaoEx
import club.xiaojiawei.hsscript.statistics.SurrenderClassifier
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscriptbase.config.log
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
                val surrendered = SurrenderClassifier.classify(
                    concededPlayerId = conceded,
                    ourGameId = me.gameId,
                    opponentGameId = rival.gameId,
                    surrenderRequestedByUs = WarEx.surrenderRequested ||
                            club.xiaojiawei.hsscript.status.E2ETrace.surrenderRequested,
                )
                log.info {
                    "STATISTICS_SURRENDER_LABEL conceded=${conceded.ifBlank { "<blank>" }} " +
                            "ourGameId=${me.gameId.ifBlank { "<blank>" }} " +
                            "opponentGameId=${rival.gameId.ifBlank { "<blank>" }} " +
                            "requestByUs=${WarEx.surrenderRequested} label=${surrendered ?: "UNKNOWN"}"
                }
                recordDao.insert(
                    Record(
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
            }
        }
    }
}
