package com.gsb.infra.domain

/**
 * A key infrastructure facility.
 *
 * @property id stable identifier, e.g. "tunnel-17".
 * @property type the kind of facility.
 * @property regionCode the administrative region code the facility belongs to,
 *   e.g. "440800". Used to select REGION-scoped rules.
 * @property name human readable name.
 */
data class Facility(
    val id: String,
    val type: FacilityType,
    val regionCode: String,
    val name: String
) {
    init {
        require(id.isNotBlank()) { "Facility id must not be blank" }
        require(regionCode.isNotBlank()) { "Facility regionCode must not be blank" }
    }
}
