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
import java.time.Instant

fun Route.evaluationRoutes(service: ControlService) {
    route("/api/evaluations") {
        post {
            val req = call.receive<EvaluateRequest>()
            val input = RiskInputParser.parse(req.values)
            val at = req.evaluatedAt?.let { Instant.parse(it) } ?: Instant.now()
            val asOf = req.asOf?.let { Instant.parse(it) } ?: at
            val stored = try {
                service.evaluate(req.facilityId, input, at, asOf)
            } catch (e: FacilityNotFoundException) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("FACILITY_NOT_FOUND", e.message ?: "not found")
                )
                return@post
            }
            call.respond(HttpStatusCode.Created, StoredEvaluationDto.fromDomain(stored))
        }

        post("/batch") {
            val req = call.receive<BatchEvaluateRequest>()
            val input = RiskInputParser.parse(req.values)
            val at = req.evaluatedAt?.let { Instant.parse(it) } ?: Instant.now()
            val asOf = req.asOf?.let { Instant.parse(it) } ?: at
            val results = try {
                service.evaluateBatch(req.facilityIds, input, at, asOf)
            } catch (e: FacilityNotFoundException) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("FACILITY_NOT_FOUND", e.message ?: "not found")
                )
                return@post
            }
            call.respond(HttpStatusCode.Created, results.map { StoredEvaluationDto.fromDomain(it) })
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("INVALID_ID", "evaluation id must be a number")
                )
                return@get
            }
            val stored = service.findResult(id)
            if (stored == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("RESULT_NOT_FOUND", "no evaluation result for id $id")
                )
                return@get
            }
            call.respond(StoredEvaluationDto.fromDomain(stored))
        }

        get("/{id}/explanation") {
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("INVALID_ID", "evaluation id must be a number")
                )
                return@get
            }
            val stored = service.findResult(id)
            if (stored == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("RESULT_NOT_FOUND", "no evaluation result for id $id")
                )
                return@get
            }
            call.respond(ExplanationDto.fromStored(stored.id, stored.result))
        }

        get("/facility/{facilityId}/history") {
            val facilityId = call.parameters["facilityId"].orEmpty()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val history = service.history(facilityId, limit)
            call.respond(history.map { StoredEvaluationDto.fromDomain(it) })
        }
    }
}
