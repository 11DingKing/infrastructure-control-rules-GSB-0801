package com.gsb.control.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Table-driven tests for the pure evaluator. Each case fixes a facility, a rule
 * set, an input snapshot and explicit instants, then asserts the decision and
 * the deciding provenance. Because the evaluator is pure, these are exhaustive
 * and repeatable without any I/O.
 */
class RuleEvaluatorTest {

    private val t0: Instant = Instant.parse("2026-08-01T00:00:00Z")
    private val facility = Facility("tunnel-17", FacilityKind.TUNNEL, "440800", "Tunnel 17")
    private val otherFacility = Facility("bridge-9", FacilityKind.TEMPORARY_STRUCTURE, "440900", "Bridge 9")

    private fun rule(
        key: String,
        version: Int = 1,
        scope: RuleScope,
        condition: Condition = Condition.Always,
        action: Action,
        validFrom: Instant = t0.minusSeconds(3600),
        validUntil: Instant? = null,
        publishedAt: Instant = t0.minusSeconds(3600),
    ) = Rule(key, version, scope, condition, action, validFrom, validUntil, publishedAt, "desc:$key")

    private fun evaluate(
        rules: List<Rule>,
        input: RiskInput,
        at: Instant = t0,
        asOf: Instant = t0,
        target: Facility = facility,
    ) = RuleEvaluator.evaluate(target, rules, input, at, asOf)

    private val heavyInput = RiskInput.of(
        RiskMetric.HOURLY_RAINFALL_MM to 72.0,
        RiskMetric.WIND_FORCE_LEVEL to 7.0,
        RiskMetric.WATER_DEPTH_CM to 18.0,
    )

    // ---- Conflict-resolution order -----------------------------------------

    @Test
    fun `manual override beats stricter facility close`() {
        val rules = listOf(
            rule("f", scope = RuleScope.FacilitySpecific("tunnel-17"), action = Action.CLOSE),
            rule("m", scope = RuleScope.Manual("tunnel-17"), action = Action.RESTRICT),
        )
        val r = evaluate(rules, heavyInput)
        assertEquals(Action.RESTRICT, r.decision)
        assertEquals(RuleLayer.MANUAL, r.decidingLayer)
        assertEquals("m:v1", r.decidingVersionRef)
        // Facility CLOSE fired but was overridden by the higher layer.
        val fTrace = r.trace.first { it.versionRef == "f:v1" }
        assertEquals(ReasonCode.SUPERSEDED_BY_HIGHER_LAYER, fTrace.outcome)
    }

    @Test
    fun `full four-layer stack resolves to manual`() {
        val rules = listOf(
            rule("default", scope = RuleScope.Global, action = Action.MONITOR),
            rule("region", scope = RuleScope.Region("440800"), action = Action.RESTRICT),
            rule("facility", scope = RuleScope.FacilitySpecific("tunnel-17"), action = Action.CLOSE),
            rule("manual", scope = RuleScope.Manual("tunnel-17"), action = Action.RESTRICT),
        )
        val r = evaluate(rules, heavyInput)
        assertEquals(Action.RESTRICT, r.decision)
        assertEquals(RuleLayer.MANUAL, r.decidingLayer)
        // All lower layers fired but were superseded.
        assertEquals(1, r.trace.count { it.decisive })
    }

    @Test
    fun `without manual, facility close wins over region and default`() {
        val rules = listOf(
            rule("default", scope = RuleScope.Global, action = Action.MONITOR),
            rule("region", scope = RuleScope.Region("440800"), action = Action.RESTRICT),
            rule("facility", scope = RuleScope.FacilitySpecific("tunnel-17"), action = Action.CLOSE),
        )
        val r = evaluate(rules, heavyInput)
        assertEquals(Action.CLOSE, r.decision)
        assertEquals(RuleLayer.FACILITY, r.decidingLayer)
    }

    // ---- Same priority + stricter action -----------------------------------

