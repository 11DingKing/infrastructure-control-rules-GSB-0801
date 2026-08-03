package com.gsb.infra.domain

import java.time.Instant

/**
 * Pure, side-effect-free rule evaluator.
 *
 * The evaluator never touches a database, clock, network or logger. The
 * "current time" is explicitly passed in as [EvaluationRequest.evaluatedAt] so
 * historical evaluations are reproducible and batch baselines are stable.
 *
 * Determinism & versioning guarantees:
 *  - Rules are processed in a stable order (layer priority, ruleId, version),
 *    so a shuffled input list produces an identical result.
 *  - A rule whose publishedAt is after `at` is traced but cannot match; history
 *    cannot see rules published later.
 *  - For each ruleId chain, the **highest version that is active at `at`**
 *    (published AND within its effective window AND scope/type matching) is the
 *    selected representative. An older still-active version is marked
 *    [ReasonCode.SUPERSEDED_BY_NEWER_VERSION] and does not match — but it is NOT
 *    retired merely because a newer version was published earlier. Until the
 *    newer version's effective window opens, the older version keeps governing.
 *  - Conflict resolution is fixed across chains: MANUAL > FACILITY > REGION >
 *    DEFAULT, and within a layer the strictest action wins. Ties are broken by
 *    ruleId/version for deterministic winner selection.
 */
object RuleEvaluator {

    data class EvaluationRequest(
        val facility: Facility,
        val rules: List<Rule>,
        val input: RiskInput,
        val evaluatedAt: Instant,
        /**
         * Visibility cutoff. A rule published after [asOf] is not considered
         * visible and cannot match or be selected, even if [evaluatedAt] is later.
         * This is what powers point-in-time historical queries: "as of 04:45,
         * replay 05:15" must not see a rule published at 04:50.
         *
         * Defaults to [evaluatedAt] (a normal live evaluation sees everything
         * published by the evaluation instant).
         */
        val asOf: Instant = evaluatedAt
    )

