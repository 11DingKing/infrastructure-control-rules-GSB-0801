package com.gsb.control.persistence

import com.gsb.control.domain.Facility
import com.gsb.control.domain.FacilityKind
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert

/** Storage for [Facility] records. */
class FacilityRepository(private val db: Db) {

    fun upsert(facility: Facility) = db.writeTx {
        FacilitiesTable.upsert {
            it[id] = facility.id
            it[kind] = facility.kind.code
            it[regionCode] = facility.regionCode
            it[name] = facility.name
        }
        Unit
    }

    fun findById(id: String): Facility? = db.tx {
        FacilitiesTable.selectAll().where { FacilitiesTable.id eq id }
            .firstOrNull()
            ?.toFacility()
    }

    fun all(): List<Facility> = db.tx {
        FacilitiesTable.selectAll().map { it.toFacility() }
    }

    private fun ResultRow.toFacility(): Facility {
        val kindCode = this[FacilitiesTable.kind]
        val kind = FacilityKind.entries.firstOrNull { it.code == kindCode }
            ?: throw IllegalStateException("Unknown facility kind in storage: $kindCode")
        return Facility(
            id = this[FacilitiesTable.id],
            kind = kind,
            regionCode = this[FacilitiesTable.regionCode],
            name = this[FacilitiesTable.name],
        )
    }
}
