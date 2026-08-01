package com.infra.domain

import kotlinx.serialization.Serializable

@Serializable
data class RuleCondition(
    val minRainfallMm: Double? = null,
    val maxRainfallMm: Double? = null,
    val minWindLevel: Int? = null,
    val maxWindLevel: Int? = null,
    val minWaterDepthCm: Double? = null,
    val maxWaterDepthCm: Double? = null
) {
    fun matches(input: RiskInput): ConditionMatch {
        val failures = mutableListOf<String>()

        minRainfallMm?.let { threshold ->
            val value = input.hourlyRainfallMm
            when {
                value == null -> failures.add("hourlyRainfallMm is required when minRainfallMm=$threshold")
                value < threshold -> failures.add("hourlyRainfallMm=$value < minRainfallMm=$threshold")
                else -> Unit
            }
        }
        maxRainfallMm?.let { threshold ->
            val value = input.hourlyRainfallMm
            when {
                value == null -> failures.add("hourlyRainfallMm is required when maxRainfallMm=$threshold")
                value > threshold -> failures.add("hourlyRainfallMm=$value > maxRainfallMm=$threshold")
                else -> Unit
            }
        }
        minWindLevel?.let { threshold ->
            val value = input.windLevel
            when {
                value == null -> failures.add("windLevel is required when minWindLevel=$threshold")
                value < threshold -> failures.add("windLevel=$value < minWindLevel=$threshold")
                else -> Unit
            }
        }
        maxWindLevel?.let { threshold ->
            val value = input.windLevel
            when {
                value == null -> failures.add("windLevel is required when maxWindLevel=$threshold")
                value > threshold -> failures.add("windLevel=$value > maxWindLevel=$threshold")
                else -> Unit
            }
        }
        minWaterDepthCm?.let { threshold ->
            val value = input.waterDepthCm
            when {
                value == null -> failures.add("waterDepthCm is required when minWaterDepthCm=$threshold")
                value < threshold -> failures.add("waterDepthCm=$value < minWaterDepthCm=$threshold")
                else -> Unit
            }
        }
        maxWaterDepthCm?.let { threshold ->
            val value = input.waterDepthCm
            when {
                value == null -> failures.add("waterDepthCm is required when maxWaterDepthCm=$threshold")
                value > threshold -> failures.add("waterDepthCm=$value > maxWaterDepthCm=$threshold")
                else -> Unit
            }
        }

        return if (failures.isEmpty()) ConditionMatch.Matched else ConditionMatch.NotMatched(failures)
    }
}

@Serializable
sealed class ConditionMatch {
    @Serializable
    data object Matched : ConditionMatch()

    @Serializable
    data class NotMatched(val reasons: List<String>) : ConditionMatch()
}
