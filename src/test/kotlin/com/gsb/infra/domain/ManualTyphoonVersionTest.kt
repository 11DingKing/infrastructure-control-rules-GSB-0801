package com.gsb.infra.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualTyphoonVersionTest {

    private val facility = Facility(
        id = "tunnel-17",
        type = FacilityType.TUNNEL,
        regionCode = "440800",
        name = "Tunnel 17"
    )

    // Round-2 region storm chain (72mm falls between v1's 60 and v2's 75).
    private val stormV1 = Rule(
        ruleId = "region-440800-storm", version = 1,
        layer = RuleLayer.REGION, facilityId = null, regionCode = "440800",
        facilityTypes = emptySet(),
        conditions = listOf(Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 60.0)),
        action = Action.RESTRICT, reason = "storm v1 60mm",
        effectiveFrom = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = null,
        publishedAt = Instant.parse("2026-01-01T00:00:00Z")
    )
    private val stormV2 = Rule(
        ruleId = "region-440800-storm", version = 2,
        layer = RuleLayer.REGION, facilityId = null, regionCode = "440800",
        facilityTypes = emptySet(),
        conditions = listOf(Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 75.0)),
        action = Action.RESTRICT, reason = "storm v2 75mm",
        effectiveFrom = Instant.parse("2026-08-01T04:00:00Z"),
        expiresAt = Instant.parse("2026-08-01T06:00:00Z"),
        publishedAt = Instant.parse("2026-08-01T03:30:00Z")
    )

    // Round-2 facility rule (18cm >= 15cm => RESTRICT), the fallback winner when
    // the manual typhoon rule is not active/visible.
    private val facilityWater = Rule(
        ruleId = "facility-tunnel-17-water-restrict", version = 1,
        layer = RuleLayer.FACILITY, facilityId = "tunnel-17", regionCode = null,
        facilityTypes = emptySet(),
        conditions = listOf(Condition(Metric.WATER_DEPTH_CM, Operator.GTE, 15.0)),
        action = Action.RESTRICT, reason = "facility water 15cm",
        effectiveFrom = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = null,
        publishedAt = Instant.parse("2026-01-01T00:00:00Z")
    )

    // The manual typhoon override v2: CLOSE only in [05:00,05:30), published 04:50.
    private val typhoonV2 = Rule(
        ruleId = "manual-tunnel-17-typhoon", version = 2,
        layer = RuleLayer.MANUAL, facilityId = "tunnel-17", regionCode = null,
        facilityTypes = emptySet(),
        conditions = emptyList(),
        action = Action.CLOSE, reason = "typhoon forced close",
        effectiveFrom = Instant.parse("2026-08-01T05:00:00Z"),
        expiresAt = Instant.parse("2026-08-01T05:30:00Z"),
        publishedAt = Instant.parse("2026-08-01T04:50:00Z")
    )

    private val allRules = listOf(stormV1, stormV2, facilityWater, typhoonV2)

    // The scenario's risk input: 72mm rain, wind 7, water 18cm.
    private val input = RiskInput.of(
        Metric.HOURLY_PRECIPITATION_MM to 72.0,
        Metric.WIND_LEVEL to 7.0,
        Metric.WATER_DEPTH_CM to 18.0
    )

    private fun typhoonTrace(result: EvaluationResult): RuleTrace =
        result.ruleTraces.first { it.ruleId == "manual-tunnel-17-typhoon" }

    private data class ReplayCase(
        val at: String,
        val expectClose: Boolean,
        val typhoonVisible: Boolean,
        val typhoonInWindow: Boolean,
        val typhoonMatched: Boolean,
        val typhoonReason: ReasonCode,
        val label: String
    )

    private val replayCases = listOf(
        ReplayCase(
            at = "2026-08-01T04:59:59Z",
            expectClose = false,
            typhoonVisible = true,
            typhoonInWindow = false,
            typhoonMatched = false,
            typhoonReason = ReasonCode.RULE_NOT_YET_EFFECTIVE,
            label = "one second before window: typhoon not effective, fallback to facility rule"
        ),
        ReplayCase(
            at = "2026-08-01T05:00:00Z",
            expectClose = true,
            typhoonVisible = true,
            typhoonInWindow = true,
            typhoonMatched = true,
            typhoonReason = ReasonCode.RULE_MATCHED,
            label = "window opens: typhoon CLOSE wins"
        ),
        ReplayCase(
            at = "2026-08-01T05:30:00Z",
            expectClose = false,
            typhoonVisible = true,
            typhoonInWindow = false,
            typhoonMatched = false,
            typhoonReason = ReasonCode.RULE_EXPIRED,
            label = "exactly at expiry: typhoon excluded, fallback to facility rule"
        )
    )

    @Test
    fun tableDrivenReplayAcrossTyphoonWindow() {
        for (case in replayCases) {
            val result = RuleEvaluator.evaluate(
                RuleEvaluator.EvaluationRequest(
                    facility = facility,
                    rules = allRules,
                    input = input,
                    evaluatedAt = Instant.parse(case.at)
                )
            )
            val t = typhoonTrace(result)

            assertEquals(case.typhoonVisible, t.alreadyPublished, "${case.label}: visible")
            assertEquals(case.typhoonInWindow, t.inEffectiveWindow, "${case.label}: inWindow")
            assertEquals(case.typhoonMatched, t.matched, "${case.label}: matched")
            assertEquals(case.typhoonReason, t.reasonCode, "${case.label}: typhoon reason")
            // The asOf defaults to evaluatedAt for a normal replay.
            assertEquals(case.at, t.asOf, "${case.label}: asOf equals evaluatedAt")

            if (case.expectClose) {
                assertEquals(Action.CLOSE, result.finalAction, case.label)
                assertEquals("manual-tunnel-17-typhoon", result.winningRuleId, case.label)
                assertEquals(2, result.winningVersion, case.label)
                assertEquals(RuleLayer.MANUAL, result.winningLayer, case.label)
                assertTrue(t.selected, "${case.label}: typhoon trace selected")
            } else {
                // After the manual rule expires (or before it starts), control must
                // fall back to the round-2 facility/region winner: the FACILITY
                // water-restrict rule (18cm >= 15cm) overrides the REGION storm
                // chain, whose v2 misses its 75mm threshold.
                assertEquals(Action.RESTRICT, result.finalAction, case.label)
                assertEquals("facility-tunnel-17-water-restrict", result.winningRuleId, case.label)
                assertEquals(RuleLayer.FACILITY, result.winningLayer, case.label)
                assertFalse(t.matched, "${case.label}: typhoon must not match")
                // The typhoon trace still exists in the chain for auditability.
                assertEquals("manual-tunnel-17-typhoon", t.ruleId)
            }

            // No trace should ever reference a rule as not-published during these
            // normal replays (publishedAt 04:50 <= all three instants).
            assertTrue(
                result.ruleTraces.none { it.reasonCode == ReasonCode.RULE_NOT_YET_PUBLISHED },
                "${case.label}: no RULE_NOT_YET_PUBLISHED at replay time"
            )
        }
    }

    @Test
    fun historicalAsOfBeforePublishHidesTyphoonV2AndFallsBack() {
        // Replay 05:15 (inside the typhoon window) but with visibility cutoff
        // asOf=04:45, before typhoon v2 was published at 04:50.
        val evaluatedAt = Instant.parse("2026-08-01T05:15:00Z")
        val asOf = Instant.parse("2026-08-01T04:45:00Z")

        val result = RuleEvaluator.evaluate(
            RuleEvaluator.EvaluationRequest(
                facility = facility,
                rules = allRules,
                input = input,
                evaluatedAt = evaluatedAt,
                asOf = asOf
            )
        )

        val t = typhoonTrace(result)

        // The typhoon rule is inside its effective window at 05:15...
        assertTrue(t.inEffectiveWindow, "typhoon window is open at 05:15")
        // ...but it is NOT visible as of 04:45.
        assertFalse(t.alreadyPublished, "typhoon v2 must not be visible as of 04:45")
        assertEquals(ReasonCode.RULE_NOT_YET_PUBLISHED, t.reasonCode)
        assertFalse(t.versionSelected, "unpublished version cannot be selected")
        assertFalse(t.matched, "unpublished version cannot match")
        assertEquals(asOf.toString(), t.asOf, "trace records the asOf cutoff")

        // Final result must fall back to the round-2 facility rule, NOT close.
        assertEquals(Action.RESTRICT, result.finalAction)
        assertEquals("facility-tunnel-17-water-restrict", result.winningRuleId)
        assertEquals(RuleLayer.FACILITY, result.winningLayer)

        // Region storm v2 IS visible (published 03:30) and in window, but its 75mm
        // threshold is missed, so the region contributes NONE; facility wins.
        val stormV2Trace = result.ruleTraces.first {
            it.ruleId == "region-440800-storm" && it.version == 2
        }
        assertTrue(stormV2Trace.alreadyPublished)
        assertTrue(stormV2Trace.versionSelected)
        assertFalse(stormV2Trace.matched)
        assertEquals(ReasonCode.CONDITION_NOT_MET, stormV2Trace.reasonCode)
        assertEquals(72.0, stormV2Trace.conditionOutcomes.single().actualValue)
        assertEquals(75.0, stormV2Trace.conditionOutcomes.single().threshold)

        // The explanation mentions the asOf cutoff.
        assertTrue(result.explanation.contains("as of 2026-08-01T04:45:00Z"))
    }

    @Test
    fun asOfDoesNotAffectEarlierVisibleRules() {
        // With asOf=04:45 the typhoon is invisible but storm v2 (published 03:30)
        // and all earlier rules remain visible and govern normally.
        val result = RuleEvaluator.evaluate(
            RuleEvaluator.EvaluationRequest(
                facility = facility,
                rules = allRules,
                input = input,
                evaluatedAt = Instant.parse("2026-08-01T05:15:00Z"),
                asOf = Instant.parse("2026-08-01T04:45:00Z")
            )
        )
        val visibleRuleIds = result.ruleTraces
            .filter { it.alreadyPublished }
            .map { it.ruleId to it.version }
        assertTrue("region-440800-storm" to 1 in visibleRuleIds)
        assertTrue("region-440800-storm" to 2 in visibleRuleIds)
        assertTrue("facility-tunnel-17-water-restrict" to 1 in visibleRuleIds)
        assertTrue("manual-tunnel-17-typhoon" to 2 !in visibleRuleIds)
        assertNull(result.winningRuleId?.takeIf { it == "manual-tunnel-17-typhoon" })
    }

    @Test
    fun liveEvaluationAt0515ClosesButHistoricalAsOfDoesNot() {
        val at = Instant.parse("2026-08-01T05:15:00Z")
        val live = RuleEvaluator.evaluate(
            RuleEvaluator.EvaluationRequest(facility, allRules, input, at)
        )
        val historical = RuleEvaluator.evaluate(
            RuleEvaluator.EvaluationRequest(
                facility, allRules, input, at,
                asOf = Instant.parse("2026-08-01T04:45:00Z")
            )
        )
        assertEquals(Action.CLOSE, live.finalAction)
        assertEquals("manual-tunnel-17-typhoon", live.winningRuleId)
        assertEquals(Action.RESTRICT, historical.finalAction)
        assertEquals("facility-tunnel-17-water-restrict", historical.winningRuleId)
        // Different asOf => different contentHash (different decision basis).
        assertTrue(live.contentHash != historical.contentHash)
    }
}
