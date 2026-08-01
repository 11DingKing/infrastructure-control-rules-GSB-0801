package com.infra.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Action(val severity: Int) {
    MONITOR(1),
    RESTRICT(2),
    CLOSE(3);

    companion object {
        fun max(a: Action, b: Action): Action =
            if (a.severity >= b.severity) a else b
    }
}

@Serializable
enum class RuleLayer(val priority: Int) {
    DEFAULT(0),
    REGION(1),
    FACILITY(2),
    MANUAL(3)
}

@Serializable
enum class ReasonCode {
    OK,
    NO_RULES_MATCHED,
    MISSING_FACILITY,
    MISSING_RULES,
    INVALID_RULE_CONFIGURATION,
    MANUAL_RULE_EXPIRED,
    INPUT_VALIDATION_FAILED
}
