package club.xiaojiawei.hsscriptcardsdk.config

import club.xiaojiawei.hsscriptbase.config.log
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * @author 肖嘉威
 * @date 2024/11/13 15:55
 */
internal object CardDatabaseLocator {
    fun candidates(rootPath: Path): List<Path> = listOf(
        rootPath.resolve(DBConfig.CARD_DB_NAME),
        rootPath.parent.resolve("hs-script-app").resolve(DBConfig.CARD_DB_NAME),
        rootPath.parent.resolve(DBConfig.CARD_DB_NAME),
    ).distinct()

    /** A SQLite file is usable only when the schema required by CardDBUtil exists. */
    fun isUsable(path: Path): Boolean = runCatching {
        if (!path.isRegularFile() || Files.size(path) == 0L) return false
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select 1 from sqlite_master where type = 'table' and name = 'cards' limit 1",
                ).use { resultSet -> resultSet.next() }
            }
        }
    }.getOrDefault(false)

    fun requireUsable(rootPath: Path): Path {
        val paths = candidates(rootPath)
        paths.firstOrNull(::isUsable)?.let { return it }
        val details = paths.joinToString(",") { path ->
            val state = when {
                !path.exists() -> "missing"
                !path.isRegularFile() -> "not-file"
                else -> "size=${runCatching { Files.size(path) }.getOrDefault(-1L)}"
            }
            "$path:$state"
        }
        val message = "Usable card database with cards table not found; candidates=$details"
        log.error { message }
        throw IllegalStateException(message)
    }
}

object DBConfig {

    val CARD_DB: JdbcTemplate

    const val CARD_DB_NAME = "hs_cards.db"

    var cardDBPath: Path
        private set

    init {
        val rootPath = System.getProperty("user.dir")

        val cardDataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.sqlite.JDBC")
            val dbPath = CardDatabaseLocator.requireUsable(Path.of(rootPath))
            cardDBPath = dbPath
            url = "jdbc:sqlite:${dbPath}"
        }
        CARD_DB = JdbcTemplate(cardDataSource)
    }
}
