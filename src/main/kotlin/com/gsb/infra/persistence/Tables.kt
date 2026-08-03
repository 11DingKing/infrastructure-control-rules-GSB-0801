package com.gsb.infra.persistence

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object FacilitiesTable : Table("facilities") {
    val id = varchar("id", 64)
    val type = varchar("type", 32)
    val regionCode = varchar("region_code", 16)
    val name = varchar("name", 255)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

object RulesTable : IntIdTable("rules") {
    val ruleId = varchar("rule_id", 64)
    val version = integer("version")
    val layer = varchar("layer", 16)
    val facilityId = varchar("facility_id", 64).nullable()
    val regionCode = varchar("region_code", 16).nullable()
    val facilityTypes = text("facility_types")
    val conditions = text("conditions")
    val action = varchar("action", 16)
    val reason = text("reason")
    val effectiveFrom = timestamp("effective_from")
    val expiresAt = timestamp("expires_at").nullable()
    val publishedAt = timestamp("published_at")
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex("uq_rule_version", ruleId, version)
        index("idx_rules_scope", false, layer, facilityId, regionCode)
        index("idx_rules_published", false, publishedAt)
    }
}

object InputSnapshotsTable : IntIdTable("input_snapshots") {
    val facilityId = varchar("facility_id", 64)
    val snapshotJson = text("snapshot_json")
    val sha256 = char("sha256", 64)
    val createdAt = timestamp("created_at")

    init {
        index("idx_snapshot_hash", false, sha256)
    }
}

object EvaluationResultsTable : IntIdTable("evaluation_results") {
    val facilityId = varchar("facility_id", 64)
    val facilityType = varchar("facility_type", 32)
    val regionCode = varchar("region_code", 16)
    val evaluatedAt = timestamp("evaluated_at")
    val asOf = timestamp("as_of")
    val finalAction = varchar("final_action", 16)
    val winningRuleId = varchar("winning_rule_id", 64).nullable()
    val winningVersion = integer("winning_version").nullable()
    val winningLayer = varchar("winning_layer", 16).nullable()
    val reasonCode = varchar("reason_code", 48)
    val snapshotId = integer("snapshot_id")
    val resultJson = text("result_json")
    val createdAt = timestamp("created_at")

    init {
        index("idx_results_facility", false, facilityId)
        index("idx_results_evaluated", false, evaluatedAt)
    }
}
