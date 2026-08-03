package app.control.domain

/** 求值与解释链中使用的可枚举原因码（序列化为 name，稳定可机读）。 */
enum class EvalCode {
    /** 求值完成并得出管控动作。 */
    OK,
    /** 没有任何规则命中，无法得出管控动作。 */
    NO_MATCHING_RULE,

    /** 该版本被同链更高且当前生效的版本取代。 */
    SUPERSEDED_BY_NEWER_VERSION,
    /** 该版本可见（publishedAt <= asOf）且生效窗口覆盖 now，被版本链选中。 */
    SELECTED_EFFECTIVE_VERSION,
    /** 规则作用域不覆盖该设施。 */
    OUT_OF_SCOPE,
    /** now < effectiveFrom，规则尚未生效。 */
    NOT_YET_EFFECTIVE,
    /** now >= effectiveTo，规则已过期（边界恰好相等也算过期）。 */
    EXPIRED,

    /** 快照缺少小时降水，而规则需要它。 */
    MISSING_PRECIPITATION_MM,
    /** 快照缺少风力等级，而规则需要它。 */
    MISSING_WIND_LEVEL,
    /** 快照缺少水深，而规则需要它。 */
    MISSING_WATER_DEPTH_CM,
    /** 观测值低于规则阈值。 */
    BELOW_THRESHOLD,
    /** 规则条件全部满足。 */
    MATCHED,

    /** 同优先级只有一条命中规则，直接当选。 */
    SELECTED_SOLE_MATCH,
    /** 按层级优先级胜出：人工强制 > 设施专用 > 区域 > 默认。 */
    SELECTED_TIER_PRECEDENCE,
    /** 同优先级内按更严格动作胜出：CLOSE > RESTRICT > MONITOR。 */
    SELECTED_STRICTER_ACTION,
    /** 同优先级且动作相同，按 ruleId 字典序确定性裁决。 */
    SELECTED_TIE_BREAK_RULE_ID,
}

/** 规则发布失败/成功的可枚举原因码。 */
enum class PublishCode {
    PUBLISHED,
    /** 未提供任何阈值条件。 */
    EMPTY_CONDITION,
    /** effectiveTo 不晚于 effectiveFrom。 */
    INVALID_WINDOW,
    /** REGION / FACILITY / MANUAL 层缺少 scopeKey。 */
    MISSING_SCOPE_KEY,
    /** scopeKey 指向的设施不存在。 */
    UNKNOWN_SCOPE_FACILITY,
    /** 同一 (ruleId, version) 已发布（含并发重复发布）。 */
    DUPLICATE_VERSION,
    /** 版本号低于或等于链上已发布的最高版本。 */
    VERSION_ROLLBACK,
}
