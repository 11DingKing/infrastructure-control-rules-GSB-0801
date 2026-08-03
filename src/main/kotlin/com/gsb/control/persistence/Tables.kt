package com.gsb.control.persistence

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** Governed facilities. */
object FacilitiesTable : Table("facilities") {
    val id = varchar("id", 64)
    val kind = varchar("kind", 32)
    val regionCode = varchar("region_code", 32)
    val name = varchar("name", 256)
    override val primaryKey = PrimaryKey(id)
}

/**
 * Versioned rules. A logical rule is (rule_key); a concrete revision is
 * (rule_key, version). The unique index enforces that the same version of a
 * logical rule can be published at most once — this is the persistence-level
 * guard exercised by the concurrent same-version-publish test.
 */
object RulesTable : IntIdTable("rules") {
    val ruleKey = varchar("rule_key", 128)
    val version = integer("version")
    val scopeKind = varchar("scope_kind", 16)
    val scopeArg = varchar("scope_arg", 64).nullable()
    val action = varchar("action", 16)
    val conditionJson = text("condition_json")
    val validFrom = timestamp("valid_from")
    val validUntil = timestamp("valid_until").nullable()
    val publishedAt = timestamp("published_at")
    val description = text("description")

    init {
        uniqueIndex("uq_rule_key_version", ruleKey, version)
    }
}

/** Persisted evaluation results — consumers only; never re-judged. */
object EvaluationResultsTable : IntIdTable("evaluation_results") {
    val facilityId = varchar("facility_id", 64)
    val decision = varchar("decision", 16).nullable()
    val decidingLayer = varchar("deciding_layer", 16).nullable()
    val decidingVersionRef = varchar("deciding_version_ref", 160).nullable()
    val inputSnapshotJson = text("input_snapshot_json")
    val evaluatedAt = timestamp("evaluated_at")
    val asOf = timestamp("as_of")
    val firedVersionRefs = text("fired_version_refs") // JSON array of strings
    val explanationJson = text("explanation_json")    // JSON array of trace lines
    val canonicalDigest = varchar("canonical_digest", 64)
    val contentHash = varchar("content_hash", 64)     // sha256 over canonical JSON (schema v2)
    val createdAt = timestamp("created_at")
}

/** Ordered list of all tables for schema creation / migration. */
val AllTables: Array<Table> = arrayOf(FacilitiesTable, RulesTable, EvaluationResultsTable)
