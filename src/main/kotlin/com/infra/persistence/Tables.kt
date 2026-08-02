package com.infra.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object FacilitiesTable : Table("facilities") {
    val id = varchar("id", 128)
    val name = varchar("name", 256)
    val type = varchar("type", 64)
    val regionCode = varchar("region_code", 32)
    val location = varchar("location", 512).nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object RulesTable : Table("rules") {
    val id = varchar("id", 128)
    val layer = varchar("layer", 32)
    val facilityId = varchar("facility_id", 128).nullable()
    val regionCode = varchar("region_code", 32).nullable()
    val facilityType = varchar("facility_type", 64).nullable()
    val conditionJson = text("condition_json")
    val action = varchar("action", 32)
    val version = integer("version")
    val publishedAt = long("published_at")
    val validFrom = long("valid_from")
    val validTo = long("valid_to").nullable()
    val description = text("description")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id, version)

    init {
        uniqueIndex("uq_rules_id_version", id, version)
        index("idx_rules_layer", false, layer)
        index("idx_rules_facility", false, facilityId)
        index("idx_rules_region", false, regionCode)
        index("idx_rules_published", false, publishedAt)
    }
}

object EvaluationResultsTable : Table("evaluation_results") {
    val id = long("id").autoIncrement()
    val requestId = varchar("request_id", 128).uniqueIndex()
    val facilityId = varchar("facility_id", 128).index()
    val finalAction = varchar("final_action", 32).nullable()
    val reasonCode = varchar("reason_code", 64)
    val hitRuleId = varchar("hit_rule_id", 128).nullable()
    val hitRuleVersion = integer("hit_rule_version").nullable()
    val hitRuleLayer = varchar("hit_rule_layer", 32).nullable()
    val inputSnapshotJson = text("input_snapshot_json")
    val explanationChainJson = text("explanation_chain_json")
    val consideredRuleIdsJson = text("considered_rule_ids_json")
    val resultHash = varchar("result_hash", 128).index()
    val evaluatedAt = long("evaluated_at").index()
    val asOf = long("as_of")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

object SchemaMigrationsTable : Table("schema_migrations") {
    val version = integer("version")
    val appliedAt = timestamp("applied_at")
    override val primaryKey = PrimaryKey(version)
}
