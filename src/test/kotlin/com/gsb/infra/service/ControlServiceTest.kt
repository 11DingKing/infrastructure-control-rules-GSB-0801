package com.gsb.infra.service

import com.gsb.infra.domain.Action
import com.gsb.infra.domain.Condition
import com.gsb.infra.domain.Facility
import com.gsb.infra.domain.FacilityType
import com.gsb.infra.domain.Metric
import com.gsb.infra.domain.Operator
import com.gsb.infra.domain.RiskInput
import com.gsb.infra.domain.Rule
import com.gsb.infra.domain.RuleLayer
import com.gsb.infra.persistence.DatabaseFactory
import com.gsb.infra.persistence.EvaluationRepository
import com.gsb.infra.persistence.FacilityRepository
import com.gsb.infra.persistence.RuleRepository
import com.gsb.infra.persistence.VersionConflictException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ControlServiceTest {

    private lateinit var service: ControlService
    private lateinit var ruleRepository: RuleRepository
    private val dbFile = java.io.File(
        System.getProperty("java.io.tmpdir"),
        "control-service-test-${System.nanoTime()}.db"
    )
    private val dbUrl: String get() = "jdbc:sqlite:${dbFile.absolutePath}"

    private val tunnel = Facility("tunnel-17", FacilityType.TUNNEL, "440800", "Tunnel 17")

    private val fixedNow = java.time.Instant.parse("2026-02-15T12:00:00Z")
    private val fixedClock = java.time.Clock.fixed(fixedNow, java.time.ZoneOffset.UTC)

    @BeforeTest
    fun setUp() {
        Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                com.gsb.infra.persistence.FacilitiesTable,
                com.gsb.infra.persistence.RulesTable,
                com.gsb.infra.persistence.InputSnapshotsTable,
                com.gsb.infra.persistence.EvaluationResultsTable
            )
        }
        ruleRepository = RuleRepository(fixedClock)
        service = ControlService(
            facilityRepository = FacilityRepository(fixedClock),
            ruleRepository = ruleRepository,
            evaluationRepository = EvaluationRepository(fixedClock),
            notifier = object : com.gsb.infra.service.Notifier {
                override fun onEvaluated(stored: com.gsb.infra.persistence.StoredEvaluation) {}
            },
            clock = fixedClock
        )
        service.registerFacility(tunnel)
    }

    @AfterTest
    fun tearDown() {
        transaction {
            SchemaUtils.drop(
                com.gsb.infra.persistence.EvaluationResultsTable,
                com.gsb.infra.persistence.InputSnapshotsTable,
                com.gsb.infra.persistence.RulesTable,
                com.gsb.infra.persistence.FacilitiesTable
            )
        }
        if (dbFile.exists()) dbFile.delete()
    }

    private fun publishDefaultRule(action: Action, threshold: Double, ruleId: String) {
        service.publishRule(
            Rule(
                ruleId = ruleId,
                version = 1,
                layer = RuleLayer.DEFAULT,
                facilityId = null,
                regionCode = null,
                facilityTypes = emptySet(),
                conditions = listOf(Condition(Metric.WATER_DEPTH_CM, Operator.GTE, threshold)),
                action = action,
                reason = "$ruleId reason",
                effectiveFrom = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                expiresAt = null,
                publishedAt = java.time.Instant.parse("2026-01-01T00:00:00Z")
            )
        )
    }

    @Test
    fun `publishing same version concurrently raises VersionConflictException`() {
        val rule = Rule(
            ruleId = "shared-rule",
            version = 7,
            layer = RuleLayer.DEFAULT,
            facilityId = null,
            regionCode = null,
            facilityTypes = emptySet(),
            conditions = listOf(Condition(Metric.WIND_LEVEL, Operator.GTE, 1.0)),
            action = Action.MONITOR,
            reason = "first",
            effectiveFrom = java.time.Instant.parse("2026-01-01T00:00:00Z"),
            expiresAt = null,
            publishedAt = java.time.Instant.parse("2026-01-01T00:00:00Z")
        )
        service.publishRule(rule)
        assertFailsWith<VersionConflictException> {
            service.publishRule(rule.copy(reason = "duplicate"))
        }
        val stored = ruleRepository.findById("shared-rule")
        assertEquals(1, stored.size)
        assertEquals(7, stored.first().version)
    }

    @Test
    fun `omitting version auto-assigns next version`() {
        publishDefaultRule(Action.MONITOR, 1.0, "auto")
        val v2 = service.publishRule(
            Rule(
                ruleId = "auto",
                version = 0,
                layer = RuleLayer.DEFAULT,
                facilityId = null,
                regionCode = null,
                facilityTypes = emptySet(),
                conditions = listOf(Condition(Metric.WIND_LEVEL, Operator.GTE, 2.0)),
                action = Action.RESTRICT,
                reason = "v2",
                effectiveFrom = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                expiresAt = null,
                publishedAt = java.time.Instant.parse("2026-01-01T00:00:00Z")
            )
        )
        assertEquals(2, v2.version)
    }

    @Test
    fun `evaluation persists matched version and snapshot hash`() {
        publishDefaultRule(Action.CLOSE, 10.0, "d-close")
        val input = RiskInput.of(Metric.WATER_DEPTH_CM to 18.0)
        val stored = service.evaluate("tunnel-17", input, fixedNow)
        assertEquals(Action.CLOSE, stored.result.finalAction)
        assertEquals("d-close", stored.result.winningRuleId)
        assertEquals(1, stored.result.winningVersion)
        assertTrue(stored.snapshotSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `identical inputs map to the same content-addressed snapshot record`() {
        publishDefaultRule(Action.MONITOR, 1.0, "d")
        val input = RiskInput.of(Metric.WATER_DEPTH_CM to 18.0)
        val a = service.evaluate("tunnel-17", input, fixedNow)
        val b = service.evaluate("tunnel-17", input, fixedNow)
        assertEquals(a.snapshotSha256, b.snapshotSha256)
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun `batch evaluation produces repeatable baseline and same results as individual calls`() {
        publishDefaultRule(Action.RESTRICT, 10.0, "d-restrict")
        val input = RiskInput.of(Metric.WATER_DEPTH_CM to 18.0)
        val batch = service.evaluateBatch(listOf("tunnel-17"), input, fixedNow)
        val individual = service.evaluate("tunnel-17", input, fixedNow)
        assertEquals(1, batch.size)
        assertEquals(individual.result.finalAction, batch.first().result.finalAction)
        assertEquals(individual.result.winningRuleId, batch.first().result.winningRuleId)
        assertEquals(individual.snapshotSha256, batch.first().snapshotSha256)
    }

    @Test
    fun `history cannot see rules published after the evaluation time`() {
        publishDefaultRule(Action.MONITOR, 1.0, "early")
        val input = RiskInput.of(Metric.WATER_DEPTH_CM to 18.0)
        val earlyResult = service.evaluate("tunnel-17", input, fixedNow)

        val laterPublish = java.time.Instant.parse("2026-12-31T00:00:00Z")
        service.publishRule(
            Rule(
                ruleId = "late-close",
                version = 1,
                layer = RuleLayer.MANUAL,
                facilityId = "tunnel-17",
                regionCode = null,
                facilityTypes = emptySet(),
                conditions = emptyList(),
                action = Action.CLOSE,
                reason = "published later",
                effectiveFrom = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                expiresAt = null,
                publishedAt = laterPublish
            )
        )

        val stored = service.findResult(earlyResult.id)
        requireNotNull(stored)
        assertEquals(Action.MONITOR, stored.result.finalAction)
        val traceIds = stored.result.ruleTraces.map { it.ruleId }
        assertTrue("late-close" !in traceIds, "history must not include later-published rule")
    }

    @Test
    fun `missing facility throws FacilityNotFoundException`() {
        val input = RiskInput.of(Metric.WATER_DEPTH_CM to 18.0)
        assertFailsWith<FacilityNotFoundException> {
            service.evaluate("does-not-exist", input, fixedNow)
        }
    }
}
