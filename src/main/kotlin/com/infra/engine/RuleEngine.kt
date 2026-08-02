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

    private data class RuleChain(
        val ruleId: String,
        val layer: RuleLayer,
        val versions: List<Rule>
    )

    private data class VersionSelection(
        val selected: Rule?,
        val entries: List<ExplanationEntry>
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

        val visibleRules = ctx.rules
            .filter { rule ->
                rule.isApplicableTo(facility) &&
                    rule.publishedAt <= ctx.evaluationTime
            }

        if (visibleRules.isEmpty()) {
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

        val chains = visibleRules
            .groupBy { it.id }
            .map { (ruleId, versions) ->
                RuleChain(
                    ruleId = ruleId,
                    layer = versions.first().layer,
                    versions = versions.sortedByDescending { it.version }
                )
            }
            .sortedWith(
                compareByDescending<RuleChain> { it.layer.priority }
                    .thenBy { it.ruleId }
            )

        val explanationChain = mutableListOf<ExplanationEntry>()
        val matchedByLayer = linkedMapOf<RuleLayer, Rule>()
        val consideredRuleIds = sortedSetOf<String>()

        for (chain in chains) {
            consideredRuleIds.add(chain.ruleId)
            val selection = selectVersionAndExplain(chain, facility, ctx.input, ctx.evaluationTime)
            explanationChain.addAll(selection.entries)

            val selected = selection.selected
            if (selected != null) {
                val conditionMatch = selected.condition.matches(ctx.input)
                if (conditionMatch is ConditionMatch.Matched) {
                    val existing = matchedByLayer[chain.layer]
                    if (existing == null || selected.action.severity > existing.action.severity) {
                        matchedByLayer[chain.layer] = selected
                    }
                }
            }
        }

        val sortedChain = explanationChain.sortedWith(
            compareByDescending<ExplanationEntry> { it.layer.priority }
                .thenBy { it.ruleId }
                .thenByDescending { it.version }
        )

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
            explanationChain = sortedChain,
            consideredRuleIds = consideredRuleIds.toList()
        )
    }

    private fun selectVersionAndExplain(
        chain: RuleChain,
        facility: Facility,
        input: RiskInput,
        evaluationTime: Long
    ): VersionSelection {
        val activeVersions = chain.versions.filter { it.isActiveAt(evaluationTime) }
        val selected = activeVersions.maxByOrNull { it.version }

        val entries = chain.versions.map { rule ->
            val visible = rule.publishedAt <= evaluationTime
            val active = rule.isActiveAt(evaluationTime)
            val applicable = rule.isApplicableTo(facility)
            val isSelected = selected != null && rule.id == selected.id && rule.version == selected.version

            val selectionReason = when {
                !visible -> "not yet published at evaluation time (publishedAt=${rule.publishedAt})"
                !active && selected != null ->
                    "outside validity window [${rule.validFrom}, ${rule.validTo}); " +
                        "superseded by ${selected.id} v${selected.version}"
                !active && selected == null ->
                    "outside validity window [${rule.validFrom}, ${rule.validTo}); no active version in chain"
                isSelected ->
                    "selected as active version (highest version=${rule.version} within validity window)"
                active && selected != null && rule.version < selected.version ->
                    "active but superseded by higher version ${selected.version}"
                else -> "not selected"
            }

            val reasons = mutableListOf<String>()

            if (isSelected) {
                when (val match = rule.condition.matches(input)) {
                    is ConditionMatch.Matched -> Unit
                    is ConditionMatch.NotMatched -> reasons.addAll(match.reasons)
                }
            } else {
                reasons.add("version not selected; condition not evaluated for decision")
            }

            if (!applicable) {
                reasons.add("rule not applicable to facility")
            }

            val conditionMatches = isSelected &&
                rule.condition.matches(input) is ConditionMatch.Matched &&
                applicable

            ExplanationEntry(
                ruleId = rule.id,
                layer = rule.layer,
                version = rule.version,
                action = rule.action,
                matched = conditionMatches,
                visibleAtEvaluation = visible,
                activeAtEvaluationTime = active,
                applicableToFacility = applicable,
                selectedAsActiveVersion = isSelected,
                versionSelectionReason = selectionReason,
                reasons = reasons,
                description = rule.description
            )
        }

        return VersionSelection(selected, entries)
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
