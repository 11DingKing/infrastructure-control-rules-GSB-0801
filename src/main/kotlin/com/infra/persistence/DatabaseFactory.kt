package com.infra.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseFactory {

    fun init(jdbcUrl: String) {
        val dbFile = jdbcUrl.removePrefix("jdbc:sqlite:")
        val dbDir = File(dbFile).parentFile
        if (dbDir != null && !dbDir.exists()) {
            dbDir.mkdirs()
        }

        val config = HikariConfig().apply {
            driverClassName = "org.sqlite.JDBC"
            this.jdbcUrl = jdbcUrl
            maximumPoolSize = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_SERIALIZABLE"
            validate()
        }
        val dataSource = HikariDataSource(config)
        Database.connect(dataSource)

        transaction {
            SchemaUtils.create(SchemaMigrationsTable)
        }

        MigrationRunner.runMigrations()
    }
}
