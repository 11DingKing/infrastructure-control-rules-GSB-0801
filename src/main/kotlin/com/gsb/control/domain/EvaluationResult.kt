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
    /**
     * Per-dimension breakdown (visibility, scope, effective window, version
     * selection, condition, priority adjudication) computed independently of
     * the collapsed [outcome]. Lets an audit see each factor on its own.
     */
    val breakdown: TraceBreakdown,
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

    /**
     * A canonical JSON rendering of the full result, including each trace line's
     * independent [TraceBreakdown]. Object keys are emitted in a fixed order and
     * the trace is already in the evaluator's deterministic order, so the output
     * is byte-identical regardless of the order rules were supplied in. This is
     * the stable source for [contentHash].
     */
    fun canonicalJson(): String = buildString {
        append('{')
        appendKey("facilityId"); appendStr(facilityId); append(',')
        appendKey("decision"); appendNullableStr(decision?.name); append(',')
        appendKey("decidingLayer"); appendNullableStr(decidingLayer?.name); append(',')
        appendKey("decidingVersionRef"); appendNullableStr(decidingVersionRef); append(',')
        appendKey("evaluatedAt"); appendStr(evaluatedAt.toString()); append(',')
        appendKey("asOf"); appendStr(asOf.toString()); append(',')
        appendKey("input"); append(inputJson()); append(',')
        appendKey("firedVersionRefs")
        append('[')
        firedVersionRefs.forEachIndexed { i, ref ->
            if (i > 0) append(',')
            appendStr(ref)
        }
        append(']'); append(',')
        appendKey("trace")
        append('[')
        trace.forEachIndexed { i, t ->
            if (i > 0) append(',')
            append(traceJson(t))
        }
        append(']')
        append('}')
    }

    /** Deterministic SHA-256 over [canonicalJson]; stable and reorder-invariant. */
    val contentHash: String get() = Hashing.sha256Hex(canonicalJson())

    private fun inputJson(): String = buildString {
        append('{')
        input.values.entries.sortedBy { it.key.key }.forEachIndexed { i, (metric, value) ->
            if (i > 0) append(',')
            appendKey(metric.key)
            append(RiskInput.canonicalDouble(value))
        }
        append('}')
    }

    private fun traceJson(t: RuleTrace): String = buildString {
        val b = t.breakdown
        append('{')
        appendKey("versionRef"); appendStr(t.versionRef); append(',')
        appendKey("ruleKey"); appendStr(t.ruleKey); append(',')
        appendKey("version"); append(t.version.toString()); append(',')
        appendKey("layer"); appendStr(t.layer.name); append(',')
        appendKey("action"); appendStr(t.action.name); append(',')
        appendKey("outcome"); appendStr(t.outcome.code); append(',')
        appendKey("decisive"); append(t.decisive.toString()); append(',')
        appendKey("breakdown")
        append('{')
        appendKey("visibility"); appendStr(b.visibility.name); append(',')
        appendKey("scope"); appendStr(b.scope.name); append(',')
        appendKey("window"); appendStr(b.window.name); append(',')
        appendKey("versionSelection"); appendStr(b.versionSelection.name); append(',')
        appendKey("condition"); appendStr(b.condition.name); append(',')
        appendKey("missingMetric"); appendNullableStr(b.missingMetric); append(',')
        appendKey("priority"); appendStr(b.priority.name)
        append('}')
        append('}')
    }

    private fun StringBuilder.appendKey(key: String) {
        appendStr(key); append(':')
    }

    private fun StringBuilder.appendStr(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun StringBuilder.appendNullableStr(s: String?) {
        if (s == null) append("null") else appendStr(s)
    }
}
