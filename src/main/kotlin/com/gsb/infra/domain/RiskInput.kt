package com.gsb.infra.domain

import kotlinx.serialization.Serializable

/**
 * An immutable snapshot of risk inputs for a single evaluation.
 *
 * Values are stored in a sorted [LinkedHashMap] (insertion order follows [Metric]
 * declaration order) so that serialised snapshots are byte-level deterministic
 * for the same logical input. This is required for repeatable baselines and
 * content-addressable snapshot hashing.
 *
 * The public surface never uses `!!`; missing metrics are represented by absence
 * and surfaced through [ReasonCode.CONDITION_MISSING_INPUT].
 */
@Serializable
data class RiskInput(
    val values: Map<Metric, Double>
) {
    init {
        require(values.size <= Metric.entries.size) {
            "RiskInput cannot contain more values than there are known metrics"
        }
    }

    fun value(metric: Metric): Double? = values[metric]

    /**
     * Returns the metrics in deterministic declaration order, regardless of how
     * the map was originally populated.
     */
    fun orderedValues(): Map<Metric, Double> {
        val result = LinkedHashMap<Metric, Double>(Metric.entries.size)
        for (m in Metric.entries) {
            values[m]?.let { result[m] = it }
        }
        return result
    }

    companion object {
        fun of(vararg pairs: Pair<Metric, Double>): RiskInput {
            val map = LinkedHashMap<Metric, Double>(pairs.size)
            for (p in pairs) {
                map[p.first] = p.second
            }
            return RiskInput(map)
        }
    }
}
