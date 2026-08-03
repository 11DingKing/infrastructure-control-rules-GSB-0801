package com.gsb.infra.api

import com.gsb.infra.domain.Metric
import com.gsb.infra.domain.ReasonCode
import com.gsb.infra.domain.RiskInput

class InvalidInputException(
    val reasonCode: ReasonCode,
    message: String
) : RuntimeException(message)

object RiskInputParser {

    fun parse(values: Map<String, Double>): RiskInput {
        val map = LinkedHashMap<Metric, Double>()
        for ((key, value) in values) {
            val metric = Metric.fromKey(key)
                ?: throw InvalidInputException(
                    ReasonCode.UNKNOWN_METRIC,
                    "Unknown metric key: $key"
                )
            if (value.isNaN() || value.isInfinite()) {
                throw InvalidInputException(
                    ReasonCode.CONDITION_INPUT_OUT_OF_DOMAIN,
                    "Metric $key has a non-finite value: $value"
                )
            }
            map[metric] = value
        }
        return RiskInput(map)
    }
}
