package com.gsb.control.domain

import com.gsb.control.persistence.Digest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Determinism guarantees for the pure domain layer. */
class DeterminismTest {

    private val t0 = Instant.parse("2026-08-01T00:00:00Z")
    private val facility = Facility("tunnel-17", FacilityKind.TUNNEL, "440800", "Tunnel 17")

    private fun sampleRules() = listOf(
        Rule("default", 1, RuleScope.Global, Condition.Threshold(RiskMetric.HOURLY_RAINFALL_MM, Comparator.GTE, 10.0), Action.MONITOR, t0.minusSeconds(9000), null, t0.minusSeconds(9000), "d"),
        Rule("region", 1, RuleScope.Region("440800"), Condition.Threshold(RiskMetric.WIND_FORCE_LEVEL, Comparator.GTE, 6.0), Action.RESTRICT, t0.minusSeconds(9000), null, t0.minusSeconds(9000), "r"),
        Rule("facility", 1, RuleScope.FacilitySpecific("tunnel-17"), Condition.Threshold(RiskMetric.WATER_DEPTH_CM, Comparator.GTE, 15.0), Action.CLOSE, t0.minusSeconds(9000), null, t0.minusSeconds(9000), "f"),
    )

    private val input = RiskInput.of(
        RiskMetric.HOURLY_RAINFALL_MM to 72.0,
        RiskMetric.WIND_FORCE_LEVEL to 7.0,
        RiskMetric.WATER_DEPTH_CM to 18.0,
    )

    @Test
    fun `same snapshot yields byte-identical canonical output`() {
        val a = RuleEvaluator.evaluate(facility, sampleRules(), input, t0, t0)
        val b = RuleEvaluator.evaluate(facility, sampleRules(), input, t0, t0)
        assertEquals(a.canonicalString(), b.canonicalString())
        assertEquals(Digest.sha256Hex(a.canonicalString()), Digest.sha256Hex(b.canonicalString()))
    }

    @Test
    fun `input list ordering does not change the result`() {
        val shuffled = sampleRules().reversed()
        val a = RuleEvaluator.evaluate(facility, sampleRules(), input, t0, t0)
        val b = RuleEvaluator.evaluate(facility, shuffled, input, t0, t0)
        assertEquals(a.canonicalString(), b.canonicalString())
    }

    @Test
    fun `equal risk inputs built differently canonicalize identically`() {
        val i1 = RiskInput(linkedMapOf(RiskMetric.WATER_DEPTH_CM to 18.0, RiskMetric.HOURLY_RAINFALL_MM to 72.0))
        val i2 = RiskInput(linkedMapOf(RiskMetric.HOURLY_RAINFALL_MM to 72.0, RiskMetric.WATER_DEPTH_CM to 18.0))
        assertEquals(i1.canonicalString(), i2.canonicalString())
    }

    @Test
    fun `batch over repeated input produces stable per-item digests`() {
        val digests = (1..100).map {
            val r = RuleEvaluator.evaluate(facility, sampleRules(), input, t0, t0)
            Digest.sha256Hex(r.canonicalString())
        }
        // Every run is identical: exactly one distinct digest.
        assertEquals(1, digests.toSet().size)
    }
}
