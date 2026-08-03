package com.gsb.control.domain

/**
 * Outcome of evaluating a [Condition] against a [RiskInput]. Distinguishes the
 * three cases the domain must never conflate: satisfied, unsatisfied, and
 * "a referenced metric was absent from the snapshot".
 */
sealed interface ConditionOutcome {
    data object Satisfied : ConditionOutcome
    data object NotSatisfied : ConditionOutcome

    /** A metric referenced by the condition was missing from the snapshot. */
    data class MissingInput(val metric: RiskMetric) : ConditionOutcome
}

/** Comparison operators supported by threshold conditions. */
enum class Comparator(val symbol: String) {
    GTE(">="),
    GT(">"),
    LTE("<="),
    LT("<"),
    EQ("==");

    fun test(actual: Double, threshold: Double): Boolean = when (this) {
        GTE -> actual >= threshold
        GT -> actual > threshold
        LTE -> actual <= threshold
        LT -> actual < threshold
        EQ -> actual == threshold
    }
}

/**
 * A pure, side-effect-free boolean condition over risk metrics. Evaluation is
 * total: it always returns a [ConditionOutcome], never throws.
 *
 * Missing-input propagation is deliberate and short-circuiting so that the
 * *reason* a rule failed is precise:
 *  - [Threshold] over an absent metric -> MissingInput.
 *  - [And] -> first missing metric wins; else NotSatisfied if any branch is
 *    unsatisfied; else Satisfied.
 *  - [Or]  -> Satisfied if any branch is satisfied; else first missing metric;
 *    else NotSatisfied.
 */
sealed interface Condition {
    fun evaluate(input: RiskInput): ConditionOutcome

    /** True when the metric threshold holds; missing metric -> MissingInput. */
    data class Threshold(
        val metric: RiskMetric,
        val comparator: Comparator,
        val threshold: Double,
    ) : Condition {
        override fun evaluate(input: RiskInput): ConditionOutcome {
            val actual = input.get(metric) ?: return ConditionOutcome.MissingInput(metric)
            return if (comparator.test(actual, threshold)) {
                ConditionOutcome.Satisfied
            } else {
                ConditionOutcome.NotSatisfied
            }
        }
    }

    /** Always fires. Useful for baseline DEFAULT/MANUAL rules. */
    data object Always : Condition {
        override fun evaluate(input: RiskInput): ConditionOutcome = ConditionOutcome.Satisfied
    }

    data class And(val terms: List<Condition>) : Condition {
        override fun evaluate(input: RiskInput): ConditionOutcome {
            var sawUnsatisfied = false
            for (term in terms) {
                when (val outcome = term.evaluate(input)) {
                    is ConditionOutcome.MissingInput -> return outcome
                    ConditionOutcome.NotSatisfied -> sawUnsatisfied = true
                    ConditionOutcome.Satisfied -> {}
                }
            }
            return if (sawUnsatisfied) ConditionOutcome.NotSatisfied else ConditionOutcome.Satisfied
        }
    }

    data class Or(val terms: List<Condition>) : Condition {
        override fun evaluate(input: RiskInput): ConditionOutcome {
            var firstMissing: ConditionOutcome.MissingInput? = null
            for (term in terms) {
                when (val outcome = term.evaluate(input)) {
                    ConditionOutcome.Satisfied -> return ConditionOutcome.Satisfied
                    is ConditionOutcome.MissingInput -> if (firstMissing == null) firstMissing = outcome
                    ConditionOutcome.NotSatisfied -> {}
                }
            }
            return firstMissing ?: ConditionOutcome.NotSatisfied
        }
    }
}