    @Test
    fun `same layer picks stricter action`() {
        val rules = listOf(
            rule("a", scope = RuleScope.Region("440800"), action = Action.MONITOR),
            rule("b", scope = RuleScope.Region("440800"), action = Action.CLOSE),
            rule("c", scope = RuleScope.Region("440800"), action = Action.RESTRICT),
        )
        val r = evaluate(rules, heavyInput)
        assertEquals(Action.CLOSE, r.decision)
        assertEquals("b:v1", r.decidingVersionRef)
        val siblings = r.trace.filter { it.versionRef != "b:v1" && it.layer == RuleLayer.REGION }
        assertTrue(siblings.all { it.outcome == ReasonCode.SUPERSEDED_BY_STRICTER_SIBLING })
    }

    @Test
    fun `same layer same action ties break deterministically by versionRef`() {
        val rules = listOf(
            rule("z-rule", scope = RuleScope.Region("440800"), action = Action.RESTRICT),
            rule("a-rule", scope = RuleScope.Region("440800"), action = Action.RESTRICT),
        )
        val r = evaluate(rules, heavyInput)
        assertEquals(Action.RESTRICT, r.decision)
        // Tie broken by lexicographically smallest versionRef.
        assertEquals("a-rule:v1", r.decidingVersionRef)
    }

    // ---- Validity intervals -------------------------------------------------

    @Test
    fun `overlapping validity windows both fire and stricter wins`() {
        val rules = listOf(
            rule(
                "old", scope = RuleScope.Region("440800"), action = Action.RESTRICT,
                validFrom = t0.minusSeconds(7200), validUntil = t0.plusSeconds(3600),
            ),
            rule(
                "new", scope = RuleScope.Region("440800"), action = Action.CLOSE,
                validFrom = t0.minusSeconds(3600), validUntil = t0.plusSeconds(7200),
            ),
        )
        val r = evaluate(rules, heavyInput)
        assertEquals(Action.CLOSE, r.decision)
    }

    @Test
    fun `exactly at expiry is expired (exclusive upper bound)`() {
        val expiry = t0
        val rules = listOf(
            rule(
                "m", scope = RuleScope.Manual("tunnel-17"), action = Action.RESTRICT,
                validFrom = t0.minusSeconds(3600), validUntil = expiry,
            ),
            rule("f", scope = RuleScope.FacilitySpecific("tunnel-17"), action = Action.CLOSE),
        )
        // Evaluate exactly at the expiry instant: manual is EXPIRED, facility wins.
        val r = evaluate(rules, heavyInput, at = expiry)
        assertEquals(Action.CLOSE, r.decision)
        val mTrace = r.trace.first { it.versionRef == "m:v1" }
        assertEquals(ReasonCode.EXPIRED, mTrace.outcome)
    }

    @Test
    fun `one second before expiry manual still applies`() {
        val expiry = t0
        val rules = listOf(
            rule(
                "m", scope = RuleScope.Manual("tunnel-17"), action = Action.RESTRICT,
                validFrom = t0.minusSeconds(3600), validUntil = expiry,
            ),
            rule("f", scope = RuleScope.FacilitySpecific("tunnel-17"), action = Action.CLOSE),
        )
        val r = evaluate(rules, heavyInput, at = expiry.minusSeconds(1))
        assertEquals(Action.RESTRICT, r.decision)
        assertEquals(RuleLayer.MANUAL, r.decidingLayer)
    }

    @Test
    fun `not yet effective rule does not fire`() {
        val rules = listOf(
            rule(
                "future", scope = RuleScope.FacilitySpecific("tunnel-17"), action = Action.CLOSE,
                validFrom = t0.plusSeconds(3600),
            ),
        )
        val r = evaluate(rules, heavyInput)
        assertNull(r.decision)
        assertEquals(ReasonCode.NOT_YET_EFFECTIVE, r.trace.first().outcome)
    }

    // ---- Missing input ------------------------------------------------------

    @Test
    fun `missing metric yields MISSING_INPUT and rule does not fire`() {
        val rules = listOf(
            rule(
                "f", scope = RuleScope.FacilitySpecific("tunnel-17"), action = Action.CLOSE,
                condition = Condition.Threshold(RiskMetric.WATER_DEPTH_CM, Comparator.GTE, 15.0),
            ),
        )
        val inputWithoutDepth = RiskInput.of(RiskMetric.HOURLY_RAINFALL_MM to 72.0)
        val r = evaluate(rules, inputWithoutDepth)
        assertNull(r.decision)
        assertEquals(ReasonCode.MISSING_INPUT, r.trace.first().outcome)
    }

