package com.gsb.control.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * Tracks the applied schema version so migrations are explicit and forward-only.
 * A single row (id = 1) holds the current version.
 */
object SchemaMetaTable : Table("schema_meta") {
    val id = integer("id")
    val version = integer("version")
    val appliedAt = timestamp("applied_at")
    override val primaryKey = PrimaryKey(id)

    /** Current applied version, or 0 if the schema has never been migrated. */
    fun currentVersion(): Int =
        selectAll().where { id eq 1 }.firstOrNull()?.get(version) ?: 0

    fun setVersion(v: Int) {
        val exists = selectAll().where { id eq 1 }.firstOrNull() != null
        if (exists) {
            update({ id eq 1 }) {
                it[version] = v
                it[appliedAt] = Instant.now()
            }
        } else {
            insert {
                it[id] = 1
                it[version] = v
                it[appliedAt] = Instant.now()
            }
        }
    }
}
