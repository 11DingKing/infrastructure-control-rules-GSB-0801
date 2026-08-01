package app.control

import app.control.db.Migrations
import app.control.db.connectDatabase
import app.control.http.configureApi
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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * API 端到端测试：隧道 tunnel-17 四层规则（default / region:440800 / facility / manual 带过期），
 * 验证求值详情、解释链、原因码、历史隔离与批量基准的 HTTP 行为。
 */
class ApiTest {

    private val NOW = 1_800_000_000_000L
    private var testNow = NOW
    private val json = Json { ignoreUnknownKeys = true }

    private fun freshDb(): String {
        val path = Files.createTempFile("control-api-test-", ".db").toAbsolutePath().toString()
        Files.deleteIfExists(java.nio.file.Path.of(path))
        connectDatabase(path)
        Migrations.run()
        return path
    }

    @Test
    fun `tunnel-17 四层规则全链路：发布、求值、解释、历史隔离、批量基准`() = testApplication {
        freshDb()
        testNow = NOW
        val services = buildServices { testNow }
        application { configureApi(services) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        // 设施 + 风险输入（降水 72mm/h、风力 7 级、水深 18cm）
        client.post("/api/facilities") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"tunnel-17","type":"TUNNEL","region":"440800","name":"示例隧道 17 号"}""")
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        val inputId = client.post("/api/risk-inputs") {
            contentType(ContentType.Application.Json)
            setBody("""{"facilityId":"tunnel-17","observedAt":$NOW,"precipitationMm":72.0,"windLevel":7,"waterDepthCm":18.0}""")
        }.body<app.control.http.IngestRiskInputResponse>().id

        suspend fun publishRule(body: String) = client.post("/api/rules") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        // 四层规则：动作覆盖 MONITOR / RESTRICT / CLOSE，manual 带过期时间
        publishRule("""{"ruleId":"default-heavy-rain","version":1,"tier":"DEFAULT","action":"MONITOR","precipitationMmAtLeast":50.0,"effectiveFrom":0}""")
            .also { assertEquals(HttpStatusCode.Created, it.status) }
        publishRule("""{"ruleId":"region-440800-storm","version":1,"tier":"REGION","scopeKey":"440800","action":"RESTRICT","precipitationMmAtLeast":60.0,"effectiveFrom":0}""")
            .also { assertEquals(HttpStatusCode.Created, it.status) }
        publishRule("""{"ruleId":"facility-tunnel-17-depth","version":1,"tier":"FACILITY","scopeKey":"tunnel-17","facilityType":"TUNNEL","action":"CLOSE","waterDepthCmAtLeast":15.0,"effectiveFrom":0}""")
            .also { assertEquals(HttpStatusCode.Created, it.status) }
        publishRule("""{"ruleId":"manual-tunnel-17-typhoon","version":1,"tier":"MANUAL","scopeKey":"tunnel-17","action":"CLOSE","windLevelAtLeast":8,"effectiveFrom":0,"effectiveTo":$NOW}""")
            .also { assertEquals(HttpStatusCode.Created, it.status) }

        // 同版本重复发布 -> 409 DUPLICATE_VERSION
        publishRule("""{"ruleId":"manual-tunnel-17-typhoon","version":1,"tier":"MANUAL","scopeKey":"tunnel-17","action":"CLOSE","windLevelAtLeast":8,"effectiveFrom":0}""").also { resp ->
            assertEquals(HttpStatusCode.Conflict, resp.status)
            assertEquals("DUPLICATE_VERSION", resp.body<app.control.http.PublishRuleResponse>().code)
        }
        // 无条件规则 -> 422 EMPTY_CONDITION
        publishRule("""{"ruleId":"default-empty","version":1,"tier":"DEFAULT","action":"MONITOR","effectiveFrom":0}""").also { resp ->
            assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
            assertEquals("EMPTY_CONDITION", resp.body<app.control.http.PublishRuleResponse>().code)
        }

        // 求值：manual 恰好过期（now == effectiveTo），manual 风力阈值也未达；设施层 CLOSE 胜出
        val evaluation = client.post("/api/evaluations") {
            contentType(ContentType.Application.Json)
            setBody("""{"facilityId":"tunnel-17","inputId":$inputId,"now":$NOW}""")
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<app.control.http.EvaluationResponse>()

        assertEquals("CLOSE", evaluation.result.decision?.action?.name)
        assertEquals("facility-tunnel-17-depth", evaluation.result.decision?.rule?.ruleId)
        assertEquals(1, evaluation.result.decision?.rule?.version)
        assertEquals(listOf("OK"), evaluation.result.reasonCodes)
        val codes = evaluation.result.explanation.map { it.code }.toSet()
        assertTrue("EXPIRED" in codes, "manual rule must be EXPIRED at boundary")
        assertTrue("MATCHED" in codes)
        assertTrue("SELECTED_TIER_PRECEDENCE" in codes)
        // 解释链保留了输入快照与命中规则版本
        assertEquals(72.0, evaluation.result.input.precipitationMm)
        assertTrue(evaluation.result.matchedRules.all { it.version == 1 })

        // 详情重放：内容指纹稳定
        val replayed = client.get("/api/evaluations/${evaluation.id}").body<app.control.http.EvaluationResponse>()
        assertEquals(evaluation.contentHash, replayed.contentHash)

        // 历史隔离：v2 发布后，历史 asOf 求值仍只看 v1
        testNow = NOW + 1_000
        publishRule("""{"ruleId":"facility-tunnel-17-depth","version":2,"tier":"FACILITY","scopeKey":"tunnel-17","action":"RESTRICT","waterDepthCmAtLeast":15.0,"effectiveFrom":0}""")
            .also { assertEquals(HttpStatusCode.Created, it.status) }
        val historical = client.post("/api/evaluations") {
            contentType(ContentType.Application.Json)
            setBody("""{"facilityId":"tunnel-17","inputId":$inputId,"now":$NOW,"asOf":$NOW}""")
        }.body<app.control.http.EvaluationResponse>()
        assertEquals(evaluation.contentHash, historical.contentHash, "历史 asOf 求值必须复现当时结果")

        // 批量求值：同一请求重跑，指纹序列可复现
        suspend fun runBatch() = client.post("/api/batches") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"items":[
                {"facilityId":"tunnel-17","inputId":$inputId,"now":$NOW,"asOf":$NOW},
                {"facilityId":"tunnel-17","inputId":$inputId,"now":${NOW - 1},"asOf":$NOW}
            ]}""".trimIndent(),
            )
        }.body<app.control.http.BatchResponse>()
        val batch1 = runBatch()
        val batch2 = runBatch()
        assertEquals(2, batch1.itemCount)
        assertEquals(batch1.contentHashes, batch2.contentHashes)
        assertNotEquals(batch1.id, batch2.id)
        assertEquals(batch1.contentHashes, client.get("/api/batches/${batch1.id}").body<app.control.http.BatchResponse>().contentHashes)

        // 通知只消费结果：批量 4 条 + 单次 2 条（首次求值 + 历史求值）
        val notifications = json.parseToJsonElement(
            client.get("/api/notifications?facilityId=tunnel-17").body<String>(),
        ).jsonArray
        assertEquals(6, notifications.size)
        assertTrue(notifications.all { it.jsonObject["payload"]?.jsonPrimitive?.content?.contains("hash=") == true })

        // OpenAPI 可用
        val spec = client.get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, spec.status)
        assertTrue(spec.body<String>().contains("Infrastructure Control Rules API"))
    }

    @Test
    fun `缺失输入时返回可枚举原因码`() = testApplication {
        freshDb()
        val services = buildServices { NOW }
        application { configureApi(services) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        client.post("/api/facilities") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"school-3","type":"SCHOOL","region":"440800","name":"第三中学"}""")
        }
        val inputId = client.post("/api/risk-inputs") {
            contentType(ContentType.Application.Json)
            setBody("""{"facilityId":"school-3","observedAt":$NOW,"precipitationMm":65.0}""")
        }.body<app.control.http.IngestRiskInputResponse>().id

        client.post("/api/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"ruleId":"facility-school-3-depth","version":1,"tier":"FACILITY","scopeKey":"school-3","action":"CLOSE","waterDepthCmAtLeast":10.0,"effectiveFrom":0}""")
        }
        client.post("/api/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"ruleId":"region-440800-storm","version":1,"tier":"REGION","scopeKey":"440800","action":"RESTRICT","precipitationMmAtLeast":60.0,"effectiveFrom":0}""")
        }

        val evaluation = client.post("/api/evaluations") {
            contentType(ContentType.Application.Json)
            setBody("""{"facilityId":"school-3","inputId":$inputId,"now":$NOW}""")
        }.body<app.control.http.EvaluationResponse>()

        assertEquals("RESTRICT", evaluation.result.decision?.action?.name)
        assertTrue("MISSING_WATER_DEPTH_CM" in evaluation.result.explanation.map { it.code })
    }
}
