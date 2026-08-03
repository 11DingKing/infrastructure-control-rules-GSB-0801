package app.control.domain

import kotlinx.serialization.Serializable

enum class FacilityType { TUNNEL, FLOOD_ROAD, SCHOOL, TEMP_STRUCTURE }

/** 管控动作，strictness 用于同优先级冲突裁决（更严格者胜出）。 */
@Serializable
enum class Action(val strictness: Int) {
    MONITOR(1), RESTRICT(2), CLOSE(3)
}

/**
 * 规则层级，precedence 固定裁决顺序：人工强制 > 设施专用 > 区域 > 默认。
 */
@Serializable
enum class RuleTier(val precedence: Int) {
    DEFAULT(1), REGION(2), FACILITY(3), MANUAL(4)
}

@Serializable
data class Facility(
    val id: String,
    val type: FacilityType,
    val region: String,
    val name: String,
)

/** 规则条件：仅非空阈值参与判定；全部满足的规则才算命中。 */
@Serializable
data class RuleCondition(
    val precipitationMmAtLeast: Double? = null,
    val windLevelAtLeast: Int? = null,
    val waterDepthCmAtLeast: Double? = null,
) {
    fun isEmpty(): Boolean =
        precipitationMmAtLeast == null && windLevelAtLeast == null && waterDepthCmAtLeast == null
}

/**
 * 版本化规则。(ruleId, version) 唯一标识一条已发布规则；
 * 同 ruleId 构成一条版本链，求值时取 asOf 之前发布的最高版本。
 *
 * scopeKey 语义：DEFAULT -> "default"；REGION -> 区域编码（如 "440800"）；
 * FACILITY / MANUAL -> 设施 id。
 */
@Serializable
data class Rule(
    val ruleId: String,
    val version: Int,
    val tier: RuleTier,
    val scopeKey: String,
    val facilityType: FacilityType? = null,
    val condition: RuleCondition,
    val action: Action,
    /** 生效起点（含），epoch millis。 */
    val effectiveFrom: Long,
    /** 生效终点（不含），null 表示永久有效；now == effectiveTo 视为已过期。 */
    val effectiveTo: Long? = null,
    /** 发布时间，epoch millis；历史求值只能看到 publishedAt <= asOf 的版本。 */
    val publishedAt: Long,
) {
    fun ref(): RuleRef = RuleRef(ruleId = ruleId, version = version, tier = tier.name, scopeKey = scopeKey)
}

/**
 * 风险输入快照。字段可空：缺失的输入不参与判定，
 * 任何需要该指标的规则都会得到对应的 MISSING_* 原因码。
 */
@Serializable
data class RiskSnapshot(
    val facilityId: String,
    val observedAt: Long,
    val precipitationMm: Double? = null,
    val windLevel: Int? = null,
    val waterDepthCm: Double? = null,
)
