package com.gsb.infra.persistence

import com.gsb.infra.domain.EvaluationResult
import com.gsb.infra.domain.RiskInput
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

data class StoredEvaluation(
    val id: Long,
    val result: EvaluationResult,
    val snapshotSha256: String
)

class EvaluationRepository(private val clock: Clock = Clock.systemUTC()) {

    fun save(result: EvaluationResult, input: RiskInput): StoredEvaluation = transaction {
        val snapshotJson = PersistenceJson.encodeSnapshot(input)
        val sha = sha256(snapshotJson)

        val existingSnapshot = InputSnapshotsTable.selectAll()
            .where { InputSnapshotsTable.sha256 eq sha }
            .singleOrNull()
        val snapshotId: Int = existingSnapshot?.get(InputSnapshotsTable.id)?.value
            ?: InputSnapshotsTable.insertAndGetId {
                it[InputSnapshotsTable.facilityId] = result.facilityId
                it[InputSnapshotsTable.snapshotJson] = snapshotJson
                it[InputSnapshotsTable.sha256] = sha
                it[InputSnapshotsTable.createdAt] = Instant.now(clock)
            }.value

        val resultId: Int = EvaluationResultsTable.insertAndGetId {
            it[EvaluationResultsTable.facilityId] = result.facilityId
            it[EvaluationResultsTable.facilityType] = result.facilityType.name
            it[EvaluationResultsTable.regionCode] = result.regionCode
            it[EvaluationResultsTable.evaluatedAt] = Instant.parse(result.evaluatedAt)
            it[EvaluationResultsTable.asOf] = Instant.parse(result.asOf)
            it[EvaluationResultsTable.finalAction] = result.finalAction.name
            it[EvaluationResultsTable.winningRuleId] = result.winningRuleId
            it[EvaluationResultsTable.winningVersion] = result.winningVersion
            it[EvaluationResultsTable.winningLayer] = result.winningLayer?.name
            it[EvaluationResultsTable.reasonCode] = result.reasonCode.name
            it[EvaluationResultsTable.snapshotId] = snapshotId
            it[EvaluationResultsTable.resultJson] = PersistenceJson.encodeResult(result)
            it[EvaluationResultsTable.createdAt] = Instant.now(clock)
        }.value

        StoredEvaluation(resultId.toLong(), result, sha)
    }

    fun findById(id: Long): StoredEvaluation? = transaction {
        EvaluationResultsTable.join(
            InputSnapshotsTable,
            JoinType.INNER,
            onColumn = EvaluationResultsTable.snapshotId,
            otherColumn = InputSnapshotsTable.id
        )
            .selectAll()
            .where { EvaluationResultsTable.id eq EntityID(id.toInt(), EvaluationResultsTable) }
            .singleOrNull()
            ?.toStoredEvaluation()
    }

    fun historyForFacility(facilityId: String, limit: Int = 50): List<StoredEvaluation> =
        transaction {
            EvaluationResultsTable.join(
                InputSnapshotsTable,
                JoinType.INNER,
                onColumn = EvaluationResultsTable.snapshotId,
                otherColumn = InputSnapshotsTable.id
            )
                .selectAll()
                .where { EvaluationResultsTable.facilityId eq facilityId }
                .orderBy(EvaluationResultsTable.evaluatedAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toStoredEvaluation() }
        }

    private fun ResultRow.toStoredEvaluation(): StoredEvaluation {
        val result = PersistenceJson.decodeResult(this[EvaluationResultsTable.resultJson])
        return StoredEvaluation(
            id = this[EvaluationResultsTable.id].value.toLong(),
            result = result,
            snapshotSha256 = this[InputSnapshotsTable.sha256]
        )
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
