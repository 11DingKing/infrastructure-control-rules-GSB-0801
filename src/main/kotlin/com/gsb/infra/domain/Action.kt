package com.gsb.infra.domain

/**
 * Control actions ordered from least to most strict.
 *
 * [NONE] is the neutral baseline used when no rule matches and as the starting
 * accumulator during conflict resolution.
 */
enum class Action(val severity: Int) {
    NONE(0),
    MONITOR(1),
    RESTRICT(2),
    CLOSE(3);

    companion object {
        fun stricter(a: Action, b: Action): Action = if (a.severity >= b.severity) a else b
    }
}
