package com.gsb.control.domain

/**
 * Control actions, ordered from least to most severe.
 *
 * [strictness] gives a total order used by conflict resolution: when two rules
 * share the same layer priority, the stricter action wins. The values are
 * stable and part of the domain contract (persisted, serialized, tested).
 */
enum class Action(val strictness: Int) {
    /** Keep watching; no operational limitation. */
    MONITOR(1),

    /** Limit access / capacity but stay open. */
    RESTRICT(2),

    /** Close the facility. */
    CLOSE(3);

    companion object {
        /** Returns the stricter of two actions. Deterministic and commutative. */
        fun stricter(a: Action, b: Action): Action = if (a.strictness >= b.strictness) a else b
    }
}
