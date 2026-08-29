package club.xiaojiawei.hsscriptcardsdk.config

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.util.isFalse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * @author 肖嘉威
 * @date 2024/11/13 15:55
 */
object DBConfig {

    val CARD_DB: JdbcTemplate

    const val CARD_DB_NAME = "hs_cards.db"

    var cardDBPath: Path
        private set

    init {
        val rootPath = System.getProperty("user.dir")

        val cardDataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.sqlite.JDBC")
            var dbPath = Path.of(rootPath, CARD_DB_NAME)
            dbPath.exists().isFalse {
                dbPath = Path.of(rootPath).parent.resolve("hs-script-app").resolve(CARD_DB_NAME)
            }
            dbPath.exists().isFalse {
                dbPath = Path.of(rootPath).parent.resolve(CARD_DB_NAME)
            }
            dbPath.exists().isFalse {
                dbPath = Path.of(rootPath, CARD_DB_NAME)
                log.error { "不存在默认的卡牌数据库" }
            }
            cardDBPath = dbPath
            url = "jdbc:sqlite:${dbPath}"
        }
        CARD_DB = JdbcTemplate(cardDataSource)
    }

}