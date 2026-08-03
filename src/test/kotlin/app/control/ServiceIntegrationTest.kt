package app.control

import app.control.db.Migrations
import app.control.db.connectDatabase
import app.control.domain.Action
import app.control.domain.FacilityType
import app.control.domain.PublishCode
import app.control.domain.RuleCondition
import app.control.domain.RuleTier
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 服务层集成测试：真实 SQLite 文件库，
 * 覆盖并发同版本发布、历史解释隔离、确定性、批量基准与通知消费。
 */
class ServiceIntegrationTest {

    private val NOW = 1_800_000_000_000L
    private var testNow = NOW
    private val clock: () -> Long = { testNow }

    private var dbPath: String? = null

    private fun freshServices(): app.control.http.Services {
        val path = Files.createTempFile("control-test-", ".db").toAbsolutePath().toString()
        Files.deleteIfExists(java.nio.file.Path.of(path))
        dbPath = path
        connectDatabase(path)
        Migrations.run()
        return buildServices(clock)
    }

    @AfterTest
    fun cleanup() {
        dbPath?.let { Files.deleteIfExists(java.nio.file.Path.of(it)) }
    }

    private fun seedFacilityAndInput(services: app.control.http.Services): Long {
        services.facilities.create("tunnel-17", FacilityType.TUNNEL, "440800", "示例隧道 17 号")
        return services.riskInputs.ingest(
            facilityId = "tunnel-17",
            observedAt = NOW - 60_000,
            precipitationMm = 72.0,
            windLevel = 7,
            waterDepthCm = 18.0,
        )
    }

