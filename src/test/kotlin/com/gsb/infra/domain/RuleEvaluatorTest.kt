package com.gsb.infra.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleEvaluatorTest {

    private val facility = Facility(
        id = "tunnel-17",
        type = FacilityType.TUNNEL,
        regionCode = "440800",
        name = "Tunnel 17"
    )

    private val baseTime = Instant.parse("2026-02-15T12:00:00Z")
    private val published = Instant.parse("2026-01-01T00:00:00Z")

    private fun rule(
        ruleId: String,
        layer: RuleLayer,
        action: Action,
        conditions: List<Condition> = emptyList(),
        effectiveFrom: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt: Instant? = null,
        publishedAt: Instant = published,
        facilityId: String? = when (layer) {
            RuleLayer.FACILITY, RuleLayer.MANUAL -> "tunnel-17"
            else -> null
        },
        regionCode: String? = if (layer == RuleLayer.REGION) "440800" else null
    ) = Rule(
        ruleId = ruleId,
        version = 1,
        layer = layer,
        facilityId = facilityId,
        regionCode = regionCode,
        facilityTypes = emptySet(),
        conditions = conditions,
        action = action,
        reason = "test rule $ruleId",
        effectiveFrom = effectiveFrom,
        expiresAt = expiresAt,
        publishedAt = publishedAt
    )

    private fun eval(
        rules: List<Rule>,
        input: RiskInput,
        at: Instant = baseTime
    ): EvaluationResult = RuleEvaluator.evaluate(
        RuleEvaluator.EvaluationRequest(facility, rules, input, at)
    )

    private val fullInput = RiskInput.of(
        Metric.HOURLY_PRECIPITATION_MM to 72.0,
        Metric.WIND_LEVEL to 7.0,
        Metric.WATER_DEPTH_CM to 18.0
    )

    @Test
    fun `manual layer overrides facility region and default regardless of action severity`() {
        val rules = listOf(
            rule("d-close", RuleLayer.DEFAULT, Action.CLOSE, listOf(Condition(Metric.WATER_DEPTH_CM, Operator.GTE, 10.0))),
            rule("r-restrict", RuleLayer.REGION, Action.RESTRICT, listOf(Condition(Metric.WIND_LEVEL, Operator.GTE, 5.0))),
            rule("f-monitor", RuleLayer.FACILITY, Action.MONITOR, listOf(Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 1.0))),
            rule("m-monitor", RuleLayer.MANUAL, Action.MONITOR, emptyList())
        )
        val result = eval(rules, fullInput)
        assertEquals(Action.MONITOR, result.finalAction)
        assertEquals(RuleLayer.MANUAL, result.winningLayer)
        assertEquals("m-monitor", result.winningRuleId)
        assertEquals(ReasonCode.RULE_MATCHED, result.reasonCode)
    }

    @Test
    fun `strictest action wins within same priority layer`() {
        val rules = listOf(
            rule("f-monitor", RuleLayer.FACILITY, Action.MONITOR, listOf(Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 1.0))),
            rule("f-restrict", RuleLayer.FACILITY, Action.RESTRICT, listOf(Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 1.0))),
            rule("f-close", RuleLayer.FACILITY, Action.CLOSE, listOf(Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 1.0)))
        )
        val result = eval(rules, fullInput)
        assertEquals(Action.CLOSE, result.finalAction)
        assertEquals("f-close", result.winningRuleId)
    }

    @Test
    fun `same priority and same action is deterministic and does not crash`() {
        val rules = listOf(
            rule("a-close", RuleLayer.FACILITY, Action.CLOSE, listOf(Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 1.0))),
            rule("b-close", RuleLayer.FACILITY, Action.CLOSE, listOf(Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 1.0)))
        )
        val first = eval(rules, fullInput)
        val second = eval(rules, fullInput)
        assertEquals(first.finalAction, second.finalAction)
        assertEquals(first.winningRuleId, second.winningRuleId)
        assertEquals("a-close", first.winningRuleId)
        assertEquals(Action.CLOSE, first.finalAction)
    }

    @Test
    fun `manual rule exactly at expiry is excluded`() {
        val expiry = Instant.parse("2026-03-01T00:00:00Z")
        val rules = listOf(
            rule("d-close", RuleLayer.DEFAULT, Action.CLOSE, listOf(Condition(Metric.WATER_DEPTH_CM, Operator.GTE, 10.0))),
            rule("m-monitor", RuleLayer.MANUAL, Action.MONITOR, emptyList(), expiresAt = expiry)
        )
        val atExpiry = eval(rules, fullInput, at = expiry)
        assertEquals(Action.CLOSE, atExpiry.finalAction)
        assertEquals(RuleLayer.DEFAULT, atExpiry.winningLayer)
        val manualTrace = atExpiry.ruleTraces.first { it.ruleId == "m-monitor" }
        assertEquals(ReasonCode.RULE_EXPIRED, manualTrace.reasonCode)

        val justBefore = eval(rules, fullInput, at = expiry.minusSeconds(1))
        assertEquals(Action.MONITOR, justBefore.finalAction)
        assertEquals(RuleLayer.MANUAL, justBefore.winningLayer)
    }

    @Test
    fun `missing required input yields CONDITION_MISSING_INPUT and rule does not match`() {
        val rules = listOf(
            rule("d-close", RuleLayer.DEFAULT, Action.CLOSE,
                listOf(Condition(Metric.WATER_DEPTH_CM, Operator.GTE, 10.0)))
        )
        val inputWithoutWater = RiskInput.of(Metric.HOURLY_PRECIPITATION_MM to 72.0)
        val result = eval(rules, inputWithoutWater)
        assertEquals(Action.NONE, result.finalAction)
        assertEquals(ReasonCode.NO_ACTIVE_RULE, result.reasonCode)
        val trace = result.ruleTraces.first()
        assertEquals(ReasonCode.CONDITION_MISSING_INPUT, trace.reasonCode)
        val conditionOutcome = trace.conditionOutcomes.first()
        assertNull(conditionOutcome.actualValue)
    }

    @Test
    fun `rule not yet published is invisible to historical evaluation`() {
        val futurePublish = Instant.parse("2026-12-01T00:00:00Z")
        val rules = listOf(
            rule("d-monitor", RuleLayer.DEFAULT, Action.MONITOR, listOf(Condition(Metric.WIND_LEVEL, Operator.GTE, 1.0))),
            rule("future-close", RuleLayer.MANUAL, Action.CLOSE, emptyList(), publishedAt = futurePublish)
        )
        val result = eval(rules, fullInput, at = baseTime)
        assertEquals(Action.MONITOR, result.finalAction)
        val futureTrace = result.ruleTraces.first { it.ruleId == "future-close" }
        assertEquals(ReasonCode.RULE_NOT_YET_PUBLISHED, futureTrace.reasonCode)
        assertEquals(false, futureTrace.alreadyPublished)
    }

    @Test
    fun `same snapshot produces byte-level identical serialized result`() {
        val rules = listOf(
            rule("d-restrict", RuleLayer.DEFAULT, Action.RESTRICT,
                listOf(Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 50.0))),
            rule("f-close", RuleLayer.FACILITY, Action.CLOSE,
                listOf(Condition(Metric.WATER_DEPTH_CM, Operator.GTE, 15.0)))
        )
        val a = eval(rules, fullInput)
        val b = eval(rules, fullInput)
        val jsonA = Json.encodeToString(EvaluationResult.serializer(), a)
        val jsonB = Json.encodeToString(EvaluationResult.serializer(), b)
        assertEquals(jsonA, jsonB)
        assertEquals(jsonA.hashCode(), jsonB.hashCode())
    }

    @Test
    fun `explanation chain records every candidate rule including non-winners`() {
        val rules = listOf(
            rule("d-monitor", RuleLayer.DEFAULT, Action.MONITOR, listOf(Condition(Metric.WIND_LEVEL, Operator.GTE, 1.0))),
            rule("r-close", RuleLayer.REGION, Action.CLOSE, listOf(Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 100.0)))
        )
        val result = eval(rules, fullInput)
        assertEquals(2, result.ruleTraces.size)
        val winner = result.ruleTraces.first { it.selected }
        val loser = result.ruleTraces.first { !it.matched }
        assertEquals("d-monitor", winner.ruleId)
        assertEquals("r-close", loser.ruleId)
        assertEquals(ReasonCode.CONDITION_NOT_MET, loser.reasonCode)
        assertTrue(result.explanation.contains("d-monitor"))
    }

    @Test
    fun `layer decisions report strictest action per layer`() {
        val rules = listOf(
            rule("d-monitor", RuleLayer.DEFAULT, Action.MONITOR, listOf(Condition(Metric.WIND_LEVEL, Operator.GTE, 1.0))),
            rule("r-restrict", RuleLayer.REGION, Action.RESTRICT, listOf(Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 1.0)))
        )
        val result = eval(rules, fullInput)
        val byLayer = result.layerDecisions.associateBy { it.layer }
        assertEquals(Action.MONITOR, byLayer[RuleLayer.DEFAULT]?.strictestAction)
        assertEquals(Action.RESTRICT, byLayer[RuleLayer.REGION]?.strictestAction)
        assertEquals(Action.NONE, byLayer[RuleLayer.FACILITY]?.strictestAction)
        assertEquals(Action.NONE, byLayer[RuleLayer.MANUAL]?.strictestAction)
    }

    @Test
    fun `effective window overlap resolves to highest active layer`() {
        val rules = listOf(
            rule("m-close-jan", RuleLayer.MANUAL, Action.CLOSE, emptyList(),
                effectiveFrom = Instant.parse("2026-01-01T00:00:00Z"),
                expiresAt = Instant.parse("2026-02-01T00:00:00Z")),
            rule("m-close-feb", RuleLayer.MANUAL, Action.RESTRICT, emptyList(),
                effectiveFrom = Instant.parse("2026-02-01T00:00:00Z"),
                expiresAt = Instant.parse("2026-03-01T00:00:00Z"))
        )
        val jan = eval(rules, fullInput, at = Instant.parse("2026-01-15T12:00:00Z"))
        assertEquals(Action.CLOSE, jan.finalAction)
        assertEquals("m-close-jan", jan.winningRuleId)

        val feb = eval(rules, fullInput, at = Instant.parse("2026-02-15T12:00:00Z"))
        assertEquals(Action.RESTRICT, feb.finalAction)
        assertEquals("m-close-feb", feb.winningRuleId)
    }

    @Test
    fun `no active rule yields NONE and enumerable reason code`() {
        val result = eval(emptyList(), fullInput)
        assertEquals(Action.NONE, result.finalAction)
        assertNull(result.winningRuleId)
        assertEquals(ReasonCode.NO_ACTIVE_RULE, result.reasonCode)
    }

    @Test
    fun `condition operators evaluate thresholds correctly`() {
        val cases = listOf(
            Triple(Operator.GTE, 7.0, true),
            Triple(Operator.GTE, 8.0, false),
            Triple(Operator.GT, 7.0, false),
            Triple(Operator.LTE, 7.0, true),
            Triple(Operator.LT, 7.0, false),
            Triple(Operator.EQ, 7.0, true)
        )
        for ((op, threshold, expected) in cases) {
            val condition = Condition(Metric.WIND_LEVEL, op, threshold)
            assertEquals(expected, condition.evaluate(7.0), "wind 7.0 $op $threshold")
        }
    }

    @Test
    fun `scoped rules from other facilities do not match`() {
        val otherFacilityRule = rule(
            "other", RuleLayer.FACILITY, Action.CLOSE, emptyList(),
            facilityId = "tunnel-99"
        )
        val result = eval(listOf(otherFacilityRule), fullInput)
        assertEquals(Action.NONE, result.finalAction)
        val trace = result.ruleTraces.first()
        assertEquals(ReasonCode.OVERRIDDEN_BY_HIGHER_PRIORITY_LAYER, trace.reasonCode)
    }
}
