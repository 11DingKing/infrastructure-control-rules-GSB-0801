package com.gsb.infra.domain

/**
 * Enumerable reason codes explaining why a single rule did not take effect or
 * why evaluation produced a particular outcome.
 *
 * These are stable, machine-readable identifiers so callers never have to parse
 * human-readable text.
 */
enum class ReasonCode {
    RULE_MATCHED,
    RULE_OUTSIDE_EFFECTIVE_WINDOW,
    RULE_EXPIRED,
    RULE_NOT_YET_EFFECTIVE,
    CONDITION_MISSING_INPUT,
    CONDITION_INPUT_OUT_OF_DOMAIN,
    CONDITION_NOT_MET,
    CONDITION_EVALUATION_ERROR,
    OVERRIDDEN_BY_HIGHER_PRIORITY_LAYER,
    SAME_PRIORITY_LESS_STRICT,
    NO_ACTIVE_RULE,
    UNKNOWN_METRIC,
    INVALID_OPERATOR
}
