package com.gsb.control.persistence

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the SQLite connection and schema lifecycle.
 *
 * Migration strategy: a lightweight meta table records the applied schema
 * version; migrations are forward-only and idempotent. For the current schema
 * (version 1) migration creates the base tables via Exposed. Tracking the
 * version explicitly keeps the migration auditable rather than implicit.
 */
class Db private constructor(
    private val database: Database,
    // Held open for the lifetime of an in-memory database. A shared-cache
    // memory DB is destroyed the moment its last connection closes; since
    // Exposed opens and closes a connection per transaction, we keep one
    // parked connection alive so the schema survives between transactions.
    @Suppress("unused") private val keepAlive: Connection?,
) {

    private val writeLock = ReentrantLock()

    fun <T> tx(block: () -> T): T = transaction(database) { block() }

    /**
     * A serialized write transaction. SQLite permits only one writer at a time;
     * under shared-cache in-memory mode lock contention surfaces as transient
     * errors rather than honoring busy_timeout. Serializing writes at the JVM
     * boundary is faithful to SQLite's single-writer model and lets the UNIQUE
     * (rule_key, version) index deterministically pick the one winner among
     * concurrent same-version publishes.
     */
    fun <T> writeTx(block: () -> T): T = writeLock.withLock { transaction(database) { block() } }

    companion object {
        const val SCHEMA_VERSION = 1

        /**
         * Connect to SQLite at [path] (use ":memory:" for an isolated in-memory
         * database) and run migrations. SQLite is single-writer, so concurrent
         * publishes are serialized deterministically at the storage layer.
         */
        fun connect(path: String): Db {
            val inMemory = path == ":memory:"
            val url = if (inMemory) {
                "jdbc:sqlite:file:memdb_${System.nanoTime()}?mode=memory&cache=shared"
            } else {
                File(path).absoluteFile.parentFile?.mkdirs()
                "jdbc:sqlite:$path"
            }
            // Ensure the driver is registered before we open the keep-alive.
            Class.forName("org.sqlite.JDBC")
            val keepAlive: Connection? = if (inMemory) DriverManager.getConnection(url) else null
            val database = Database.connect(
                url = url,
                driver = "org.sqlite.JDBC",
                setupConnection = { conn ->
                    conn.createStatement().use { st ->
                        st.execute("PRAGMA foreign_keys = ON")
                        // SQLite is single-writer. Make concurrent writers wait
                        // for the lock (up to 5s) rather than fail immediately,
                        // so contention serializes into a proper UNIQUE
                        // violation instead of a transient LOCKED error.
                        st.execute("PRAGMA busy_timeout = 5000")
                    }
                },
            )
            migrate(database)
            return Db(database, keepAlive)
        }

        private fun migrate(database: Database) {
            transaction(database) {
                SchemaUtils.create(SchemaMetaTable)
                val current = SchemaMetaTable.currentVersion()
                if (current < SCHEMA_VERSION) {
                    SchemaUtils.createMissingTablesAndColumns(*AllTables)
                    SchemaMetaTable.setVersion(SCHEMA_VERSION)
                }
            }
        }
    }
}
