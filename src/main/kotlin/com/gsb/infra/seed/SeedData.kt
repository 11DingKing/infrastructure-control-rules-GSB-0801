package com.gsb.infra.seed

import com.gsb.infra.domain.Action
import com.gsb.infra.domain.Condition
import com.gsb.infra.domain.Facility
import com.gsb.infra.domain.FacilityType
import com.gsb.infra.domain.Metric
import com.gsb.infra.domain.Operator
import com.gsb.infra.domain.Rule
import com.gsb.infra.domain.RuleLayer
import com.gsb.infra.service.ControlService
import java.time.Instant

/**
 * Seeds the canonical demonstration scenario:
 *
 * Facility: tunnel-17 (TUNNEL, region 440800).
 * Risk input: hourly precipitation 72mm, wind level 7, water depth 18cm.
 *
 * Four rule layers, each versioned, covering monitor/restrict/close:
 *  - default: baseline thresholds for all facilities.
 *  - region:440800: stricter regional thresholds.
 *  - facility:tunnel-17: facility-specific thresholds.
 *  - manual:tunnel-17: a human-forced close with an expiration time.
 */
object SeedData {

    val TUNNEL_17 = Facility(
        id = "tunnel-17",
        type = FacilityType.TUNNEL,
        regionCode = "440800",
        name = "海滨隧道 17 号"
    )

    private val publishedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val effectiveFrom: Instant = Instant.parse("2026-01-01T00:00:00Z")

    fun seedIfEmpty(service: ControlService) {
        if (service.listFacilities().isNotEmpty()) return

        service.registerFacility(TUNNEL_17)

        // DEFAULT layer: monitor at light rain, restrict at heavy rain.
        service.publishRule(
            Rule(
                ruleId = "default-rain-monitor",
                version = 1,
                layer = RuleLayer.DEFAULT,
                facilityId = null,
                regionCode = null,
                facilityTypes = setOf(FacilityType.TUNNEL, FacilityType.WATER_SECTION),
                conditions = listOf(
                    Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 20.0)
                ),
                action = Action.MONITOR,
                reason = "默认层：小时降水达到 20mm 进入监测",
                effectiveFrom = effectiveFrom,
                expiresAt = null,
                publishedAt = publishedAt
            )
        )
        service.publishRule(
            Rule(
                ruleId = "default-rain-restrict",
                version = 1,
                layer = RuleLayer.DEFAULT,
                facilityId = null,
                regionCode = null,
                facilityTypes = setOf(FacilityType.TUNNEL, FacilityType.WATER_SECTION),
                conditions = listOf(
                    Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 50.0)
                ),
                action = Action.RESTRICT,
                reason = "默认层：小时降水达到 50mm 采取限行",
                effectiveFrom = effectiveFrom,
                expiresAt = null,
                publishedAt = publishedAt
            )
        )
        service.publishRule(
            Rule(
                ruleId = "default-water-close",
                version = 1,
                layer = RuleLayer.DEFAULT,
                facilityId = null,
                regionCode = null,
                facilityTypes = setOf(FacilityType.TUNNEL),
                conditions = listOf(
                    Condition(Metric.WATER_DEPTH_CM, Operator.GTE, 30.0)
                ),
                action = Action.CLOSE,
                reason = "默认层：隧道积水达到 30cm 关闭",
                effectiveFrom = effectiveFrom,
                expiresAt = null,
                publishedAt = publishedAt
            )
        )

        // REGION 440800: stricter rain thresholds for the region.
        service.publishRule(
            Rule(
                ruleId = "region-440800-rain-restrict",
                version = 1,
                layer = RuleLayer.REGION,
                facilityId = null,
                regionCode = "440800",
                facilityTypes = setOf(FacilityType.TUNNEL, FacilityType.WATER_SECTION),
                conditions = listOf(
                    Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 60.0)
                ),
                action = Action.RESTRICT,
                reason = "区域 440800：小时降水达到 60mm 限行",
                effectiveFrom = effectiveFrom,
                expiresAt = null,
                publishedAt = publishedAt
            )
        )
        service.publishRule(
            Rule(
                ruleId = "region-440800-wind-monitor",
                version = 1,
                layer = RuleLayer.REGION,
                facilityId = null,
                regionCode = "440800",
                facilityTypes = setOf(FacilityType.TUNNEL, FacilityType.TEMPORARY_STRUCTURE),
                conditions = listOf(
                    Condition(Metric.WIND_LEVEL, Operator.GTE, 6.0)
                ),
                action = Action.MONITOR,
                reason = "区域 440800：风力达到 6 级进入监测",
                effectiveFrom = effectiveFrom,
                expiresAt = null,
                publishedAt = publishedAt
            )
        )

        // FACILITY tunnel-17: water depth restriction specific to this tunnel.
        service.publishRule(
            Rule(
                ruleId = "facility-tunnel-17-water-restrict",
                version = 1,
                layer = RuleLayer.FACILITY,
                facilityId = "tunnel-17",
                regionCode = null,
                facilityTypes = emptySet(),
                conditions = listOf(
                    Condition(Metric.WATER_DEPTH_CM, Operator.GTE, 15.0)
                ),
                action = Action.RESTRICT,
                reason = "设施 tunnel-17：积水达到 15cm 限行",
                effectiveFrom = effectiveFrom,
                expiresAt = null,
                publishedAt = publishedAt
            )
        )
        service.publishRule(
            Rule(
                ruleId = "facility-tunnel-17-wind-close",
                version = 1,
                layer = RuleLayer.FACILITY,
                facilityId = "tunnel-17",
                regionCode = null,
                facilityTypes = emptySet(),
                conditions = listOf(
                    Condition(Metric.WIND_LEVEL, Operator.GTE, 8.0),
                    Condition(Metric.HOURLY_PRECIPITATION_MM, Operator.GTE, 50.0)
                ),
                action = Action.CLOSE,
                reason = "设施 tunnel-17：风力 8 级且降水 50mm 关闭",
                effectiveFrom = effectiveFrom,
                expiresAt = null,
                publishedAt = publishedAt
            )
        )

        // MANUAL tunnel-17: human-forced CLOSE with an expiration. At the seed
        // baseline time this is active and wins; after expiry it is ignored.
        service.publishRule(
            Rule(
                ruleId = "manual-tunnel-17-close",
                version = 1,
                layer = RuleLayer.MANUAL,
                facilityId = "tunnel-17",
                regionCode = null,
                facilityTypes = emptySet(),
                conditions = emptyList(),
                action = Action.CLOSE,
                reason = "人工强制：应急演练关闭 tunnel-17",
                effectiveFrom = Instant.parse("2026-02-01T00:00:00Z"),
                expiresAt = Instant.parse("2026-03-01T00:00:00Z"),
                publishedAt = Instant.parse("2026-01-15T00:00:00Z")
            )
        )
    }
}
