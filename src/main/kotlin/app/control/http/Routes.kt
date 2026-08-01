package app.control.http

import app.control.domain.Action
import app.control.domain.FacilityType
import app.control.domain.PublishCode
import app.control.domain.RuleCondition
import app.control.domain.RuleTier
import app.control.services.BatchItem
import app.control.services.BatchService
import app.control.services.EvaluationService
import app.control.services.FacilityService
import app.control.services.NotificationQueryService
import app.control.services.RiskInputService
import app.control.services.RuleService
import app.control.services.ServiceException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

class Services(
    val facilities: FacilityService,
    val riskInputs: RiskInputService,
    val rules: RuleService,
    val evaluations: EvaluationService,
    val batches: BatchService,
    val notifications: NotificationQueryService,
)

fun Application.configureApi(services: Services) {
    install(ContentNegotiation) {
        json(Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        })
    }
    install(StatusPages) {
        exception<ServiceException> { call, e ->
            call.respond(
                HttpStatusCode.fromValue(e.httpStatus),
                ErrorResponse(ErrorBody(code = e.code, message = e.message)),
            )
        }
        exception<Throwable> { call, e ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(ErrorBody(code = "INTERNAL_ERROR", message = e.message ?: "internal error")),
            )
        }
    }

    routing {
        get("/openapi.yaml") {
            val text = requireNotNull(Thread.currentThread().contextClassLoader.getResource("openapi.yaml"))
                .readText()
            call.respondText(text, ContentType.parse("application/yaml"))
        }

        route("/api") {
            post("/facilities") {
                val req = call.receive<CreateFacilityRequest>()
                val type = parseEnum<FacilityType>(req.type, "type")
                val facility = services.facilities.create(req.id, type, req.region, req.name)
                call.respond(
                    HttpStatusCode.Created,
                    FacilityResponse(facility.id, facility.type.name, facility.region, facility.name),
                )
            }
            get("/facilities/{id}") {
                val facility = services.facilities.get(pathParam(call.parameters["id"], "id"))
                call.respond(FacilityResponse(facility.id, facility.type.name, facility.region, facility.name))
            }

            post("/rules") {
                val req = call.receive<PublishRuleRequest>()
                val tier = parseEnum<RuleTier>(req.tier, "tier")
                val action = parseEnum<Action>(req.action, "action")
                val facilityType = req.facilityType?.let { parseEnum<FacilityType>(it, "facilityType") }
                val outcome = services.rules.publish(
                    ruleId = req.ruleId,
                    version = req.version,
                    tier = tier,
                    scopeKey = req.scopeKey ?: if (tier == RuleTier.DEFAULT) "default" else "",
                    facilityType = facilityType,
                    condition = RuleCondition(
                        precipitationMmAtLeast = req.precipitationMmAtLeast,
                        windLevelAtLeast = req.windLevelAtLeast,
                        waterDepthCmAtLeast = req.waterDepthCmAtLeast,
                    ),
                    action = action,
                    effectiveFrom = req.effectiveFrom,
                    effectiveTo = req.effectiveTo,
                    publishedAt = req.publishedAt,
                )
                val status = when (outcome.code) {
                    PublishCode.PUBLISHED -> HttpStatusCode.Created
                    PublishCode.DUPLICATE_VERSION, PublishCode.VERSION_ROLLBACK -> HttpStatusCode.Conflict
                    else -> HttpStatusCode.UnprocessableEntity
                }
                call.respond(
                    status,
                    PublishRuleResponse(
                        code = outcome.code.name,
                        ruleId = outcome.rule?.ruleId,
                        version = outcome.rule?.version,
                        publishedAt = outcome.rule?.publishedAt,
                    ),
                )
            }
            get("/rules/{ruleId}/versions") {
                val versions = services.rules.versionsOf(pathParam(call.parameters["ruleId"], "ruleId"))
                call.respond(
                    versions.map { rule ->
                        PublishRuleResponse(
                            code = PublishCode.PUBLISHED.name,
                            ruleId = rule.ruleId,
                            version = rule.version,
                            publishedAt = rule.publishedAt,
                        )
                    },
                )
            }

            post("/risk-inputs") {
                val req = call.receive<IngestRiskInputRequest>()
                services.facilities.get(req.facilityId)
                val id = services.riskInputs.ingest(
                    facilityId = req.facilityId,
                    observedAt = req.observedAt,
                    precipitationMm = req.precipitationMm,
                    windLevel = req.windLevel,
                    waterDepthCm = req.waterDepthCm,
                )
                call.respond(HttpStatusCode.Created, IngestRiskInputResponse(id))
            }

            post("/evaluations") {
                val req = call.receive<EvaluateRequest>()
                val stored = services.evaluations.evaluate(req.facilityId, req.inputId, req.now, req.asOf)
                call.respond(
                    HttpStatusCode.Created,
                    EvaluationResponse(stored.id, stored.contentHash, stored.createdAt, stored.result),
                )
            }
            get("/evaluations/{id}") {
                val stored = services.evaluations.get(pathParam(call.parameters["id"], "id"))
                call.respond(EvaluationResponse(stored.id, stored.contentHash, stored.createdAt, stored.result))
            }
            get("/facilities/{id}/evaluations") {
                val stored = services.evaluations.listByFacility(pathParam(call.parameters["id"], "id"))
                call.respond(
                    stored.map { item ->
                        EvaluationSummary(
                            id = item.id,
                            decisionAction = item.result.decision?.action?.name,
                            decisionRuleId = item.result.decision?.rule?.ruleId,
                            decisionRuleVersion = item.result.decision?.rule?.version,
                            contentHash = item.contentHash,
                            createdAt = item.createdAt,
                        )
                    },
                )
            }

            post("/batches") {
                val req = call.receive<RunBatchRequest>()
                val batch = services.batches.run(
                    req.items.map { item -> BatchItem(item.facilityId, item.inputId, item.now, item.asOf) },
                )
                call.respond(
                    HttpStatusCode.Created,
                    BatchResponse(
                        id = batch.id,
                        createdAt = batch.createdAt,
                        itemCount = batch.itemCount,
                        durationMs = batch.durationMs,
                        evaluationIds = batch.evaluationIds,
                        contentHashes = batch.contentHashes,
                    ),
                )
            }
            get("/batches/{id}") {
                val batch = services.batches.get(pathParam(call.parameters["id"], "id"))
                call.respond(
                    BatchResponse(
                        id = batch.id,
                        createdAt = batch.createdAt,
                        itemCount = batch.itemCount,
                        durationMs = batch.durationMs,
                        evaluationIds = batch.evaluationIds,
                        contentHashes = batch.contentHashes,
                    ),
                )
            }

            get("/notifications") {
                val facilityId = call.request.queryParameters["facilityId"]
                    ?: throw ServiceException("INVALID_REQUEST", 400, "query parameter 'facilityId' is required")
                val records = services.notifications.listByFacility(facilityId)
                call.respond(
                    records.map { record ->
                        NotificationResponse(
                            id = record.id,
                            evaluationId = record.evaluationId,
                            facilityId = record.facilityId,
                            action = record.action,
                            channel = record.channel,
                            payload = record.payload,
                            sentAt = record.sentAt,
                        )
                    },
                )
            }
        }
    }
}

private fun pathParam(value: String?, name: String): String =
    value ?: throw ServiceException("INVALID_REQUEST", 400, "missing path parameter '$name'")

private inline fun <reified T : Enum<T>> parseEnum(raw: String, field: String): T =
    enumValues<T>().firstOrNull { it.name == raw }
        ?: throw ServiceException(
            "INVALID_REQUEST", 400,
            "invalid $field: '$raw' (allowed: ${enumValues<T>().joinToString(",") { it.name }})",
        )
