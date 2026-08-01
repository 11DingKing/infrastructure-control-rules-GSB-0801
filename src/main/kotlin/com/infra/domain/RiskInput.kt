package com.infra.domain

import kotlinx.serialization.Serializable

@Serializable
data class RiskInput(
    val facilityId: String,
    val hourlyRainfallMm: Double? = null,
    val windLevel: Int? = null,
    val waterDepthCm: Double? = null,
    val observedAt: Long,
    val requestId: String
) {
    fun snapshotKey(): String = buildString {
        append(facilityId).append('|')
        append(hourlyRainfallMm?.let { "%.4f".format(it) } ?: "null").append('|')
        append(windLevel?.toString() ?: "null").append('|')
        append(waterDepthCm?.let { "%.4f".format(it) } ?: "null").append('|')
        append(observedAt)
    }
}
