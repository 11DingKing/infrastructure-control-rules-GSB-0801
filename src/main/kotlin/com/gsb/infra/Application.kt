package com.gsb.infra

import com.gsb.infra.api.ErrorResponse
import com.gsb.infra.api.InvalidInputException
import com.gsb.infra.api.evaluationRoutes
import com.gsb.infra.api.facilityRoutes
import com.gsb.infra.api.ruleRoutes
import com.gsb.infra.persistence.DatabaseFactory
import com.gsb.infra.persistence.EvaluationRepository
import com.gsb.infra.persistence.FacilityRepository
import com.gsb.infra.persistence.RuleRepository
import com.gsb.infra.service.ControlService
import com.gsb.infra.service.FacilityNotFoundException
import com.gsb.infra.seed.SeedData
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.plugins.openapi.openAPI
import io.ktor.server.plugins.swagger.swaggerUI
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val jdbcUrl = System.getenv("DATABASE_URL")
        ?: environment.config.propertyOrNull("db.url")?.getString()
        ?: DatabaseFactory.defaultJdbcUrl()
    DatabaseFactory.init(jdbcUrl)

    val service = ControlService(
        facilityRepository = FacilityRepository(),
        ruleRepository = RuleRepository(),
        evaluationRepository = EvaluationRepository()
    )

    SeedData.seedIfEmpty(service)

    configureSerialization()
    configureHTTP()
    configureStatusPages()
    configureRouting(service)
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                isLenient = true
                encodeDefaults = true
                ignoreUnknownKeys = true
            }
        )
    }
}

fun Application.configureHTTP() {
    install(CORS) {
        anyHost()
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
    }
}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<InvalidInputException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.reasonCode.name, cause.message ?: "invalid input")
            )
        }
        exception<FacilityNotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse("FACILITY_NOT_FOUND", cause.message ?: "not found")
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_ARGUMENT", cause.message ?: "invalid argument")
            )
        }
    }
}

fun Application.configureRouting(service: ControlService) {
    routing {
        get("/") {
            call.respond(mapOf("status" to "UP", "service" to "infrastructure-control-rules"))
        }
        facilityRoutes(service)
        ruleRoutes(service)
        evaluationRoutes(service)

        openAPI(path = "openapi", swaggerFile = "openapi/documentation.yaml")
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
    }
}
