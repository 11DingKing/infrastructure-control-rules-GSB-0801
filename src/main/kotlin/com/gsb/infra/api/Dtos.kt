package com.gsb.infra.api

import com.gsb.infra.domain.Action
import com.gsb.infra.domain.Condition
import com.gsb.infra.domain.EvaluationResult
import com.gsb.infra.domain.Facility
import com.gsb.infra.domain.FacilityType
import com.gsb.infra.domain.LayerDecision
import com.gsb.infra.domain.Metric
import com.gsb.infra.domain.Operator
import com.gsb.infra.domain.ReasonCode
import com.gsb.infra.domain.RiskInput
import com.gsb.infra.domain.Rule
import com.gsb.infra.domain.RuleLayer
import com.gsb.infra.domain.RuleTrace
import com.gsb.infra.persistence.StoredEvaluation
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class FacilityDto(
    val id: String,
    val type: String,
    val regionCode: String,
    val name: String
) {
    fun toDomain(): Facility = Facility(
        id = id,
        type = FacilityType.valueOf(type),
        regionCode = regionCode,
        name = name
    )

    companion object {
        fun fromDomain(f: Facility): FacilityDto =
            FacilityDto(f.id, f.type.name, f.regionCode, f.name)
    }
}

@Serializable
data class ConditionDto(
    val metric: String,
    val operator: String,
    val threshold: Double
) {
    fun toDomain(): Condition {
        val m = Metric.fromKey(metric)
            ?: throw IllegalArgumentException("Unknown metric: $metric")
        return Condition(m, Operator.valueOf(operator), threshold)
    }

    companion object {
        fun fromDomain(c: Condition): ConditionDto =
            ConditionDto(c.metric.key, c.operator.name, c.threshold)
    }
}

@Serializable
data class RuleDto(
    val ruleId: String,
    val version: Int = 0,
    val layer: String,
    val facilityId: String? = null,
    val regionCode: String? = null,
    val facilityTypes: List<String> = emptyList(),
    val conditions: List<ConditionDto>,
    val action: String,
    val reason: String,
    val effectiveFrom: String,
    val expiresAt: String? = null,
    val publishedAt: String? = null
) {
    fun toDomain(): Rule = Rule(
        ruleId = ruleId,
        version = if (version <= 0) 1 else version,
        layer = RuleLayer.valueOf(layer),
        facilityId = facilityId,
        regionCode = regionCode,
        facilityTypes = facilityTypes.map { FacilityType.valueOf(it) }.toSet(),
        conditions = conditions.map { it.toDomain() },
        action = Action.valueOf(action),
        reason = reason,
        effectiveFrom = Instant.parse(effectiveFrom),
        expiresAt = expiresAt?.let { Instant.parse(it) },
        publishedAt = publishedAt?.let { Instant.parse(it) } ?: Instant.now()
    )

    companion object {
        fun fromDomain(r: Rule): RuleDto = RuleDto(
            ruleId = r.ruleId,
            version = r.version,
            layer = r.layer.name,
            facilityId = r.facilityId,
            regionCode = r.regionCode,
            facilityTypes = r.facilityTypes.map { it.name },
            conditions = r.conditions.map { ConditionDto.fromDomain(it) },
            action = r.action.name,
            reason = r.reason,
            effectiveFrom = r.effectiveFrom.toString(),
            expiresAt = r.expiresAt?.toString(),
            publishedAt = r.publishedAt.toString()
        )
    }
}

@Serializable
data class EvaluateRequest(
    val facilityId: String,
    val values: Map<String, Double>,
    val evaluatedAt: String? = null,
    val asOf: String? = null
)

@Serializable
data class BatchEvaluateRequest(
    val facilityIds: List<String>,
    val values: Map<String, Double>,
    val evaluatedAt: String? = null,
    val asOf: String? = null
)

@Serializable
data class StoredEvaluationDto(
    val id: Long,
    val snapshotSha256: String,
    val result: EvaluationResult
) {
    companion object {
        fun fromDomain(s: StoredEvaluation): StoredEvaluationDto =
            StoredEvaluationDto(s.id, s.snapshotSha256, s.result)
    }
}

@Serializable
data class ErrorResponse(val reasonCode: String, val message: String)

@Serializable
data class ExplanationDto(
    val id: Long,
    val facilityId: String,
    val finalAction: Action,
    val winningRuleId: String? = null,
    val winningVersion: Int? = null,
    val winningLayer: RuleLayer? = null,
    val reasonCode: ReasonCode,
    val evaluatedAt: String,
    val asOf: String,
    val inputSnapshot: Map<String, Double>,
    val contentHash: String,
    val explanation: String,
    val layerDecisions: List<LayerDecision>,
    val ruleTraces: List<RuleTrace>
) {
    companion object {
        fun fromStored(id: Long, result: EvaluationResult): ExplanationDto = ExplanationDto(
            id = id,
            facilityId = result.facilityId,
            finalAction = result.finalAction,
            winningRuleId = result.winningRuleId,
            winningVersion = result.winningVersion,
            winningLayer = result.winningLayer,
            reasonCode = result.reasonCode,
            evaluatedAt = result.evaluatedAt,
            asOf = result.asOf,
            inputSnapshot = result.inputSnapshot,
            contentHash = result.contentHash,
            explanation = result.explanation,
            layerDecisions = result.layerDecisions,
            ruleTraces = result.ruleTraces
        )
    }
}
