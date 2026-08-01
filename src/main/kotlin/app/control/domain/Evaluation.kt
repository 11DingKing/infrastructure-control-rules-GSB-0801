package app.control.domain

import kotlinx.serialization.Serializable

@Serializable
data class RuleRef(
    val ruleId: String,
    val version: Int,
    val tier: String,
    val scopeKey: String,
)

/** 结构化事实，保持构造顺序以保证序列化字节稳定。 */
@Serializable
data class Fact(val key: String, val value: String)

@Serializable
enum class Phase {
    VERSION_SELECTION, SCOPE_CHECK,

    /** 旧版输出使用；窗口判定现已并入 VERSION_SELECTION，仅为兼容历史持久化结果保留。 */
    WINDOW_CHECK,
    CONDITION_CHECK, CONFLICT_RESOLUTION, DECISION
}

/** 解释链中的一个确定性步骤。 */
@Serializable
data class ExplanationEntry(
    val phase: Phase,
    val rule: RuleRef? = null,
    val code: String,
    val facts: List<Fact> = emptyList(),
)

@Serializable
data class Decision(
    val action: Action,
    val tier: String,
    val rule: RuleRef,
)

/**
 * 求值结果：命中规则版本、输入快照、完整解释链全部内嵌，
 * 序列化后即为持久化与通知消费的唯一事实来源。
 */
@Serializable
data class EvaluationResult(
    val facilityId: String,
    val input: RiskSnapshot,
    /** 求值时刻（判定生效窗口用），epoch millis。 */
    val now: Long,
    /** 规则可见性截止（历史隔离用），epoch millis。 */
    val asOf: Long,
    val decision: Decision? = null,
    val reasonCodes: List<String>,
    val matchedRules: List<RuleRef>,
    val explanation: List<ExplanationEntry>,
)
