package com.gsb.control.app

import com.gsb.control.domain.RiskInput
import com.gsb.control.domain.RiskMetric
import com.gsb.control.domain.Action
import com.gsb.control.domain.RuleLayer
import com.gsb.control.persistence.Db
import com.gsb.control.persistence.EvaluationResultRepository
import com.gsb.control.persistence.FacilityRepository
import com.gsb.control.persistence.PublishOutcome
import com.gsb.control.persistence.RuleRepository
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Service-level tests exercising persistence, the seed scenario, and concurrency. */
class ControlServiceTest {

    private fun newService(): Pair<ControlService, RuleRepository> {
        val db = Db.connect(":memory:")
        val rules = RuleRepository(db)
        val service = ControlService(
            facilities = FacilityRepository(db),
            rules = rules,
            results = EvaluationResultRepository(db),
            notifier = EvaluationNotifier { },
        )
        return service to rules
    }

    @Test
    fun `seed scenario evaluates to manual RESTRICT and persists full explanation`() {
        val (service, _) = newService()
        Seed.apply(service, Seed.T0)

        val res = service.evaluateAndRecord(
            facilityId = Seed.FACILITY_ID,
            input = Seed.REFERENCE_INPUT,
            evaluatedAt = Seed.T0,
            asOf = Seed.T0,
        )
        val ok = res as ServiceResult.Ok
        val result = ok.value.result

        // Manual override (RESTRICT) beats the facility CLOSE rule.
        assertEquals(Action.RESTRICT, result.decision)
        assertEquals(RuleLayer.MANUAL, result.decidingLayer)
        assertEquals("manual-tunnel-17.override:v1", result.decidingVersionRef)

        // Full explanation retained: all four rules are traced.
        assertEquals(4, result.trace.size)
        // Result persisted with a digest.
        assertNotNull(ok.value.storedId)
        assertNotNull(ok.value.canonicalDigest)

        // Round-trip: stored result matches canonical digest.
        val stored = service.result(ok.value.storedId ?: -1)
        assertNotNull(stored)
        assertEquals(ok.value.canonicalDigest, stored.canonicalDigest)
        assertEquals(result.canonicalString(), stored.result.canonicalString())
    }

    @Test
    fun `missing facility returns enumerable error`() {
        val (service, _) = newService()
        val res = service.evaluateAndRecord("ghost", RiskInput.of(RiskMetric.HOURLY_RAINFALL_MM to 1.0))
        assertTrue(res is ServiceResult.Err)
        assertTrue((res as ServiceResult.Err).error is ServiceError.FacilityNotFound)
    }

    @Test
    fun `concurrent publish of same version admits exactly one winner`() {
        val (service, _) = newService()
        Seed.apply(service, Seed.T0)

        val rule = Seed.rules(Seed.T0).first { it.ruleKey == "region-440800.storm" }
            .copy(version = 99) // a fresh version to contend on

        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val published = AtomicInteger(0)
        val conflicts = AtomicInteger(0)
        val tasks = (1..threads).map {
            pool.submit {
                when (service.publishRule(rule)) {
                    is ServiceResult.Ok -> published.incrementAndGet()
                    is ServiceResult.Err -> conflicts.incrementAndGet()
                }
            }
        }
        tasks.forEach { it.get() }
        pool.shutdown()

        assertEquals(1, published.get(), "exactly one publish should win")
        assertEquals(threads - 1, conflicts.get(), "the rest must be version conflicts")

        // Storage holds exactly one revision of version 99.
        val revs = service.rulesForKey("region-440800.storm").filter { it.version == 99 }
        assertEquals(1, revs.size)
    }

    @Test
    fun `direct repository publish reports version conflict`() {
        val (_, rules) = newService()
        val rule = Seed.rules(Seed.T0).first()
        assertTrue(rules.publish(rule) is PublishOutcome.Published)
        assertTrue(rules.publish(rule) is PublishOutcome.VersionConflict)
    }

    @Test
    fun `batch evaluation is reproducible and bypasses no domain logic`() {
        val (service, _) = newService()
        Seed.apply(service, Seed.T0)

        fun runBatch() = (1..50).map {
            val res = service.evaluateAndRecord(
                facilityId = Seed.FACILITY_ID,
                input = Seed.REFERENCE_INPUT,
                evaluatedAt = Seed.T0,
                asOf = Seed.T0,
                persist = false,
                notify = false,
            ) as ServiceResult.Ok
            res.value.result.canonicalString()
        }

        val first = runBatch()
        val second = runBatch()
        assertEquals(first, second)
        assertEquals(1, first.toSet().size)
    }
}
