package com.gsb.control.domain

import java.time.Instant

/**
 * A versioned control rule.
 *
 * Identity has two parts:
 *  - [ruleKey] is the *logical* identity that persists across versions.
 *  - [version] is a monotonically increasing revision of that logical rule.
 * A concrete published revision is uniquely identified by ([ruleKey], [version]).
 *
 * Validity is a half-open interval `[validFrom, validUntil)`:
 *  - `validFrom` inclusive; before it the rule is [ReasonCode.NOT_YET_EFFECTIVE].
 *  - `validUntil` exclusive; at or after it the rule is [ReasonCode.EXPIRED].
 *    `null` means "never expires". The exclusive upper bound makes the
 *    "exactly at expiry" instant deterministically EXPIRED.
 *
 * [publishedAt] records when this revision became known to the system. It is
 * the "as-of" clock used to reproduce historical explanations: an evaluation
 * as-of instant T must ignore any rule with `publishedAt > T`, so history can
 * never peek at rules published later.
 *
 * All fields are immutable value data; the type carries no framework or I/O
 * dependency and is safe to use inside the pure evaluator.
 */
data class Rule(
    val ruleKey: String,
    val version: Int,
    val scope: RuleScope,
    val condition: Condition,
    val action: Action,
    val validFrom: Instant,
    val validUntil: Instant?,
    val publishedAt: Instant,
    val description: String,
) {
    val layer: RuleLayer get() = scope.layer

    /** Stable, human-readable revision identifier, e.g. "tunnel-17.manual:v3". */
    val versionRef: String get() = "$ruleKey:v$version"

    /**
     * Temporal validity of the rule at [at], independent of scope or condition.
     * Returns MATCHED when within the window, else NOT_YET_EFFECTIVE / EXPIRED.
     */
    fun validityAt(at: Instant): ReasonCode = when {
        at.isBefore(validFrom) -> ReasonCode.NOT_YET_EFFECTIVE
        validUntil != null && !at.isBefore(validUntil) -> ReasonCode.EXPIRED
        else -> ReasonCode.MATCHED
    }

    /** True if this revision was already published as-of [asOf]. */
    fun isPublishedAsOf(asOf: Instant): Boolean = !publishedAt.isAfter(asOf)
}
