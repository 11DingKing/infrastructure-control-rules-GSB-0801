package com.gsb.infra.api

import com.gsb.infra.persistence.VersionConflictException
import com.gsb.infra.service.ControlService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.ruleRoutes(service: ControlService) {
    route("/api/rules") {
        get {
            call.respond(service.listRules().map { RuleDto.fromDomain(it) })
        }
        post {
            val dto = call.receive<RuleDto>()
            val saved = try {
                service.publishRule(dto.toDomain())
            } catch (e: VersionConflictException) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("VERSION_CONFLICT", e.message ?: "version conflict")
                )
                return@post
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("INVALID_RULE", e.message ?: "invalid rule")
                )
                return@post
            }
            call.respond(HttpStatusCode.Created, RuleDto.fromDomain(saved))
        }
        get("/facility/{facilityId}") {
            val facilityId = call.parameters["facilityId"].orEmpty()
            val rules = try {
                service.listRulesForFacility(facilityId)
            } catch (e: com.gsb.infra.service.FacilityNotFoundException) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("FACILITY_NOT_FOUND", e.message ?: "not found")
                )
                return@get
            }
            call.respond(rules.map { RuleDto.fromDomain(it) })
        }
    }
}
