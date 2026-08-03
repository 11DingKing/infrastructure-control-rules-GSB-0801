package app.control

import app.control.db.Migrations
import app.control.db.connectDatabase
import app.control.http.Services
import app.control.http.configureApi
import app.control.services.BatchService
import app.control.services.EvaluationService
import app.control.services.FacilityService
import app.control.services.NotificationQueryService
import app.control.services.RiskInputService
import app.control.services.RuleService
import app.control.services.TableNotificationSink
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun buildServices(clock: () -> Long): Services {
    val facilities = FacilityService(clock)
    val riskInputs = RiskInputService(clock)
    val rules = RuleService(facilities, clock)
    val evaluations = EvaluationService(
        facilityService = facilities,
        riskInputService = riskInputs,
        ruleService = rules,
        notificationSink = TableNotificationSink(clock),
        clock = clock,
    )
    return Services(
        facilities = facilities,
        riskInputs = riskInputs,
        rules = rules,
        evaluations = evaluations,
        batches = BatchService(evaluations, clock),
        notifications = NotificationQueryService(),
    )
}

fun Application.appModule(services: Services) {
    configureApi(services)
}

fun main() {
    val dbFile = System.getenv("DB_FILE") ?: "data/control.db"
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val clock: () -> Long = { System.currentTimeMillis() }

    connectDatabase(dbFile)
    Migrations.run()

    val services = buildServices(clock)
    if (System.getenv("SEED_DEMO")?.lowercase() != "false") {
        DemoSeed.seedIfEmpty(services, clock)
    }

    embeddedServer(Netty, port = port) {
        appModule(services)
    }.start(wait = true)
}
