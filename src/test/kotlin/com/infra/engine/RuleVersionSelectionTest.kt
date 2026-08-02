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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuleVersionSelectionTest {

    private val facility = Facility(
        id = "tunnel-17",
        name = "海滨隧道 17 号",
        type = FacilityType.TUNNEL,
        regionCode = "440800"
    )

    private val publishedV2 = Instant.parse("2026-08-01T03:30:00Z").toEpochMilli()
    private val validFromV2 = Instant.parse("2026-08-01T04:00:00Z").toEpochMilli()
    private val validToV2 = Instant.parse("2026-08-01T06:00:00Z").toEpochMilli()

    private val t03_59_59 = Instant.parse("2026-08-01T03:59:59Z").toEpochMilli()
    private val t04_00_00 = Instant.parse("2026-08-01T04:00:00Z").toEpochMilli()
    private val t05_59_59 = Instant.parse("2026-08-01T05:59:59Z").toEpochMilli()
    private val t06_00_00 = Instant.parse("2026-08-01T06:00:00Z").toEpochMilli()

    private fun stormV1() = Rule(
        id = "region-440800-storm",
        layer = RuleLayer.REGION,
        regionCode = "440800",
        condition = RuleCondition(minRainfallMm = 60.0),
        action = Action.RESTRICT,
        version = 1,
        publishedAt = 0L,
        validFrom = 0L,
        description = "v1 threshold 60mm"
    )

    private fun stormV2() = Rule(
        id = "region-440800-storm",
        layer = RuleLayer.REGION,
        regionCode = "440800",
        condition = RuleCondition(minRainfallMm = 75.0),
        action = Action.RESTRICT,
        version = 2,
        publishedAt = publishedV2,
        validFrom = validFromV2,
        validTo = validToV2,
        description = "v2 threshold 75mm"
    )

    private fun input72mm(requestId: String) = RiskInput(
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
        val expectedSelectedVersion: Int,
        val expectedStormMatched: Boolean,
        val expectedFinalAction: Action,
        val expectedV1Visible: Boolean,
        val expectedV2Visible: Boolean,
        val expectedV1Active: Boolean,
        val expectedV2Active: Boolean,
        val expectedV1Selected: Boolean,
        val expectedV2Selected: Boolean
    )

    @Test
    fun `storm v1 and v2 version selection at four boundary times in isolation`() {
        val rules = listOf(stormV1(), stormV2())

        val cases = listOf(
            TimePointCase(
                label = "03:59:59 - v2 published but not yet valid, v1 selected",
                evalTime = t03_59_59,
                expectedSelectedVersion = 1,
                expectedStormMatched = true,
                expectedFinalAction = Action.RESTRICT,
                expectedV1Visible = true,
                expectedV2Visible = true,
                expectedV1Active = true,
                expectedV2Active = false,
                expectedV1Selected = true,
                expectedV2Selected = false
            ),
            TimePointCase(
                label = "04:00:00 - v2 becomes valid (left-inclusive), v2 selected, 72 < 75 miss",
                evalTime = t04_00_00,
                expectedSelectedVersion = 2,
                expectedStormMatched = false,
                expectedFinalAction = Action.CLOSE,
                expectedV1Visible = true,
                expectedV2Visible = true,
                expectedV1Active = true,
                expectedV2Active = true,
                expectedV1Selected = false,
                expectedV2Selected = true
            ),
            TimePointCase(
                label = "05:59:59 - v2 still valid, v2 selected, 72 < 75 miss",
                evalTime = t05_59_59,
                expectedSelectedVersion = 2,
                expectedStormMatched = false,
                expectedFinalAction = Action.CLOSE,
                expectedV1Visible = true,
                expectedV2Visible = true,
                expectedV1Active = true,
                expectedV2Active = true,
                expectedV1Selected = false,
                expectedV2Selected = true
            ),
            TimePointCase(
                label = "06:00:00 - v2 expires (right-exclusive), v1 selected again",
                evalTime = t06_00_00,
                expectedSelectedVersion = 1,
                expectedStormMatched = true,
                expectedFinalAction = Action.RESTRICT,
                expectedV1Visible = true,
                expectedV2Visible = true,
                expectedV1Active = true,
                expectedV2Active = false,
                expectedV1Selected = true,
                expectedV2Selected = false
            )
        )

        for (case in cases) {
            val result = RuleEngine.evaluate(
                RuleEngine.EvaluationContext(
                    facility = facility,
                    rules = rules,
                    input = input72mm("storm-${case.label.take(8)}"),
                    evaluationTime = case.evalTime
                )
            )

            val stormV1Entry = result.explanationChain.first {
                it.ruleId == "region-440800-storm" && it.version == 1
            }
            val stormV2Entry = result.explanationChain.first {
                it.ruleId == "region-440800-storm" && it.version == 2
            }

            assertEquals(case.expectedV1Visible, stormV1Entry.visibleAtEvaluation, "v1 visible: ${case.label}")
            assertEquals(case.expectedV2Visible, stormV2Entry.visibleAtEvaluation, "v2 visible: ${case.label}")
            assertEquals(case.expectedV1Active, stormV1Entry.activeAtEvaluationTime, "v1 active: ${case.label}")
            assertEquals(case.expectedV2Active, stormV2Entry.activeAtEvaluationTime, "v2 active: ${case.label}")
            assertEquals(case.expectedV1Selected, stormV1Entry.selectedAsActiveVersion, "v1 selected: ${case.label}")
            assertEquals(case.expectedV2Selected, stormV2Entry.selectedAsActiveVersion, "v2 selected: ${case.label}")

            if (case.expectedStormMatched) {
                assertEquals(case.expectedFinalAction, result.finalAction, "final action: ${case.label}")
                assertEquals("region-440800-storm", result.hitRuleId, "hit rule: ${case.label}")
                assertEquals(case.expectedSelectedVersion, result.hitRuleVersion, "hit version: ${case.label}")
                assertEquals(ReasonCode.OK, result.reasonCode, "reason: ${case.label}")
                assertTrue(stormV1Entry.matched, "v1 matched: ${case.label}")
                assertFalse(stormV2Entry.matched, "v2 not matched: ${case.label}")
            } else {
                assertTrue(
                    stormV2Entry.reasons.any { it.contains("72.0 < 75.0") || it.contains("hourlyRainfallMm") },
                    "v2 should record threshold miss: ${case.label}, reasons=${stormV2Entry.reasons}"
                )
                assertTrue(
                    stormV2Entry.versionSelectionReason.contains("selected as active version"),
                    "v2 selection reason: ${case.label}"
                )
                assertTrue(
                    stormV1Entry.versionSelectionReason.contains("superseded by higher version 2"),
                    "v1 should be superseded: ${case.label}, got: ${stormV1Entry.versionSelectionReason}"
                )
            }
        }
    }

    @Test
    fun `v2 not yet published is invisible and does not retire v1`() {
        val beforePublish = Instant.parse("2026-08-01T03:00:00Z").toEpochMilli()
        val rules = listOf(stormV1(), stormV2())

        val result = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(
                facility = facility,
                rules = rules,
                input = input72mm("before-publish"),
                evaluationTime = beforePublish
            )
        )

        assertEquals(Action.RESTRICT, result.finalAction)
        assertEquals(1, result.hitRuleVersion)

        val v2Entry = result.explanationChain.firstOrNull {
            it.ruleId == "region-440800-storm" && it.version == 2
        }
        assertTrue(v2Entry == null, "v2 should not appear in explanation when not yet published")
    }

    @Test
    fun `shuffled rules produce identical canonical JSON and contentHash`() {
        val rulesOriginal = listOf(
            stormV1(),
            stormV2(),
            Rule(
                id = "default-tunnel",
                layer = RuleLayer.DEFAULT,
                facilityType = FacilityType.TUNNEL,
                condition = RuleCondition(minRainfallMm = 50.0),
                action = Action.MONITOR,
                version = 1,
                publishedAt = 0L,
                validFrom = 0L,
                description = "default"
            ),
            Rule(
                id = "manual-close",
                layer = RuleLayer.MANUAL,
                facilityId = "tunnel-17",
                condition = RuleCondition(),
                action = Action.CLOSE,
                version = 1,
                publishedAt = 0L,
                validFrom = 0L,
                description = "manual"
            )
        )

        val rulesShuffled = listOf(
            rulesOriginal[3],
            rulesOriginal[1],
            rulesOriginal[2],
            rulesOriginal[0]
        )

        val rulesReversed = rulesOriginal.reversed()

        val canonicalJson = Json {
            prettyPrint = false
            encodeDefaults = true
        }

        val evalTimes = listOf(t03_59_59, t04_00_00, t05_59_59, t06_00_00)

        for (evalTime in evalTimes) {
            val result1 = RuleEngine.evaluate(
                RuleEngine.EvaluationContext(facility, rulesOriginal, input72mm("ord-$evalTime"), evalTime)
            )
            val result2 = RuleEngine.evaluate(
                RuleEngine.EvaluationContext(facility, rulesShuffled, input72mm("ord-$evalTime"), evalTime)
            )
            val result3 = RuleEngine.evaluate(
                RuleEngine.EvaluationContext(facility, rulesReversed, input72mm("ord-$evalTime"), evalTime)
            )

            assertEquals(result1.resultHash, result2.resultHash, "hash mismatch original vs shuffled at $evalTime")
            assertEquals(result1.resultHash, result3.resultHash, "hash mismatch original vs reversed at $evalTime")

            val json1 = canonicalJson.encodeToString(result1)
            val json2 = canonicalJson.encodeToString(result2)
            val json3 = canonicalJson.encodeToString(result3)

            assertEquals(json1, json2, "canonical JSON mismatch original vs shuffled at $evalTime")
            assertEquals(json1, json3, "canonical JSON mismatch original vs reversed at $evalTime")

            assertEquals(
                result1.explanationChain.map { it.ruleId to it.version },
                result2.explanationChain.map { it.ruleId to it.version },
                "explanation order mismatch at $evalTime"
            )
        }
    }

    @Test
    fun `explanation chain records visibility window selection and arbitration`() {
        val rules = listOf(stormV1(), stormV2())

        val result = RuleEngine.evaluate(
            RuleEngine.EvaluationContext(
                facility = facility,
                rules = rules,
                input = input72mm("full-explain"),
                evaluationTime = t04_00_00
            )
        )

        val v1 = result.explanationChain.first { it.ruleId == "region-440800-storm" && it.version == 1 }
        val v2 = result.explanationChain.first { it.ruleId == "region-440800-storm" && it.version == 2 }

        assertTrue(v1.visibleAtEvaluation)
        assertTrue(v1.activeAtEvaluationTime)
        assertFalse(v1.selectedAsActiveVersion)
        assertTrue(v1.versionSelectionReason.contains("superseded by higher version 2"))

        assertTrue(v2.visibleAtEvaluation)
        assertTrue(v2.activeAtEvaluationTime)
        assertTrue(v2.selectedAsActiveVersion)
        assertTrue(v2.versionSelectionReason.contains("selected as active version"))
        assertFalse(v2.matched)
        assertTrue(
            v2.reasons.any { it.contains("hourlyRainfallMm=72.0") && it.contains("minRainfallMm=75.0") },
            "Expected threshold miss reason, got: ${v2.reasons}"
        )

        assertNotNull(result.resultHash)
        assertTrue(result.consideredRuleIds.contains("region-440800-storm"))
    }

    @Test
    fun `same snapshot produces byte-identical result across repeated evaluations`() {
        val rules = listOf(stormV1(), stormV2())
        val snapshot = input72mm("byte-identical")

        val r1 = RuleEngine.evaluate(RuleEngine.EvaluationContext(facility, rules, snapshot, t04_00_00))
        val r2 = RuleEngine.evaluate(RuleEngine.EvaluationContext(facility, rules, snapshot, t04_00_00))
        val r3 = RuleEngine.evaluate(RuleEngine.EvaluationContext(facility, rules.shuffled(), snapshot, t04_00_00))

        assertEquals(r1.resultHash, r2.resultHash)
        assertEquals(r1.resultHash, r3.resultHash)

        val json = Json { prettyPrint = false; encodeDefaults = true }
        assertEquals(json.encodeToString(r1), json.encodeToString(r2))
        assertEquals(json.encodeToString(r1), json.encodeToString(r3))
    }
}
