package app.control.services

import app.control.db.Batches
import java.util.UUID
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

data class BatchItem(
    val facilityId: String,
    val inputId: Long,
    val now: Long? = null,
    val asOf: Long? = null,
)

data class StoredBatch(
    val id: String,
    val createdAt: Long,
    val itemCount: Int,
    val durationMs: Long,
    val evaluationIds: List<String>,
    val contentHashes: List<String>,
)

/**
 * 批量求值：逐项走与单次求值完全相同的服务路径（不绕过领域层），
 * 并落库可复现的基准数据（每项结果的内容指纹）。
 */
class BatchService(
    private val evaluationService: EvaluationService,
    private val clock: () -> Long,
) {

    private val json = Json { encodeDefaults = true }

    fun run(items: List<BatchItem>): StoredBatch {
        if (items.isEmpty()) {
            throw ServiceException("EMPTY_BATCH", 422, "batch requires at least one item")
        }
        val started = System.nanoTime()
        val evaluations = items.map { item ->
            evaluationService.evaluate(item.facilityId, item.inputId, item.now, item.asOf)
        }
        val durationMs = (System.nanoTime() - started) / 1_000_000

        val batch = StoredBatch(
            id = UUID.randomUUID().toString(),
            createdAt = clock(),
            itemCount = evaluations.size,
            durationMs = durationMs,
            evaluationIds = evaluations.map { it.id },
            contentHashes = evaluations.map { it.contentHash },
        )
        transaction {
            Batches.insert {
                it[Batches.id] = batch.id
                it[Batches.createdAt] = batch.createdAt
                it[Batches.itemCount] = batch.itemCount
                it[Batches.durationMs] = batch.durationMs
                it[Batches.evaluationIds] = json.encodeToString(batch.evaluationIds)
                it[Batches.contentHashes] = json.encodeToString(batch.contentHashes)
            }
        }
        return batch
    }

    fun get(id: String): StoredBatch = transaction {
        val row = Batches.selectAll().where { Batches.id eq id }.singleOrNull()
            ?: throw ServiceException("BATCH_NOT_FOUND", 404, "batch '$id' not found")
        StoredBatch(
            id = row[Batches.id],
            createdAt = row[Batches.createdAt],
            itemCount = row[Batches.itemCount],
            durationMs = row[Batches.durationMs],
            evaluationIds = json.decodeFromString(row[Batches.evaluationIds]),
            contentHashes = json.decodeFromString(row[Batches.contentHashes]),
        )
    }
}
