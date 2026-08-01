package com.gsb.control.domain

import java.time.Instant

/**
 * The pure, side-effect-free rule evaluator.
 *
 * Contract:
 *  - No I/O, no clock reads, no randomness, no mutation of inputs. Every input
 *    is supplied explicitly ([facility], [rules], [input], [evaluatedAt],
 *    [asOf]).
 *  - Deterministic: equal arguments always yield an [EvaluationResult] with a
 *    byte-identical [EvaluationResult.canonicalString].
 *  - Total: never throws for normal evaluation; every rule is explained by an
 *    enumerable [ReasonCode]. No use of `!!`.
 *
 * Conflict resolution (fixed):
 *   MANUAL > FACILITY > REGION > DEFAULT. The highest layer that has any fired
 *   rule decides the action; lower layers are recorded but overridden. Within
 *   the deciding layer, the stricter [Action] wins; equally-strict siblings are
 *   recorded as superseded by the chosen one (chosen deterministically).
 *
 * Historical replay ([asOf]):
 *   Only rule revisions with `publishedAt <= asOf` are eligible, so an
 *   explanation reproduced as-of a past instant can never observe rules
 *   published afterwards.
 */
object RuleEvaluator {

    fun evaluate(
        facility: Facility,
        rules: List<Rule>,
        input: RiskInput,
        evaluatedAt: Instant,
        asOf: Instant,
    ): EvaluationResult {
        // Deterministic candidate ordering: highest layer first, then rule key,
        // then newest version first. This fixes trace order regardless of the
        // caller's list order (important for byte-identical output).
        val ordered = rules.sortedWith(
            compareByDescending<Rule> { it.layer.priority }
                .thenBy { it.ruleKey }
                .thenByDescending { it.version },
        )

        // For each logical rule key, find the "active" revision: the newest
        // version that is published-as-of, in-scope, and temporally valid.
        val activeVersionByKey: Map<String, Int> = ordered
            .filter { r ->
                r.isPublishedAsOf(asOf) &&
                    r.scope.matches(facility) &&
                    r.validityAt(evaluatedAt) == ReasonCode.MATCHED
            }
            .groupBy { it.ruleKey }
            .mapValues { (_, revs) -> revs.maxOf { it.version } }

        val traces = ArrayList<RuleTrace>(ordered.size)
        // Fired rules, keyed by layer, preserving deterministic order.
        val firedByLayer = LinkedHashMap<RuleLayer, MutableList<Rule>>()

        for (rule in ordered) {
            val outcome = classify(rule, facility, input, evaluatedAt, asOf, activeVersionByKey)
            traces.add(
                RuleTrace(
                    ruleKey = rule.ruleKey,
                    version = rule.version,
                    versionRef = rule.versionRef,
                    layer = rule.layer,
                    action = rule.action,
                    outcome = outcome,
                    decisive = false,
                    detail = detailFor(rule, outcome),
                ),
            )
            if (outcome == ReasonCode.MATCHED) {
                firedByLayer.getOrPut(rule.layer) { ArrayList() }.add(rule)
            }
        }

        // Pick the highest-priority layer that has any fired rule.
        val decidingLayer: RuleLayer? = firedByLayer.keys.maxByOrNull { it.priority }

        if (decidingLayer == null) {
            // No rule fired: decision is "no control action".
            return EvaluationResult(
                facilityId = facility.id,
                decision = null,
                decidingLayer = null,
                decidingVersionRef = null,
                input = input,
                evaluatedAt = evaluatedAt,
                asOf = asOf,
                firedVersionRefs = emptyList(),
                trace = traces,
            )
        }

        val firedInDecidingLayer = firedByLayer.getValue(decidingLayer)

        // Within the deciding layer, the stricter action wins. Ties are broken
        // deterministically by versionRef so the choice is reproducible.
        val decisiveRule = firedInDecidingLayer
            .sortedWith(
                compareByDescending<Rule> { it.action.strictness }
                    .thenBy { it.versionRef },
            )
            .first()

        val decision = decisiveRule.action

        // Reclassify fired rules against the decision to refine their reasons,
        // and mark the single decisive rule.
        val refinedTraces = traces.map { t ->
            if (t.outcome != ReasonCode.MATCHED) return@map t
            when {
                t.versionRef == decisiveRule.versionRef && t.layer == decidingLayer ->
                    t.copy(decisive = true)

                t.layer.priority < decidingLayer.priority ->
                    t.copy(outcome = ReasonCode.SUPERSEDED_BY_HIGHER_LAYER, detail = supersededHigherDetail(decidingLayer))

                else ->
                    // Same deciding layer but not the chosen sibling.
                    t.copy(outcome = ReasonCode.SUPERSEDED_BY_STRICTER_SIBLING, detail = supersededSiblingDetail(decisiveRule))
            }
        }

        val firedRefs = firedInDecidingLayer
            .map { it.versionRef }
            .sorted()

        return EvaluationResult(
            facilityId = facility.id,
            decision = decision,
            decidingLayer = decidingLayer,
            decidingVersionRef = decisiveRule.versionRef,
            input = input,
            evaluatedAt = evaluatedAt,
            asOf = asOf,
            firedVersionRefs = firedRefs,
            trace = refinedTraces,
        )
    }

