package com.gsb.infra.domain

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Outcome of evaluating a single [Condition] against the [RiskInput] snapshot.
 */
@Serializable
data class ConditionOutcome(
    val metricKey: String,
    val operator: Operator,
    val threshold: Double,
    val actualValue: Double?,
    val matched: Boolean,
    val reasonCode: ReasonCode
)

/**
 * The full, auditable trace of one [Rule] version during an evaluation.
 *
 * Every candidate rule version produces a [RuleTrace] regardless of whether it
 * ultimately won, so the explanation chain can show not just "why closed" but
 * also "why this other version/rule did not override".
 *
 * Version selection fields:
 *  - [versionSelected]: this version is the selected representative of its
 *    [ruleId] chain at the evaluation instant (the highest active version).
 *  - [supersededByVersion]: when non-null, an older active version was displaced
 *    by a newer active version of the same [ruleId].
 *  - [conditionsMet]: always evaluated (even when superseded/window-missed) so the
 *    chain records what *would* have happened.
 *
 * @property selected true when this rule version's action contributed to the final
 *   result (it matched and was the strictest match in its layer).
 */
@Serializable
data class RuleTrace(
    val ruleId: String,
    val version: Int,
    val layer: RuleLayer,
    val action: Action,
    val reason: String,
    val scopeMatched: Boolean,
    val typeMatched: Boolean,
    val inEffectiveWindow: Boolean,
    val alreadyPublished: Boolean,
    val effectiveFrom: String,
    val expiresAt: String?,
    val publishedAt: String,
    val conditionOutcomes: List<ConditionOutcome>,
    val conditionsMet: Boolean,
    val versionSelected: Boolean,
    val supersededByVersion: Int? = null,
    val matched: Boolean,
    val selected: Boolean,
    val reasonCode: ReasonCode
)

/**
 * A summary of how conflict resolution proceeded across layers.
 */
@Serializable
data class LayerDecision(
    val layer: RuleLayer,
    val strictestAction: Action,
    val winningRuleId: String?,
    val winningVersion: Int?,
    val matchedRuleIds: List<String>
)

/**
 * The immutable result of evaluating a facility against a rule set at an instant.
 *
 * This is a pure value: identical inputs (facility, rules, snapshot, evaluatedAt)
 * always yield a byte-level identical result when serialized. Persistence and
 * notification only consume this object and never participate in the decision.
 */
@Serializable
data class EvaluationResult(
    val facilityId: String,
    val facilityType: FacilityType,
    val regionCode: String,
    val evaluatedAt: String,
    val finalAction: Action,
    val winningRuleId: String?,
    val winningVersion: Int?,
    val winningLayer: RuleLayer?,
    val reasonCode: ReasonCode,
    val ruleTraces: List<RuleTrace>,
    val layerDecisions: List<LayerDecision>,
    val inputSnapshot: Map<String, Double>,
    val contentHash: String,
    val explanation: String
) {
    companion object {
        const val NO_WINNING_RULE = ""
    }
}
