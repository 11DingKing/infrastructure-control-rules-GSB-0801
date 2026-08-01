package app.control.domain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * 纯领域求值的表驱动测试：四层规则排列组合，
 * 覆盖重叠生效区间、相同优先级与动作、恰好过期、缺失输入、版本链与历史隔离。
 */
class RuleEngineTest {

    private val facility = Facility(
        id = "tunnel-17",
        type = FacilityType.TUNNEL,
        region = "440800",
        name = "示例隧道 17 号",
    )

    private val fullInput = RiskSnapshot(
        facilityId = "tunnel-17",
        observedAt = 900_000L,
        precipitationMm = 72.0,
        windLevel = 7,
        waterDepthCm = 18.0,
    )

    private fun rule(
        ruleId: String,
        tier: RuleTier,
        scopeKey: String,
        action: Action,
        condition: RuleCondition,
        version: Int = 1,
        effectiveFrom: Long = 0L,
        effectiveTo: Long? = null,
        publishedAt: Long = 0L,
        facilityType: FacilityType? = null,
    ): Rule = Rule(
        ruleId = ruleId,
        version = version,
        tier = tier,
        scopeKey = scopeKey,
        facilityType = facilityType,
        condition = condition,
        action = action,
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
        publishedAt = publishedAt,
    )

    private fun defaultRule(
        ruleId: String = "default-heavy-rain",
        threshold: Double = 50.0,
        action: Action = Action.MONITOR,
    ): Rule = rule(ruleId, RuleTier.DEFAULT, "default", action, RuleCondition(precipitationMmAtLeast = threshold))

    private data class Case(
        val name: String,
        val rules: List<Rule>,
        val input: RiskSnapshot,
        val now: Long,
        val asOf: Long,
        val expectedAction: Action?,
        val expectedRuleId: String?,
        val expectCodes: Set<String> = emptySet(),
        val forbidCodes: Set<String> = emptySet(),
        val expectRuleCode: Pair<String, String>? = null,
        val expectedMatchedRuleIds: List<String>? = null,
    )

    private val NOW = 1_000_000L

