package com.gsb.control.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Scenario: publishing region-440800-storm v2 with a raised rainfall threshold
 * and a bounded effective window [04:00Z, 06:00Z). Verifies that a
 * published-but-not-yet-effective (or already-expired) v2 never prematurely
 * retires the currently-effective v1, and that inside the window v2 is selected
 * (retiring v1) yet records an independent threshold miss.
 */
class RegionVersionWindowTest {

    private val facility = Facility("tunnel-17", FacilityKind.TUNNEL, "440800", "Tunnel 17")

    private val input72 = RiskInput.of(RiskMetric.HOURLY_RAINFALL_MM to 72.0)

    // v1: effective since long ago, no expiry, rainfall >= 60 -> RESTRICT.
    private val v1 = Rule(
        ruleKey = "region-440800-storm",
        version = 1,
        scope = RuleScope.Region("440800"),
        condition = Condition.Threshold(RiskMetric.HOURLY_RAINFALL_MM, Comparator.GTE, 60.0),
        action = Action.RESTRICT,
        validFrom = Instant.parse("2026-07-01T00:00:00Z"),
        validUntil = null,
        publishedAt = Instant.parse("2026-07-01T00:00:00Z"),
        description = "storm v1: rainfall >= 60",
    )

    // v2: published 03:30Z, effective [04:00Z, 06:00Z), rainfall >= 75 -> RESTRICT.
    private val v2 = v1.copy(
        version = 2,
        condition = Condition.Threshold(RiskMetric.HOURLY_RAINFALL_MM, Comparator.GTE, 75.0),
        validFrom = Instant.parse("2026-08-01T04:00:00Z"),
        validUntil = Instant.parse("2026-08-01T06:00:00Z"),
        publishedAt = Instant.parse("2026-08-01T03:30:00Z"),
        description = "storm v2: rainfall >= 75",
    )

    private val rules = listOf(v1, v2)

    private fun evalAt(iso: String): EvaluationResult {
        val at = Instant.parse(iso)
        // asOf tracks the evaluation instant; v2 is already published by 03:30Z.
        return RuleEvaluator.evaluate(facility, rules, input72, at, at)
    }

    private fun trace(r: EvaluationResult, ver: Int) =
        r.trace.first { it.versionRef == "region-440800-storm:v$ver" }

    @Test
    fun `just before window v1 applies and v2 is not yet effective`() {
        val r = evalAt("2026-08-01T03:59:59Z")
        assertEquals(Action.RESTRICT, r.decision)
        assertEquals("region-440800-storm:v1", r.decidingVersionRef)

        val t1 = trace(r, 1)
        val t2 = trace(r, 2)
        // v1: visible, in scope, effective, selected, condition met, decisive.
        assertEquals(Visibility.VISIBLE, t1.breakdown.visibility)
        assertEquals(WindowState.EFFECTIVE, t1.breakdown.window)
        assertEquals(VersionSelection.SELECTED, t1.breakdown.versionSelection)
        assertEquals(ConditionState.MET, t1.breakdown.condition)
        assertEquals(PriorityResolution.DECISIVE, t1.breakdown.priority)
        // v2: visible but not yet effective; NOT retiring v1.
        assertEquals(Visibility.VISIBLE, t2.breakdown.visibility)
        assertEquals(WindowState.NOT_YET_EFFECTIVE, t2.breakdown.window)
        assertEquals(VersionSelection.NOT_APPLICABLE, t2.breakdown.versionSelection)
        assertEquals(ReasonCode.NOT_YET_EFFECTIVE, t2.outcome)
    }

    @Test
    fun `at window start v2 is selected and records threshold miss, v1 retired`() {
        val r = evalAt("2026-08-01T04:00:00Z")
        // v2 selected but 72 < 75, v1 retired by newer version -> region does not fire.
        assertNull(r.decision)

        val t1 = trace(r, 1)
        val t2 = trace(r, 2)
        // v2: effective, selected, threshold NOT met.
        assertEquals(WindowState.EFFECTIVE, t2.breakdown.window)
        assertEquals(VersionSelection.SELECTED, t2.breakdown.versionSelection)
        assertEquals(ConditionState.NOT_MET, t2.breakdown.condition)
        assertEquals(ReasonCode.CONDITION_NOT_MET, t2.outcome)
        // v1: superseded by the newer, now-effective v2 (only inside the window).
        assertEquals(VersionSelection.SUPERSEDED_BY_NEWER_VERSION, t1.breakdown.versionSelection)
        assertEquals(ReasonCode.SUPERSEDED_BY_NEWER_VERSION, t1.outcome)
    }

    @Test
    fun `just before window end v2 still selected with threshold miss`() {
        val r = evalAt("2026-08-01T05:59:59Z")
        assertNull(r.decision)
        assertEquals(ReasonCode.CONDITION_NOT_MET, trace(r, 2).outcome)
        assertEquals(ReasonCode.SUPERSEDED_BY_NEWER_VERSION, trace(r, 1).outcome)
    }

    @Test
    fun `at window end v2 expired and v1 applies again`() {
        val r = evalAt("2026-08-01T06:00:00Z")
        assertEquals(Action.RESTRICT, r.decision)
        assertEquals("region-440800-storm:v1", r.decidingVersionRef)

        val t1 = trace(r, 1)
        val t2 = trace(r, 2)
        // v2: expired (exclusive upper bound) -> not applicable, cannot retire v1.
        assertEquals(WindowState.EXPIRED, t2.breakdown.window)
        assertEquals(VersionSelection.NOT_APPLICABLE, t2.breakdown.versionSelection)
        assertEquals(ReasonCode.EXPIRED, t2.outcome)
        // v1: selected again and decisive.
        assertEquals(VersionSelection.SELECTED, t1.breakdown.versionSelection)
        assertEquals(PriorityResolution.DECISIVE, t1.breakdown.priority)
    }

    @Test
    fun `canonical json and contentHash are invariant under rule reordering`() {
        val at = Instant.parse("2026-08-01T05:00:00Z")
        val forward = RuleEvaluator.evaluate(facility, listOf(v1, v2), input72, at, at)
        val reversed = RuleEvaluator.evaluate(facility, listOf(v2, v1), input72, at, at)
        assertEquals(forward.canonicalJson(), reversed.canonicalJson())
        assertEquals(forward.contentHash, reversed.contentHash)
    }
}
