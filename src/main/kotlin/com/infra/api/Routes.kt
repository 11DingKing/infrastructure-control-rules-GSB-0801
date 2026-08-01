package com.infra.api

import com.infra.domain.RiskInput
import com.infra.engine.RuleEngine
import com.infra.notification.NotificationService
import com.infra.persistence.Repository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID

fun Application.configureRoutes(
    repository: Repository,
    notificationService: NotificationService
) {
    routing {
        route("/api/facilities") {
            get {
                val facilities = repository.listFacilities()
                call.respond(facilities.map { FacilityDto.from(it) })
            }

            post {
                val req = call.receive<CreateFacilityRequest>()
                val facility = com.infra.domain.Facility(
                    id = req.id,
                    name = req.name,
                    type = req.type,
                    regionCode = req.regionCode,
                    location = req.location
                )
                val created = repository.createFacility(facility)
                call.respond(HttpStatusCode.Created, FacilityDto.from(created))
            }

            get("/{id}") {
                val id = call.parameters["id"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Missing facility id")
                )
                val facility = repository.getFacility(id)
                if (facility == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Facility not found", "FACILITY_NOT_FOUND"))
                } else {
                    call.respond(FacilityDto.from(facility))
                }
            }
        }

        route("/api/rules") {
            get {
                val layer = call.request.queryParameters["layer"]?.let {
                    runCatching { com.infra.domain.RuleLayer.valueOf(it) }.getOrNull()
                }
                val facilityId = call.request.queryParameters["facilityId"]
                val regionCode = call.request.queryParameters["regionCode"]
                val rules = repository.listRules(layer, facilityId, regionCode)
                call.respond(rules.map { RuleDto.from(it) })
            }

            post {
                val req = call.receive<PublishRuleRequest>()
                val now = System.currentTimeMillis()
                val rule = com.infra.domain.Rule(
                    id = req.id,
                    layer = req.layer,
                    facilityId = req.facilityId,
                    regionCode = req.regionCode,
                    facilityType = req.facilityType,
                    condition = req.condition.toDomain(),
                    action = req.action,
                    version = req.version,
                    publishedAt = req.publishedAt ?: now,
                    validFrom = req.validFrom ?: now,
                    validTo = req.validTo,
                    description = req.description
                )
                try {
                    val created = repository.publishRule(rule)
                    call.respond(HttpStatusCode.Created, RuleDto.from(created))
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(
                            error = e.message ?: "Rule version conflict",
                            code = "VERSION_CONFLICT",
                            details = "A rule with same id and version already exists"
                        )
                    )
                }
            }

            get("/{id}") {
                val id = call.parameters["id"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Missing rule id")
                )
                val version = call.request.queryParameters["version"]?.toIntOrNull()
                val rule = repository.getRule(id, version)
                if (rule == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Rule not found", "RULE_NOT_FOUND"))
                } else {
                    call.respond(RuleDto.from(rule))
                }
            }
        }

        route("/api/evaluate") {
            post {
                val req = call.receive<RiskInputDto>()
                val now = System.currentTimeMillis()
                val requestId = req.requestId ?: UUID.randomUUID().toString()
                val observedAt = req.observedAt ?: now
                val evaluationTime = req.evaluationTime ?: now

                val input = RiskInput(
                    facilityId = req.facilityId,
                    hourlyRainfallMm = req.hourlyRainfallMm,
                    windLevel = req.windLevel,
                    waterDepthCm = req.waterDepthCm,
                    observedAt = observedAt,
                    requestId = requestId
                )

                val facility = repository.getFacility(req.facilityId)
                val allRules = repository.listAllRules()

                val result = RuleEngine.evaluate(
                    RuleEngine.EvaluationContext(
                        facility = facility,
                        rules = allRules,
                        input = input,
                        evaluationTime = evaluationTime
                    )
                )

                repository.saveEvaluationResult(result)
                notificationService.notify(result)

                call.respond(HttpStatusCode.OK, EvaluationResultDto.from(result))
            }
        }

        route("/api/evaluate/batch") {
            post {
                val req = call.receive<BatchEvaluationRequest>()
                val now = System.currentTimeMillis()
                val evaluationTime = req.evaluationTime ?: now

                val inputs = req.inputs.map { dto ->
                    RiskInput(
                        facilityId = dto.facilityId,
                        hourlyRainfallMm = dto.hourlyRainfallMm,
                        windLevel = dto.windLevel,
                        waterDepthCm = dto.waterDepthCm,
                        observedAt = dto.observedAt ?: now,
                        requestId = dto.requestId ?: UUID.randomUUID().toString()
                    )
                }

                val facilities = repository.listFacilities()
                val allRules = repository.listAllRules()

                val results = RuleEngine.evaluateBatch(
                    facilities = facilities,
                    rules = allRules,
                    inputs = inputs,
                    evaluationTime = evaluationTime
                )

                results.forEach { result ->
                    repository.saveEvaluationResult(result)
                    notificationService.notify(result)
                }

                call.respond(HttpStatusCode.OK, results.map { EvaluationResultDto.from(it) })
            }
        }

        route("/api/results") {
            get {
                val facilityId = call.request.queryParameters["facilityId"]
                val results = repository.listEvaluationResults(facilityId)
                call.respond(results.map { EvaluationResultDto.from(it) })
            }

            get("/{requestId}") {
                val requestId = call.parameters["requestId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Missing requestId")
                )
                val result = repository.getEvaluationResult(requestId)
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Result not found", "RESULT_NOT_FOUND"))
                } else {
                    call.respond(EvaluationResultDto.from(result))
                }
            }
        }

        route("/api/results/{requestId}/explanation") {
            get {
                val requestId = call.parameters["requestId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Missing requestId")
                )
                val result = repository.getEvaluationResult(requestId)
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Result not found", "RESULT_NOT_FOUND"))
                } else {
                    call.respond(ExplanationResponseDto.from(result))
                }
            }
        }
    }
}