    private val cases: List<Case>
        get() {
            val default = defaultRule()
            val region = rule("region-440800-storm", RuleTier.REGION, "440800", Action.RESTRICT, RuleCondition(precipitationMmAtLeast = 60.0))
            val facilityClose = rule("facility-tunnel-17-depth", RuleTier.FACILITY, "tunnel-17", Action.CLOSE, RuleCondition(waterDepthCmAtLeast = 15.0))
            val manualWind8 = rule("manual-tunnel-17-typhoon", RuleTier.MANUAL, "tunnel-17", Action.CLOSE, RuleCondition(windLevelAtLeast = 8))
            val manualWind7 = rule("manual-tunnel-17-gale", RuleTier.MANUAL, "tunnel-17", Action.CLOSE, RuleCondition(windLevelAtLeast = 7))

            return listOf(
                Case(
                    name = "四层全部命中：人工强制胜出",
                    rules = listOf(default, region, facilityClose, manualWind7),
                    input = fullInput, now = NOW, asOf = NOW,
                    expectedAction = Action.CLOSE, expectedRuleId = "manual-tunnel-17-gale",
                    expectCodes = setOf(EvalCode.SELECTED_TIER_PRECEDENCE.name),
                    expectedMatchedRuleIds = listOf(
                        "manual-tunnel-17-gale", "facility-tunnel-17-depth", "region-440800-storm", "default-heavy-rain",
                    ),
                ),
                Case(
                    name = "人工规则阈值未达：设施专用规则胜出",
                    rules = listOf(default, region, facilityClose, manualWind8),
                    input = fullInput, now = NOW, asOf = NOW,
                    expectedAction = Action.CLOSE, expectedRuleId = "facility-tunnel-17-depth",
                    expectCodes = setOf(EvalCode.BELOW_THRESHOLD.name, EvalCode.SELECTED_TIER_PRECEDENCE.name),
                ),
                Case(
                    name = "人工规则恰好过期：now == effectiveTo 视为已过期",
                    rules = listOf(
                        default, region, facilityClose,
                        rule("manual-tunnel-17-gale", RuleTier.MANUAL, "tunnel-17", Action.CLOSE, RuleCondition(windLevelAtLeast = 7), effectiveTo = NOW),
                    ),
                    input = fullInput, now = NOW, asOf = NOW,
                    expectedAction = Action.CLOSE, expectedRuleId = "facility-tunnel-17-depth",
                    expectCodes = setOf(EvalCode.EXPIRED.name),
                    expectRuleCode = "manual-tunnel-17-gale" to EvalCode.EXPIRED.name,
                ),
                Case(
                    name = "过期前一毫秒：人工规则仍然生效",
                    rules = listOf(
                        default, region, facilityClose,
                        rule("manual-tunnel-17-gale", RuleTier.MANUAL, "tunnel-17", Action.CLOSE, RuleCondition(windLevelAtLeast = 7), effectiveTo = NOW),
                    ),
                    input = fullInput, now = NOW - 1, asOf = NOW,
                    expectedAction = Action.CLOSE, expectedRuleId = "manual-tunnel-17-gale",
                    forbidCodes = setOf(EvalCode.EXPIRED.name),
                ),
                Case(
                    name = "恰好生效：now == effectiveFrom 视为生效",
                    rules = listOf(
                        rule("manual-tunnel-17-gale", RuleTier.MANUAL, "tunnel-17", Action.CLOSE, RuleCondition(windLevelAtLeast = 7), effectiveFrom = NOW),
                    ),
                    input = fullInput, now = NOW, asOf = NOW,
                    expectedAction = Action.CLOSE, expectedRuleId = "manual-tunnel-17-gale",
                    forbidCodes = setOf(EvalCode.NOT_YET_EFFECTIVE.name),
                ),
                Case(
                    name = "尚未生效：now < effectiveFrom",
                    rules = listOf(
                        default,
                        rule("manual-tunnel-17-gale", RuleTier.MANUAL, "tunnel-17", Action.CLOSE, RuleCondition(windLevelAtLeast = 7), effectiveFrom = NOW + 1),
                    ),
                    input = fullInput, now = NOW, asOf = NOW,
                    expectedAction = Action.MONITOR, expectedRuleId = "default-heavy-rain",
                    expectCodes = setOf(EvalCode.NOT_YET_EFFECTIVE.name),
                ),
                Case(
                    name = "同层重叠生效区间：更严格动作胜出",
                    rules = listOf(
                        rule(
                            "facility-tunnel-17-restrict", RuleTier.FACILITY, "tunnel-17", Action.RESTRICT,
                            RuleCondition(precipitationMmAtLeast = 65.0),
                            effectiveFrom = 0L, effectiveTo = NOW + 500,
                        ),
                        rule(
                            "facility-tunnel-17-close", RuleTier.FACILITY, "tunnel-17", Action.CLOSE,
                            RuleCondition(waterDepthCmAtLeast = 15.0),
                            effectiveFrom = 500L, effectiveTo = NOW + 1000,
                        ),
                    ),
                    input = fullInput, now = NOW, asOf = NOW,
                    expectedAction = Action.CLOSE, expectedRuleId = "facility-tunnel-17-close",
                    expectCodes = setOf(EvalCode.SELECTED_STRICTER_ACTION.name),
                ),
                Case(
                    name = "相同优先级且动作相同：按 ruleId 字典序确定性裁决",
                    rules = listOf(
                        rule("facility-zeta", RuleTier.FACILITY, "tunnel-17", Action.CLOSE, RuleCondition(waterDepthCmAtLeast = 15.0)),
                        rule("facility-alpha", RuleTier.FACILITY, "tunnel-17", Action.CLOSE, RuleCondition(precipitationMmAtLeast = 60.0)),
                    ),
                    input = fullInput, now = NOW, asOf = NOW,
                    expectedAction = Action.CLOSE, expectedRuleId = "facility-alpha",
                    expectCodes = setOf(EvalCode.SELECTED_TIE_BREAK_RULE_ID.name),
                ),
                Case(
                    name = "缺失输入：需要水深的设施规则被跳过，落到区域规则",
                    rules = listOf(default, region, facilityClose),
                    input = fullInput.copy(waterDepthCm = null),
                    now = NOW, asOf = NOW,
                    expectedAction = Action.RESTRICT, expectedRuleId = "region-440800-storm",
                    expectCodes = setOf(EvalCode.MISSING_WATER_DEPTH_CM.name),
                ),
                Case(
                    name = "缺失输入：所有候选规则都缺指标时返回 NO_MATCHING_RULE",
                    rules = listOf(region, facilityClose),
                    input = fullInput.copy(precipitationMm = null, waterDepthCm = null),
                    now = NOW, asOf = NOW,
                    expectedAction = null, expectedRuleId = null,
                    expectCodes = setOf(
                        EvalCode.MISSING_PRECIPITATION_MM.name,
                        EvalCode.MISSING_WATER_DEPTH_CM.name,
                        EvalCode.NO_MATCHING_RULE.name,
                    ),
                ),
                Case(
                    name = "缺失输入：人工规则需要风力而快照缺失",
                    rules = listOf(manualWind7),
                    input = fullInput.copy(windLevel = null),
                    now = NOW, asOf = NOW,
                    expectedAction = null, expectedRuleId = null,
                    expectCodes = setOf(EvalCode.MISSING_WIND_LEVEL.name, EvalCode.NO_MATCHING_RULE.name),
                ),
                Case(
                    name = "作用域外区域规则不参与判定",
                    rules = listOf(
                        default,
                        rule("region-440100-storm", RuleTier.REGION, "440100", Action.CLOSE, RuleCondition(precipitationMmAtLeast = 1.0)),
                    ),
                    input = fullInput, now = NOW, asOf = NOW,
                    expectedAction = Action.MONITOR, expectedRuleId = "default-heavy-rain",
                    expectCodes = setOf(EvalCode.OUT_OF_SCOPE.name, EvalCode.SELECTED_SOLE_MATCH.name),
                ),
                Case(
                    name = "设施类型过滤：仅适用于学校的设施规则不匹配隧道",
                    rules = listOf(
                        default,
                        rule(
                            "facility-tunnel-17-school-only", RuleTier.FACILITY, "tunnel-17", Action.CLOSE,
                            RuleCondition(precipitationMmAtLeast = 1.0), facilityType = FacilityType.SCHOOL,
                        ),
                    ),
                    input = fullInput, now = NOW, asOf = NOW,
                    expectedAction = Action.MONITOR, expectedRuleId = "default-heavy-rain",
                    expectCodes = setOf(EvalCode.OUT_OF_SCOPE.name),
                ),
                Case(
                    name = "版本链：asOf 之后发布的高版本不可见（历史隔离）",
                    rules = listOf(
                        default,
                        rule("region-440800-storm", RuleTier.REGION, "440800", Action.RESTRICT, RuleCondition(precipitationMmAtLeast = 60.0), version = 1, publishedAt = 100L),
                        rule("region-440800-storm", RuleTier.REGION, "440800", Action.CLOSE, RuleCondition(precipitationMmAtLeast = 1.0), version = 2, publishedAt = 200L),
                    ),
                    input = fullInput, now = NOW, asOf = 150L,
                    expectedAction = Action.RESTRICT, expectedRuleId = "region-440800-storm",
                    forbidCodes = setOf(EvalCode.SUPERSEDED_BY_NEWER_VERSION.name),
                ),
                Case(
                    name = "版本链：asOf 覆盖高版本时取最高版本，低版本标记被取代",
                    rules = listOf(
                        default,
                        rule("region-440800-storm", RuleTier.REGION, "440800", Action.RESTRICT, RuleCondition(precipitationMmAtLeast = 60.0), version = 1, publishedAt = 100L),
                        rule("region-440800-storm", RuleTier.REGION, "440800", Action.CLOSE, RuleCondition(precipitationMmAtLeast = 65.0), version = 2, publishedAt = 200L),
                    ),
                    input = fullInput, now = NOW, asOf = 250L,
                    expectedAction = Action.CLOSE, expectedRuleId = "region-440800-storm",
                    expectCodes = setOf(EvalCode.SUPERSEDED_BY_NEWER_VERSION.name),
                ),
                Case(
                    name = "默认层无 scopeKey 要求且永久有效",
                    rules = listOf(default),
                    input = fullInput, now = NOW, asOf = NOW,
                    expectedAction = Action.MONITOR, expectedRuleId = "default-heavy-rain",
                    expectCodes = setOf(EvalCode.SELECTED_SOLE_MATCH.name),
                ),
            )
        }

