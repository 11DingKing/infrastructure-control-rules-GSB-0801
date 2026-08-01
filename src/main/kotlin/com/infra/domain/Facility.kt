package com.infra.domain

import kotlinx.serialization.Serializable

@Serializable
enum class FacilityType {
    TUNNEL,
    WATER_CROSSING,
    SCHOOL,
    TEMPORARY_STRUCTURE
}

@Serializable
data class Facility(
    val id: String,
    val name: String,
    val type: FacilityType,
    val regionCode: String,
    val location: String? = null
)
