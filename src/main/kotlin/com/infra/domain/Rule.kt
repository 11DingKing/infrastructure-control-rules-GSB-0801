package com.infra.domain

import kotlinx.serialization.Serializable

@Serializable
data class Rule(
    val id: String,
    val layer: RuleLayer,
    val facilityId: String? = null,
    val regionCode: String? = null,
    val facilityType: FacilityType? = null,
    val condition: RuleCondition,
    val action: Action,
    val version: Int,
    val publishedAt: Long,
    val validFrom: Long,
    val validTo: Long? = null,
    val description: String
) {
    fun isApplicableTo(facility: Facility): Boolean {
        val typeMatch = facilityType == null || facilityType == facility.type
        val facilityMatch = when (layer) {
            RuleLayer.FACILITY, RuleLayer.MANUAL -> facilityId == facility.id
            RuleLayer.REGION -> regionCode == facility.regionCode
            RuleLayer.DEFAULT -> true
        }
        return typeMatch && facilityMatch
    }

    fun isActiveAt(evaluationTime: Long): Boolean {
        val published = publishedAt <= evaluationTime
        val notExpired = validTo == null || validTo > evaluationTime
        val started = validFrom <= evaluationTime
        return published && notExpired && started
    }
}
