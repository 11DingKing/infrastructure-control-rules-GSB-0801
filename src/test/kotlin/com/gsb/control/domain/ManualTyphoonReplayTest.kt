package com.gsb.control.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Scenario: a short-lived manual typhoon override that forces CLOSE only inside
 * [05:00Z, 05:30Z), published at 04:50Z. Verifies:
 *  - inside the window the manual layer wins (CLOSE);
 *  - once it expires the decision falls back to the next-best rule chosen in
 *    "round 2" (here the region RESTRICT rule), never lingering on the manual;
 *  - a historical replay as-of 04:45Z (before the override was published) cannot
 *    see the typhoon rule at all — it is NOT_PUBLISHED_AS_OF and non-decisive.
 */
class ManualTyphoonReplayTest {

    private val facility = Facility("tunnel-17", FacilityKind.TUNNEL, "440800", "Tunnel 17")
    private val input = RiskInput.of(RiskMetric.HOURLY_RAINFALL_MM to 72.0)

    // Region fallback: rainfall >= 60 -> RESTRICT, effective all day, long published.
    private val regionV1 = Rule(
        ruleKey = "region-440800-storm",
        version = 1,
        scope = RuleScope.Region("440800"),
        condition = Condition.Threshold(RiskMetric.HOURLY_RAINFALL_MM, Comparator.GTE, 60.0),
        action = Action.RESTRICT,
        validFrom = Instant.parse("2026-07-01T00:00:00Z"),
        validUntil = null,
        publishedAt = Instant.parse("2026-07-01T00:00:00Z"),
        description = "region storm v1: rainfall >= 60 -> RESTRICT",
    )

    // Manual typhoon override: CLOSE, effective [05:00Z, 05:30Z), published 04:50Z.
    private val typhoonV2 = Rule(
        ruleKey = "manual-tunnel-17-typhoon",
        version = 2,
        scope = RuleScope.Manual("tunnel-17"),
        condition = Condition.Always,
        action = Action.CLOSE,
        validFrom = Instant.parse("2026-08-01T05:00:00Z"),
        validUntil = Instant.parse("2026-08-01T05:30:00Z"),
        publishedAt = Instant.parse("2026-08-01T04:50:00Z"),
        description = "manual typhoon v2: force CLOSE in [05:00,05:30)",
    )

    private val rules = listOf(regionV1, typhoonV2)

    private fun evalAt(at: String, asOf: String = at): EvaluationResult =
        RuleEvaluator.evaluate(facility, rules, input, Instant.parse(at), Instant.parse(asOf))

    private fun typhoon(r: EvaluationResult) =
        r.trace.first { it.versionRef == "manual-tunnel-17-typhoon:v2" }

    @Test
    fun `just before window region rule holds and typhoon not yet effective`() {
        val r = evalAt("2026-08-01T04:59:59Z")
        assertEquals(Action.RESTRICT, r.decision)
        assertEquals(RuleLayer.REGION, r.decidingLayer)
        assertEquals("region-440800-storm:v1", r.decidingVersionRef)

        val t = typhoon(r)
        assertEquals(ReasonCode.NOT_YET_EFFECTIVE, t.outcome)
        assertEquals(WindowState.NOT_YET_EFFECTIVE, t.breakdown.window)
        assertEquals(Visibility.VISIBLE, t.breakdown.visibility) // published 04:50
        assertFalse(t.decisive)
    }

    @Test
    fun `at window start manual typhoon forces CLOSE`() {
        val r = evalAt("2026-08-01T05:00:00Z")
        assertEquals(Action.CLOSE, r.decision)
        assertEquals(RuleLayer.MANUAL, r.decidingLayer)
        assertEquals("manual-tunnel-17-typhoon:v2", r.decidingVersionRef)
        assertEquals(PriorityResolution.DECISIVE, typhoon(r).breakdown.priority)
    }

    @Test
    fun `at window end typhoon expires and decision falls back to region rule`() {
        val r = evalAt("2026-08-01T05:30:00Z")
        // Exclusive upper bound: exactly at 05:30 the override is EXPIRED.
        assertEquals(Action.RESTRICT, r.decision)
        assertEquals(RuleLayer.REGION, r.decidingLayer)
        assertEquals("region-440800-storm:v1", r.decidingVersionRef)

        val t = typhoon(r)
        assertEquals(ReasonCode.EXPIRED, t.outcome)
        assertEquals(WindowState.EXPIRED, t.breakdown.window)
        assertFalse(t.decisive)
    }

    @Test
    fun `history as-of before publish cannot see the typhoon rule`() {
        // Replay 05:15 (inside the window) but as-of 04:45 — before the typhoon
        // rule was published at 04:50. It must be invisible, so the historical
        // decision is the region RESTRICT, not CLOSE.
        val r = evalAt(at = "2026-08-01T05:15:00Z", asOf = "2026-08-01T04:45:00Z")
        assertEquals(Action.RESTRICT, r.decision)
        assertEquals(RuleLayer.REGION, r.decidingLayer)
        assertEquals("region-440800-storm:v1", r.decidingVersionRef)

        val t = typhoon(r)
        assertEquals(ReasonCode.NOT_PUBLISHED_AS_OF, t.outcome)
        assertEquals(Visibility.NOT_PUBLISHED, t.breakdown.visibility)
        assertFalse(t.decisive)
    }

    @Test
    fun `as-of at publish instant makes the typhoon rule visible again`() {
        // Same 05:15 replay but as-of 04:50 (publish instant, inclusive) — now
        // visible and, being effective at 05:15, it wins with CLOSE.
        val r = evalAt(at = "2026-08-01T05:15:00Z", asOf = "2026-08-01T04:50:00Z")
        assertEquals(Action.CLOSE, r.decision)
        assertEquals("manual-tunnel-17-typhoon:v2", r.decidingVersionRef)
        assertEquals(Visibility.VISIBLE, typhoon(r).breakdown.visibility)
    }
}
