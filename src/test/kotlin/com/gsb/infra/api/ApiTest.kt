package com.gsb.infra.api

import com.gsb.infra.module
import com.gsb.infra.persistence.DatabaseFactory
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {

    private val dbFile = File(System.getProperty("java.io.tmpdir"), "api-test-${System.nanoTime()}.db")

    @BeforeTest
    fun setUp() {
        if (dbFile.exists()) dbFile.delete()
        DatabaseFactory.resetForTesting()
    }

    @AfterTest
    fun tearDown() {
        if (dbFile.exists()) dbFile.delete()
    }

    @Test
    fun `seeded tunnel-17 evaluation returns full explanation chain and winning rule version`() = testApplication {
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        application {
            DatabaseFactory.init(jdbcUrl)
            module()
        }
        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val eval = client.post("/api/evaluations") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "facilityId": "tunnel-17",
                  "values": {
                    "hourlyPrecipitationMm": 72,
                    "windLevel": 7,
                    "waterDepthCm": 18
                  }
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, eval.status)
        val body = eval.body<String>()
        val element = Json.parseToJsonElement(body).jsonObject
        val result = element["result"]!!.jsonObject
        assertEquals("tunnel-17", result["facilityId"]!!.jsonPrimitive.content)
        assertTrue(result["finalAction"]!!.jsonPrimitive.content in listOf("CLOSE", "RESTRICT", "MONITOR"))

        val id = element["id"]!!.jsonPrimitive.content
        val explanation = client.get("/api/evaluations/$id/explanation")
        assertEquals(HttpStatusCode.OK, explanation.status)
        val explanationBody = Json.parseToJsonElement(explanation.body<String>()).jsonObject
        assertTrue(explanationBody["explanation"]!!.jsonPrimitive.content.contains("tunnel-17"))
        assertTrue(explanationBody.containsKey("ruleTraces"))
        assertTrue(explanationBody.containsKey("layerDecisions"))
    }

    @Test
    fun `facility and rule listing endpoints work`() = testApplication {
        application {
            DatabaseFactory.init("jdbc:sqlite:${dbFile.absolutePath}")
            module()
        }
        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val facilities = client.get("/api/facilities")
        assertEquals(HttpStatusCode.OK, facilities.status)
        val facBody = Json.parseToJsonElement(facilities.body<String>())
        assertTrue(facBody is JsonArray)
        assertTrue(facBody.toString().contains("tunnel-17"))

        val rules = client.get("/api/rules")
        assertEquals(HttpStatusCode.OK, rules.status)
        val rulesBody = rules.body<String>()
        assertTrue(rulesBody.contains("manual-tunnel-17-close"))
    }
}
