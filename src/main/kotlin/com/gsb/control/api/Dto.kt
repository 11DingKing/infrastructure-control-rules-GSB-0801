package com.gsb.control.api

import kotlinx.serialization.Serializable

// ---- Requests ---------------------------------------------------------------

@Serializable
data class FacilityRequest(
    val id: String,
    val kind: String,
    val regionCode: String,
    val name: String,
)

@Serializable
data class ConditionDto(
    val type: String,                        // "threshold" | "always" | "and" | "or"
    val metric: String? = null,              // threshold
    val comparator: String? = null,          // threshold: GTE|GT|LTE|LT|EQ
    val threshold: Double? = null,           // threshold
    val terms: List<ConditionDto>? = null,   // and | or
)

@Serializable
data class RuleRequest(
    val ruleKey: String,
    val version: Int,
    val scopeKind: String,                   // "global" | "region" | "facility" | "manual"
    val scopeArg: String? = null,
    val action: String,                      // MONITOR | RESTRICT | CLOSE
    val condition: ConditionDto,
    val validFrom: String,                   // ISO-8601 instant
    val validUntil: String? = null,
    val publishedAt: String? = null,
    val description: String = "",
)

@Serializable
data class EvaluateRequest(
    val facilityId: String,
    val input: Map<String, Double>,
    val evaluatedAt: String? = null,
    val asOf: String? = null,
    val persist: Boolean = true,
    val notify: Boolean = true,
)

@Serializable
data class BatchEvaluateRequest(
    val facilityId: String,
    val inputs: List<Map<String, Double>>,
    val evaluatedAt: String? = null,
    val asOf: String? = null,
)

// ---- Responses --------------------------------------------------------------

@Serializable
data class FacilityResponse(
    val id: String,
    val kind: String,
    val regionCode: String,
    val name: String,
)

@Serializable
data class RuleResponse(
    val ruleKey: String,
    val version: Int,
    val versionRef: String,
    val layer: String,
    val scopeKind: String,
    val scopeArg: String? = null,
    val action: String,
    val validFrom: String,
    val validUntil: String? = null,
    val publishedAt: String,
    val description: String,
)

@Serializable
data class TraceBreakdownResponse(
    val visibility: String,
    val scope: String,
    val window: String,
    val versionSelection: String,
    val condition: String,
    val missingMetric: String? = null,
    val priority: String,
)

@Serializable
data class TraceLineResponse(
    val versionRef: String,
    val ruleKey: String,
    val version: Int,
    val layer: String,
    val action: String,
    val outcome: String,
    val decisive: Boolean,
    val detail: String,
    val breakdown: TraceBreakdownResponse,
)

@Serializable
data class EvaluationResponse(
    val storedId: Int? = null,
    val facilityId: String,
    val decision: String? = null,
    val decidingLayer: String? = null,
    val decidingVersionRef: String? = null,
    val input: Map<String, Double>,
    val evaluatedAt: String,
    val asOf: String,
    val firedVersionRefs: List<String>,
    val canonicalDigest: String? = null,
    val contentHash: String,
    val explanation: List<TraceLineResponse>,
)

@Serializable
data class BatchEvaluationResponse(
    val facilityId: String,
    val count: Int,
    val results: List<EvaluationResponse>,
    /** Deterministic digest over the ordered per-input digests — reproducible benchmark baseline. */
    val batchDigest: String,
)

@Serializable
data class ErrorResponse(
    val error: String,
    val reason: String,
    val detail: String? = null,
)
