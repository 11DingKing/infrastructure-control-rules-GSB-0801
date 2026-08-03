package com.gsb.control.domain

/**
 * Enumerable reasons a rule does not contribute an action for a given
 * evaluation. Every non-firing rule is explained by exactly one of these, so
 * the public API never throws for "normal" evaluation outcomes — it returns a
 * [ReasonCode] instead.
 *
 * The string [code] is the stable wire form (persisted and serialized).
 */
enum class ReasonCode(val code: String) {
    /** The rule fired and contributed its action. */
    MATCHED("MATCHED"),

    /** Evaluation instant is before the rule's validFrom. */
    NOT_YET_EFFECTIVE("NOT_YET_EFFECTIVE"),

    /** Evaluation instant is at or after the rule's expiry (exclusive upper bound). */
    EXPIRED("EXPIRED"),

    /** The rule's scope (region/facility) does not match the target facility. */
    SCOPE_MISMATCH("SCOPE_MISMATCH"),

    /** A required risk metric for this rule's condition was absent from the input snapshot. */
    MISSING_INPUT("MISSING_INPUT"),

    /** The rule's threshold condition evaluated to false against the input. */
    CONDITION_NOT_MET("CONDITION_NOT_MET"),

    /** A newer published+valid version of the same logical rule replaced this revision. */
    SUPERSEDED_BY_NEWER_VERSION("SUPERSEDED_BY_NEWER_VERSION"),

    /** The rule was not yet published as-of the evaluation instant (historical replay). */
    NOT_PUBLISHED_AS_OF("NOT_PUBLISHED_AS_OF"),

    /** The rule fired but was overridden by a higher-authority layer's decision. */
    SUPERSEDED_BY_HIGHER_LAYER("SUPERSEDED_BY_HIGHER_LAYER"),

    /** The rule fired at the same layer but a stricter sibling action was selected. */
    SUPERSEDED_BY_STRICTER_SIBLING("SUPERSEDED_BY_STRICTER_SIBLING");
}
