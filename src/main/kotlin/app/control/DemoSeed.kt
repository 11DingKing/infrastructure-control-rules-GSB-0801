package app.control

import app.control.domain.Action
import app.control.domain.FacilityType
import app.control.domain.RuleCondition
import app.control.domain.RuleTier
import app.control.http.Services
import java.time.Instant

/**
 * 演示种子：设施 tunnel-17 + 一条风险输入（降水 72mm/h、风力 7 级、水深 18cm）
 * + default / region:440800 / facility:tunnel-17 / manual:tunnel-17（带过期时间）四层规则，
 * 动作覆盖 MONITOR / RESTRICT / CLOSE。
 */
object DemoSeed {

    private const val FACILITY_ID = "tunnel-17"
    private const val REGION = "440800"

    fun seedIfEmpty(services: Services, clock: () -> Long) {
        if (!services.facilities.isEmpty()) return

        services.facilities.create(
            id = FACILITY_ID,
            type = FacilityType.TUNNEL,
            region = REGION,
            name = "示例隧道 17 号",
        )

        services.riskInputs.ingest(
            facilityId = FACILITY_ID,
            observedAt = clock(),
            precipitationMm = 72.0,
            windLevel = 7,
            waterDepthCm = 18.0,
        )

        val baseline = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()

        // 默认层：任何设施小时降水 >= 50mm 即监测
        services.rules.publish(
            ruleId = "default-heavy-rain", version = 1, tier = RuleTier.DEFAULT, scopeKey = "default",
            facilityType = null,
            condition = RuleCondition(precipitationMmAtLeast = 50.0),
            action = Action.MONITOR,
            effectiveFrom = baseline, effectiveTo = null,
            publishedAt = baseline,
        )
        // 区域层：440800 区域小时降水 >= 60mm 即限行
        services.rules.publish(
            ruleId = "region-440800-storm", version = 1, tier = RuleTier.REGION, scopeKey = REGION,
            facilityType = null,
            condition = RuleCondition(precipitationMmAtLeast = 60.0),
            action = Action.RESTRICT,
            effectiveFrom = baseline, effectiveTo = null,
            publishedAt = baseline,
        )
        // 区域层 v2：2026-08-01T03:30:00Z 发布，仅在 [04:00, 06:00) 生效，阈值上调到 75mm；
        // 生效区间外版本选择回退到永久有效的 v1。
        services.rules.publish(
            ruleId = "region-440800-storm", version = 2, tier = RuleTier.REGION, scopeKey = REGION,
            facilityType = null,
            condition = RuleCondition(precipitationMmAtLeast = 75.0),
            action = Action.RESTRICT,
            effectiveFrom = Instant.parse("2026-08-01T04:00:00Z").toEpochMilli(),
            effectiveTo = Instant.parse("2026-08-01T06:00:00Z").toEpochMilli(),
            publishedAt = Instant.parse("2026-08-01T03:30:00Z").toEpochMilli(),
        )
        // 设施层：tunnel-17 水深 >= 15cm 即封闭
        services.rules.publish(
            ruleId = "facility-tunnel-17-depth", version = 1, tier = RuleTier.FACILITY, scopeKey = FACILITY_ID,
            facilityType = FacilityType.TUNNEL,
            condition = RuleCondition(waterDepthCmAtLeast = 15.0),
            action = Action.CLOSE,
            effectiveFrom = baseline, effectiveTo = null,
            publishedAt = baseline,
        )
        // 人工强制层：tunnel-17 风力 >= 8 级即封闭，带过期时间（台风季结束失效）
        services.rules.publish(
            ruleId = "manual-tunnel-17-typhoon", version = 1, tier = RuleTier.MANUAL, scopeKey = FACILITY_ID,
            facilityType = null,
            condition = RuleCondition(windLevelAtLeast = 8),
            action = Action.CLOSE,
            effectiveFrom = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli(),
            effectiveTo = Instant.parse("2026-12-31T16:00:00Z").toEpochMilli(),
            publishedAt = baseline,
        )
        // 人工强制层 v2：2026-08-01T04:50:00Z 发布，仅在 [05:00, 05:30) 无条件强制 CLOSE；
        // 到期后回退到 v1（阈值未达）并回到设施/区域层裁决。
        services.rules.publish(
            ruleId = "manual-tunnel-17-typhoon", version = 2, tier = RuleTier.MANUAL, scopeKey = FACILITY_ID,
            facilityType = null,
            condition = RuleCondition(precipitationMmAtLeast = 0.0),
            action = Action.CLOSE,
            effectiveFrom = Instant.parse("2026-08-01T05:00:00Z").toEpochMilli(),
            effectiveTo = Instant.parse("2026-08-01T05:30:00Z").toEpochMilli(),
            publishedAt = Instant.parse("2026-08-01T04:50:00Z").toEpochMilli(),
        )
    }
}
