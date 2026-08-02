package com.gsb.infra.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VersionSelectionTest {

    private val facility = Facility(
        id = "tunnel-17",
        type = FacilityType.TUNNEL,
        regionCode = "440800",
        name = "Tunnel 17"
    )

    private val chainId = "region-440800-storm"

    private val v1 = Rule(
        ruleId = chainId,
        version = 1,
        layer = RuleLayer.REGION,
        facilityId = null,
        regionCode = "440800",
        facilityTypes = emptySet(),
        conditions = listOf(Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 60.0)),
        action = Action.RESTRICT,
        reason = "v1 60mm",
        effectiveFrom = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = null,
        publishedAt = Instant.parse("2026-01-01T00:00:00Z")
    )

    private val v2 = Rule(
        ruleId = chainId,
        version = 2,
        layer = RuleLayer.REGION,
        facilityId = null,
        regionCode = "440800",
        facilityTypes = emptySet(),
        conditions = listOf(Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 75.0)),
        action = Action.RESTRICT,
        reason = "v2 75mm",
        effectiveFrom = Instant.parse("2026-08-01T04:00:00Z"),
        expiresAt = Instant.parse("2026-08-01T06:00:00Z"),
        publishedAt = Instant.parse("2026-08-01T03:30:00Z")
    )

    private val input = RiskInput.of(Metric.HOURLY_PRECIPITATION_MM to 72.0)

    private data class Case(
        val at: String,
        val selectedVersion: Int,
        val v1Matched: Boolean,
        val v2Matched: Boolean,
        val v2Reason: ReasonCode,
        val v1Superseded: Boolean,
        val label: String
    )

    private val cases = listOf(
        Case(
            at = "2026-08-01T03:59:59Z",
            selectedVersion = 1,
            v1Matched = true,
            v2Matched = false,
            v2Reason = ReasonCode.RULE_NOT_YET_EFFECTIVE,
            v1Superseded = false,
            label = "one second before window: v1 still governs"
        ),
        Case(
            at = "2026-08-01T04:00:00Z",
            selectedVersion = 2,
            v1Matched = false,
            v2Matched = false,
            v2Reason = ReasonCode.CONDITION_NOT_MET,
            v1Superseded = true,
            label = "window opens: v2 selected, 72<75 threshold miss, v1 superseded"
        ),
        Case(
            at = "2026-08-01T05:59:59Z",
            selectedVersion = 2,
            v1Matched = false,
            v2Matched = false,
            v2Reason = ReasonCode.CONDITION_NOT_MET,
            v1Superseded = true,
            label = "inside window: v2 selected with threshold miss"
        ),
        Case(
            at = "2026-08-01T06:00:00Z",
            selectedVersion = 1,
            v1Matched = true,
            v2Matched = false,
            v2Reason = ReasonCode.RULE_EXPIRED,
            v1Superseded = false,
            label = "exactly at expiry: v2 excluded, fall back to v1"
        )
    )

    @Test
    fun tableDrivenVersionSelectionAcrossWindow() {
        for (case in cases) {
            val result = RuleEvaluator.evaluate(
                RuleEvaluator.EvaluationRequest(
                    facility = facility,
                    rules = listOf(v1, v2),
                    input = input,
                    evaluatedAt = Instant.parse(case.at)
                )
            )

            val v1Trace = result.ruleTraces.first { it.ruleId == chainId && it.version == 1 }
            val v2Trace = result.ruleTraces.first { it.ruleId == chainId && it.version == 2 }

            // Visibility: v2 was published at 03:30, so it is visible at all four
            // instants. It is the effective window (not publication) that gates it.
            assertTrue(v1Trace.alreadyPublished, "${case.label}: v1 must be published")
            assertTrue(v2Trace.alreadyPublished, "${case.label}: v2 must be published")
            val expectedV2InWindow = case.at.endsWith("T04:00:00Z") ||
                case.at.endsWith("T05:59:59Z")
            assertEquals(
                expectedV2InWindow, v2Trace.inEffectiveWindow,
                "${case.label}: v2 inEffectiveWindow"
            )

            // Version selection
            val selectedTrace = result.ruleTraces.first { it.versionSelected }
            assertEquals(
                case.selectedVersion, selectedTrace.version,
                "${case.label}: selected version"
            )
            assertTrue(selectedTrace.versionSelected, "${case.label}: selected flag")

            // Matched status and reason codes
            assertEquals(case.v1Matched, v1Trace.matched, "${case.label}: v1 matched")
            assertEquals(case.v2Matched, v2Trace.matched, "${case.label}: v2 matched")
            assertEquals(case.v2Reason, v2Trace.reasonCode, "${case.label}: v2 reason")
            if (case.v1Superseded) {
                assertEquals(2, v1Trace.supersededByVersion, "${case.label}: v1 superseded by v2")
                assertEquals(
                    ReasonCode.SUPERSEDED_BY_NEWER_VERSION, v1Trace.reasonCode,
                    "${case.label}: v1 reason superseded"
                )
            } else {
                assertEquals(null, v1Trace.supersededByVersion, "${case.label}: v1 not superseded")
            }

            // v2's condition outcome records the threshold miss (72 < 75) even when
            // it is the selected version inside the window.
            val v2Outcome = v2Trace.conditionOutcomes.single()
            if (case.selectedVersion == 2) {
                assertFalse(v2Trace.conditionsMet, "${case.label}: v2 conditions not met")
                assertEquals(72.0, v2Outcome.actualValue)
                assertEquals(75.0, v2Outcome.threshold)
                assertEquals(ReasonCode.CONDITION_NOT_MET, v2Outcome.reasonCode)
            }

            // Layer decision records the REGION layer. When v2 is selected but misses,
            // REGION contributes NONE; when v1 matches it contributes RESTRICT.
            val regionDecision = result.layerDecisions.first { it.layer == RuleLayer.REGION }
            if (case.selectedVersion == 1) {
                assertEquals(Action.RESTRICT, regionDecision.strictestAction)
                assertEquals(1, regionDecision.winningVersion)
                assertEquals(Action.RESTRICT, result.finalAction)
            } else {
                assertEquals(Action.NONE, regionDecision.strictestAction)
                assertEquals(null, regionDecision.winningVersion)
                // The DEFAULT layer (no rules here) leaves NONE since only the REGION
                // chain is present in this isolated test.
                assertEquals(Action.NONE, result.finalAction)
            }

            // Content hash is always populated and stable.
            assertTrue(result.contentHash.matches(Regex("[0-9a-f]{64}")), "${case.label}: hash shape")
        }
    }

    @Test
    fun `shuffled rule input yields identical canonical JSON and contentHash`() {
        val at = Instant.parse("2026-08-01T05:00:00Z")

        val ordered = RuleEvaluator.evaluate(
            RuleEvaluator.EvaluationRequest(facility, listOf(v1, v2), input, at)
        )
        val shuffled = RuleEvaluator.evaluate(
            RuleEvaluator.EvaluationRequest(facility, listOf(v2, v1), input, at)
        )

        // Byte-level identical serialization despite different input ordering.
        val a = Json.encodeToString(EvaluationResult.serializer(), ordered)
        val b = Json.encodeToString(EvaluationResult.serializer(), shuffled)
        assertEquals(a, b)

        // The canonical hash is unchanged.
        assertEquals(ordered.contentHash, shuffled.contentHash)

        // And recomputing over the serialized result reproduces the same hash.
        assertEquals(ordered.contentHash, CanonicalHasher.hash(ordered))
        assertNotNull(ordered.contentHash)
    }

    @Test
    fun `publishing a newer version does not retire v1 before v2 window opens`() {
        // Even though v2 was published at 03:30, at 03:59:59 its window has not
        // opened, so the eligible (active) version is v1. v2 is visible but not
        // effective; v1 must not be marked superseded.
        val result = RuleEvaluator.evaluate(
            RuleEvaluator.EvaluationRequest(
                facility, listOf(v1, v2), input,
                Instant.parse("2026-08-01T03:59:59Z")
            )
        )
        val v1Trace = result.ruleTraces.first { it.version == 1 }
        val v2Trace = result.ruleTraces.first { it.version == 2 }
        assertTrue(v1Trace.versionSelected)
        assertTrue(v1Trace.matched)
        assertEquals(null, v1Trace.supersededByVersion)
        assertFalse(v2Trace.inEffectiveWindow)
        assertEquals(ReasonCode.RULE_NOT_YET_EFFECTIVE, v2Trace.reasonCode)
    }
}
