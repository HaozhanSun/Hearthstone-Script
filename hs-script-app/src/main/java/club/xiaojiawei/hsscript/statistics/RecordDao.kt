package club.xiaojiawei.hsscript.statistics

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscript.consts.ZONE_OFFSET
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.GeneratedKeyHolder
import java.io.File
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant
import java.time.LocalDateTime

/**
 * @author 肖嘉威
 * @date 2025/3/14 0:17
 */

/**
 * Record实体类，对应records表
 */
data class Record(
    val id: Int? = null,
    val strategyId: String? = null,
    val strategyName: String? = null,
    val runMode: RunModeEnum? = null,
    val result: Boolean? = null,
    /** True when our player explicitly conceded; null means legacy data is unknown. */
    val surrendered: Boolean? = null,
    val experience: Int? = null,
    val startTime: LocalDateTime? = null,
    val endTime: LocalDateTime? = null,
)

/**
 * records表的数据访问对象 - 仅使用JdbcTemplate
 */
class RecordDao(
    dbPath: String,
) {
    private val jdbcTemplate: JdbcTemplate

    init {
        val dbFile = File(dbPath)
        if (!dbFile.exists()) {
            dbFile.parentFile.mkdirs()
        }
        val dataSource = DriverManagerDataSource()
        dataSource.setDriverClassName("org.sqlite.JDBC")
        dataSource.url = "jdbc:sqlite:$dbPath"
        jdbcTemplate = JdbcTemplate(dataSource)
        init()
    }

    companion object {
        private const val TABLE_NAME = "records"

        private const val SQL_CREATE = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                strategy_id TEXT NOT NULL,
                strategy_name TEXT NOT NULL,
                run_mode TEXT NOT NULL,
                result INTEGER NOT NULL,
                surrendered INTEGER,
                experience INTEGER NOT NULL,
                start_time INTEGER  NOT NULL,
                end_time INTEGER  NOT NULL
            )
        """

        private const val SQL_INSERT = """
            INSERT INTO $TABLE_NAME (
                strategy_id, strategy_name, run_mode, result,
                surrendered, experience, start_time, end_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """

        private const val SQL_UPDATE = """
            UPDATE $TABLE_NAME SET
                strategy_id = ?,
                strategy_name = ?,
                run_mode = ?,
                result = ?,
                surrendered = ?,
                experience = ?,
                start_time = ?,
                end_time = ?
            WHERE id = ?
        """

        private const val SQL_DELETE = "DELETE FROM $TABLE_NAME WHERE id = ?"
        private const val SQL_FIND_BY_ID = "SELECT * FROM $TABLE_NAME WHERE id = ?"
        private const val SQL_FIND_ALL = "SELECT * FROM $TABLE_NAME"
    }

    private fun init() {
        jdbcTemplate.execute(SQL_CREATE)
        // Older installations predate the surrender flag. Keep those records usable,
        // but leave the new field null so the UI can distinguish unknown history.
        val hasSurrenderColumn = jdbcTemplate.queryForList("PRAGMA table_info($TABLE_NAME)")
            .any { it["name"]?.toString() == "surrendered" }
        if (!hasSurrenderColumn) {
            jdbcTemplate.execute("ALTER TABLE $TABLE_NAME ADD COLUMN surrendered INTEGER")
        }
    }

    private val recordMapper =
        RowMapper { rs: ResultSet, _: Int ->
            Record(
                id = rs.getInt("id"),
                strategyId = rs.getString("strategy_id"),
                strategyName = rs.getString("strategy_name"),
                runMode = RunModeEnum.fromString(rs.getString("run_mode")),
                result = rs.getBoolean("result"),
                surrendered = rs.getObject("surrendered")?.let { rs.getBoolean("surrendered") },
                experience = rs.getInt("experience"),
                startTime = Instant.ofEpochSecond(rs.getLong("start_time")).atZone(ZONE_OFFSET).toLocalDateTime(),
                endTime = Instant.ofEpochSecond(rs.getLong("end_time")).atZone(ZONE_OFFSET).toLocalDateTime(),
            )
        }

    fun insert(record: Record): Record {
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection: Connection ->
            val ps = connection.prepareStatement(SQL_INSERT, Statement.RETURN_GENERATED_KEYS)
            ps.setString(1, record.strategyId)
            ps.setString(2, record.strategyName)
            ps.setString(3, record.runMode?.name)
            ps.setBoolean(4, record.result ?: false)
            ps.setObject(5, record.surrendered?.let { if (it) 1 else 0 })
            ps.setInt(6, record.experience ?: 0)
            ps.setLong(7, record.startTime?.toEpochSecond(ZONE_OFFSET) ?: 0)
            ps.setLong(8, record.endTime?.toEpochSecond(ZONE_OFFSET) ?: 0)
            ps
        }, keyHolder)
        val id = keyHolder.key?.toInt() ?: throw RuntimeException("获取生成的ID失败")
        return record.copy(id = id)
    }

    fun update(record: Record): Int =
        jdbcTemplate.update(
            SQL_UPDATE,
            record.strategyId,
            record.strategyName,
            record.runMode?.name,
            record.result,
            record.surrendered?.let { if (it) 1 else 0 },
            record.experience,
            record.startTime?.toEpochSecond(ZONE_OFFSET) ?: 0,
            record.endTime?.toEpochSecond(ZONE_OFFSET) ?: 0,
            record.id,
        )

    fun deleteById(id: Int): Int = jdbcTemplate.update(SQL_DELETE, id)

    fun findById(id: Int): Record? =
        try {
            jdbcTemplate.queryForObject(SQL_FIND_BY_ID, recordMapper, id)
        } catch (e: Exception) {
            null
        }

    fun findAll(): List<Record> = jdbcTemplate.query(SQL_FIND_ALL, recordMapper)

    fun query(record: Record? = null): List<Record> {
        val conditions = ArrayList<String>()
        val params = ArrayList<Any>()

        record?.let {
            record.id?.let {
                conditions.add("id = ?")
                params.add(it)
            }
            record.strategyId?.let {
                conditions.add("strategy_id = ?")
                params.add(it)
            }
            record.strategyName?.let {
                conditions.add("strategy_name = ?")
                params.add(it)
            }
            record.runMode?.let {
                conditions.add("run_mode = ?")
                params.add(it)
            }
            record.result?.let {
                conditions.add("result = ?")
                params.add(it)
            }
            record.experience?.let {
                conditions.add("experience = ?")
                params.add(it)
            }
            record.startTime?.let { startTime ->
                val startTimestamp = startTime.atZone(ZONE_OFFSET).toEpochSecond()
                conditions.add("end_time >= ?")
                params.add(startTimestamp)
            }
            record.endTime?.let { endTime ->
                val endTimestamp = endTime.atZone(ZONE_OFFSET).toEpochSecond()
                conditions.add("end_time < ?")
                params.add(endTimestamp)
            }
        }

        var sql = SQL_FIND_ALL
        if (conditions.isNotEmpty()) sql += " WHERE " + conditions.joinToString(" AND ")
        return jdbcTemplate.query(sql, recordMapper, *params.toArray())
    }

    fun queryByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
    ): List<Record> {
        val sql = "$SQL_FIND_ALL WHERE end_time >= ? AND end_time < ?"
        return jdbcTemplate.query(
            sql,
            recordMapper,
            startDate.atZone(ZONE_OFFSET).toEpochSecond(),
            endDate.atZone(ZONE_OFFSET).toEpochSecond(),
        )
    }

    fun findByStrategyAndResult(
        strategyId: Int,
        result: String,
    ): List<Record> {
        val sql = "$SQL_FIND_ALL WHERE strategy_id = ? AND result = ?"
        return jdbcTemplate.query(sql, recordMapper, strategyId, result)
    }
}
