package com.infra.api

import com.infra.domain.Action
import com.infra.domain.EvaluationResult
import com.infra.domain.ExplanationEntry
import com.infra.domain.Facility
import com.infra.domain.FacilityType
import com.infra.domain.ReasonCode
import com.infra.domain.RiskInput
import com.infra.domain.Rule
import com.infra.domain.RuleCondition
import com.infra.domain.RuleLayer
import kotlinx.serialization.Serializable

@Serializable
data class FacilityDto(
    val id: String,
    val name: String,
    val type: FacilityType,
    val regionCode: String,
    val location: String? = null
) {
    companion object {
        fun from(f: Facility) = FacilityDto(f.id, f.name, f.type, f.regionCode, f.location)
    }
}

@Serializable
data class CreateFacilityRequest(
    val id: String,
    val name: String,
    val type: FacilityType,
    val regionCode: String,
    val location: String? = null
)

@Serializable
data class RuleConditionDto(
    val minRainfallMm: Double? = null,
    val maxRainfallMm: Double? = null,
    val minWindLevel: Int? = null,
    val maxWindLevel: Int? = null,
    val minWaterDepthCm: Double? = null,
    val maxWaterDepthCm: Double? = null
) {
    fun toDomain() = RuleCondition(
        minRainfallMm = minRainfallMm,
        maxRainfallMm = maxRainfallMm,
        minWindLevel = minWindLevel,
        maxWindLevel = maxWindLevel,
        minWaterDepthCm = minWaterDepthCm,
        maxWaterDepthCm = maxWaterDepthCm
    )

    companion object {
        fun from(c: RuleCondition) = RuleConditionDto(
            minRainfallMm = c.minRainfallMm,
            maxRainfallMm = c.maxRainfallMm,
            minWindLevel = c.minWindLevel,
            maxWindLevel = c.maxWindLevel,
            minWaterDepthCm = c.minWaterDepthCm,
            maxWaterDepthCm = c.maxWaterDepthCm
        )
    }
}

@Serializable
data class RuleDto(
    val id: String,
    val layer: RuleLayer,
    val facilityId: String? = null,
    val regionCode: String? = null,
    val facilityType: FacilityType? = null,
    val condition: RuleConditionDto,
    val action: Action,
    val version: Int,
    val publishedAt: Long,
    val validFrom: Long,
    val validTo: Long? = null,
    val description: String
) {
    fun toDomain() = Rule(
        id = id,
        layer = layer,
        facilityId = facilityId,
        regionCode = regionCode,
        facilityType = facilityType,
        condition = condition.toDomain(),
        action = action,
        version = version,
        publishedAt = publishedAt,
        validFrom = validFrom,
        validTo = validTo,
        description = description
    )

    companion object {
        fun from(r: Rule) = RuleDto(
            id = r.id,
            layer = r.layer,
            facilityId = r.facilityId,
            regionCode = r.regionCode,
            facilityType = r.facilityType,
            condition = RuleConditionDto.from(r.condition),
            action = r.action,
            version = r.version,
            publishedAt = r.publishedAt,
            validFrom = r.validFrom,
            validTo = r.validTo,
            description = r.description
        )
    }
}

@Serializable
data class PublishRuleRequest(
    val id: String,
    val layer: RuleLayer,
    val facilityId: String? = null,
    val regionCode: String? = null,
    val facilityType: FacilityType? = null,
    val condition: RuleConditionDto,
    val action: Action,
    val version: Int,
    val publishedAt: Long? = null,
    val validFrom: Long? = null,
    val validTo: Long? = null,
    val description: String
)

@Serializable
data class RiskInputDto(
    val facilityId: String,
    val hourlyRainfallMm: Double? = null,
    val windLevel: Int? = null,
    val waterDepthCm: Double? = null,
    val observedAt: Long? = null,
    val requestId: String? = null,
    val evaluationTime: Long? = null,
    val asOf: Long? = null
)

@Serializable
data class BatchEvaluationRequest(
    val inputs: List<RiskInputDto>,
    val evaluationTime: Long? = null,
    val asOf: Long? = null
)

