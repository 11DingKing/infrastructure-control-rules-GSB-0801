package app.control.services

import app.control.db.Notifications
import app.control.domain.EvaluationResult
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

data class StoredEvaluation(
    val id: String,
    val result: EvaluationResult,
    val canonicalJson: String,
    val contentHash: String,
    val createdAt: Long,
)

data class NotificationRecord(
    val id: Long,
    val evaluationId: String,
    val facilityId: String,
    val action: String?,
    val channel: String,
    val payload: String,
    val sentAt: Long,
)

/**
 * 通知出口：只消费求值结果（持久化之后调用），绝不参与判定。
 */
interface NotificationSink {
    fun onEvaluation(evaluation: StoredEvaluation)
}

/** 落库型通知：payload 为确定性摘要，便于核对与重放。 */
class TableNotificationSink(private val clock: () -> Long) : NotificationSink {

    override fun onEvaluation(evaluation: StoredEvaluation) {
        val decision = evaluation.result.decision
        val payload = buildString {
            append("facility=").append(evaluation.result.facilityId)
            append(" action=").append(decision?.action?.name ?: "NONE")
            append(" rule=")
            append(decision?.rule?.let { ref -> "${ref.ruleId}@${ref.version}" } ?: "-")
            append(" hash=").append(evaluation.contentHash)
        }
        transaction {
            Notifications.insert {
                it[evaluationId] = evaluation.id
                it[facilityId] = evaluation.result.facilityId
                it[action] = decision?.action?.name
                it[channel] = "control-log"
                it[Notifications.payload] = payload
                it[sentAt] = clock()
            }
        }
    }
}

class NotificationQueryService {
    fun listByFacility(facilityId: String): List<NotificationRecord> = transaction {
        Notifications.selectAll()
            .where { Notifications.facilityId eq facilityId }
            .orderBy(Notifications.id)
            .map { it.toRecord() }
    }

    private fun ResultRow.toRecord(): NotificationRecord = NotificationRecord(
        id = this[Notifications.id],
        evaluationId = this[Notifications.evaluationId],
        facilityId = this[Notifications.facilityId],
        action = this[Notifications.action],
        channel = this[Notifications.channel],
        payload = this[Notifications.payload],
        sentAt = this[Notifications.sentAt],
    )
}
