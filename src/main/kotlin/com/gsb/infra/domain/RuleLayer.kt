package com.gsb.infra.domain

/**
 * Layering of rules, from lowest to highest precedence.
 *
 * Conflict resolution order is fixed:
 *   MANUAL > FACILITY > REGION > DEFAULT
 *
 * Within the same [priority], the stricter [Action] wins.
 */
enum class RuleLayer(val priority: Int) {
    DEFAULT(0),
    REGION(1),
    FACILITY(2),
    MANUAL(3);

    companion object {
        fun fromName(name: String): RuleLayer =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown rule layer: $name")
    }
}
