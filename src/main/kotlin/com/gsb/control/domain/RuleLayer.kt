package com.gsb.control.domain

/**
 * Rule layers, ordered from lowest to highest authority.
 *
 * Conflict resolution walks layers from highest [priority] downward:
 * MANUAL > FACILITY > REGION > DEFAULT. A rule that targets a higher layer
 * always overrides any decision reached at a lower layer, regardless of action
 * strictness. Only within the *same* layer does action strictness break ties.
 */
enum class RuleLayer(val priority: Int) {
    /** Fallback rules applied to everything. */
    DEFAULT(0),

    /** Rules scoped to an administrative region (e.g. region:440800). */
    REGION(1),

    /** Rules scoped to a single facility (e.g. facility:tunnel-17). */
    FACILITY(2),

    /** Human override; wins over everything while it is valid. */
    MANUAL(3);
}
