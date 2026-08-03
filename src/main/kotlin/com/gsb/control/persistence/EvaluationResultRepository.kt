package com.gsb.control.persistence

import com.gsb.control.domain.Action
import com.gsb.control.domain.EvaluationResult
import com.gsb.control.domain.ReasonCode
import com.gsb.control.domain.RuleLayer
import com.gsb.control.domain.RuleTrace
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import java.time.Instant

/** A stored evaluation result with its surrogate id and digest. */
data class StoredEvaluation(
    val id: Int,
    val result: EvaluationResult,
    val canonicalDigest: String,
    val contentHash: String,
    val createdAt: Instant,
)

/**
 * Storage for [EvaluationResult]. This repository is a pure *consumer* of
 * evaluation output: it never re-runs judgement and never reads the rule store
 * to reach a decision. It records the decision, the input snapshot, the hit
 * rule version refs and the full explanation chain verbatim.
 */
class EvaluationResultRepository(private val db: Db) {

    @Serializable
    private data class BreakdownDto(
        val visibility: String,
        val scope: String,
        val window: String,
        val versionSelection: String,
        val condition: String,
        val missingMetric: String? = null,
        val priority: String,
    )

    @Serializable
    private data class TraceLineDto(
        val ruleKey: String,
        val version: Int,
        val versionRef: String,
        val layer: String,
        val action: String,
        val outcome: String,
        val decisive: Boolean,
        val detail: String,
        val breakdown: BreakdownDto,
    )

    fun save(result: EvaluationResult): StoredEvaluation = db.writeTx {
        val digest = Digest.sha256Hex(result.canonicalString())
        val createdAt = Instant.now()
        val newId = EvaluationResultsTable.insertAndGetId {
            it[facilityId] = result.facilityId
            it[decision] = result.decision?.name
            it[decidingLayer] = result.decidingLayer?.name
            it[decidingVersionRef] = result.decidingVersionRef
            it[inputSnapshotJson] = DomainCodec.encodeInput(result.input)
            it[evaluatedAt] = result.evaluatedAt
            it[asOf] = result.asOf
            it[firedVersionRefs] = DomainCodec.json.encodeToString(result.firedVersionRefs)
            it[explanationJson] = DomainCodec.json.encodeToString(result.trace.map { t -> t.toDto() })
            it[canonicalDigest] = digest
            it[contentHash] = result.contentHash
            it[EvaluationResultsTable.createdAt] = createdAt
        }.value
        StoredEvaluation(newId, result, digest, result.contentHash, createdAt)
    }

    fun findById(id: Int): StoredEvaluation? = db.tx {
        EvaluationResultsTable.selectAll().where { EvaluationResultsTable.id eq id }
            .firstOrNull()
            ?.toStored()
    }

    fun findByFacility(facilityId: String): List<StoredEvaluation> = db.tx {
        EvaluationResultsTable.selectAll().where { EvaluationResultsTable.facilityId eq facilityId }
            .orderBy(EvaluationResultsTable.createdAt to SortOrder.DESC)
            .map { it.toStored() }
    }

    private fun RuleTrace.toDto() = TraceLineDto(
        ruleKey = ruleKey,
        version = version,
        versionRef = versionRef,
        layer = layer.name,
        action = action.name,
        outcome = outcome.code,
        decisive = decisive,
        detail = detail,
        breakdown = BreakdownDto(
            visibility = breakdown.visibility.name,
            scope = breakdown.scope.name,
            window = breakdown.window.name,
            versionSelection = breakdown.versionSelection.name,
            condition = breakdown.condition.name,
            missingMetric = breakdown.missingMetric,
            priority = breakdown.priority.name,
        ),
    )

    private fun ResultRow.toStored(): StoredEvaluation {
        val traceDtos = DomainCodec.json.decodeFromString<List<TraceLineDto>>(this[EvaluationResultsTable.explanationJson])
        val trace = traceDtos.map { dto ->
            RuleTrace(
                ruleKey = dto.ruleKey,
                version = dto.version,
                versionRef = dto.versionRef,
                layer = RuleLayer.valueOf(dto.layer),
                action = Action.valueOf(dto.action),
                outcome = ReasonCode.entries.firstOrNull { it.code == dto.outcome }
                    ?: throw IllegalStateException("Unknown reason code in storage: ${dto.outcome}"),
                decisive = dto.decisive,
                detail = dto.detail,
                breakdown = com.gsb.control.domain.TraceBreakdown(
                    visibility = com.gsb.control.domain.Visibility.valueOf(dto.breakdown.visibility),
                    scope = com.gsb.control.domain.ScopeMatch.valueOf(dto.breakdown.scope),
                    window = com.gsb.control.domain.WindowState.valueOf(dto.breakdown.window),
                    versionSelection = com.gsb.control.domain.VersionSelection.valueOf(dto.breakdown.versionSelection),
                    condition = com.gsb.control.domain.ConditionState.valueOf(dto.breakdown.condition),
                    missingMetric = dto.breakdown.missingMetric,
                    priority = com.gsb.control.domain.PriorityResolution.valueOf(dto.breakdown.priority),
                ),
            )
        }
        val fired = DomainCodec.json.decodeFromString<List<String>>(this[EvaluationResultsTable.firedVersionRefs])
        val result = EvaluationResult(
            facilityId = this[EvaluationResultsTable.facilityId],
            decision = this[EvaluationResultsTable.decision]?.let { Action.valueOf(it) },
            decidingLayer = this[EvaluationResultsTable.decidingLayer]?.let { RuleLayer.valueOf(it) },
            decidingVersionRef = this[EvaluationResultsTable.decidingVersionRef],
            input = DomainCodec.decodeInput(this[EvaluationResultsTable.inputSnapshotJson]),
            evaluatedAt = this[EvaluationResultsTable.evaluatedAt],
            asOf = this[EvaluationResultsTable.asOf],
            firedVersionRefs = fired,
            trace = trace,
        )
        return StoredEvaluation(
            id = this[EvaluationResultsTable.id].value,
            result = result,
            canonicalDigest = this[EvaluationResultsTable.canonicalDigest],
            contentHash = this[EvaluationResultsTable.contentHash],
            createdAt = this[EvaluationResultsTable.createdAt],
        )
    }
}
