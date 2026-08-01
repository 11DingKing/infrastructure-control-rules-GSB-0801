package com.gsb.control.domain

/**
 * Independent per-dimension breakdown of how one rule revision fared. Unlike
 * the single short-circuited [ReasonCode] in [RuleTrace.outcome], every
 * dimension here is computed and recorded on its own, so an audit can see —
 * separately — whether the rule was visible, in its effective window, selected
 * among its versions, whether its condition held, and how it fared in the final
 * priority adjudication.
 *
 * All fields are pure value data; the effective [RuleTrace.outcome] is derived
 * from these by fixed precedence (visibility → scope → window → version →
 * condition → priority).
 */
data class TraceBreakdown(
    val visibility: Visibility,
    val scope: ScopeMatch,
    val window: WindowState,
    val versionSelection: VersionSelection,
    val condition: ConditionState,
    /** Metric key present only when [condition] is [ConditionState.MISSING_INPUT]. */
    val missingMetric: String?,
    val priority: PriorityResolution,
)

/** Was the revision already published as-of the evaluation instant? */
enum class Visibility { VISIBLE, NOT_PUBLISHED }

/** Does the rule's scope cover the target facility? */
enum class ScopeMatch { IN_SCOPE, OUT_OF_SCOPE }

/** Where the evaluation instant falls relative to the half-open validity window. */
enum class WindowState { NOT_YET_EFFECTIVE, EFFECTIVE, EXPIRED }

/**
 * Version-selection outcome among the visible + in-scope + in-window revisions
 * of the same logical rule.
 *
 * NOT_APPLICABLE means this revision never entered version selection (it was
 * invisible, out of scope, or outside its window), so it is neither selected
 * nor retired — this is what prevents a published-but-not-yet-effective newer
 * version from prematurely eliminating an older, currently-effective one.
 */
enum class VersionSelection { SELECTED, SUPERSEDED_BY_NEWER_VERSION, NOT_APPLICABLE }

/** Threshold condition result, evaluated independently of the other dimensions. */
enum class ConditionState { MET, NOT_MET, MISSING_INPUT }

/** How the revision fared in the cross-layer / same-layer adjudication. */
enum class PriorityResolution {
    DECISIVE,
    SUPERSEDED_BY_HIGHER_LAYER,
    SUPERSEDED_BY_STRICTER_SIBLING,
    DID_NOT_FIRE,
}
