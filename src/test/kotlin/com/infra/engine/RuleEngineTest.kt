package com.infra.engine

import com.infra.domain.Action
import com.infra.domain.ConditionMatch
import com.infra.domain.Facility
import com.infra.domain.FacilityType
import com.infra.domain.ReasonCode
import com.infra.domain.RiskInput
import com.infra.domain.Rule
import com.infra.domain.RuleCondition
import com.infra.domain.RuleLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleEngineTest {

    private val facility = Facility(
        id = "tunnel-17",
        name = "Test Tunnel",
        type = FacilityType.TUNNEL,
        regionCode = "440800"
    )

    private val baseTime = 1000000L
    private val futureTime = 2000000L

    private fun input(
        rain: Double? = 72.0,
        wind: Int? = 7,
        water: Double? = 18.0,
        observedAt: Long = baseTime,
        requestId: String = "req-1",
        facilityId: String = "tunnel-17"
    ) = RiskInput(
        facilityId = facilityId,
        hourlyRainfallMm = rain,
        windLevel = wind,
        waterDepthCm = water,
        observedAt = observedAt,
        requestId = requestId
    )

    private fun rule(
        id: String,
        layer: RuleLayer,
        action: Action,
        condition: RuleCondition = RuleCondition(),
        version: Int = 1,
        publishedAt: Long = 0L,
        validFrom: Long = 0L,
        validTo: Long? = null,
        facilityId: String? = null,
        regionCode: String? = null,
        facilityType: FacilityType? = null
    ) = Rule(
        id = id,
        layer = layer,
        facilityId = facilityId,
        regionCode = regionCode,
        facilityType = facilityType,
        condition = condition,
        action = action,
        version = version,
        publishedAt = publishedAt,
        validFrom = validFrom,
        validTo = validTo,
        description = "Test rule $id"
    )

    data class LayerPriorityTestCase(
        val name: String,
        val rules: List<Rule>,
        val expectedAction: Action,
        val expectedHitRuleId: String,
        val expectedReason: ReasonCode
    )

    @Test
    fun `layer priority conflict resolution`() {
        val cases = listOf(
            LayerPriorityTestCase(
                name = "manual overrides facility region and default",
                rules = listOf(
                    rule("default-1", RuleLayer.DEFAULT, Action.MONITOR, facilityType = FacilityType.TUNNEL),
                    rule("region-1", RuleLayer.REGION, Action.RESTRICT, regionCode = "440800"),
                    rule("facility-1", RuleLayer.FACILITY, Action.CLOSE, facilityId = "tunnel-17"),
                    rule("manual-1", RuleLayer.MANUAL, Action.CLOSE, facilityId = "tunnel-17")
                ),
                expectedAction = Action.CLOSE,
                expectedHitRuleId = "manual-1",
                expectedReason = ReasonCode.OK
            ),
            LayerPriorityTestCase(
                name = "facility overrides region and default when no manual",
                rules = listOf(
                    rule("default-1", RuleLayer.DEFAULT, Action.MONITOR, facilityType = FacilityType.TUNNEL),
                    rule("region-1", RuleLayer.REGION, Action.RESTRICT, regionCode = "440800"),
                    rule("facility-1", RuleLayer.FACILITY, Action.CLOSE, facilityId = "tunnel-17")
                ),
                expectedAction = Action.CLOSE,
                expectedHitRuleId = "facility-1",
                expectedReason = ReasonCode.OK
            ),
            LayerPriorityTestCase(
                name = "region overrides default when no facility or manual",
                rules = listOf(
                    rule("default-1", RuleLayer.DEFAULT, Action.MONITOR, facilityType = FacilityType.TUNNEL),
                    rule("region-1", RuleLayer.REGION, Action.RESTRICT, regionCode = "440800")
                ),
                expectedAction = Action.RESTRICT,
                expectedHitRuleId = "region-1",
                expectedReason = ReasonCode.OK
            ),
            LayerPriorityTestCase(
                name = "default only",
                rules = listOf(
                    rule("default-1", RuleLayer.DEFAULT, Action.MONITOR, facilityType = FacilityType.TUNNEL)
                ),
                expectedAction = Action.MONITOR,
                expectedHitRuleId = "default-1",
                expectedReason = ReasonCode.OK
            )
        )

        cases.forEach { tc ->
            val result = RuleEngine.evaluate(
                RuleEngine.EvaluationContext(
                    facility = facility,
                    rules = tc.rules,
                    input = input(),
                    evaluationTime = futureTime
                )
            )
            assertEquals(tc.expectedAction, result.finalAction, "Case: ${tc.name}")
            assertEquals(tc.expectedHitRuleId, result.hitRuleId, "Case: ${tc.name}")
            assertEquals(tc.expectedReason, result.reasonCode, "Case: ${tc.name}")
        }
    }

    data class SamePriorityTestCase(
        val name: String,
        val rules: List<Rule>,
        val expectedAction: Action
    )

    @Test
    fun `same priority picks stricter action`() {
        val cases = listOf(
            SamePriorityTestCase(
                name = "monitor and restrict in same layer picks restrict",
                rules = listOf(
                    rule("r1", RuleLayer.REGION, Action.MONITOR, regionCode = "440800"),
                    rule("r2", RuleLayer.REGION, Action.RESTRICT, regionCode = "440800")
                ),
                expectedAction = Action.RESTRICT
            ),
            SamePriorityTestCase(
                name = "restrict and close in same layer picks close",
                rules = listOf(
                    rule("r1", RuleLayer.FACILITY, Action.RESTRICT, facilityId = "tunnel-17"),
                    rule("r2", RuleLayer.FACILITY, Action.CLOSE, facilityId = "tunnel-17")
                ),
                expectedAction = Action.CLOSE
            ),
            SamePriorityTestCase(
                name = "all three actions in same layer picks close",
                rules = listOf(
                    rule("r1", RuleLayer.FACILITY, Action.MONITOR, facilityId = "tunnel-17"),
                    rule("r2", RuleLayer.FACILITY, Action.RESTRICT, facilityId = "tunnel-17"),
                    rule("r3", RuleLayer.FACILITY, Action.CLOSE, facilityId = "tunnel-17")
                ),
                expectedAction = Action.CLOSE
            ),
            SamePriorityTestCase(
                name = "same action in same layer is fine",
                rules = listOf(
                    rule("r1", RuleLayer.REGION, Action.RESTRICT, regionCode = "440800"),
                    rule("r2", RuleLayer.REGION, Action.RESTRICT, regionCode = "440800")
                ),
                expectedAction = Action.RESTRICT
            )
        )

        cases.forEach { tc ->
            val result = RuleEngine.evaluate(
                RuleEngine.EvaluationContext(
                    facility = facility,
                    rules = tc.rules,
                    input = input(),
                    evaluationTime = futureTime
                )
            )
            assertEquals(tc.expectedAction, result.finalAction, "Case: ${tc.name}")
        }
    }

    data class ExpirationTestCase(
        val name: String,
        val validFrom: Long,
        val validTo: Long?,
        val publishedAt: Long,
        val evaluationTime: Long,
        val shouldBeActive: Boolean
    )

    @Test
    fun `rule validity intervals and exact expiration`() {
        val cases = listOf(
            ExpirationTestCase(
                name = "active within interval",
                validFrom = 100,
                validTo = 200,
                publishedAt = 50,
                evaluationTime = 150,
                shouldBeActive = true
            ),
            ExpirationTestCase(
                name = "exactly at validFrom is active (inclusive)",
                validFrom = 100,
                validTo = 200,
                publishedAt = 50,
                evaluationTime = 100,
                shouldBeActive = true
            ),
            ExpirationTestCase(
                name = "exactly at validTo is expired (exclusive)",
                validFrom = 100,
                validTo = 200,
                publishedAt = 50,
                evaluationTime = 200,
                shouldBeActive = false
            ),
            ExpirationTestCase(
                name = "before validFrom is inactive",
                validFrom = 100,
                validTo = 200,
                publishedAt = 50,
                evaluationTime = 99,
                shouldBeActive = false
            ),
            ExpirationTestCase(
                name = "after validTo is expired",
                validFrom = 100,
                validTo = 200,
                publishedAt = 50,
                evaluationTime = 201,
                shouldBeActive = false
            ),
            ExpirationTestCase(
                name = "no validTo means never expires",
                validFrom = 100,
                validTo = null,
                publishedAt = 50,
                evaluationTime = 999999,
                shouldBeActive = true
            ),
            ExpirationTestCase(
                name = "published after evaluation time is not visible",
                validFrom = 0,
                validTo = null,
                publishedAt = 500,
                evaluationTime = 400,
                shouldBeActive = false
            ),
            ExpirationTestCase(
                name = "published exactly at evaluation time is visible",
                validFrom = 0,
                validTo = null,
                publishedAt = 500,
                evaluationTime = 500,
                shouldBeActive = true
            )
        )

        cases.forEach { tc ->
            val r = rule(
                id = "test-rule",
                layer = RuleLayer.DEFAULT,
                action = Action.MONITOR,
                validFrom = tc.validFrom,
                validTo = tc.validTo,
                publishedAt = tc.publishedAt,
                facilityType = FacilityType.TUNNEL
            )
            assertEquals(
                tc.shouldBeActive,
                r.isActiveAt(tc.evaluationTime),
                "Case: ${tc.name}"
            )
        }
    }

    @Test
    fun `expired manual rule falls through to lower layers`() {
        val rules = listOf(
            rule("default-1", RuleLayer.DEFAULT, Action.MONITOR, facilityType = FacilityType.TUNNEL),
            rule("region-1", RuleLayer.REGION, Action.RESTRICT, regionCode = "440800"),
            rule(
                "manual-expired",
                RuleLayer.MANUAL,
                Action.CLOSE,
                facilityId = "tunnel-17",
                validFrom = 0,
                validTo = 1000
            )
        )

        val result = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(
                facility = facility,
                rules = rules,
                input = input(),
                evaluationTime = 1500
            )
        )

        assertEquals(Action.RESTRICT, result.finalAction)
        assertEquals("region-1", result.hitRuleId)

        val manualEntry = result.explanationChain.first { it.ruleId == "manual-expired" }
        assertEquals(false, manualEntry.matched)
        assertEquals(false, manualEntry.activeAtEvaluationTime)
        assertEquals(false, manualEntry.selectedAsActiveVersion)
        assertTrue(manualEntry.versionSelectionReason.contains("outside validity window"))
    }

    data class MissingInputTestCase(
        val name: String,
        val condition: RuleCondition,
        val input: RiskInput,
        val shouldMatch: Boolean,
        val expectedReasonFragment: String?
    )

    @Test
    fun `missing required inputs produce explanation reasons`() {
        val cases = listOf(
            MissingInputTestCase(
                name = "missing rainfall when minRainfall set",
                condition = RuleCondition(minRainfallMm = 50.0),
                input = input(rain = null),
                shouldMatch = false,
                expectedReasonFragment = "hourlyRainfallMm is required"
            ),
            MissingInputTestCase(
                name = "missing wind when minWindLevel set",
                condition = RuleCondition(minWindLevel = 6),
                input = input(wind = null),
                shouldMatch = false,
                expectedReasonFragment = "windLevel is required"
            ),
            MissingInputTestCase(
                name = "missing water depth when minWaterDepth set",
                condition = RuleCondition(minWaterDepthCm = 15.0),
                input = input(water = null),
                shouldMatch = false,
                expectedReasonFragment = "waterDepthCm is required"
            ),
            MissingInputTestCase(
                name = "empty condition matches even with all null inputs",
                condition = RuleCondition(),
                input = input(rain = null, wind = null, water = null),
                shouldMatch = true,
                expectedReasonFragment = null
            )
        )

        cases.forEach { tc ->
            val matchResult = tc.condition.matches(tc.input)
            when (matchResult) {
                is ConditionMatch.Matched -> assertTrue(tc.shouldMatch, "Case: ${tc.name}")
                is ConditionMatch.NotMatched -> {
                    assertEquals(!tc.shouldMatch, true, "Case: ${tc.name}")
                    tc.expectedReasonFragment?.let { fragment ->
                        assertTrue(
                            matchResult.reasons.any { it.contains(fragment) },
                            "Expected reason containing '$fragment', got: ${matchResult.reasons}"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `missing facility returns MISSING_FACILITY`() {
        val result = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(
                facility = null,
                rules = listOf(rule("r1", RuleLayer.DEFAULT, Action.MONITOR)),
                input = input(),
                evaluationTime = futureTime
            )
        )
        assertEquals(ReasonCode.MISSING_FACILITY, result.reasonCode)
        assertNull(result.finalAction)
    }

    @Test
    fun `no applicable rules returns MISSING_RULES`() {
        val otherFacility = Facility(
            id = "other-1",
            name = "Other",
            type = FacilityType.SCHOOL,
            regionCode = "110000"
        )
        val result = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(
                facility = otherFacility,
                rules = listOf(
                    rule("tunnel-only", RuleLayer.DEFAULT, Action.MONITOR, facilityType = FacilityType.TUNNEL)
                ),
                input = input().copy(facilityId = "other-1"),
                evaluationTime = futureTime
            )
        )
        assertEquals(ReasonCode.MISSING_RULES, result.reasonCode)
        assertNull(result.finalAction)
    }

    @Test
    fun `no rules match conditions returns NO_RULES_MATCHED`() {
        val result = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(
                facility = facility,
                rules = listOf(
                    rule(
                        "high-threshold",
                        RuleLayer.DEFAULT,
                        Action.CLOSE,
                        RuleCondition(minRainfallMm = 999.0),
                        facilityType = FacilityType.TUNNEL
                    )
                ),
                input = input(rain = 10.0),
                evaluationTime = futureTime
            )
        )
        assertEquals(ReasonCode.NO_RULES_MATCHED, result.reasonCode)
        assertNull(result.finalAction)
    }

    @Test
    fun `same snapshot produces byte-level identical results`() {
        val rules = listOf(
            rule("default-1", RuleLayer.DEFAULT, Action.MONITOR, facilityType = FacilityType.TUNNEL),
            rule("region-1", RuleLayer.REGION, Action.RESTRICT, regionCode = "440800"),
            rule("facility-1", RuleLayer.FACILITY, Action.CLOSE, facilityId = "tunnel-17")
        )

        val snapshot = input(rain = 72.0, wind = 7, water = 18.0, requestId = "deterministic-1")

        val result1 = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(facility, rules, snapshot, futureTime)
        )
        val result2 = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(facility, rules, snapshot, futureTime)
        )

        assertEquals(result1.resultHash, result2.resultHash)
        assertEquals(result1.finalAction, result2.finalAction)
        assertEquals(result1.hitRuleId, result2.hitRuleId)
        assertEquals(result1.explanationChain.size, result2.explanationChain.size)
        assertEquals(
            result1.explanationChain.map { it.ruleId to it.matched },
            result2.explanationChain.map { it.ruleId to it.matched }
        )
    }

    @Test
    fun `historical evaluation cannot see future published rules`() {
        val rules = listOf(
            rule(
                "old-rule",
                RuleLayer.DEFAULT,
                Action.MONITOR,
                facilityType = FacilityType.TUNNEL,
                publishedAt = 100
            ),
            rule(
                "future-rule",
                RuleLayer.MANUAL,
                Action.CLOSE,
                facilityId = "tunnel-17",
                publishedAt = 5000
            )
        )

        val historicalResult = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(facility, rules, input(), evaluationTime = 200)
        )
        assertEquals(Action.MONITOR, historicalResult.finalAction)
        assertEquals("old-rule", historicalResult.hitRuleId)

        val futureResult = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(facility, rules, input(), evaluationTime = 6000)
        )
        assertEquals(Action.CLOSE, futureResult.finalAction)
        assertEquals("future-rule", futureResult.hitRuleId)
    }

    @Test
    fun `explanation chain contains all considered rules with reasons`() {
        val rules = listOf(
            rule(
                "default-match",
                RuleLayer.DEFAULT,
                Action.MONITOR,
                RuleCondition(minRainfallMm = 50.0),
                facilityType = FacilityType.TUNNEL
            ),
            rule(
                "facility-no-match",
                RuleLayer.FACILITY,
                Action.CLOSE,
                RuleCondition(minRainfallMm = 200.0),
                facilityId = "tunnel-17"
            )
        )

        val result = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(facility, rules, input(rain = 72.0), futureTime)
        )

        assertEquals(2, result.explanationChain.size)

        val defaultEntry = result.explanationChain.first { it.ruleId == "default-match" }
        assertTrue(defaultEntry.matched)
        assertTrue(defaultEntry.activeAtEvaluationTime)
        assertTrue(defaultEntry.applicableToFacility)

        val facilityEntry = result.explanationChain.first { it.ruleId == "facility-no-match" }
        assertTrue(!facilityEntry.matched)
        assertTrue(facilityEntry.reasons.isNotEmpty())
    }

    @Test
    fun `batch evaluation returns sorted reproducible results`() {
        val facilities = listOf(
            facility,
            facility.copy(id = "tunnel-18", regionCode = "440800")
        )
        val rules = listOf(
            rule("default-1", RuleLayer.DEFAULT, Action.MONITOR, facilityType = FacilityType.TUNNEL),
            rule("region-1", RuleLayer.REGION, Action.RESTRICT, regionCode = "440800")
        )
        val inputs = listOf(
            input(requestId = "req-b"),
            input(requestId = "req-a", facilityId = "tunnel-18")
        )

        val batch1 = RuleEngine.evaluateBatch(facilities, rules, inputs, futureTime)
        val batch2 = RuleEngine.evaluateBatch(facilities, rules, inputs, futureTime)

        assertEquals(2, batch1.size)
        assertEquals(batch1[0].resultHash, batch2[0].resultHash)
        assertEquals(batch1[1].resultHash, batch2[1].resultHash)

        assertEquals("tunnel-17", batch1[0].facilityId)
        assertEquals("tunnel-18", batch1[1].facilityId)
    }

    @Test
    fun `overlapping rule intervals select correct version`() {
        val rules = listOf(
            rule(
                "rule-v1",
                RuleLayer.FACILITY,
                Action.MONITOR,
                facilityId = "tunnel-17",
                version = 1,
                validFrom = 0,
                validTo = 1000
            ),
            rule(
                "rule-v2",
                RuleLayer.FACILITY,
                Action.RESTRICT,
                facilityId = "tunnel-17",
                version = 2,
                validFrom = 1000,
                validTo = 2000
            ),
            rule(
                "rule-v3",
                RuleLayer.FACILITY,
                Action.CLOSE,
                facilityId = "tunnel-17",
                version = 3,
                validFrom = 2000,
                validTo = null
            )
        )

        val at500 = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(facility, rules, input(), 500)
        )
        assertEquals(Action.MONITOR, at500.finalAction)

        val at1000 = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(facility, rules, input(), 1000)
        )
        assertEquals(Action.RESTRICT, at1000.finalAction)

        val at1500 = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(facility, rules, input(), 1500)
        )
        assertEquals(Action.RESTRICT, at1500.finalAction)

        val at2000 = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(facility, rules, input(), 2000)
        )
        assertEquals(Action.CLOSE, at2000.finalAction)
    }

    @Test
    fun `rule condition thresholds with actual seed data values`() {
        val rules = listOf(
            rule(
                "default-tunnel-rainfall",
                RuleLayer.DEFAULT,
                Action.MONITOR,
                RuleCondition(minRainfallMm = 50.0),
                facilityType = FacilityType.TUNNEL
            ),
            rule(
                "region-440800-rain-wind",
                RuleLayer.REGION,
                Action.RESTRICT,
                RuleCondition(minRainfallMm = 70.0, minWindLevel = 6),
                regionCode = "440800"
            ),
            rule(
                "region-440800-water-depth",
                RuleLayer.REGION,
                Action.RESTRICT,
                RuleCondition(minWaterDepthCm = 15.0),
                regionCode = "440800"
            ),
            rule(
                "facility-tunnel-17-extreme-rain",
                RuleLayer.FACILITY,
                Action.CLOSE,
                RuleCondition(minRainfallMm = 100.0),
                facilityId = "tunnel-17"
            ),
            rule(
                "facility-tunnel-17-extreme-water",
                RuleLayer.FACILITY,
                Action.CLOSE,
                RuleCondition(minWaterDepthCm = 25.0),
                facilityId = "tunnel-17"
            ),
            rule(
                "manual-tunnel-17-close",
                RuleLayer.MANUAL,
                Action.CLOSE,
                RuleCondition(),
                facilityId = "tunnel-17",
                validTo = 5000
            )
        )

        val result = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(
                facility = facility,
                rules = rules,
                input = input(rain = 72.0, wind = 7, water = 18.0),
                evaluationTime = 1000
            )
        )

        assertEquals(Action.CLOSE, result.finalAction)
        assertEquals("manual-tunnel-17-close", result.hitRuleId)
        assertEquals(ReasonCode.OK, result.reasonCode)
        assertNotNull(result.resultHash)
        assertEquals(6, result.explanationChain.size)
        assertTrue(result.explanationChain.all { it.applicableToFacility })
    }

    @Test
    fun `engine is pure - repeated calls do not mutate input`() {
        val rules = listOf(
            rule("r1", RuleLayer.DEFAULT, Action.MONITOR, facilityType = FacilityType.TUNNEL)
        )
        val snapshot = input()
        val rulesBefore = rules.toList()

        repeat(10) {
            RuleEngine.evaluate(
                RuleEngine.EvaluationContext(facility, rules, snapshot, futureTime)
            )
        }

        assertEquals(rulesBefore, rules)
        assertEquals("tunnel-17", snapshot.facilityId)
        assertEquals(72.0, snapshot.hourlyRainfallMm)
    }

    @Test
    fun `reason codes are enumerable and cover failure cases`() {
        val allReasons = ReasonCode.entries
        assertTrue(allReasons.contains(ReasonCode.OK))
        assertTrue(allReasons.contains(ReasonCode.NO_RULES_MATCHED))
        assertTrue(allReasons.contains(ReasonCode.MISSING_FACILITY))
        assertTrue(allReasons.contains(ReasonCode.MISSING_RULES))
        assertTrue(allReasons.contains(ReasonCode.INVALID_RULE_CONFIGURATION))
        assertTrue(allReasons.contains(ReasonCode.MANUAL_RULE_EXPIRED))
        assertTrue(allReasons.contains(ReasonCode.INPUT_VALIDATION_FAILED))
    }
}
