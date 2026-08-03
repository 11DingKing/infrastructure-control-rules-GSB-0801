package com.gsb.infra.persistence

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseFactory {

    @Volatile
    private var initialized = false

    fun init(jdbcUrl: String = defaultJdbcUrl()) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            Database.connect(
                url = jdbcUrl,
                driver = "org.sqlite.JDBC"
            )
            transaction {
                SchemaUtils.create(
                    FacilitiesTable,
                    RulesTable,
                    InputSnapshotsTable,
                    EvaluationResultsTable
                )
            }
            initialized = true
        }
    }

    /**
     * Resets the initialised flag. Intended for tests that need to point the
     * factory at a fresh database; not used in production code.
     */
    fun resetForTesting() {
        synchronized(this) {
            initialized = false
        }
    }

    fun defaultJdbcUrl(): String {
        val dataDir = File("data")
        if (!dataDir.exists()) dataDir.mkdirs()
        val dbFile = File(dataDir, "control.db")
        return "jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on"
    }
}