    @TestFactory
    fun `四层规则排列组合的求值表`(): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.name) {
                val result = RuleEngine.evaluate(case.facilityFor(), case.rules, case.input, case.now, case.asOf)
                assertEquals(case.expectedAction, result.decision?.action, "decision.action")
                assertEquals(case.expectedRuleId, result.decision?.rule?.ruleId, "decision.ruleId")

                val codes = result.explanation.map { it.code }.toSet()
                for (code in case.expectCodes) {
                    assertTrue(code in codes, "explanation missing code $code (got $codes)")
                }
                for (code in case.forbidCodes) {
                    assertFalse(code in codes, "explanation must not contain $code")
                }
                case.expectRuleCode?.let { (ruleId, code) ->
                    assertTrue(
                        result.explanation.any { it.rule?.ruleId == ruleId && it.code == code },
                        "explanation missing entry $code for rule $ruleId",
                    )
                }
                case.expectedMatchedRuleIds?.let { expected ->
                    assertEquals(expected, result.matchedRules.map { it.ruleId }, "matchedRules order")
                }
                if (case.expectedAction == null) {
                    assertEquals(listOf(EvalCode.NO_MATCHING_RULE.name), result.reasonCodes)
                } else {
                    assertEquals(listOf(EvalCode.OK.name), result.reasonCodes)
                }
                // 历史解释链不得出现 asOf 之后发布的规则版本
                for (entry in result.explanation) {
                    val ref = entry.rule ?: continue
                    val published = case.rules.first { it.ruleId == ref.ruleId && it.version == ref.version }.publishedAt
                    assertTrue(published <= case.asOf, "explanation leaks rule ${ref.ruleId}@${ref.version} published after asOf")
                }
            }
        }

    private fun Case.facilityFor(): Facility = this@RuleEngineTest.facility

    @Test
    fun `同一快照的纯求值字节级一致，且与规则传入顺序无关`() {
        val rules = listOf(
            defaultRule(),
            rule("region-440800-storm", RuleTier.REGION, "440800", Action.RESTRICT, RuleCondition(precipitationMmAtLeast = 60.0)),
            rule("facility-tunnel-17-depth", RuleTier.FACILITY, "tunnel-17", Action.CLOSE, RuleCondition(waterDepthCmAtLeast = 15.0)),
            rule("manual-tunnel-17-gale", RuleTier.MANUAL, "tunnel-17", Action.CLOSE, RuleCondition(windLevelAtLeast = 7), effectiveTo = NOW + 1),
            rule("region-440800-storm", RuleTier.REGION, "440800", Action.RESTRICT, RuleCondition(precipitationMmAtLeast = 60.0), version = 2, publishedAt = 100L),
        )
        val first = RuleEngine.evaluate(facility, rules, fullInput, NOW, NOW)
        val second = RuleEngine.evaluate(facility, rules, fullInput, NOW, NOW)
        val reordered = RuleEngine.evaluate(facility, rules.reversed(), fullInput, NOW, NOW)
        val shuffled = RuleEngine.evaluate(facility, rules.shuffled(kotlin.random.Random(42)), fullInput, NOW, NOW)

        assertContentEquals(Canonical.bytesOf(first), Canonical.bytesOf(second))
        assertContentEquals(Canonical.bytesOf(first), Canonical.bytesOf(reordered))
        assertContentEquals(Canonical.bytesOf(first), Canonical.bytesOf(shuffled))
        assertEquals(Canonical.hashOf(first), Canonical.hashOf(shuffled))
    }
}
