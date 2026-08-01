package app.control.services

import app.control.db.RiskInputs
import app.control.domain.RiskSnapshot
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class RiskInputService(private val clock: () -> Long) {

    fun ingest(
        facilityId: String,
        observedAt: Long,
        precipitationMm: Double?,
        windLevel: Int?,
        waterDepthCm: Double?,
    ): Long = transaction {
        RiskInputs.insert {
            it[RiskInputs.facilityId] = facilityId
            it[RiskInputs.observedAt] = observedAt
            it[RiskInputs.precipitationMm] = precipitationMm
            it[RiskInputs.windLevel] = windLevel
            it[RiskInputs.waterDepthCm] = waterDepthCm
            it[receivedAt] = clock()
        } get RiskInputs.id
    }

    fun get(id: Long): Pair<Long, RiskSnapshot> = transaction {
        RiskInputs.selectAll().where { RiskInputs.id eq id }.singleOrNull()?.let { row ->
            row[RiskInputs.id] to row.toDomain()
        } ?: throw ServiceException("INPUT_NOT_FOUND", 404, "risk input '$id' not found")
    }

    private fun ResultRow.toDomain(): RiskSnapshot = RiskSnapshot(
        facilityId = this[RiskInputs.facilityId],
        observedAt = this[RiskInputs.observedAt],
        precipitationMm = this[RiskInputs.precipitationMm],
        windLevel = this[RiskInputs.windLevel],
        waterDepthCm = this[RiskInputs.waterDepthCm],
    )
}
