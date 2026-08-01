package com.infra.persistence

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

object MigrationRunner {

    private data class Migration(val version: Int, val description: String, val up: () -> Unit)

    private val migrations = listOf(
        Migration(1, "Create facilities table") {
            SchemaUtils.create(FacilitiesTable)
        },
        Migration(2, "Create rules table") {
            SchemaUtils.create(RulesTable)
        },
        Migration(3, "Create evaluation results table") {
            SchemaUtils.create(EvaluationResultsTable)
        }
    )

    fun runMigrations() {
        transaction {
            val appliedVersions = SchemaMigrationsTable.selectAll()
                .map { it[SchemaMigrationsTable.version] }
                .toSet()

            migrations
                .filter { it.version !in appliedVersions }
                .sortedBy { it.version }
                .forEach { migration ->
                    migration.up()
                    SchemaMigrationsTable.insert {
                        it[version] = migration.version
                        it[appliedAt] = Instant.now()
                    }
                }
        }
    }
}
