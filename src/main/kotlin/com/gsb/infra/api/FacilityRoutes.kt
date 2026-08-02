package com.gsb.infra.api

import com.gsb.infra.service.ControlService
import com.gsb.infra.service.FacilityNotFoundException
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.facilityRoutes(service: ControlService) {
    route("/api/facilities") {
        get {
            call.respond(service.listFacilities().map { FacilityDto.fromDomain(it) })
        }
        post {
            val dto = call.receive<FacilityDto>()
            val saved = service.registerFacility(dto.toDomain())
            call.respond(HttpStatusCode.Created, FacilityDto.fromDomain(saved))
        }
        get("/{id}") {
            val id = call.parameters["id"].orEmpty()
            val facility = service.findFacility(id)
                ?: throw FacilityNotFoundException(id)
            call.respond(FacilityDto.fromDomain(facility))
        }
    }
}
