package app.control.domain

/**
 * 规则求值引擎：无副作用纯函数。
 * 不读时钟、不碰数据库、不发通知；now 与 asOf 均由调用方传入。
 * 相同输入必然产生相同的 [EvaluationResult]，进而产生字节级一致的规范化 JSON。
 */
object RuleEngine {

    fun evaluate(
        facility: Facility,
        rules: List<Rule>,
        input: RiskSnapshot,
        now: Long,
        asOf: Long,
    ): EvaluationResult {
        val explanation = mutableListOf<ExplanationEntry>()

        // 1. 历史隔离：仅 asOf 之前发布的版本可见；排序保证迭代顺序确定。
        val visible = rules
            .filter { rule -> rule.publishedAt <= asOf }
            .sortedWith(compareBy({ it.ruleId }, { it.version }))

        // 2. 版本选择（回退语义）：在可见版本内先用生效窗口过滤，再取覆盖 now 的最高版本。
        //    已发布但尚未生效 / 已经过期的高版本不会淘汰仍然有效的低版本；
        //    每个可见版本都在解释链中记录“可见且生效被选中 / 被取代 / 尚未生效 / 已过期”。
        val selected = mutableListOf<Rule>()
        val chains = visible.groupBy { rule -> rule.ruleId }
        for ((_, versions) in chains) {
            val chosen = versions
                .filter { rule -> windowViolation(rule, now) == null }
                .maxByOrNull { rule -> rule.version }
            for (rule in versions) {
                val window = windowViolation(rule, now)
                val code = when {
                    chosen != null && rule.version == chosen.version && rule.ruleId == chosen.ruleId ->
                        EvalCode.SELECTED_EFFECTIVE_VERSION
                    window != null -> window
                    else -> EvalCode.SUPERSEDED_BY_NEWER_VERSION
                }
                explanation += ExplanationEntry(
                    phase = Phase.VERSION_SELECTION,
                    rule = rule.ref(),
                    code = code.name,
                    facts = listOf(
                        Fact("publishedAt", rule.publishedAt.toString()),
                        Fact("asOf", asOf.toString()),
                        Fact("now", now.toString()),
                        Fact("effectiveFrom", rule.effectiveFrom.toString()),
                        Fact("effectiveTo", rule.effectiveTo?.toString() ?: "null"),
                    ),
                )
            }
            if (chosen != null) selected += chosen
        }

        // 3. 作用域 / 条件检查（生效窗口已在版本选择阶段判定）
        val matched = mutableListOf<Rule>()
        for (rule in selected) {
            if (!scopeMatches(facility, rule)) {
                explanation += ExplanationEntry(
                    phase = Phase.SCOPE_CHECK,
                    rule = rule.ref(),
                    code = EvalCode.OUT_OF_SCOPE.name,
                    facts = listOf(
                        Fact("ruleScopeKey", rule.scopeKey),
                        Fact("facilityRegion", facility.region),
                        Fact("facilityId", facility.id),
                    ),
                )
                continue
            }

            val failures = conditionFailures(rule, input)
            if (failures.isEmpty()) {
                explanation += ExplanationEntry(
                    phase = Phase.CONDITION_CHECK,
                    rule = rule.ref(),
                    code = EvalCode.MATCHED.name,
                )
                matched += rule
            } else {
                explanation += failures
            }
        }

        // 4. 冲突裁决：层级优先，同级更严格动作优先，再按 ruleId 字典序确定性裁决。
        val ranked = matched.sortedWith(
            compareByDescending<Rule> { it.tier.precedence }
                .thenByDescending { it.action.strictness }
                .thenBy { it.ruleId }
        )
        val winner = ranked.firstOrNull()

        val decision: Decision?
        val reasonCodes: List<String>
        if (winner == null) {
            decision = null
            reasonCodes = listOf(EvalCode.NO_MATCHING_RULE.name)
            explanation += ExplanationEntry(
                phase = Phase.DECISION,
                code = EvalCode.NO_MATCHING_RULE.name,
            )
        } else {
            decision = Decision(action = winner.action, tier = winner.tier.name, rule = winner.ref())
            reasonCodes = listOf(EvalCode.OK.name)
            val basis = resolutionBasis(ranked)
            explanation += ExplanationEntry(
                phase = Phase.CONFLICT_RESOLUTION,
                rule = winner.ref(),
                code = basis.name,
                facts = listOf(
                    Fact("candidates", ranked.joinToString(";") { rule -> "${rule.ruleId}@${rule.version}(${rule.tier.name},${rule.action.name})" }),
                ),
            )
            explanation += ExplanationEntry(
                phase = Phase.DECISION,
                rule = winner.ref(),
                code = EvalCode.OK.name,
                facts = listOf(Fact("action", winner.action.name)),
            )
        }

        return EvaluationResult(
            facilityId = facility.id,
            input = input,
            now = now,
            asOf = asOf,
            decision = decision,
            reasonCodes = reasonCodes,
            matchedRules = ranked.map { it.ref() },
            explanation = explanation,
        )
    }

    private fun scopeMatches(facility: Facility, rule: Rule): Boolean {
        if (rule.facilityType != null && rule.facilityType != facility.type) return false
        return when (rule.tier) {
            RuleTier.DEFAULT -> true
            RuleTier.REGION -> rule.scopeKey == facility.region
            RuleTier.FACILITY, RuleTier.MANUAL -> rule.scopeKey == facility.id
        }
    }

    private fun windowViolation(rule: Rule, now: Long): EvalCode? {
        if (now < rule.effectiveFrom) return EvalCode.NOT_YET_EFFECTIVE
        val end = rule.effectiveTo
        if (end != null && now >= end) return EvalCode.EXPIRED
        return null
    }

    private fun conditionFailures(rule: Rule, input: RiskSnapshot): List<ExplanationEntry> {
        val failures = mutableListOf<ExplanationEntry>()

        fun check(threshold: Double?, observed: Double?, metric: String, missing: EvalCode) {
            if (threshold == null) return
            val code = if (observed == null) missing else if (observed < threshold) EvalCode.BELOW_THRESHOLD else return
            failures += ExplanationEntry(
                phase = Phase.CONDITION_CHECK,
                rule = rule.ref(),
                code = code.name,
                facts = listOf(
                    Fact("metric", metric),
                    Fact("thresholdAtLeast", threshold.toString()),
                    Fact("observed", observed?.toString() ?: "null"),
                ),
            )
        }

        // 固定指标顺序，保证解释链字节稳定。
        check(rule.condition.precipitationMmAtLeast, input.precipitationMm, "precipitationMm", EvalCode.MISSING_PRECIPITATION_MM)
        check(rule.condition.windLevelAtLeast?.toDouble(), input.windLevel?.toDouble(), "windLevel", EvalCode.MISSING_WIND_LEVEL)
        check(rule.condition.waterDepthCmAtLeast, input.waterDepthCm, "waterDepthCm", EvalCode.MISSING_WATER_DEPTH_CM)
        return failures
    }

    private fun resolutionBasis(ranked: List<Rule>): EvalCode {
        val winner = ranked[0]
        val runnerUp = ranked.getOrNull(1) ?: return EvalCode.SELECTED_SOLE_MATCH
        return when {
            winner.tier.precedence != runnerUp.tier.precedence -> EvalCode.SELECTED_TIER_PRECEDENCE
            winner.action.strictness != runnerUp.action.strictness -> EvalCode.SELECTED_STRICTER_ACTION
            else -> EvalCode.SELECTED_TIE_BREAK_RULE_ID
        }
    }
}
