package com.gsb.infra.persistence

import com.gsb.infra.domain.Facility
import com.gsb.infra.domain.FacilityType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Clock
import java.time.Instant

class FacilityRepository(private val clock: Clock = Clock.systemUTC()) {

    fun upsert(facility: Facility): Facility = transaction {
        val existing = FacilitiesTable.selectAll()
            .where { FacilitiesTable.id eq facility.id }
            .singleOrNull()
        if (existing == null) {
            FacilitiesTable.insert {
                it[id] = facility.id
                it[type] = facility.type.name
                it[regionCode] = facility.regionCode
                it[name] = facility.name
                it[createdAt] = Instant.now(clock)
            }
        }
        facility
    }

    fun findById(id: String): Facility? = transaction {
        FacilitiesTable.selectAll().where { FacilitiesTable.id eq id }
            .singleOrNull()?.toFacility()
    }

    fun all(): List<Facility> = transaction {
        FacilitiesTable.selectAll().map { it.toFacility() }
    }

    private fun ResultRow.toFacility(): Facility = Facility(
        id = this[FacilitiesTable.id],
        type = FacilityType.valueOf(this[FacilitiesTable.type]),
        regionCode = this[FacilitiesTable.regionCode],
        name = this[FacilitiesTable.name]
    )
}