    fun evaluate(request: EvaluationRequest): EvaluationResult {
        val (facility, rules, input, at, asOf) = request

        val orderedRules = rules
            .sortedWith(compareBy({ it.layer.priority }, { it.ruleId }, { it.version }))

        // --- Phase 1: trace every candidate version ------------------------
        val rawTraces = orderedRules.map { traceRule(it, facility, input, at, asOf) }
        val traceByRuleVersion = rawTraces.associateBy { it.ruleId to it.version }

        // --- Phase 2: version selection per ruleId chain -------------------
        // A version is "eligible" when it is published, in its effective window,
        // and its scope/type match this facility. The highest eligible version
        // is the selected representative of the chain. An older eligible version
        // is superseded by it.
        val selectedVersionByRule = HashMap<String, Int>()
        val superseded = HashMap<Pair<String, Int>, Int>()

        for (ruleId in orderedRules.map { it.ruleId }.distinct()) {
            val chainTraces = traceByRuleVersion.values
                .filter { it.ruleId == ruleId }
                .sortedByDescending { it.version }
            val selected = chainTraces.firstOrNull { it.versionEligible() }
            if (selected != null) {
                selectedVersionByRule[ruleId] = selected.version
                for (older in chainTraces) {
                    if (older.version < selected.version && older.versionEligible()) {
                        superseded[older.ruleId to older.version] = selected.version
                    }
                }
            }
        }

        // --- Phase 3: finalise traces with version selection ---------------
        val traces = rawTraces.map { trace ->
            val selectedVersion = selectedVersionByRule[trace.ruleId]
            val isSelected = selectedVersion == trace.version
            val supersededBy = superseded[trace.ruleId to trace.version]

            val matched = isSelected && trace.conditionsMet &&
                trace.scopeMatched && trace.typeMatched &&
                trace.alreadyPublished && trace.inEffectiveWindow

            val reasonCode = when {
                supersededBy != null -> ReasonCode.SUPERSEDED_BY_NEWER_VERSION
                !trace.alreadyPublished -> ReasonCode.RULE_NOT_YET_PUBLISHED
                at.isBefore(Instant.parse(trace.effectiveFrom)) ->
                    ReasonCode.RULE_NOT_YET_EFFECTIVE
                trace.expiresAt != null &&
                    !at.isBefore(Instant.parse(trace.expiresAt)) ->
                    if (at == Instant.parse(trace.expiresAt)) ReasonCode.RULE_EXPIRED
                    else ReasonCode.RULE_OUTSIDE_EFFECTIVE_WINDOW
                !trace.scopeMatched || !trace.typeMatched ->
                    ReasonCode.OVERRIDDEN_BY_HIGHER_PRIORITY_LAYER
                !isSelected -> {
                    // Not selected and not superseded: no eligible version of this
                    // chain is active (e.g. newer version window has closed). This
                    // version itself is outside its window too, so use window reason.
                    trace.reasonCode
                }
                !trace.conditionsMet ->
                    trace.conditionOutcomes.firstOrNull { !it.matched }?.reasonCode
                        ?: ReasonCode.CONDITION_NOT_MET
                else -> ReasonCode.RULE_MATCHED
            }

            trace.copy(
                versionSelected = isSelected,
                supersededByVersion = supersededBy,
                matched = matched,
                reasonCode = reasonCode
            )
        }

        // --- Phase 4: layer conflict resolution ----------------------------
        val layerDecisions = mutableListOf<LayerDecision>()
        var overallWinner: Rule? = null
        var overallAction = Action.NONE

        for (layer in RuleLayer.entries) {
            val layerRules = orderedRules.filter { it.layer == layer }
            val layerMatched = mutableListOf<Rule>()
            var layerWinner: Rule? = null
            var layerAction = Action.NONE

            for (rule in layerRules) {
                val trace = traces.first { it.ruleId == rule.ruleId && it.version == rule.version }
                if (trace.matched) {
                    layerMatched.add(rule)
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

        val result = EvaluationResult(
            facilityId = facility.id,
            facilityType = facility.type,
            regionCode = facility.regionCode,
            evaluatedAt = at.toString(),
            asOf = asOf.toString(),
            finalAction = overallAction,
            winningRuleId = overallWinner?.ruleId,
            winningVersion = overallWinner?.version,
            winningLayer = overallWinner?.layer,
            reasonCode = reasonCode,
            ruleTraces = finalTraces,
            layerDecisions = layerDecisions,
            inputSnapshot = orderedSnapshot,
            contentHash = "",
            explanation = buildExplanation(
                facility = facility,
                winner = overallWinner,
                action = overallAction,
                at = at,
                asOf = asOf,
                reasonCode = reasonCode
            )
        )

        return result.copy(contentHash = CanonicalHasher.hash(result))
    }

    private fun RuleTrace.versionEligible(): Boolean =
        alreadyPublished && inEffectiveWindow && scopeMatched && typeMatched

    private fun traceRule(
        rule: Rule,
        facility: Facility,
        input: RiskInput,
        at: Instant,
        asOf: Instant
    ): RuleTrace {
        val scopeMatched = rule.scopeMatches(facility)
        val typeMatched = rule.typeMatches(facility)
        // Visibility is governed by asOf (the point-in-time query cutoff), while
        // the effective window is governed by evaluatedAt. These can differ.
        val alreadyPublished = rule.isPublished(asOf)
        val inWindow = rule.isInEffectiveWindow(at)

        val conditionOutcomes = rule.conditions.map { evaluateCondition(it, input) }
        val conditionsMet = conditionOutcomes.all { it.matched }

        val provisionalReason = when {
            !alreadyPublished -> ReasonCode.RULE_NOT_YET_PUBLISHED
            at.isBefore(rule.effectiveFrom) -> ReasonCode.RULE_NOT_YET_EFFECTIVE
            rule.expiresAt != null && !at.isBefore(rule.expiresAt) ->
                if (at == rule.expiresAt) ReasonCode.RULE_EXPIRED
                else ReasonCode.RULE_OUTSIDE_EFFECTIVE_WINDOW
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
            asOf = asOf.toString(),
            conditionOutcomes = conditionOutcomes,
            conditionsMet = conditionsMet,
            versionSelected = false,
            supersededByVersion = null,
            matched = false,
            selected = false,
            reasonCode = provisionalReason
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
        asOf: Instant,
        reasonCode: ReasonCode
    ): String {
        val asOfClause = if (asOf != at) " (as of $asOf)" else ""
        return if (winner != null) {
            "Facility ${facility.id} (${facility.type}, region ${facility.regionCode}) " +
                "evaluated at $at$asOfClause: action=$action from layer ${winner.layer} " +
                "rule ${winner.ruleId} v${winner.version} (${winner.reason})."
        } else {
            "Facility ${facility.id} (${facility.type}, region ${facility.regionCode}) " +
                "evaluated at $at$asOfClause: no active rule matched ($reasonCode); action=$action."
        }
    }
}
