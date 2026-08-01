package app.control.services

import app.control.db.Evaluations
import app.control.domain.Canonical
import app.control.domain.EvaluationResult
import app.control.domain.RuleEngine
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 求值编排：装载设施 / 输入 / 规则 → 调用纯引擎 → 持久化结果 → 通知消费。
 * 判定本身完全发生在 [RuleEngine] 内，持久化与通知只消费结果。
 */
class EvaluationService(
    private val facilityService: FacilityService,
    private val riskInputService: RiskInputService,
    private val ruleService: RuleService,
    private val notificationSink: NotificationSink,
    private val clock: () -> Long,
) {

    fun evaluate(
        facilityId: String,
        inputId: Long,
        now: Long? = null,
        asOf: Long? = null,
    ): StoredEvaluation {
        val facility = facilityService.get(facilityId)
        val (storedInputId, snapshot) = riskInputService.get(inputId)
        if (snapshot.facilityId != facilityId) {
            throw ServiceException(
                "INPUT_FACILITY_MISMATCH", 422,
                "risk input '$inputId' belongs to '${snapshot.facilityId}', not '$facilityId'",
            )
        }

        val effectiveNow = now ?: clock()
        val effectiveAsOf = asOf ?: effectiveNow

        // —— 纯领域求值：无副作用 ——
        val result = RuleEngine.evaluate(
            facility = facility,
            rules = ruleService.loadAll(),
            input = snapshot,
            now = effectiveNow,
            asOf = effectiveAsOf,
        )

        // —— 以下只消费结果，不参与判定 ——
        val canonicalJson = Canonical.stringOf(result)
        val contentHash = Canonical.hashOf(result)
        val id = UUID.randomUUID().toString()
        val createdAt = clock()
        transaction {
            Evaluations.insert {
                it[Evaluations.id] = id
                it[Evaluations.facilityId] = facilityId
                it[Evaluations.inputId] = storedInputId
                it[Evaluations.now] = effectiveNow
                it[Evaluations.asOf] = effectiveAsOf
                it[decisionAction] = result.decision?.action?.name
                it[decisionRuleId] = result.decision?.rule?.ruleId
                it[decisionRuleVersion] = result.decision?.rule?.version
                it[reasonCodes] = result.reasonCodes.joinToString(",")
                it[Evaluations.canonicalJson] = canonicalJson
                it[Evaluations.contentHash] = contentHash
                it[Evaluations.createdAt] = createdAt
            }
        }
        val stored = StoredEvaluation(
            id = id,
            result = result,
            canonicalJson = canonicalJson,
            contentHash = contentHash,
            createdAt = createdAt,
        )
        notificationSink.onEvaluation(stored)
        return stored
    }

    fun get(id: String): StoredEvaluation = transaction {
        Evaluations.selectAll().where { Evaluations.id eq id }.singleOrNull()?.toStored()
            ?: throw ServiceException("EVALUATION_NOT_FOUND", 404, "evaluation '$id' not found")
    }

    fun listByFacility(facilityId: String): List<StoredEvaluation> = transaction {
        Evaluations.selectAll()
            .where { Evaluations.facilityId eq facilityId }
            .orderBy(Evaluations.createdAt to SortOrder.ASC, Evaluations.id to SortOrder.ASC)
            .map { it.toStored() }
    }

    private fun ResultRow.toStored(): StoredEvaluation {
        val canonical = this[Evaluations.canonicalJson]
        return StoredEvaluation(
            id = this[Evaluations.id],
            result = Canonical.parse(canonical),
            canonicalJson = canonical,
            contentHash = this[Evaluations.contentHash],
            createdAt = this[Evaluations.createdAt],
        )
    }
}
