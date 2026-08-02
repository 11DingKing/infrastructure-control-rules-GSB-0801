package com.infra.engine

import com.infra.domain.Action
import com.infra.domain.Facility
import com.infra.domain.FacilityType
import com.infra.domain.ReasonCode
import com.infra.domain.RiskInput
import com.infra.domain.Rule
import com.infra.domain.RuleCondition
import com.infra.domain.RuleLayer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TyphoonManualRuleTest {

    private val facility = Facility(
        id = "tunnel-17",
        name = "海滨隧道 17 号",
        type = FacilityType.TUNNEL,
        regionCode = "440800"
    )

    private val epoch0 = 0L
    private val stormV2Published = Instant.parse("2026-08-01T03:30:00Z").toEpochMilli()
    private val stormV2ValidFrom = Instant.parse("2026-08-01T04:00:00Z").toEpochMilli()
    private val stormV2ValidTo = Instant.parse("2026-08-01T06:00:00Z").toEpochMilli()

    private val typhoonV1ValidFrom = Instant.parse("2026-07-31T00:00:00Z").toEpochMilli()
    private val typhoonV1ValidTo = Instant.parse("2026-07-31T12:00:00Z").toEpochMilli()
    private val typhoonV2Published = Instant.parse("2026-08-01T04:50:00Z").toEpochMilli()
    private val typhoonV2ValidFrom = Instant.parse("2026-08-01T05:00:00Z").toEpochMilli()
    private val typhoonV2ValidTo = Instant.parse("2026-08-01T05:30:00Z").toEpochMilli()

    private val manualCloseExpires = Instant.parse("2026-07-31T23:59:59Z").toEpochMilli()

    private val t04_59_59 = Instant.parse("2026-08-01T04:59:59Z").toEpochMilli()
    private val t05_00_00 = Instant.parse("2026-08-01T05:00:00Z").toEpochMilli()
    private val t05_15_00 = Instant.parse("2026-08-01T05:15:00Z").toEpochMilli()
    private val t05_30_00 = Instant.parse("2026-08-01T05:30:00Z").toEpochMilli()
    private val t04_45_00 = Instant.parse("2026-08-01T04:45:00Z").toEpochMilli()

    private fun allRules(): List<Rule> = listOf(
        Rule(
            id = "default-tunnel-rainfall",
            layer = RuleLayer.DEFAULT,
            facilityType = FacilityType.TUNNEL,
            condition = RuleCondition(minRainfallMm = 50.0),
            action = Action.MONITOR,
            version = 1,
            publishedAt = epoch0,
            validFrom = epoch0,
            description = "default"
        ),
        Rule(
            id = "region-440800-storm",
            layer = RuleLayer.REGION,
            regionCode = "440800",
            condition = RuleCondition(minRainfallMm = 60.0),
            action = Action.RESTRICT,
            version = 1,
            publishedAt = epoch0,
            validFrom = epoch0,
            description = "storm v1"
        ),
        Rule(
            id = "region-440800-storm",
            layer = RuleLayer.REGION,
            regionCode = "440800",
            condition = RuleCondition(minRainfallMm = 75.0),
            action = Action.RESTRICT,
            version = 2,
            publishedAt = stormV2Published,
            validFrom = stormV2ValidFrom,
            validTo = stormV2ValidTo,
            description = "storm v2"
        ),
        Rule(
            id = "region-440800-rain-wind",
            layer = RuleLayer.REGION,
            regionCode = "440800",
            condition = RuleCondition(minRainfallMm = 70.0, minWindLevel = 6),
            action = Action.RESTRICT,
            version = 1,
            publishedAt = epoch0,
            validFrom = epoch0,
            description = "rain-wind"
        ),
        Rule(
            id = "region-440800-water-depth",
            layer = RuleLayer.REGION,
            regionCode = "440800",
            condition = RuleCondition(minWaterDepthCm = 15.0),
            action = Action.RESTRICT,
            version = 1,
            publishedAt = epoch0,
            validFrom = epoch0,
            description = "water-depth"
        ),
        Rule(
            id = "manual-tunnel-17-close",
            layer = RuleLayer.MANUAL,
            facilityId = "tunnel-17",
            condition = RuleCondition(),
            action = Action.CLOSE,
            version = 1,
            publishedAt = epoch0,
            validFrom = epoch0,
            validTo = manualCloseExpires,
            description = "manual close expired"
        ),
        Rule(
            id = "manual-tunnel-17-typhoon",
            layer = RuleLayer.MANUAL,
            facilityId = "tunnel-17",
            condition = RuleCondition(),
            action = Action.CLOSE,
            version = 1,
            publishedAt = epoch0,
            validFrom = typhoonV1ValidFrom,
            validTo = typhoonV1ValidTo,
            description = "typhoon v1 expired"
        ),
        Rule(
            id = "manual-tunnel-17-typhoon",
            layer = RuleLayer.MANUAL,
            facilityId = "tunnel-17",
            condition = RuleCondition(),
            action = Action.CLOSE,
            version = 2,
            publishedAt = typhoonV2Published,
            validFrom = typhoonV2ValidFrom,
            validTo = typhoonV2ValidTo,
            description = "typhoon v2"
        )
    )

    private fun input72(requestId: String) = RiskInput(
        facilityId = "tunnel-17",
        hourlyRainfallMm = 72.0,
        windLevel = 7,
        waterDepthCm = 18.0,
        observedAt = 0L,
        requestId = requestId
    )

    data class TimePointCase(
        val label: String,
        val evalTime: Long,
        val expectedAction: Action?,
        val expectedHitRuleId: String?,
        val expectedHitVersion: Int?,
        val expectedReason: ReasonCode,
        val expectTyphoonV2Visible: Boolean,
        val expectTyphoonV2Active: Boolean,
        val expectTyphoonV2Selected: Boolean,
        val expectStormV2Selected: Boolean
    )

    @Test
    fun `typhoon v2 window boundaries and fallback to region rules`() {
        val rules = allRules()

        val cases = listOf(
            TimePointCase(
                label = "04:59:59 - typhoon v2 not yet valid, region RESTRICT",
                evalTime = t04_59_59,
                expectedAction = Action.RESTRICT,
                expectedHitRuleId = "region-440800-rain-wind",
                expectedHitVersion = 1,
                expectedReason = ReasonCode.OK,
                expectTyphoonV2Visible = true,
                expectTyphoonV2Active = false,
                expectTyphoonV2Selected = false,
                expectStormV2Selected = true
            ),
            TimePointCase(
                label = "05:00:00 - typhoon v2 becomes valid, force CLOSE",
                evalTime = t05_00_00,
                expectedAction = Action.CLOSE,
                expectedHitRuleId = "manual-tunnel-17-typhoon",
                expectedHitVersion = 2,
                expectedReason = ReasonCode.OK,
                expectTyphoonV2Visible = true,
                expectTyphoonV2Active = true,
                expectTyphoonV2Selected = true,
                expectStormV2Selected = true
            ),
            TimePointCase(
                label = "05:30:00 - typhoon v2 expires right-exclusive, fall back to RESTRICT",
                evalTime = t05_30_00,
                expectedAction = Action.RESTRICT,
                expectedHitRuleId = "region-440800-rain-wind",
                expectedHitVersion = 1,
                expectedReason = ReasonCode.OK,
                expectTyphoonV2Visible = true,
                expectTyphoonV2Active = false,
                expectTyphoonV2Selected = false,
                expectStormV2Selected = true
            )
        )

        for (case in cases) {
            val result = RuleEngine.evaluate(
                RuleEngine.EvaluationContext(
                    facility = facility,
                    rules = rules,
                    input = input72("typhoon-${case.label.take(8)}"),
                    evaluationTime = case.evalTime
                )
            )

            assertEquals(case.expectedAction, result.finalAction, "action: ${case.label}")
            assertEquals(case.expectedHitRuleId, result.hitRuleId, "hit rule: ${case.label}")
            assertEquals(case.expectedHitVersion, result.hitRuleVersion, "hit version: ${case.label}")
            assertEquals(case.expectedReason, result.reasonCode, "reason: ${case.label}")
            when (case.expectedAction) {
                Action.CLOSE -> assertEquals(RuleLayer.MANUAL, result.hitRuleLayer)
                Action.RESTRICT -> assertEquals(RuleLayer.REGION, result.hitRuleLayer)
                else -> {}
            }

            val typhoonV2 = result.explanationChain.firstOrNull {
                it.ruleId == "manual-tunnel-17-typhoon" && it.version == 2
            }
            assertTrue(typhoonV2 != null, "typhoon v2 should be in explanation: ${case.label}")
            assertEquals(case.expectTyphoonV2Visible, typhoonV2.visibleAtEvaluation, "typhoon v2 visible: ${case.label}")
            assertEquals(case.expectTyphoonV2Active, typhoonV2.activeAtEvaluationTime, "typhoon v2 active: ${case.label}")
            assertEquals(case.expectTyphoonV2Selected, typhoonV2.selectedAsActiveVersion, "typhoon v2 selected: ${case.label}")

            val stormV2 = result.explanationChain.first {
                it.ruleId == "region-440800-storm" && it.version == 2
            }
            assertEquals(case.expectStormV2Selected, stormV2.selectedAsActiveVersion, "storm v2 selected: ${case.label}")
            if (case.expectStormV2Selected) {
                assertFalse(stormV2.matched, "storm v2 72 < 75 should not match: ${case.label}")
                assertTrue(
                    stormV2.reasons.any { it.contains("hourlyRainfallMm=72.0") && it.contains("minRainfallMm=75.0") },
                    "storm v2 threshold miss reason: ${case.label}"
                )
            }
        }
    }

    @Test
    fun `asOf 04-45 hides typhoon v2 published at 04-50`() {
        val rules = allRules()

        val fullResult = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(
                facility = facility,
                rules = rules,
                input = input72("typhoon-full-0515"),
                evaluationTime = t05_15_00
            )
        )
        assertEquals(Action.CLOSE, fullResult.finalAction)
        assertEquals("manual-tunnel-17-typhoon", fullResult.hitRuleId)
        assertEquals(2, fullResult.hitRuleVersion)

        val historicalResult = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(
                facility = facility,
                rules = rules,
                input = input72("typhoon-asof-0515"),
                evaluationTime = t05_15_00,
                asOf = t04_45_00
            )
        )

        assertEquals(Action.RESTRICT, historicalResult.finalAction)
        assertEquals("region-440800-rain-wind", historicalResult.hitRuleId)
        assertEquals(ReasonCode.OK, historicalResult.reasonCode)
        assertEquals(t04_45_00, historicalResult.asOf)

        val typhoonV2Entry = historicalResult.explanationChain.firstOrNull {
            it.ruleId == "manual-tunnel-17-typhoon" && it.version == 2
        }
        assertNull(
            typhoonV2Entry,
            "typhoon v2 must NOT appear in explanation when asOf=04:45 (published at 04:50)"
        )

        val typhoonV1Entry = historicalResult.explanationChain.firstOrNull {
            it.ruleId == "manual-tunnel-17-typhoon" && it.version == 1
        }
        assertTrue(typhoonV1Entry != null, "typhoon v1 should still be visible")
        assertFalse(typhoonV1Entry.activeAtEvaluationTime, "typhoon v1 should be expired")
        assertFalse(typhoonV1Entry.selectedAsActiveVersion)

        val stormV2Entry = historicalResult.explanationChain.first {
            it.ruleId == "region-440800-storm" && it.version == 2
        }
        assertTrue(stormV2Entry.visibleAtEvaluation, "storm v2 published at 03:30 should be visible as of 04:45")
        assertTrue(stormV2Entry.selectedAsActiveVersion, "storm v2 should be selected")
        assertFalse(stormV2Entry.matched, "storm v2 72 < 75 miss")
    }

    @Test
    fun `asOf historical result hash differs from full result`() {
        val rules = allRules()
        val input = input72("hash-compare")

        val fullResult = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(facility, rules, input, t05_15_00)
        )
        val historicalResult = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(facility, rules, input, t05_15_00, asOf = t04_45_00)
        )

        assertTrue(
            fullResult.resultHash != historicalResult.resultHash,
            "hash must differ when asOf changes visible rule set"
        )
        assertTrue(
            fullResult.explanationChain.size > historicalResult.explanationChain.size,
            "full result should contain more explanation entries (typhoon v2 visible) than historical asOf result"
        )
        assertTrue(
            fullResult.explanationChain.any { it.ruleId == "manual-tunnel-17-typhoon" && it.version == 2 && it.matched },
            "full result should have typhoon v2 matched"
        )
    }

    @Test
    fun `shuffled rules with asOf produce identical canonical JSON`() {
        val rules = allRules()
        val canonical = Json { prettyPrint = false; encodeDefaults = true }

        val r1 = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(facility, rules, input72("canon-1"), t05_15_00, asOf = t04_45_00)
        )
        val r2 = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(facility, rules.shuffled(), input72("canon-1"), t05_15_00, asOf = t04_45_00)
        )
        val r3 = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(facility, rules.reversed(), input72("canon-1"), t05_15_00, asOf = t04_45_00)
        )

        assertEquals(r1.resultHash, r2.resultHash)
        assertEquals(r1.resultHash, r3.resultHash)
        assertEquals(canonical.encodeToString(r1), canonical.encodeToString(r2))
        assertEquals(canonical.encodeToString(r1), canonical.encodeToString(r3))
    }

    @Test
    fun `expired manual close does not prevent typhoon or region selection`() {
        val rules = allRules()

        val result = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(
                facility = facility,
                rules = rules,
                input = input72("manual-close-expired"),
                evaluationTime = t05_15_00
            )
        )

        assertEquals(Action.CLOSE, result.finalAction)
        assertEquals("manual-tunnel-17-typhoon", result.hitRuleId)
        assertEquals(2, result.hitRuleVersion)

        val manualCloseEntry = result.explanationChain.first {
            it.ruleId == "manual-tunnel-17-close"
        }
        assertFalse(manualCloseEntry.activeAtEvaluationTime)
        assertFalse(manualCloseEntry.selectedAsActiveVersion)
    }

    @Test
    fun `after typhoon expires explanation shows fallback to region water depth`() {
        val rules = allRules()

        val result = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(
                facility = facility,
                rules = rules,
                input = input72("post-typhoon"),
                evaluationTime = t05_30_00
            )
        )

        assertEquals(Action.RESTRICT, result.finalAction)
        assertEquals(RuleLayer.REGION, result.hitRuleLayer)

        val typhoonV2 = result.explanationChain.first {
            it.ruleId == "manual-tunnel-17-typhoon" && it.version == 2
        }
        assertFalse(typhoonV2.activeAtEvaluationTime)
        assertFalse(typhoonV2.selectedAsActiveVersion)
        assertTrue(typhoonV2.versionSelectionReason.contains("outside validity window"))
    }
}
