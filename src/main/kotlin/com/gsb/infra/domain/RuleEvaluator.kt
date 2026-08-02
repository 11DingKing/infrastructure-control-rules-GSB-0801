package com.gsb.infra.domain

import java.time.Instant

/**
 * Pure, side-effect-free rule evaluator.
 *
 * The evaluator never touches a database, clock, network or logger. The
 * "current time" is explicitly passed in as [EvaluationRequest.evaluatedAt] so
 * historical evaluations are reproducible and batch baselines are stable.
 *
 * Determinism guarantees:
 *  - Rules are processed in a stable order (by ruleId, then version).
 *  - The set of rules considered is only those published at [EvaluationRequest.evaluatedAt],
 *    so historical explanations cannot see rules published later.
 *  - Input snapshots are normalised to declaration order.
 *  - Conflict resolution is fixed: MANUAL > FACILITY > REGION > DEFAULT, and within
 *    a layer the strictest action wins. Ties are broken by ruleId/version for
 *    deterministic winner selection.
 */
object RuleEvaluator {

    data class EvaluationRequest(
        val facility: Facility,
        val rules: List<Rule>,
        val input: RiskInput,
        val evaluatedAt: Instant
    )

    fun evaluate(request: EvaluationRequest): EvaluationResult {
        val (facility, rules, input, at) = request

        // History isolation: rules are sorted deterministically. A rule whose
        // publishedAt is after `at` is still traced (for an auditable explanation
        // chain) but can never match — see traceRule. This guarantees historical
        // explanations cannot be influenced by rules published later.
        val orderedRules = rules
            .sortedWith(compareBy({ it.layer.priority }, { it.ruleId }, { it.version }))

        val traces = mutableListOf<RuleTrace>()
        val layerDecisions = mutableListOf<LayerDecision>()

        // Winner across all layers. Higher layer priority always wins over lower;
        // within a layer the strictest action wins.
        var overallWinner: Rule? = null
        var overallAction = Action.NONE

        for (layer in RuleLayer.entries) {
            val layerRules = orderedRules.filter { it.layer == layer }
            val layerMatched = mutableListOf<Rule>()
            var layerWinner: Rule? = null
            var layerAction = Action.NONE

            for (rule in layerRules) {
                val trace = traceRule(rule, facility, input, at)
                traces.add(trace)
                if (trace.matched) {
                    layerMatched.add(rule)
                    // Strictest action wins within the layer; on equal severity the
                    // deterministic ordering (ruleId, version) keeps the first one.
                    if (rule.action.severity > layerAction.severity) {
                        layerAction = rule.action
                        layerWinner = rule
                    }
                }
            }

            if (layerWinner != null) {
                layerDecisions.add(
                    LayerDecision(
                        layer = layer,
                        strictestAction = layerAction,
                        winningRuleId = layerWinner.ruleId,
                        winningVersion = layerWinner.version,
                        matchedRuleIds = layerMatched.map { it.ruleId + "#v" + it.version }
                    )
                )
                // Layers are iterated in ascending priority. A matched higher layer
                // always overrides any lower layer, regardless of action severity.
                overallWinner = layerWinner
                overallAction = layerAction
            } else {
                layerDecisions.add(
                    LayerDecision(
                        layer = layer,
                        strictestAction = Action.NONE,
                        winningRuleId = null,
                        winningVersion = null,
                        matchedRuleIds = layerMatched.map { it.ruleId + "#v" + it.version }
                    )
                )
            }
        }

        // Mark which traces were selected so the explanation chain is explicit.
        val finalTraces = traces.map { trace ->
            val selected = overallWinner != null &&
                trace.ruleId == overallWinner.ruleId &&
                trace.version == overallWinner.version &&
                trace.matched
            trace.copy(selected = selected)
        }

        val reasonCode = if (overallWinner != null) {
            ReasonCode.RULE_MATCHED
        } else {
            ReasonCode.NO_ACTIVE_RULE
        }

        val orderedSnapshot = input.orderedValues().mapKeys { it.key.key }

        return EvaluationResult(
            facilityId = facility.id,
            facilityType = facility.type,
            regionCode = facility.regionCode,
            evaluatedAt = at.toString(),
            finalAction = overallAction,
            winningRuleId = overallWinner?.ruleId,
            winningVersion = overallWinner?.version,
            winningLayer = overallWinner?.layer,
            reasonCode = reasonCode,
            ruleTraces = finalTraces,
            layerDecisions = layerDecisions,
            inputSnapshot = orderedSnapshot,
            explanation = buildExplanation(
                facility = facility,
                winner = overallWinner,
                action = overallAction,
                at = at,
                reasonCode = reasonCode
            )
        )
    }

