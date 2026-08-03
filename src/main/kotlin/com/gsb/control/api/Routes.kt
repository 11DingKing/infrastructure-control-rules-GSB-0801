package com.gsb.control.api

import com.gsb.control.app.ControlService
import com.gsb.control.app.ServiceError
import com.gsb.control.app.ServiceResult
import com.gsb.control.persistence.Digest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant

/** Wires all HTTP endpoints to the [ControlService]. */
fun Route.controlRoutes(service: ControlService, openApiYaml: String) {

    get("/health") {
        call.respondText("ok")
    }

    get("/openapi.yaml") {
        call.respondText(openApiYaml, io.ktor.http.ContentType.parse("application/yaml"))
    }

    // ---- Facilities ---------------------------------------------------------

    route("/facilities") {
        post {
            val req = call.receive<FacilityRequest>()
            when (val parsed = Mappers.toFacility(req)) {
                is Parsed.Ok -> {
                    service.upsertFacility(parsed.value)
                    call.respond(HttpStatusCode.Created, Mappers.facilityResponse(parsed.value))
                }
                is Parsed.Err -> call.respondError(HttpStatusCode.BadRequest, parsed.error)
            }
        }

        get("/{id}") {
            val id = call.parameters["id"].orEmpty()
            val f = service.facility(id)
            if (f == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "FACILITY_NOT_FOUND", id))
            } else {
                call.respond(Mappers.facilityResponse(f))
            }
        }
    }

    // ---- Rules --------------------------------------------------------------

    route("/rules") {
        post {
            val req = call.receive<RuleRequest>()
            when (val parsed = Mappers.toRule(req, Instant.now())) {
                is Parsed.Ok -> when (val res = service.publishRule(parsed.value)) {
                    is ServiceResult.Ok -> call.respond(HttpStatusCode.Created, Mappers.ruleResponse(res.value))
                    is ServiceResult.Err -> call.respondServiceError(res.error)
                }
                is Parsed.Err -> call.respondError(HttpStatusCode.BadRequest, parsed.error)
            }
        }

        get("/{ruleKey}") {
            val ruleKey = call.parameters["ruleKey"].orEmpty()
            val revisions = service.rulesForKey(ruleKey)
            call.respond(revisions.map { Mappers.ruleResponse(it) })
        }
    }

    // ---- Evaluate -----------------------------------------------------------

    post("/evaluate") {
        val req = call.receive<EvaluateRequest>()
        val input = when (val p = Mappers.toRiskInput(req.input)) {
            is Parsed.Ok -> p.value
            is Parsed.Err -> return@post call.respondError(HttpStatusCode.BadRequest, p.error)
        }
        val evaluatedAt = when (val p = optionalInstant(req.evaluatedAt)) {
            is Parsed.Ok -> p.value ?: Instant.now()
            is Parsed.Err -> return@post call.respondError(HttpStatusCode.BadRequest, p.error)
        }
        val asOf = when (val p = optionalInstant(req.asOf)) {
            is Parsed.Ok -> p.value
            is Parsed.Err -> return@post call.respondError(HttpStatusCode.BadRequest, p.error)
        }
        when (
            val res = service.evaluateAndRecord(
                facilityId = req.facilityId,
                input = input,
                evaluatedAt = evaluatedAt,
                asOf = asOf,
                persist = req.persist,
                notify = req.notify,
            )
        ) {
            is ServiceResult.Ok -> call.respond(
                Mappers.evaluationResponse(res.value.result, res.value.storedId, res.value.canonicalDigest),
            )
            is ServiceResult.Err -> call.respondServiceError(res.error)
        }
    }

    // ---- Batch evaluate (reproducible benchmark baseline) -------------------

    post("/evaluate/batch") {
        val req = call.receive<BatchEvaluateRequest>()
        val evaluatedAt = when (val p = optionalInstant(req.evaluatedAt)) {
            is Parsed.Ok -> p.value ?: Instant.now()
            is Parsed.Err -> return@post call.respondError(HttpStatusCode.BadRequest, p.error)
        }
        val asOf = when (val p = optionalInstant(req.asOf)) {
            is Parsed.Ok -> p.value
            is Parsed.Err -> return@post call.respondError(HttpStatusCode.BadRequest, p.error)
        }

        val responses = ArrayList<EvaluationResponse>(req.inputs.size)
        val digests = ArrayList<String>(req.inputs.size)
        for (raw in req.inputs) {
            val input = when (val p = Mappers.toRiskInput(raw)) {
                is Parsed.Ok -> p.value
                is Parsed.Err -> return@post call.respondError(HttpStatusCode.BadRequest, p.error)
            }
            // Batch runs through the identical pure domain path; no shortcut.
            when (
                val res = service.evaluateAndRecord(
                    facilityId = req.facilityId,
                    input = input,
                    evaluatedAt = evaluatedAt,
                    asOf = asOf,
                    persist = false,
                    notify = false,
                )
            ) {
                is ServiceResult.Ok -> {
                    val digest = Digest.sha256Hex(res.value.result.canonicalString())
                    digests.add(digest)
                    responses.add(Mappers.evaluationResponse(res.value.result, null, digest))
                }
                is ServiceResult.Err -> return@post call.respondServiceError(res.error)
            }
        }
        val batchDigest = Digest.sha256Hex(digests.joinToString("\n"))
        call.respond(
            BatchEvaluationResponse(
                facilityId = req.facilityId,
                count = responses.size,
                results = responses,
                batchDigest = batchDigest,
            ),
        )
    }

    // ---- Results ------------------------------------------------------------

    route("/results") {
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", "BAD_VALUE", "id must be an integer"))
                return@get
            }
            val stored = service.result(id)
            if (stored == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "RESULT_NOT_FOUND", id.toString()))
            } else {
                call.respond(Mappers.evaluationResponse(stored.result, stored.id, stored.canonicalDigest))
            }
        }

        // Explanation chain only, for auditing.
        get("/{id}/explanation") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", "BAD_VALUE", "id must be an integer"))
                return@get
            }
            val stored = service.result(id)
            if (stored == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "RESULT_NOT_FOUND", id.toString()))
            } else {
                val resp = Mappers.evaluationResponse(stored.result, stored.id, stored.canonicalDigest)
                call.respond(resp.explanation)
            }
        }
    }

    get("/facilities/{id}/results") {
        val id = call.parameters["id"].orEmpty()
        val list = service.resultsForFacility(id)
        call.respond(list.map { Mappers.evaluationResponse(it.result, it.id, it.canonicalDigest) })
    }
}

private fun optionalInstant(text: String?): Parsed<Instant?> {
    if (text == null) return Parsed.Ok(null)
    return when (val p = Mappers.parseInstant(text)) {
        is Parsed.Ok -> Parsed.Ok(p.value)
        is Parsed.Err -> p
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondError(
    status: HttpStatusCode,
    error: ParseError,
) {
    respond(status, ErrorResponse(error = "VALIDATION_ERROR", reason = error.reason, detail = error.detail))
}

private suspend fun io.ktor.server.application.ApplicationCall.respondServiceError(error: ServiceError) {
    when (error) {
        is ServiceError.FacilityNotFound -> respond(
            HttpStatusCode.NotFound,
            ErrorResponse("NOT_FOUND", "FACILITY_NOT_FOUND", error.facilityId),
        )
        is ServiceError.VersionConflict -> respond(
            HttpStatusCode.Conflict,
            ErrorResponse("CONFLICT", "RULE_VERSION_CONFLICT", "${error.ruleKey}:v${error.version}"),
        )
    }
}
