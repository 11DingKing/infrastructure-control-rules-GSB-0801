package com.gsb.control.domain

/** The kinds of critical facilities this service governs. */
enum class FacilityKind(val code: String) {
    TUNNEL("tunnel"),
    WATERLOGGED_ROAD("waterlogged_road"),
    SCHOOL("school"),
    TEMPORARY_STRUCTURE("temporary_structure");
}

/**
 * A governed facility. [regionCode] links the facility to REGION-layer rules
 * (e.g. "440800"). Immutable value type — carries no framework dependencies.
 */
data class Facility(
    val id: String,
    val kind: FacilityKind,
    val regionCode: String,
    val name: String,
)
