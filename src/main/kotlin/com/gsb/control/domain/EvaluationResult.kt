package com.gsb.control.domain

import java.time.Instant

/**
 * One line of the explanation chain: how a single candidate rule revision fared
 * during evaluation. The chain is complete — every rule considered produces
 * exactly one entry — so the decision is fully auditable.
 */
data class RuleTrace(
    val ruleKey: String,
    val version: Int,
    val versionRef: String,
    val layer: RuleLayer,
    val action: Action,
    val outcome: ReasonCode,
    /** True only for the single rule whose action became the decision. */
    val decisive: Boolean,
    val detail: String,
)

/**
 * The immutable, self-contained result of a pure evaluation. It pins:
 *  - the [decision] action (null only when no rule fired — see [decided]),
 *  - the [decidingLayer] and [decidingVersionRef] that produced it,
 *  - the exact [input] snapshot that was evaluated,
 *  - the [evaluatedAt] and [asOf] instants,
 *  - the full [trace] explanation chain covering every candidate rule,
 *  - the [firedVersionRefs] actually hit (validity + scope + condition all met).
 *
 * This value is the *only* thing persistence and notification are allowed to
 * consume. They must never re-run judgement or reach back into the rule store.
 */
data class EvaluationResult(
    val facilityId: String,
    val decision: Action?,
    val decidingLayer: RuleLayer?,
    val decidingVersionRef: String?,
    val input: RiskInput,
    val evaluatedAt: Instant,
    val asOf: Instant,
    val firedVersionRefs: List<String>,
    val trace: List<RuleTrace>,
) {
    /** True when at least one rule fired and produced a decision. */
    val decided: Boolean get() = decision != null

    /**
     * A deterministic, canonical rendering of the decision-relevant content.
     * Equal snapshots + equal rule sets + equal instants yield byte-identical
     * output. Used by determinism tests and as a stable digest source.
     */
    fun canonicalString(): String = buildString {
        append("facility=").append(facilityId).append('\n')
        append("decision=").append(decision?.name ?: "NONE").append('\n')
        append("decidingLayer=").append(decidingLayer?.name ?: "NONE").append('\n')
        append("decidingVersionRef=").append(decidingVersionRef ?: "NONE").append('\n')
        append("input=").append(input.canonicalString()).append('\n')
        append("evaluatedAt=").append(evaluatedAt.toString()).append('\n')
        append("asOf=").append(asOf.toString()).append('\n')
        append("fired=").append(firedVersionRefs.joinToString(",")).append('\n')
        append("trace=\n")
        // Trace order is already deterministic (produced by the evaluator in a
        // fixed layer/version order); render it verbatim.
        for (t in trace) {
            append("  ")
                .append(t.versionRef).append('|')
                .append(t.layer.name).append('|')
                .append(t.action.name).append('|')
                .append(t.outcome.code).append('|')
                .append(if (t.decisive) "DECISIVE" else "-")
                .append('\n')
        }
    }
}