@Serializable
data class ExplanationEntryDto(
    val ruleId: String,
    val layer: RuleLayer,
    val version: Int,
    val action: Action,
    val matched: Boolean,
    val visibleAtEvaluation: Boolean,
    val activeAtEvaluationTime: Boolean,
    val applicableToFacility: Boolean,
    val selectedAsActiveVersion: Boolean,
    val versionSelectionReason: String,
    val reasons: List<String>,
    val description: String
) {
    companion object {
        fun from(e: ExplanationEntry) = ExplanationEntryDto(
            ruleId = e.ruleId,
            layer = e.layer,
            version = e.version,
            action = e.action,
            matched = e.matched,
            visibleAtEvaluation = e.visibleAtEvaluation,
            activeAtEvaluationTime = e.activeAtEvaluationTime,
            applicableToFacility = e.applicableToFacility,
            selectedAsActiveVersion = e.selectedAsActiveVersion,
            versionSelectionReason = e.versionSelectionReason,
            reasons = e.reasons,
            description = e.description
        )
    }
}

@Serializable
data class EvaluationResultDto(
    val requestId: String,
    val facilityId: String,
    val finalAction: Action?,
    val reasonCode: ReasonCode,
    val hitRuleId: String?,
    val hitRuleVersion: Int?,
    val hitRuleLayer: RuleLayer?,
    val inputSnapshot: RiskInputDto,
    val evaluatedAt: Long,
    val asOf: Long,
    val explanationChain: List<ExplanationEntryDto>,
    val consideredRuleIds: List<String>,
    val resultHash: String
) {
    companion object {
        fun from(r: EvaluationResult) = EvaluationResultDto(
            requestId = r.requestId,
            facilityId = r.facilityId,
            finalAction = r.finalAction,
            reasonCode = r.reasonCode,
            hitRuleId = r.hitRuleId,
            hitRuleVersion = r.hitRuleVersion,
            hitRuleLayer = r.hitRuleLayer,
            inputSnapshot = RiskInputDto(
                facilityId = r.inputSnapshot.facilityId,
                hourlyRainfallMm = r.inputSnapshot.hourlyRainfallMm,
                windLevel = r.inputSnapshot.windLevel,
                waterDepthCm = r.inputSnapshot.waterDepthCm,
                observedAt = r.inputSnapshot.observedAt,
                requestId = r.inputSnapshot.requestId
            ),
            evaluatedAt = r.evaluatedAt,
            asOf = r.asOf,
            explanationChain = r.explanationChain.map { ExplanationEntryDto.from(it) },
            consideredRuleIds = r.consideredRuleIds,
            resultHash = r.resultHash
        )
    }
}

@Serializable
data class ErrorResponse(
    val error: String,
    val code: String? = null,
    val details: String? = null
)

@Serializable
data class ExplanationResponseDto(
    val requestId: String,
    val facilityId: String,
    val finalAction: Action?,
    val reasonCode: ReasonCode,
    val hitRuleId: String?,
    val hitRuleVersion: Int?,
    val hitRuleLayer: RuleLayer?,
    val evaluatedAt: Long,
    val asOf: Long,
    val resultHash: String,
    val inputSnapshot: RiskInputDto,
    val explanationChain: List<ExplanationEntryDto>,
    val summary: String
) {
    companion object {
        fun from(r: EvaluationResult) = ExplanationResponseDto(
            requestId = r.requestId,
            facilityId = r.facilityId,
            finalAction = r.finalAction,
            reasonCode = r.reasonCode,
            hitRuleId = r.hitRuleId,
            hitRuleVersion = r.hitRuleVersion,
            hitRuleLayer = r.hitRuleLayer,
            evaluatedAt = r.evaluatedAt,
            asOf = r.asOf,
            resultHash = r.resultHash,
            inputSnapshot = RiskInputDto(
                facilityId = r.inputSnapshot.facilityId,
                hourlyRainfallMm = r.inputSnapshot.hourlyRainfallMm,
                windLevel = r.inputSnapshot.windLevel,
                waterDepthCm = r.inputSnapshot.waterDepthCm,
                observedAt = r.inputSnapshot.observedAt,
                requestId = r.inputSnapshot.requestId
            ),
            explanationChain = r.explanationChain.map { ExplanationEntryDto.from(it) },
            summary = buildString {
                append("Evaluation for facility '${r.facilityId}' at ${r.evaluatedAt} ")
                append("(asOf=${r.asOf}): ")
                if (r.finalAction != null) {
                    append("final action = ${r.finalAction}, ")
                    append("hit by rule '${r.hitRuleId}' version ${r.hitRuleVersion} (${r.hitRuleLayer}). ")
                } else {
                    append("no action triggered (${r.reasonCode}). ")
                }
                append("Considered ${r.consideredRuleIds.size} rules, ")
                append("${r.explanationChain.count { it.matched }} matched. ")
                append("Result hash: ${r.resultHash}")
            }
        )
    }
}
