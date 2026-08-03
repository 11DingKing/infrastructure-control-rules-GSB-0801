package com.infra

import com.infra.api.configureRoutes
import com.infra.api.ErrorResponse
import com.infra.notification.LoggingNotificationService
import com.infra.persistence.DatabaseFactory
import com.infra.persistence.Repository
import com.infra.seed.SeedData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    val jdbcUrl = environment.config.propertyOrNull("storage.jdbcUrl")?.getString()
        ?: "jdbc:sqlite:data/infra-control.db"

    DatabaseFactory.init(jdbcUrl)

    val repository = Repository()
    val notificationService = LoggingNotificationService()

    SeedData.seedIfEmpty(repository)

    configureSerialization()
    configureCORS()
    configureCallLogging()
    configureStatusPages()
    configureOpenApi()
    configureRoutes(repository, notificationService)
}

private fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
}

private fun Application.configureCORS() {
    install(CORS) {
        anyHost()
    }
}

private fun Application.configureCallLogging() {
    install(CallLogging)
}

private fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    error = cause.message ?: "Internal server error",
                    code = "INTERNAL_ERROR",
                    details = cause.stackTraceToString().take(2000)
                )
            )
        }
    }
}

private fun Application.configureOpenApi() {
    routing {
        get("/openapi.json") {
            val spec = this::class.java.classLoader
                .getResource("openapi.json")
                ?.readText()
                ?: """{"openapi":"3.0.3","info":{"title":"Infrastructure Control Rules API","version":"1.0.0"}}"""
            call.respondText(spec, ContentType.Application.Json)
        }

        get("/swagger-ui") {
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Infrastructure Control Rules API - Swagger UI</title>
                    <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
                </head>
                <body>
                    <div id="swagger-ui"></div>
                    <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
                    <script>
                        SwaggerUIBundle({
                            url: '/openapi.json',
                            dom_id: '#swagger-ui'
                        });
                    </script>
                </body>
                </html>
            """.trimIndent()
            call.respondText(html, ContentType.Text.Html)
        }
    }
}
