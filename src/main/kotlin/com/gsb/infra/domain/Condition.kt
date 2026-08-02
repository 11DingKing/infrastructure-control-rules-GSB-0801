package com.gsb.infra.domain

import kotlinx.serialization.Serializable

/**
 * Supported risk metrics. Only these metrics may appear in [RiskInput] values
 * and [Condition] expressions; any other name yields [ReasonCode.UNKNOWN_METRIC].
 */
@Serializable
enum class Metric(val key: String) {
    HOURLY_PRECIPITATION_MM("hourlyPrecipitationMm"),
    WIND_LEVEL("windLevel"),
    WATER_DEPTH_CM("waterDepthCm");

    companion object {
        fun fromKey(key: String): Metric? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Comparison operators used in threshold [Condition]s.
 */
@Serializable
enum class Operator {
    GTE,
    GT,
    LTE,
    LT,
    EQ
}

/**
 * A single threshold condition: `metric op threshold`.
 *
 * For example WIND_LEVEL GTE 7 means "wind level >= 7".
 */
@Serializable
data class Condition(
    val metric: Metric,
    val operator: Operator,
    val threshold: Double
) {
    fun evaluate(value: Double): Boolean = when (operator) {
        Operator.GTE -> value >= threshold
        Operator.GT -> value > threshold
        Operator.LTE -> value <= threshold
        Operator.LT -> value < threshold
        Operator.EQ -> value == threshold
    }
}