    @Test
    fun `同一版本并发发布只有一个赢家，其余返回 DUPLICATE_VERSION`() {
        val services = freshServices()
        services.facilities.create("tunnel-17", FacilityType.TUNNEL, "440800", "示例隧道 17 号")

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val gate = CountDownLatch(1)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<PublishCode>())
        repeat(threads) {
            pool.submit {
                gate.await()
                val outcome = services.rules.publish(
                    ruleId = "manual-tunnel-17-typhoon",
                    version = 1,
                    tier = RuleTier.MANUAL,
                    scopeKey = "tunnel-17",
                    facilityType = null,
                    condition = RuleCondition(windLevelAtLeast = 8),
                    action = Action.CLOSE,
                    effectiveFrom = 0L,
                    effectiveTo = NOW + 10_000,
                )
                outcomes += outcome.code
            }
        }
        gate.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "concurrent publishes should finish")

        assertEquals(threads, outcomes.size)
        assertEquals(1, outcomes.count { it == PublishCode.PUBLISHED }, "exactly one winner expected")
        assertEquals(threads - 1, outcomes.count { it == PublishCode.DUPLICATE_VERSION })
        assertEquals(listOf(1), services.rules.versionsOf("manual-tunnel-17-typhoon").map { it.version })
    }

    @Test
    fun `历史解释不偷看后来发布的规则，重放结果字节不变`() {
        val services = freshServices()
        val inputId = seedFacilityAndInput(services)

        services.rules.publish(
            ruleId = "region-440800-storm", version = 1, tier = RuleTier.REGION, scopeKey = "440800",
            facilityType = null, condition = RuleCondition(precipitationMmAtLeast = 60.0),
            action = Action.RESTRICT, effectiveFrom = 0L, effectiveTo = null,
        )
        val historical = services.evaluations.evaluate("tunnel-17", inputId, now = NOW, asOf = NOW)
        assertEquals("region-440800-storm", historical.result.decision?.rule?.ruleId)
        assertEquals(1, historical.result.decision?.rule?.version)

        // 后来发布 v2（阈值更高，将导致不命中）
        testNow = NOW + 1_000
        val laterOutcome = services.rules.publish(
            ruleId = "region-440800-storm", version = 2, tier = RuleTier.REGION, scopeKey = "440800",
            facilityType = null, condition = RuleCondition(precipitationMmAtLeast = 90.0),
            action = Action.CLOSE, effectiveFrom = 0L, effectiveTo = null,
        )
        assertEquals(PublishCode.PUBLISHED, laterOutcome.code)

        // 已持久化的历史结果：内容与指纹不变，解释链不含 v2
        val replayed = services.evaluations.get(historical.id)
        assertEquals(historical.canonicalJson, replayed.canonicalJson)
        assertEquals(historical.contentHash, replayed.contentHash)
        assertTrue(replayed.result.explanation.none { it.rule?.version == 2 })

        // 用历史 asOf 重新求值：仍然只看 v1，指纹与当时一致
        val reEvaluated = services.evaluations.evaluate("tunnel-17", inputId, now = NOW, asOf = NOW)
        assertEquals(historical.contentHash, reEvaluated.contentHash)

        // 不带 asOf 的新求值：v2 可见且生效（阈值 90 不命中 -> 无匹配规则）
        val current = services.evaluations.evaluate("tunnel-17", inputId, now = NOW + 2_000)
        assertEquals(null, current.result.decision)
        assertTrue(current.result.explanation.any { it.rule?.version == 2 })
        assertNotEquals(historical.contentHash, current.contentHash)
        testNow = NOW
    }

    @Test
    fun `相同求值参数重复执行得到相同内容指纹`() {
        val services = freshServices()
        val inputId = seedFacilityAndInput(services)
        services.rules.publish(
            ruleId = "facility-tunnel-17-depth", version = 1, tier = RuleTier.FACILITY, scopeKey = "tunnel-17",
            facilityType = FacilityType.TUNNEL, condition = RuleCondition(waterDepthCmAtLeast = 15.0),
            action = Action.CLOSE, effectiveFrom = 0L, effectiveTo = null,
        )
        val first = services.evaluations.evaluate("tunnel-17", inputId, now = NOW, asOf = NOW)
        val second = services.evaluations.evaluate("tunnel-17", inputId, now = NOW, asOf = NOW)
        assertNotEquals(first.id, second.id)
        assertEquals(first.contentHash, second.contentHash)
        assertEquals(first.canonicalJson, second.canonicalJson)
    }

    @Test
    fun `恰好过期的边界在持久化路径上同样成立`() {
        val services = freshServices()
        val inputId = seedFacilityAndInput(services)
        services.rules.publish(
            ruleId = "manual-tunnel-17-gale", version = 1, tier = RuleTier.MANUAL, scopeKey = "tunnel-17",
            facilityType = null, condition = RuleCondition(windLevelAtLeast = 7),
            action = Action.CLOSE, effectiveFrom = 0L, effectiveTo = NOW,
        )
        val expired = services.evaluations.evaluate("tunnel-17", inputId, now = NOW, asOf = NOW)
        assertEquals(null, expired.result.decision)
        assertTrue(expired.result.explanation.any { it.code == "EXPIRED" })

        val stillActive = services.evaluations.evaluate("tunnel-17", inputId, now = NOW - 1, asOf = NOW)
        assertEquals("manual-tunnel-17-gale", stillActive.result.decision?.rule?.ruleId)
    }

    @Test
    fun `批量求值留下可复现基准且逐项通知`() {
        val services = freshServices()
        val inputId = seedFacilityAndInput(services)
        services.rules.publish(
            ruleId = "facility-tunnel-17-depth", version = 1, tier = RuleTier.FACILITY, scopeKey = "tunnel-17",
            facilityType = FacilityType.TUNNEL, condition = RuleCondition(waterDepthCmAtLeast = 15.0),
            action = Action.CLOSE, effectiveFrom = 0L, effectiveTo = null,
        )
        val items = listOf(
            app.control.services.BatchItem("tunnel-17", inputId, now = NOW, asOf = NOW),
            app.control.services.BatchItem("tunnel-17", inputId, now = NOW + 1, asOf = NOW),
        )
        val batch1 = services.batches.run(items)
        val batch2 = services.batches.run(items)

        assertEquals(items.size, batch1.itemCount)
        assertEquals(batch1.contentHashes, batch2.contentHashes, "same batch inputs must reproduce hashes")
        assertNotEquals(batch1.id, batch2.id)
        assertEquals(batch1.contentHashes, services.batches.get(batch1.id).contentHashes)

        // 通知只消费结果：每次求值一条，payload 含结果指纹
        val notes = services.notifications.listByFacility("tunnel-17")
        assertEquals(4, notes.size)
        assertTrue(notes.all { it.payload.contains("action=CLOSE") })
        assertTrue(notes.all { note -> batch1.contentHashes.union(batch2.contentHashes).any { h -> note.payload.contains(h) } })
    }

    @Test
    fun `发布校验返回可枚举原因码`() {
        val services = freshServices()
        services.facilities.create("tunnel-17", FacilityType.TUNNEL, "440800", "示例隧道 17 号")

        val empty = services.rules.publish(
            ruleId = "r-empty", version = 1, tier = RuleTier.DEFAULT, scopeKey = "default",
            facilityType = null, condition = RuleCondition(), action = Action.MONITOR,
            effectiveFrom = 0L, effectiveTo = null,
        )
        assertEquals(PublishCode.EMPTY_CONDITION, empty.code)

        val badWindow = services.rules.publish(
            ruleId = "r-window", version = 1, tier = RuleTier.DEFAULT, scopeKey = "default",
            facilityType = null, condition = RuleCondition(precipitationMmAtLeast = 1.0), action = Action.MONITOR,
            effectiveFrom = 100L, effectiveTo = 100L,
        )
        assertEquals(PublishCode.INVALID_WINDOW, badWindow.code)

        val unknownScope = services.rules.publish(
            ruleId = "r-scope", version = 1, tier = RuleTier.FACILITY, scopeKey = "ghost-1",
            facilityType = null, condition = RuleCondition(precipitationMmAtLeast = 1.0), action = Action.MONITOR,
            effectiveFrom = 0L, effectiveTo = null,
        )
        assertEquals(PublishCode.UNKNOWN_SCOPE_FACILITY, unknownScope.code)

        services.rules.publish(
            ruleId = "r-chain", version = 2, tier = RuleTier.DEFAULT, scopeKey = "default",
            facilityType = null, condition = RuleCondition(precipitationMmAtLeast = 1.0), action = Action.MONITOR,
            effectiveFrom = 0L, effectiveTo = null,
        )
        val rollback = services.rules.publish(
            ruleId = "r-chain", version = 1, tier = RuleTier.DEFAULT, scopeKey = "default",
            facilityType = null, condition = RuleCondition(precipitationMmAtLeast = 1.0), action = Action.MONITOR,
            effectiveFrom = 0L, effectiveTo = null,
        )
        assertEquals(PublishCode.VERSION_ROLLBACK, rollback.code)
    }
}
