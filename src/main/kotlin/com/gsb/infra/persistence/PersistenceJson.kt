package com.gsb.infra.persistence

import com.gsb.infra.domain.Condition
import com.gsb.infra.domain.EvaluationResult
import com.gsb.infra.domain.FacilityType
import com.gsb.infra.domain.Metric
import com.gsb.infra.domain.Operator
import com.gsb.infra.domain.RiskInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ConditionDto(
    val metric: String,
    val operator: String,
    val threshold: Double
) {
    fun toDomain(): Condition {
        val m = Metric.fromKey(metric)
            ?: throw IllegalArgumentException("Unknown metric key: $metric")
        return Condition(m, Operator.valueOf(operator), threshold)
    }

    companion object {
        fun fromDomain(c: Condition): ConditionDto =
            ConditionDto(c.metric.key, c.operator.name, c.threshold)
    }
}

object PersistenceJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeConditions(conditions: List<Condition>): String =
        json.encodeToString(conditions.map { ConditionDto.fromDomain(it) })

    fun decodeConditions(raw: String): List<Condition> {
        val dtos = json.decodeFromString<List<ConditionDto>>(raw)
        return dtos.map { it.toDomain() }
    }

    fun encodeFacilityTypes(types: Set<FacilityType>): String =
        json.encodeToString(types.map { it.name })

    fun decodeFacilityTypes(raw: String): Set<FacilityType> {
        if (raw.isBlank()) return emptySet()
        return json.decodeFromString<List<String>>(raw).map { FacilityType.valueOf(it) }.toSet()
    }

    fun encodeSnapshot(input: RiskInput): String =
        json.encodeToString(input.orderedValues().mapKeys { it.key.key })

    fun decodeSnapshot(raw: String): RiskInput {
        val map = json.decodeFromString<Map<String, Double>>(raw)
        val values = LinkedHashMap<Metric, Double>()
        for ((k, v) in map) {
            val m = Metric.fromKey(k)
            if (m != null) values[m] = v
        }
        return RiskInput(values)
    }

    fun encodeResult(result: EvaluationResult): String =
        json.encodeToString(result)

    fun decodeResult(raw: String): EvaluationResult =
        json.decodeFromString(raw)
}
