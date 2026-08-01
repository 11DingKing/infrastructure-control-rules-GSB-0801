package com.gsb.control.app

import com.gsb.control.domain.Action
import com.gsb.control.domain.Comparator
import com.gsb.control.domain.Condition
import com.gsb.control.domain.Facility
import com.gsb.control.domain.FacilityKind
import com.gsb.control.domain.RiskInput
import com.gsb.control.domain.RiskMetric
import com.gsb.control.domain.Rule
import com.gsb.control.domain.RuleScope
import java.time.Instant

/**
 * Deterministic demo seed: facility tunnel-17 plus the four rule layers from
 * the brief, and the reference risk input. Kept out of the pure domain and out
 * of persistence so it can be reused by tests and the boot sequence with fixed
 * instants (no wall-clock reads inside).
 */
object Seed {

    const val FACILITY_ID = "tunnel-17"
    const val REGION_CODE = "440800"

    /** A fixed reference epoch so seeded instants are reproducible. */
    val T0: Instant = Instant.parse("2026-08-01T00:00:00Z")

    /** Reference risk input: 72 mm/h rainfall, wind force 7, water depth 18 cm. */
    val REFERENCE_INPUT: RiskInput = RiskInput.of(
        RiskMetric.HOURLY_RAINFALL_MM to 72.0,
        RiskMetric.WIND_FORCE_LEVEL to 7.0,
        RiskMetric.WATER_DEPTH_CM to 18.0,
    )

    val FACILITY = Facility(
        id = FACILITY_ID,
        kind = FacilityKind.TUNNEL,
        regionCode = REGION_CODE,
        name = "City Ring Tunnel #17",
    )

    /**
     * Four layered rules. Publish timestamps are staggered so historical replay
     * is meaningful. The manual override carries an explicit expiry.
     */
    fun rules(now: Instant = T0): List<Rule> = listOf(
        // DEFAULT: any measurable rainfall -> monitor baseline for all facilities.
        Rule(
            ruleKey = "default.baseline",
            version = 1,
            scope = RuleScope.Global,
            condition = Condition.Threshold(RiskMetric.HOURLY_RAINFALL_MM, Comparator.GTE, 10.0),
            action = Action.MONITOR,
            validFrom = now.minusSeconds(86_400),
            validUntil = null,
            publishedAt = now.minusSeconds(86_400),
            description = "Baseline monitoring when rainfall >= 10 mm/h.",
        ),
        // REGION 440800: heavy rain or high wind -> restrict access.
        Rule(
            ruleKey = "region-440800.storm",
            version = 1,
            scope = RuleScope.Region(REGION_CODE),
            condition = Condition.Or(
                listOf(
                    Condition.Threshold(RiskMetric.HOURLY_RAINFALL_MM, Comparator.GTE, 50.0),
                    Condition.Threshold(RiskMetric.WIND_FORCE_LEVEL, Comparator.GTE, 6.0),
                ),
            ),
            action = Action.RESTRICT,
            validFrom = now.minusSeconds(43_200),
            validUntil = null,
            publishedAt = now.minusSeconds(43_200),
            description = "Regional storm restriction for 440800.",
        ),
        // FACILITY tunnel-17: standing water >= 15 cm -> close (tunnel-specific).
        Rule(
            ruleKey = "facility-tunnel-17.flood",
            version = 1,
            scope = RuleScope.FacilitySpecific(FACILITY_ID),
            condition = Condition.Threshold(RiskMetric.WATER_DEPTH_CM, Comparator.GTE, 15.0),
            action = Action.CLOSE,
            validFrom = now.minusSeconds(3_600),
            validUntil = null,
            publishedAt = now.minusSeconds(3_600),
            description = "Tunnel flooding closure when water depth >= 15 cm.",
        ),
        // MANUAL tunnel-17: human override to restrict, expiring in 6 hours.
        Rule(
            ruleKey = "manual-tunnel-17.override",
            version = 1,
            scope = RuleScope.Manual(FACILITY_ID),
            condition = Condition.Always,
            action = Action.RESTRICT,
            validFrom = now,
            validUntil = now.plusSeconds(6 * 3_600),
            publishedAt = now,
            description = "Manual override: restrict (supervisor), expires in 6h.",
        ),
    )

    /** Publish facility + all four rules into a service. Consumers only. */
    fun apply(service: ControlService, now: Instant = T0) {
        service.upsertFacility(FACILITY)
        for (rule in rules(now)) {
            service.publishRule(rule)
        }
    }
}
