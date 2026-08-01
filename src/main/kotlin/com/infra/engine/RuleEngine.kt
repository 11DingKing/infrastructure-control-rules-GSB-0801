package com.infra.engine

import com.infra.domain.Action
import com.infra.domain.ConditionMatch
import com.infra.domain.EvaluationResult
import com.infra.domain.ExplanationEntry
import com.infra.domain.Facility
import com.infra.domain.ReasonCode
import com.infra.domain.RiskInput
import com.infra.domain.Rule
import com.infra.domain.RuleLayer

object RuleEngine {

    data class EvaluationContext(
        val facility: Facility?,
        val rules: List<Rule>,
        val input: RiskInput,
        val evaluationTime: Long
    )

    fun evaluate(ctx: EvaluationContext): EvaluationResult {
        val facility = ctx.facility
        if (facility == null) {
            return buildResult(
                input = ctx.input,
                evaluatedAt = ctx.evaluationTime,
                finalAction = null,
                reasonCode = ReasonCode.MISSING_FACILITY,
                hitRule = null,
                explanationChain = emptyList(),
                consideredRuleIds = emptyList()
            )
        }

        val applicableRules = ctx.rules
            .filter { rule ->
                rule.isApplicableTo(facility) &&
                    rule.publishedAt <= ctx.evaluationTime
            }
            .sortedWith(
                compareByDescending<Rule> { it.layer.priority }
                    .thenByDescending { it.version }
                    .thenBy { it.id }
            )

        if (applicableRules.isEmpty()) {
            return buildResult(
                input = ctx.input,
                evaluatedAt = ctx.evaluationTime,
                finalAction = null,
                reasonCode = ReasonCode.MISSING_RULES,
                hitRule = null,
                explanationChain = emptyList(),
                consideredRuleIds = emptyList()
            )
        }

        val explanationChain = mutableListOf<ExplanationEntry>()
        val matchedByLayer = linkedMapOf<RuleLayer, Rule>()

        for (rule in applicableRules) {
            val active = rule.isActiveAt(ctx.evaluationTime)
            val applicable = rule.isApplicableTo(facility)
            val conditionMatch = rule.condition.matches(ctx.input)

            val matched = active && applicable && conditionMatch is ConditionMatch.Matched

            val reasons = when (conditionMatch) {
                is ConditionMatch.Matched -> emptyList()
                is ConditionMatch.NotMatched -> conditionMatch.reasons
            }.toMutableList()

            if (!active) {
                reasons.add("rule not active at evaluation time (validFrom=${rule.validFrom}, validTo=${rule.validTo}, publishedAt=${rule.publishedAt})")
            }
            if (!applicable) {
                reasons.add("rule not applicable to facility")
            }

            explanationChain.add(
                ExplanationEntry(
                    ruleId = rule.id,
                    layer = rule.layer,
                    version = rule.version,
                    action = rule.action,
                    matched = matched,
                    activeAtEvaluationTime = active,
                    applicableToFacility = applicable,
                    reasons = reasons,
                    description = rule.description
                )
            )

            if (matched) {
                val existing = matchedByLayer[rule.layer]
                if (existing == null || rule.action.severity > existing.action.severity) {
                    matchedByLayer[rule.layer] = rule
                }
            }
        }

        val layersInPriority = RuleLayer.entries.sortedByDescending { it.priority }
        var hitRule: Rule? = null
        for (layer in layersInPriority) {
            val candidate = matchedByLayer[layer]
            if (candidate != null) {
                hitRule = candidate
                break
            }
        }

        val reasonCode = if (hitRule != null) {
            ReasonCode.OK
        } else {
            ReasonCode.NO_RULES_MATCHED
        }

        return buildResult(
            input = ctx.input,
            evaluatedAt = ctx.evaluationTime,
            finalAction = hitRule?.action,
            reasonCode = reasonCode,
            hitRule = hitRule,
            explanationChain = explanationChain,
            consideredRuleIds = applicableRules.map { it.id }
        )
    }

    fun evaluateBatch(
        facilities: List<Facility>,
        rules: List<Rule>,
        inputs: List<RiskInput>,
        evaluationTime: Long
    ): List<EvaluationResult> {
        return inputs.map { input ->
            val facility = facilities.firstOrNull { it.id == input.facilityId }
            evaluate(
                EvaluationContext(
                    facility = facility,
                    rules = rules,
                    input = input,
                    evaluationTime = evaluationTime
                )
            )
        }.sortedWith(compareBy({ it.facilityId }, { it.requestId }))
    }

    private fun buildResult(
        input: RiskInput,
        evaluatedAt: Long,
        finalAction: Action?,
        reasonCode: ReasonCode,
        hitRule: Rule?,
        explanationChain: List<ExplanationEntry>,
        consideredRuleIds: List<String>
    ): EvaluationResult {
        val hash = EvaluationResult.computeHash(
            requestId = input.requestId,
            facilityId = input.facilityId,
            finalAction = finalAction,
            reasonCode = reasonCode,
            hitRuleId = hitRule?.id,
            hitRuleVersion = hitRule?.version,
            hitRuleLayer = hitRule?.layer,
            inputSnapshot = input,
            evaluatedAt = evaluatedAt,
            consideredRuleIds = consideredRuleIds
        )

        return EvaluationResult(
            requestId = input.requestId,
            facilityId = input.facilityId,
            finalAction = finalAction,
            reasonCode = reasonCode,
            hitRuleId = hitRule?.id,
            hitRuleVersion = hitRule?.version,
            hitRuleLayer = hitRule?.layer,
            inputSnapshot = input,
            evaluatedAt = evaluatedAt,
            explanationChain = explanationChain,
            consideredRuleIds = consideredRuleIds,
            resultHash = hash
        )
    }
}