    private fun traceRule(
        rule: Rule,
        facility: Facility,
        input: RiskInput,
        at: Instant
    ): RuleTrace {
        val scopeMatched = rule.scopeMatches(facility)
        val typeMatched = rule.typeMatches(facility)
        val alreadyPublished = rule.isPublished(at)
        val inWindow = rule.isInEffectiveWindow(at)

        val windowReason = when {
            !alreadyPublished -> ReasonCode.RULE_NOT_YET_EFFECTIVE
            at.isBefore(rule.effectiveFrom) -> ReasonCode.RULE_NOT_YET_EFFECTIVE
            rule.expiresAt != null && !at.isBefore(rule.expiresAt) ->
                if (at == rule.expiresAt) ReasonCode.RULE_EXPIRED
                else ReasonCode.RULE_OUTSIDE_EFFECTIVE_WINDOW
            else -> ReasonCode.RULE_MATCHED
        }

        val conditionOutcomes = rule.conditions.map { evaluateCondition(it, input) }
        val conditionsMet = conditionOutcomes.all { it.matched }

        val matched = scopeMatched && typeMatched && alreadyPublished &&
            inWindow && conditionsMet

        val reasonCode = when {
            !alreadyPublished -> ReasonCode.RULE_NOT_YET_EFFECTIVE
            at.isBefore(rule.effectiveFrom) -> ReasonCode.RULE_NOT_YET_EFFECTIVE
            rule.expiresAt != null && !at.isBefore(rule.expiresAt) -> windowReason
            !scopeMatched || !typeMatched -> ReasonCode.OVERRIDDEN_BY_HIGHER_PRIORITY_LAYER
            !conditionsMet -> conditionOutcomes.firstOrNull { !it.matched }?.reasonCode
                ?: ReasonCode.CONDITION_NOT_MET
            else -> ReasonCode.RULE_MATCHED
        }

        return RuleTrace(
            ruleId = rule.ruleId,
            version = rule.version,
            layer = rule.layer,
            action = rule.action,
            reason = rule.reason,
            scopeMatched = scopeMatched,
            typeMatched = typeMatched,
            inEffectiveWindow = inWindow,
            alreadyPublished = alreadyPublished,
            effectiveFrom = rule.effectiveFrom.toString(),
            expiresAt = rule.expiresAt?.toString(),
            publishedAt = rule.publishedAt.toString(),
            conditionOutcomes = conditionOutcomes,
            matched = matched,
            selected = false,
            reasonCode = reasonCode
        )
    }

    private fun evaluateCondition(
        condition: Condition,
        input: RiskInput
    ): ConditionOutcome {
        val actual = input.value(condition.metric)
        return if (actual == null) {
            ConditionOutcome(
                metricKey = condition.metric.key,
                operator = condition.operator,
                threshold = condition.threshold,
                actualValue = null,
                matched = false,
                reasonCode = ReasonCode.CONDITION_MISSING_INPUT
            )
        } else {
            val ok = condition.evaluate(actual)
            ConditionOutcome(
                metricKey = condition.metric.key,
                operator = condition.operator,
                threshold = condition.threshold,
                actualValue = actual,
                matched = ok,
                reasonCode = if (ok) ReasonCode.RULE_MATCHED else ReasonCode.CONDITION_NOT_MET
            )
        }
    }

    private fun buildExplanation(
        facility: Facility,
        winner: Rule?,
        action: Action,
        at: Instant,
        reasonCode: ReasonCode
    ): String = if (winner != null) {
        "Facility ${facility.id} (${facility.type}, region ${facility.regionCode}) " +
            "evaluated at $at: action=$action from layer ${winner.layer} " +
            "rule ${winner.ruleId} v${winner.version} (${winner.reason})."
    } else {
        "Facility ${facility.id} (${facility.type}, region ${facility.regionCode}) " +
            "evaluated at $at: no active rule matched ($reasonCode); action=$action."
    }
}