    @Test
    fun `missing input on one rule does not block another that fires`() {
        val rules = listOf(
            rule(
                "needs-depth", scope = RuleScope.FacilitySpecific("tunnel-17"), action = Action.CLOSE,
                condition = Condition.Threshold(RiskMetric.WATER_DEPTH_CM, Comparator.GTE, 15.0),
            ),
            rule(
                "needs-rain", scope = RuleScope.Region("440800"), action = Action.RESTRICT,
                condition = Condition.Threshold(RiskMetric.HOURLY_RAINFALL_MM, Comparator.GTE, 50.0),
            ),
        )
        val inputWithoutDepth = RiskInput.of(RiskMetric.HOURLY_RAINFALL_MM to 72.0)
        val r = evaluate(rules, inputWithoutDepth)
        assertEquals(Action.RESTRICT, r.decision)
        assertEquals(ReasonCode.MISSING_INPUT, r.trace.first { it.versionRef == "needs-depth:v1" }.outcome)
    }

    // ---- Scope mismatch -----------------------------------------------------

    @Test
    fun `region rule does not apply to facility in another region`() {
        val rules = listOf(
            rule("region", scope = RuleScope.Region("440800"), action = Action.CLOSE),
        )
        val r = evaluate(rules, heavyInput, target = otherFacility)
        assertNull(r.decision)
        assertEquals(ReasonCode.SCOPE_MISMATCH, r.trace.first().outcome)
    }

    // ---- Versioning ---------------------------------------------------------

    @Test
    fun `newer valid version supersedes older version of same rule`() {
        val rules = listOf(
            rule("k", version = 1, scope = RuleScope.Region("440800"), action = Action.CLOSE),
            rule("k", version = 2, scope = RuleScope.Region("440800"), action = Action.MONITOR),
        )
        val r = evaluate(rules, heavyInput)
        // v2 is active; even though v1 is stricter, it is superseded by version.
        assertEquals(Action.MONITOR, r.decision)
        assertEquals("k:v2", r.decidingVersionRef)
        assertEquals(
            ReasonCode.SUPERSEDED_BY_NEWER_VERSION,
            r.trace.first { it.versionRef == "k:v1" }.outcome,
        )
    }

    // ---- Historical replay (as-of) -----------------------------------------

    @Test
    fun `history does not peek at rules published later`() {
        val rules = listOf(
            rule(
                "m", scope = RuleScope.Manual("tunnel-17"), action = Action.RESTRICT,
                validFrom = t0.minusSeconds(7200), publishedAt = t0.plusSeconds(3600),
            ),
            rule(
                "f", scope = RuleScope.FacilitySpecific("tunnel-17"), action = Action.CLOSE,
                validFrom = t0.minusSeconds(7200), publishedAt = t0.minusSeconds(7200),
            ),
        )
        // As-of t0, the manual rule was not yet published.
        val r = evaluate(rules, heavyInput, at = t0, asOf = t0)
        assertEquals(Action.CLOSE, r.decision)
        assertEquals(
            ReasonCode.NOT_PUBLISHED_AS_OF,
            r.trace.first { it.versionRef == "m:v1" }.outcome,
        )

        // As-of later, the manual rule is visible and wins.
        val r2 = evaluate(rules, heavyInput, at = t0, asOf = t0.plusSeconds(3600))
        assertEquals(Action.RESTRICT, r2.decision)
    }

    // ---- No rule fires ------------------------------------------------------

    @Test
    fun `no matching rule yields no decision`() {
        val rules = listOf(
            rule(
                "f", scope = RuleScope.FacilitySpecific("tunnel-17"), action = Action.CLOSE,
                condition = Condition.Threshold(RiskMetric.WATER_DEPTH_CM, Comparator.GTE, 100.0),
            ),
        )
        val r = evaluate(rules, heavyInput)
        assertNull(r.decision)
        assertNull(r.decidingLayer)
        assertTrue(r.firedVersionRefs.isEmpty())
        assertEquals(ReasonCode.CONDITION_NOT_MET, r.trace.first().outcome)
    }
}
