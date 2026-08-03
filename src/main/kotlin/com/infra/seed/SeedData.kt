package com.infra.seed

import com.infra.domain.Action
import com.infra.domain.Facility
import com.infra.domain.FacilityType
import com.infra.domain.Rule
import com.infra.domain.RuleCondition
import com.infra.domain.RuleLayer
import com.infra.persistence.Repository
import org.slf4j.LoggerFactory
import java.time.Instant

object SeedData {

    private val logger = LoggerFactory.getLogger(javaClass)

    private const val BASE_TIME = 0L

    private val MANUAL_CLOSE_EXPIRES_AT = Instant.parse("2026-07-31T23:59:59Z").toEpochMilli()

    private val STORM_V2_PUBLISHED_AT = Instant.parse("2026-08-01T03:30:00Z").toEpochMilli()
    private val STORM_V2_VALID_FROM = Instant.parse("2026-08-01T04:00:00Z").toEpochMilli()
    private val STORM_V2_VALID_TO = Instant.parse("2026-08-01T06:00:00Z").toEpochMilli()

    private val TYPHOON_V1_VALID_FROM = Instant.parse("2026-07-31T00:00:00Z").toEpochMilli()
    private val TYPHOON_V1_VALID_TO = Instant.parse("2026-07-31T12:00:00Z").toEpochMilli()
    private val TYPHOON_V2_PUBLISHED_AT = Instant.parse("2026-08-01T04:50:00Z").toEpochMilli()
    private val TYPHOON_V2_VALID_FROM = Instant.parse("2026-08-01T05:00:00Z").toEpochMilli()
    private val TYPHOON_V2_VALID_TO = Instant.parse("2026-08-01T05:30:00Z").toEpochMilli()

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

        val regionStormV1 = Rule(
            id = "region-440800-storm",
            layer = RuleLayer.REGION,
            regionCode = "440800",
            condition = RuleCondition(minRainfallMm = 60.0),
            action = Action.RESTRICT,
            version = 1,
            publishedAt = BASE_TIME,
            validFrom = BASE_TIME,
            description = "区域规则 440800 暴雨 v1：小时降水≥60mm 触发管制"
        )
        repository.publishRule(regionStormV1)

        val regionStormV2 = Rule(
            id = "region-440800-storm",
            layer = RuleLayer.REGION,
            regionCode = "440800",
            condition = RuleCondition(minRainfallMm = 75.0),
            action = Action.RESTRICT,
            version = 2,
            publishedAt = STORM_V2_PUBLISHED_AT,
            validFrom = STORM_V2_VALID_FROM,
            validTo = STORM_V2_VALID_TO,
            description = "区域规则 440800 暴雨 v2：2026-08-01 04:00-06:00 临时上调阈值至 75mm"
        )
        repository.publishRule(regionStormV2)

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
            validTo = MANUAL_CLOSE_EXPIRES_AT,
            description = "人工强制规则 tunnel-17：常规关闭，已于 2026-07-31 到期"
        )
        repository.publishRule(manualCloseRule)

        val typhoonV1 = Rule(
            id = "manual-tunnel-17-typhoon",
            layer = RuleLayer.MANUAL,
            facilityId = "tunnel-17",
            condition = RuleCondition(),
            action = Action.CLOSE,
            version = 1,
            publishedAt = BASE_TIME,
            validFrom = TYPHOON_V1_VALID_FROM,
            validTo = TYPHOON_V1_VALID_TO,
            description = "人工台风规则 tunnel-17 v1：前一轮台风，已于 2026-07-31 12:00 到期"
        )
        repository.publishRule(typhoonV1)

        val typhoonV2 = Rule(
            id = "manual-tunnel-17-typhoon",
            layer = RuleLayer.MANUAL,
            facilityId = "tunnel-17",
            condition = RuleCondition(),
            action = Action.CLOSE,
            version = 2,
            publishedAt = TYPHOON_V2_PUBLISHED_AT,
            validFrom = TYPHOON_V2_VALID_FROM,
            validTo = TYPHOON_V2_VALID_TO,
            description = "人工台风规则 tunnel-17 v2：2026-08-01 05:00-05:30 台风强制关闭"
        )
        repository.publishRule(typhoonV2)

        logger.info(
            "Seed completed: 1 facility, 10 rules (default/storm v1+v2/region×2/facility×2/manual close/typhoon v1+v2)"
        )
    }
}
