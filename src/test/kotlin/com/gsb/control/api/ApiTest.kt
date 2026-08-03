package com.gsb.control.api

import com.gsb.control.app.ControlService
import com.gsb.control.app.EvaluationNotifier
import com.gsb.control.app.Seed
import com.gsb.control.controlModule
import com.gsb.control.persistence.Db
import com.gsb.control.persistence.EvaluationResultRepository
import com.gsb.control.persistence.FacilityRepository
import com.gsb.control.persistence.RuleRepository
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun testService(): ControlService {
        val db = Db.connect(":memory:")
        return ControlService(
            facilities = FacilityRepository(db),
            rules = RuleRepository(db),
            results = EvaluationResultRepository(db),
            notifier = EvaluationNotifier { },
        )
    }

    @Test
    fun `seed evaluation over reference input returns manual RESTRICT with explanation`() = testApplication {
        val service = testService()
        application { controlModule(service, seed = true) }

        val body = json.encodeToString(
            EvaluateRequest.serializer(),
            EvaluateRequest(
                facilityId = Seed.FACILITY_ID,
                input = mapOf(
                    "hourly_rainfall_mm" to 72.0,
                    "wind_force_level" to 7.0,
                    "water_depth_cm" to 18.0,
                ),
                evaluatedAt = Seed.T0.toString(),
                asOf = Seed.T0.toString(),
            ),
        )
        val resp = client.post("/evaluate") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val parsed = json.decodeFromString(EvaluationResponse.serializer(), resp.bodyAsText())
        assertEquals("RESTRICT", parsed.decision)
        assertEquals("MANUAL", parsed.decidingLayer)
        assertEquals(4, parsed.explanation.size)
        assertTrue(parsed.explanation.any { it.decisive })
    }

    @Test
    fun `duplicate rule version returns 409`() = testApplication {
        val service = testService()
        application { controlModule(service, seed = true) }

        val ruleJson = json.encodeToString(
            RuleRequest.serializer(),
            RuleRequest(
                ruleKey = "default.baseline",
                version = 1,
                scopeKind = "global",
                action = "MONITOR",
                condition = ConditionDto(type = "always"),
                validFrom = Seed.T0.toString(),
            ),
        )
        val resp = client.post("/rules") {
            contentType(ContentType.Application.Json)
            setBody(ruleJson)
        }
        // Seed already published default.baseline v1.
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `unknown risk metric yields 400 with reason code`() = testApplication {
        val service = testService()
        application { controlModule(service, seed = true) }

        val body = json.encodeToString(
            EvaluateRequest.serializer(),
            EvaluateRequest(facilityId = Seed.FACILITY_ID, input = mapOf("bogus_metric" to 1.0)),
        )
        val resp = client.post("/evaluate") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val err = json.decodeFromString(ErrorResponse.serializer(), resp.bodyAsText())
        assertEquals("UNKNOWN_ENUM", err.reason)
    }

    @Test
    fun `batch evaluate returns reproducible batch digest`() = testApplication {
        val service = testService()
        application { controlModule(service, seed = true) }

        val req = BatchEvaluateRequest(
            facilityId = Seed.FACILITY_ID,
            inputs = List(10) {
                mapOf("hourly_rainfall_mm" to 72.0, "wind_force_level" to 7.0, "water_depth_cm" to 18.0)
            },
            evaluatedAt = Seed.T0.toString(),
            asOf = Seed.T0.toString(),
        )
        val body = json.encodeToString(BatchEvaluateRequest.serializer(), req)

        val first = client.post("/evaluate/batch") {
            contentType(ContentType.Application.Json); setBody(body)
        }.bodyAsText()
        val second = client.post("/evaluate/batch") {
            contentType(ContentType.Application.Json); setBody(body)
        }.bodyAsText()

        val a = json.decodeFromString(BatchEvaluationResponse.serializer(), first)
        val b = json.decodeFromString(BatchEvaluationResponse.serializer(), second)
        assertEquals(a.batchDigest, b.batchDigest)
        assertEquals(10, a.count)
    }

    @Test
    fun `openapi spec is served`() = testApplication {
        val service = testService()
        application { controlModule(service, seed = true) }
        val resp = client.get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("openapi:"))
    }
}
