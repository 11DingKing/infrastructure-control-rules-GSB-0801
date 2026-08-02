package com.infra.domain

import kotlinx.serialization.Serializable

@Serializable
data class ExplanationEntry(
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
)

@Serializable
data class EvaluationResult(
    val requestId: String,
    val facilityId: String,
    val finalAction: Action?,
    val reasonCode: ReasonCode,
    val hitRuleId: String?,
    val hitRuleVersion: Int?,
    val hitRuleLayer: RuleLayer?,
    val inputSnapshot: RiskInput,
    val evaluatedAt: Long,
    val asOf: Long,
    val explanationChain: List<ExplanationEntry>,
    val consideredRuleIds: List<String>,
    val resultHash: String
) {
    companion object {
        fun computeHash(
            requestId: String,
            facilityId: String,
            finalAction: Action?,
            reasonCode: ReasonCode,
            hitRuleId: String?,
            hitRuleVersion: Int?,
            hitRuleLayer: RuleLayer?,
            inputSnapshot: RiskInput,
            evaluatedAt: Long,
            asOf: Long,
            consideredRuleIds: List<String>
        ): String {
            val raw = buildString {
                append(requestId).append('|')
                append(facilityId).append('|')
                append(finalAction?.name ?: "null").append('|')
                append(reasonCode.name).append('|')
                append(hitRuleId ?: "null").append('|')
                append(hitRuleVersion?.toString() ?: "null").append('|')
                append(hitRuleLayer?.name ?: "null").append('|')
                append(inputSnapshot.snapshotKey()).append('|')
                append(evaluatedAt).append('|')
                append(asOf).append('|')
                append(consideredRuleIds.sorted().joinToString(","))
            }
            val bytes = raw.encodeToByteArray()
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
