package com.gsb.infra.domain

import java.time.Instant

/**
 * An immutable, versioned control rule.
 *
 * Layering/scope rules:
 *  - [RuleLayer.DEFAULT]: applies to all facilities. [facilityId] and [regionCode] are null.
 *  - [RuleLayer.REGION]: applies to facilities in [regionCode]. [facilityId] is null.
 *  - [RuleLayer.FACILITY]: applies to a single [facilityId]. [regionCode] is null.
 *  - [RuleLayer.MANUAL]: a human-forced override for a single [facilityId]. May carry
 *    an [expiresAt] after which it is no longer effective.
 *
 * A rule matches an evaluation when all [conditions] hold (logical AND), it is within
 * its effective window ([effectiveFrom] <= evaluatedAt < [expiresAt]), and its scope
 * matches the facility.
 *
 * @property version monotonically increasing version number within a rule identity.
 *   Two rules sharing the same [ruleId] but different [version] are different
 *   publications; the latest published one (per [publishedAt]) wins at any given time.
 * @property publishedAt when this version was published. Historical evaluations must
 *   only see rules whose [publishedAt] is not after the evaluation time, so that
 *   "history cannot peek at later rules".
 */
data class Rule(
    val ruleId: String,
    val version: Int,
    val layer: RuleLayer,
    val facilityId: String?,
    val regionCode: String?,
    val facilityTypes: Set<FacilityType>,
    val conditions: List<Condition>,
    val action: Action,
    val reason: String,
    val effectiveFrom: Instant,
    val expiresAt: Instant?,
    val publishedAt: Instant
) {
    init {
        require(ruleId.isNotBlank()) { "ruleId must not be blank" }
        require(version >= 0) { "version must be non-negative (0 means auto-assign)" }
        require(reason.isNotBlank()) { "reason must not be blank" }
        when (layer) {
            RuleLayer.DEFAULT -> {
                require(facilityId == null) { "DEFAULT rule must not have facilityId" }
                require(regionCode == null) { "DEFAULT rule must not have regionCode" }
            }
            RuleLayer.REGION -> {
                require(facilityId == null) { "REGION rule must not have facilityId" }
                require(!regionCode.isNullOrBlank()) { "REGION rule must have regionCode" }
            }
            RuleLayer.FACILITY -> {
                require(!facilityId.isNullOrBlank()) { "FACILITY rule must have facilityId" }
                require(regionCode == null) { "FACILITY rule must not have regionCode" }
            }
            RuleLayer.MANUAL -> {
                require(!facilityId.isNullOrBlank()) { "MANUAL rule must have facilityId" }
                require(regionCode == null) { "MANUAL rule must not have regionCode" }
            }
        }
        if (expiresAt != null) {
            require(expiresAt.isAfter(effectiveFrom)) { "expiresAt must be after effectiveFrom" }
        }
    }

    fun scopeMatches(facility: Facility): Boolean = when (layer) {
        RuleLayer.DEFAULT -> true
        RuleLayer.REGION -> facility.regionCode == regionCode
        RuleLayer.FACILITY -> facility.id == facilityId
        RuleLayer.MANUAL -> facility.id == facilityId
    }

    fun typeMatches(facility: Facility): Boolean =
        facilityTypes.isEmpty() || facility.type in facilityTypes

    fun isInEffectiveWindow(at: Instant): Boolean {
        if (at.isBefore(effectiveFrom)) return false
        if (expiresAt != null && !at.isBefore(expiresAt)) return false
        return true
    }

    fun isPublished(at: Instant): Boolean = !at.isBefore(publishedAt)
}
