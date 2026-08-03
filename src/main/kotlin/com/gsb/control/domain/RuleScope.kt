package com.gsb.control.domain

/**
 * What a rule applies to. The layer is implied by the scope variant, keeping
 * the two consistent by construction.
 */
sealed interface RuleScope {
    val layer: RuleLayer

    /** Matches every facility. */
    data object Global : RuleScope {
        override val layer: RuleLayer get() = RuleLayer.DEFAULT
    }

    /** Matches facilities whose regionCode equals [regionCode] (e.g. "440800"). */
    data class Region(val regionCode: String) : RuleScope {
        override val layer: RuleLayer get() = RuleLayer.REGION
    }

    /** Matches the single facility [facilityId]. */
    data class FacilitySpecific(val facilityId: String) : RuleScope {
        override val layer: RuleLayer get() = RuleLayer.FACILITY
    }

    /** Human override targeting the single facility [facilityId]. */
    data class Manual(val facilityId: String) : RuleScope {
        override val layer: RuleLayer get() = RuleLayer.MANUAL
    }

    /** True if this scope applies to [facility]. Pure. */
    fun matches(facility: Facility): Boolean = when (this) {
        Global -> true
        is Region -> facility.regionCode == regionCode
        is FacilitySpecific -> facility.id == facilityId
        is Manual -> facility.id == facilityId
    }
}
