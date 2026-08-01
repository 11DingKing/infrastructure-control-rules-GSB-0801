package app.control.http

import app.control.domain.EvaluationResult
import kotlinx.serialization.Serializable

@Serializable
data class ErrorBody(val code: String, val message: String)

@Serializable
data class ErrorResponse(val error: ErrorBody)

@Serializable
data class CreateFacilityRequest(
    val id: String,
    val type: String,
    val region: String,
    val name: String,
)

@Serializable
data class FacilityResponse(
    val id: String,
    val type: String,
    val region: String,
    val name: String,
)

/** 所有时间字段均为 epoch millis。 */
@Serializable
data class PublishRuleRequest(
    val ruleId: String,
    val version: Int,
    val tier: String,
    val scopeKey: String? = null,
    val facilityType: String? = null,
    val precipitationMmAtLeast: Double? = null,
    val windLevelAtLeast: Int? = null,
    val waterDepthCmAtLeast: Double? = null,
    val action: String,
    val effectiveFrom: Long,
    val effectiveTo: Long? = null,
)

@Serializable
data class PublishRuleResponse(
    val code: String,
    val ruleId: String? = null,
    val version: Int? = null,
    val publishedAt: Long? = null,
)

@Serializable
data class IngestRiskInputRequest(
    val facilityId: String,
    val observedAt: Long,
    val precipitationMm: Double? = null,
    val windLevel: Int? = null,
    val waterDepthCm: Double? = null,
)

@Serializable
data class IngestRiskInputResponse(val id: Long)

@Serializable
data class EvaluateRequest(
    val facilityId: String,
    val inputId: Long,
    val now: Long? = null,
    val asOf: Long? = null,
)

@Serializable
data class EvaluationResponse(
    val id: String,
    val contentHash: String,
    val createdAt: Long,
    val result: EvaluationResult,
)

@Serializable
data class EvaluationSummary(
    val id: String,
    val decisionAction: String? = null,
    val decisionRuleId: String? = null,
    val decisionRuleVersion: Int? = null,
    val contentHash: String,
    val createdAt: Long,
)

@Serializable
data class BatchItemRequest(
    val facilityId: String,
    val inputId: Long,
    val now: Long? = null,
    val asOf: Long? = null,
)

@Serializable
data class RunBatchRequest(val items: List<BatchItemRequest>)

@Serializable
data class BatchResponse(
    val id: String,
    val createdAt: Long,
    val itemCount: Int,
    val durationMs: Long,
    val evaluationIds: List<String>,
    val contentHashes: List<String>,
)

@Serializable
data class NotificationResponse(
    val id: Long,
    val evaluationId: String,
    val facilityId: String,
    val action: String? = null,
    val channel: String,
    val payload: String,
    val sentAt: Long,
)
