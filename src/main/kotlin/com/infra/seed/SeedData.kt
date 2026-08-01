package com.infra.seed

import com.infra.domain.Action
import com.infra.domain.Facility
import com.infra.domain.FacilityType
import com.infra.domain.Rule
import com.infra.domain.RuleCondition
import com.infra.domain.RuleLayer
import com.infra.persistence.Repository
import org.slf4j.LoggerFactory

object SeedData {

    private val logger = LoggerFactory.getLogger(javaClass)

    private const val BASE_TIME = 0L
    private val MANUAL_EXPIRES_AT = 1893456000000L

    fun seedIfEmpty(repository: Repository) {
        if (repository.getFacility("tunnel-17") != null) {
            logger.info("Seed data already present, skipping")
            return
        }

        logger.info("Seeding initial facility and rules...")

        val tunnel17 = Facility(
            id = "tunnel-17",
            name = "海滨隧道 17 号",
            type = FacilityType.TUNNEL,
            regionCode = "440800",
            location = "广东省湛江市海滨大道"
        )
        repository.createFacility(tunnel17)

        val defaultRule = Rule(
            id = "default-tunnel-rainfall",
            layer = RuleLayer.DEFAULT,
            facilityType = FacilityType.TUNNEL,
            condition = RuleCondition(minRainfallMm = 50.0),
            action = Action.MONITOR,
            version = 1,
            publishedAt = BASE_TIME,
            validFrom = BASE_TIME,
            description = "默认规则：隧道小时降水≥50mm 触发监控"
        )
        repository.publishRule(defaultRule)

        val regionRainWindRule = Rule(
            id = "region-440800-rain-wind",
            layer = RuleLayer.REGION,
            regionCode = "440800",
            condition = RuleCondition(minRainfallMm = 70.0, minWindLevel = 6),
            action = Action.RESTRICT,
            version = 1,
            publishedAt = BASE_TIME,
            validFrom = BASE_TIME,
            description = "区域规则 440800：小时降水≥70mm 且风力≥6 级触发管制"
        )
        repository.publishRule(regionRainWindRule)

        val regionWaterRule = Rule(
            id = "region-440800-water-depth",
            layer = RuleLayer.REGION,
            regionCode = "440800",
            condition = RuleCondition(minWaterDepthCm = 15.0),
            action = Action.RESTRICT,
            version = 1,
            publishedAt = BASE_TIME,
            validFrom = BASE_TIME,
            description = "区域规则 440800：水深≥15cm 触发管制"
        )
        repository.publishRule(regionWaterRule)

        val facilityExtremeRainRule = Rule(
            id = "facility-tunnel-17-extreme-rain",
            layer = RuleLayer.FACILITY,
            facilityId = "tunnel-17",
            condition = RuleCondition(minRainfallMm = 100.0),
            action = Action.CLOSE,
            version = 1,
            publishedAt = BASE_TIME,
            validFrom = BASE_TIME,
            description = "设施规则 tunnel-17：小时降水≥100mm 关闭隧道"
        )
        repository.publishRule(facilityExtremeRainRule)

        val facilityExtremeWaterRule = Rule(
            id = "facility-tunnel-17-extreme-water",
            layer = RuleLayer.FACILITY,
            facilityId = "tunnel-17",
            condition = RuleCondition(minWaterDepthCm = 25.0),
            action = Action.CLOSE,
            version = 1,
            publishedAt = BASE_TIME,
            validFrom = BASE_TIME,
            description = "设施规则 tunnel-17：水深≥25cm 关闭隧道"
        )
        repository.publishRule(facilityExtremeWaterRule)

        val manualCloseRule = Rule(
            id = "manual-tunnel-17-close",
            layer = RuleLayer.MANUAL,
            facilityId = "tunnel-17",
            condition = RuleCondition(),
            action = Action.CLOSE,
            version = 1,
            publishedAt = BASE_TIME,
            validFrom = BASE_TIME,
            validTo = MANUAL_EXPIRES_AT,
            description = "人工强制规则 tunnel-17：无条件关闭，有效期至 2030-01-01"
        )
        repository.publishRule(manualCloseRule)

        logger.info("Seed completed: 1 facility, 6 rules (default/region×2/facility×2/manual)")
    }
}
