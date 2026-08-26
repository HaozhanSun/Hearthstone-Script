package club.xiaojiawei.hsscript.statistics

import club.xiaojiawei.hsscript.consts.DATA_DIR
import club.xiaojiawei.hsscript.consts.STATISTICS_DB_NAME
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * @author 肖嘉威
 * @date 2025/3/14 0:40
 */
object RecordDaoEx {

    val RECORD_DAO: RecordDao by lazy {
        RecordDao(DATA_DIR.resolve(STATISTICS_DB_NAME).toString())
    }

    fun queryRecord(startDate: LocalDate, endDate: LocalDate): List<Record> {
        return queryRecord(startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay())
    }

    fun queryRecord(startDateTime: LocalDateTime, endDateTimeExclusive: LocalDateTime): List<Record> {
        return RECORD_DAO.queryByDateRange(startDateTime, endDateTimeExclusive)
    }

    fun queryMonthlyRecordsByStrategy(strategyId: String?, month: YearMonth = YearMonth.now()): List<Record> {
        if (strategyId.isNullOrBlank()) return emptyList()
        val startDateTime = month.atDay(1).atStartOfDay()
        val endDateTime = month.plusMonths(1).atDay(1).atStartOfDay()
        return RECORD_DAO.query(
            Record(
                strategyId = strategyId,
                startTime = startDateTime,
                endTime = endDateTime,
            )
        )
    }
}
