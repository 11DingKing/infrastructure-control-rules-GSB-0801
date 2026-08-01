package com.gsb.control

import com.gsb.control.api.ErrorResponse
import com.gsb.control.api.controlRoutes
import com.gsb.control.app.ControlService
import com.gsb.control.app.LoggingNotifier
import com.gsb.control.app.Seed
import com.gsb.control.persistence.Db
import com.gsb.control.persistence.EvaluationResultRepository
import com.gsb.control.persistence.FacilityRepository
import com.gsb.control.persistence.RuleRepository
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/** Builds a [ControlService] backed by SQLite at [dbPath]. */
fun buildService(dbPath: String): ControlService {
    val db = Db.connect(dbPath)
    return ControlService(
        facilities = FacilityRepository(db),
        rules = RuleRepository(db),
        results = EvaluationResultRepository(db),
        notifier = LoggingNotifier(),
    )
}

/**
 * Ktor module. [service] is injected so tests can supply an in-memory instance;
 * [seed] controls whether the demo facility + four rule layers are loaded.
 */
fun Application.controlModule(service: ControlService, seed: Boolean = true) {
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                encodeDefaults = true
                explicitNulls = false
            },
        )
    }
    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "UNEXPECTED", cause.message),
            )
        }
    }

    if (seed) {
        Seed.apply(service)
    }

    val openApi = this::class.java.classLoader.getResource("openapi.yaml")?.readText().orEmpty()

    routing {
        controlRoutes(service, openApi)
    }
}

fun main() {
    val dbPath = System.getenv("CONTROL_DB_PATH") ?: "data/control.db"
    val port = System.getenv("CONTROL_PORT")?.toIntOrNull() ?: 8080
    val service = buildService(dbPath)
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        controlModule(service, seed = true)
    }.start(wait = true)
}
