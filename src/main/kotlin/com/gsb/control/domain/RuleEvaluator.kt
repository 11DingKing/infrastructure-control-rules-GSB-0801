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
            val (outcome, breakdown) = classify(rule, facility, input, evaluatedAt, asOf, activeVersionByKey)
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
                    breakdown = breakdown,
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
        // and mark the single decisive rule. Also fill in the independent
        // priority-resolution dimension of the breakdown.
        val refinedTraces = traces.map { t ->
            if (t.outcome != ReasonCode.MATCHED) return@map t
            when {
                t.versionRef == decisiveRule.versionRef && t.layer == decidingLayer ->
                    t.copy(
                        decisive = true,
                        breakdown = t.breakdown.copy(priority = PriorityResolution.DECISIVE),
                    )

                t.layer.priority < decidingLayer.priority ->
                    t.copy(
                        outcome = ReasonCode.SUPERSEDED_BY_HIGHER_LAYER,
                        detail = supersededHigherDetail(decidingLayer),
                        breakdown = t.breakdown.copy(priority = PriorityResolution.SUPERSEDED_BY_HIGHER_LAYER),
                    )

                else ->
                    // Same deciding layer but not the chosen sibling.
                    t.copy(
                        outcome = ReasonCode.SUPERSEDED_BY_STRICTER_SIBLING,
                        detail = supersededSiblingDetail(decisiveRule),
                        breakdown = t.breakdown.copy(priority = PriorityResolution.SUPERSEDED_BY_STRICTER_SIBLING),
                    )
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

    /**
     * Compute every dimension of a rule's fate independently, then derive the
     * collapsed effective [ReasonCode] from them by fixed precedence
     * (visibility → scope → window → version → condition). The priority
     * dimension is filled in later during adjudication. Returning the full
     * [TraceBreakdown] lets an audit see, e.g., that a rule was EFFECTIVE and
     * SELECTED yet failed only on its condition — rather than a single code.
     */
    private fun classify(
        rule: Rule,
        facility: Facility,
        input: RiskInput,
        evaluatedAt: Instant,
        asOf: Instant,
        activeVersionByKey: Map<String, Int>,
    ): Pair<ReasonCode, TraceBreakdown> {
        val visibility = if (rule.isPublishedAsOf(asOf)) Visibility.VISIBLE else Visibility.NOT_PUBLISHED
        val scope = if (rule.scope.matches(facility)) ScopeMatch.IN_SCOPE else ScopeMatch.OUT_OF_SCOPE
        val window = when (rule.validityAt(evaluatedAt)) {
            ReasonCode.NOT_YET_EFFECTIVE -> WindowState.NOT_YET_EFFECTIVE
            ReasonCode.EXPIRED -> WindowState.EXPIRED
            else -> WindowState.EFFECTIVE
        }

        // Version selection is only meaningful for a revision that is itself
        // visible + in-scope + in-window. A newer version that is not currently
        // effective never enters selection, so it cannot retire this one.
        val eligible = visibility == Visibility.VISIBLE &&
            scope == ScopeMatch.IN_SCOPE &&
            window == WindowState.EFFECTIVE
        val activeVersion = activeVersionByKey[rule.ruleKey]
        val versionSelection = when {
            !eligible -> VersionSelection.NOT_APPLICABLE
            activeVersion != null && rule.version < activeVersion -> VersionSelection.SUPERSEDED_BY_NEWER_VERSION
            else -> VersionSelection.SELECTED
        }

        // Condition is evaluated independently, even for non-firing rules, so
        // the breakdown records whether the threshold held on its own terms.
        val conditionOutcome = rule.condition.evaluate(input)
        val conditionState = when (conditionOutcome) {
            ConditionOutcome.Satisfied -> ConditionState.MET
            ConditionOutcome.NotSatisfied -> ConditionState.NOT_MET
            is ConditionOutcome.MissingInput -> ConditionState.MISSING_INPUT
        }
        val missingMetric = (conditionOutcome as? ConditionOutcome.MissingInput)?.metric?.key

        val breakdown = TraceBreakdown(
            visibility = visibility,
            scope = scope,
            window = window,
            versionSelection = versionSelection,
            condition = conditionState,
            missingMetric = missingMetric,
            priority = PriorityResolution.DID_NOT_FIRE,
        )

        // Derive the collapsed effective reason by fixed precedence.
        val outcome = when {
            visibility == Visibility.NOT_PUBLISHED -> ReasonCode.NOT_PUBLISHED_AS_OF
            scope == ScopeMatch.OUT_OF_SCOPE -> ReasonCode.SCOPE_MISMATCH
            window == WindowState.NOT_YET_EFFECTIVE -> ReasonCode.NOT_YET_EFFECTIVE
            window == WindowState.EXPIRED -> ReasonCode.EXPIRED
            versionSelection == VersionSelection.SUPERSEDED_BY_NEWER_VERSION -> ReasonCode.SUPERSEDED_BY_NEWER_VERSION
            conditionState == ConditionState.MISSING_INPUT -> ReasonCode.MISSING_INPUT
            conditionState == ConditionState.NOT_MET -> ReasonCode.CONDITION_NOT_MET
            else -> ReasonCode.MATCHED
        }
        return outcome to breakdown
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
