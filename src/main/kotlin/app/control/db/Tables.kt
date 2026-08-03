package app.control.db

import org.jetbrains.exposed.sql.Table

object Facilities : Table("facilities") {
    val id = varchar("id", 128)
    val type = varchar("type", 32)
    val region = varchar("region", 64)
    val name = varchar("name", 256)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * 版本化规则。主键 (rule_id, version) 是并发重复发布的最终防线：
 * 同一版本并发插入时 SQLite 唯一约束保证只有一个赢家。
 */
object Rules : Table("rules") {
    val ruleId = varchar("rule_id", 128)
    val version = integer("version")
    val tier = varchar("tier", 16)
    val scopeKey = varchar("scope_key", 128)
    val facilityType = varchar("facility_type", 32).nullable()
    val precipitationMmAtLeast = double("precipitation_mm_at_least").nullable()
    val windLevelAtLeast = integer("wind_level_at_least").nullable()
    val waterDepthCmAtLeast = double("water_depth_cm_at_least").nullable()
    val action = varchar("action", 16)
    val effectiveFrom = long("effective_from")
    val effectiveTo = long("effective_to").nullable()
    val publishedAt = long("published_at")
    override val primaryKey = PrimaryKey(ruleId, version)
}

object RiskInputs : Table("risk_inputs") {
    val id = long("id").autoIncrement()
    val facilityId = varchar("facility_id", 128)
    val observedAt = long("observed_at")
    val precipitationMm = double("precipitation_mm").nullable()
    val windLevel = integer("wind_level").nullable()
    val waterDepthCm = double("water_depth_cm").nullable()
    val receivedAt = long("received_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * 求值结果：canonical_json 内嵌命中规则版本、输入快照与完整解释链；
 * content_hash 是其 SHA-256 指纹，用于复现与对账。
 */
object Evaluations : Table("evaluations") {
    val id = varchar("id", 64)
    val facilityId = varchar("facility_id", 128)
    val inputId = long("input_id")
    val now = long("now")
    val asOf = long("as_of")
    val decisionAction = varchar("decision_action", 16).nullable()
    val decisionRuleId = varchar("decision_rule_id", 128).nullable()
    val decisionRuleVersion = integer("decision_rule_version").nullable()
    val reasonCodes = varchar("reason_codes", 512)
    val canonicalJson = text("canonical_json")
    val contentHash = varchar("content_hash", 64)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, contentHash)
        index(false, facilityId, createdAt)
    }
}

/** 批量求值基准记录：per-item 内容指纹可复现，时长仅作参考。 */
object Batches : Table("batches") {
    val id = varchar("id", 64)
    val createdAt = long("created_at")
    val itemCount = integer("item_count")
    val durationMs = long("duration_ms")
    val evaluationIds = text("evaluation_ids")
    val contentHashes = text("content_hashes")
    override val primaryKey = PrimaryKey(id)
}

/** 通知只消费求值结果，不参与判定。 */
object Notifications : Table("notifications") {
    val id = long("id").autoIncrement()
    val evaluationId = varchar("evaluation_id", 64)
    val facilityId = varchar("facility_id", 128)
    val action = varchar("action", 16).nullable()
    val channel = varchar("channel", 64)
    val payload = text("payload")
    val sentAt = long("sent_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, facilityId, sentAt)
    }
}
