package app.control.services

import app.control.db.Facilities
import app.control.domain.Facility
import app.control.domain.FacilityType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class FacilityService(private val clock: () -> Long) {

    fun create(id: String, type: FacilityType, region: String, name: String): Facility = transaction {
        if (Facilities.selectAll().where { Facilities.id eq id }.count() > 0) {
            throw ServiceException("FACILITY_EXISTS", 409, "facility '$id' already exists")
        }
        Facilities.insert {
            it[Facilities.id] = id
            it[Facilities.type] = type.name
            it[Facilities.region] = region
            it[Facilities.name] = name
            it[createdAt] = clock()
        }
        Facility(id = id, type = type, region = region, name = name)
    }

    fun get(id: String): Facility = transaction {
        Facilities.selectAll().where { Facilities.id eq id }.singleOrNull()?.toDomain()
            ?: throw ServiceException("FACILITY_NOT_FOUND", 404, "facility '$id' not found")
    }

    fun exists(id: String): Boolean = transaction {
        Facilities.selectAll().where { Facilities.id eq id }.count() > 0
    }

    fun isEmpty(): Boolean = transaction {
        Facilities.selectAll().count() == 0L
    }

    private fun ResultRow.toDomain(): Facility = Facility(
        id = this[Facilities.id],
        type = FacilityType.valueOf(this[Facilities.type]),
        region = this[Facilities.region],
        name = this[Facilities.name],
    )
}