    /** Classify a single rule into the reason it fired or not (pre-conflict). */
    private fun classify(
        rule: Rule,
        facility: Facility,
        input: RiskInput,
        evaluatedAt: Instant,
        asOf: Instant,
        activeVersionByKey: Map<String, Int>,
    ): ReasonCode {
        if (!rule.isPublishedAsOf(asOf)) return ReasonCode.NOT_PUBLISHED_AS_OF
        if (!rule.scope.matches(facility)) return ReasonCode.SCOPE_MISMATCH

        val validity = rule.validityAt(evaluatedAt)
        if (validity != ReasonCode.MATCHED) return validity

        // A newer published+valid version of the same logical rule wins.
        val activeVersion = activeVersionByKey[rule.ruleKey]
        if (activeVersion != null && rule.version < activeVersion) {
            return ReasonCode.SUPERSEDED_BY_NEWER_VERSION
        }

        return when (rule.condition.evaluate(input)) {
            ConditionOutcome.Satisfied -> ReasonCode.MATCHED
            ConditionOutcome.NotSatisfied -> ReasonCode.CONDITION_NOT_MET
            is ConditionOutcome.MissingInput -> ReasonCode.MISSING_INPUT
        }
    }

    private fun detailFor(rule: Rule, outcome: ReasonCode): String = when (outcome) {
        ReasonCode.MATCHED -> "Rule ${rule.versionRef} matched; proposes ${rule.action.name}."
        ReasonCode.NOT_YET_EFFECTIVE -> "Rule ${rule.versionRef} not yet effective (validFrom=${rule.validFrom})."
        ReasonCode.EXPIRED -> "Rule ${rule.versionRef} expired (validUntil=${rule.validUntil})."
        ReasonCode.SCOPE_MISMATCH -> "Rule ${rule.versionRef} scope does not match facility."
        ReasonCode.MISSING_INPUT -> "Rule ${rule.versionRef} references a metric absent from the input snapshot."
        ReasonCode.CONDITION_NOT_MET -> "Rule ${rule.versionRef} condition evaluated false."
        ReasonCode.SUPERSEDED_BY_NEWER_VERSION -> "Rule ${rule.versionRef} superseded by a newer published version."
        ReasonCode.NOT_PUBLISHED_AS_OF -> "Rule ${rule.versionRef} not yet published as-of evaluation instant."
        ReasonCode.SUPERSEDED_BY_HIGHER_LAYER -> "Rule ${rule.versionRef} overridden by a higher-authority layer."
        ReasonCode.SUPERSEDED_BY_STRICTER_SIBLING -> "Rule ${rule.versionRef} superseded within its layer."
    }

    private fun supersededHigherDetail(decidingLayer: RuleLayer): String =
        "Fired but overridden by higher-authority ${decidingLayer.name} layer decision."

    private fun supersededSiblingDetail(decisive: Rule): String =
        "Fired but ${decisive.versionRef} (${decisive.action.name}) was selected in the same layer."
}
