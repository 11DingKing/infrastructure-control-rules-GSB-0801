package app.control.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection

/**
 * 轻量有序迁移：每一步只追加、不修改；schema_migrations 记录已应用的序号。
 */
object Migrations {

    private val steps: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS facilities (
            id VARCHAR(128) PRIMARY KEY,
            type VARCHAR(32) NOT NULL,
            region VARCHAR(64) NOT NULL,
            name VARCHAR(256) NOT NULL,
            created_at BIGINT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS rules (
            rule_id VARCHAR(128) NOT NULL,
            version INT NOT NULL,
            tier VARCHAR(16) NOT NULL,
            scope_key VARCHAR(128) NOT NULL,
            facility_type VARCHAR(32),
            precipitation_mm_at_least REAL,
            wind_level_at_least INT,
            water_depth_cm_at_least REAL,
            action VARCHAR(16) NOT NULL,
            effective_from BIGINT NOT NULL,
            effective_to BIGINT,
            published_at BIGINT NOT NULL,
            PRIMARY KEY (rule_id, version)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS risk_inputs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            facility_id VARCHAR(128) NOT NULL,
            observed_at BIGINT NOT NULL,
            precipitation_mm REAL,
            wind_level INT,
            water_depth_cm REAL,
            received_at BIGINT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS evaluations (
            id VARCHAR(64) PRIMARY KEY,
            facility_id VARCHAR(128) NOT NULL,
            input_id BIGINT NOT NULL,
            now BIGINT NOT NULL,
            as_of BIGINT NOT NULL,
            decision_action VARCHAR(16),
            decision_rule_id VARCHAR(128),
            decision_rule_version INT,
            reason_codes VARCHAR(512) NOT NULL,
            canonical_json TEXT NOT NULL,
            content_hash VARCHAR(64) NOT NULL,
            created_at BIGINT NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS evaluations_content_hash_index ON evaluations (content_hash)",
        "CREATE INDEX IF NOT EXISTS evaluations_facility_id_created_at_index ON evaluations (facility_id, created_at)",
        """
        CREATE TABLE IF NOT EXISTS batches (
            id VARCHAR(64) PRIMARY KEY,
            created_at BIGINT NOT NULL,
            item_count INT NOT NULL,
            duration_ms BIGINT NOT NULL,
            evaluation_ids TEXT NOT NULL,
            content_hashes TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS notifications (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            evaluation_id VARCHAR(64) NOT NULL,
            facility_id VARCHAR(128) NOT NULL,
            action VARCHAR(16),
            channel VARCHAR(64) NOT NULL,
            payload TEXT NOT NULL,
            sent_at BIGINT NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS notifications_facility_id_sent_at_index ON notifications (facility_id, sent_at)",
    )

    fun run() {
        transaction {
            exec(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    idx INT PRIMARY KEY,
                    applied_at BIGINT NOT NULL
                )
                """.trimIndent()
            )
            val applied = mutableSetOf<Int>()
            exec("SELECT idx FROM schema_migrations") { rs ->
                while (rs.next()) {
                    applied += rs.getInt(1)
                }
            }
            steps.forEachIndexed { index, sql ->
                if (index !in applied) {
                    exec(sql)
                    exec("INSERT INTO schema_migrations (idx, applied_at) VALUES ($index, ${System.currentTimeMillis()})")
                }
            }
        }
    }
}

fun connectDatabase(dbFile: String): Database {
    val file = java.io.File(dbFile)
    file.parentFile?.mkdirs()
    val db = Database.connect(
        url = "jdbc:sqlite:${file.absolutePath}",
        driver = "org.sqlite.JDBC",
        setupConnection = { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout = 10000")
            }
        },
    )
    // 约束冲突需要在事务内立刻抛出，保证并发发布只有一个赢家。
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
    return db
}
