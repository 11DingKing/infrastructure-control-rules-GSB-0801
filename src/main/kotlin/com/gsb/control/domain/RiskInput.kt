package com.gsb.control.domain

/**
 * The risk metrics a rule condition can reference. Kept as an enum so that a
 * rule referencing a metric absent from an input snapshot yields a precise,
 * enumerable [ReasonCode.MISSING_INPUT] rather than a silent zero.
 *
 * [key] is the stable wire form used in snapshots and rule conditions.
 */
enum class RiskMetric(val key: String, val unit: String) {
    /** Rainfall over the trailing hour. */
    HOURLY_RAINFALL_MM("hourly_rainfall_mm", "mm"),

    /** Beaufort wind force scale (0-12). */
    WIND_FORCE_LEVEL("wind_force_level", "level"),

    /** Standing water depth on the surface. */
    WATER_DEPTH_CM("water_depth_cm", "cm");

    companion object {
        fun fromKey(key: String): RiskMetric? = entries.firstOrNull { it.key == key }
    }
}

/**
 * An immutable snapshot of risk metrics at a single instant. Missing metrics
 * are simply absent from [values] — never defaulted — so the evaluator can
 * distinguish "not measured" from "measured as zero".
 *
 * The snapshot is the sole environmental input to pure evaluation. Two
 * evaluations over an equal snapshot and equal rule set must produce
 * byte-identical results; [canonicalString] provides the stable ordering that
 * makes that guarantee observable.
 */
data class RiskInput(
    val values: Map<RiskMetric, Double>,
) {
    fun get(metric: RiskMetric): Double? = values[metric]

    /**
     * A canonical, deterministic textual form: metrics sorted by their stable
     * key. Used for hashing / equality checks in determinism tests.
     */
    fun canonicalString(): String =
        values.entries
            .sortedBy { it.key.key }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (metric, value) ->
                "${metric.key}=${canonicalDouble(value)}"
            }

    companion object {
        fun of(vararg pairs: Pair<RiskMetric, Double>): RiskInput = RiskInput(pairs.toMap())

        /** Locale-independent, stable rendering of a double. */
        fun canonicalDouble(value: Double): String {
            if (value == value.toLong().toDouble()) return value.toLong().toString()
            return value.toBigDecimal().stripTrailingZeros().toPlainString()
        }
    }
}
