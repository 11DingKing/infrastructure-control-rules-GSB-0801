package com.gsb.infra.persistence

import com.gsb.infra.domain.Action
import com.gsb.infra.domain.Rule
import com.gsb.infra.domain.RuleLayer
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Clock
import java.time.Instant

/**
 * Thrown when two concurrent publishers attempt to create the same (ruleId, version).
 * Callers must treat this as a conflict and retry with a higher version.
 */
class VersionConflictException(
    val ruleId: String,
    val version: Int
) : RuntimeException("Rule $ruleId version $version already exists")

class RuleRepository(private val clock: Clock = Clock.systemUTC()) {

    /**
     * Persists a new rule version. The unique (rule_id, version) index guarantees
     * that concurrent same-version publishes cannot both succeed; the loser sees
     * [VersionConflictException].
     */
    fun insert(rule: Rule): Rule = transaction {
        val exists = RulesTable.selectAll()
            .where { (RulesTable.ruleId eq rule.ruleId) and (RulesTable.version eq rule.version) }
            .count() > 0
        if (exists) throw VersionConflictException(rule.ruleId, rule.version)

        RulesTable.insert {
            it[ruleId] = rule.ruleId
            it[version] = rule.version
            it[layer] = rule.layer.name
            it[facilityId] = rule.facilityId
            it[regionCode] = rule.regionCode
            it[facilityTypes] = PersistenceJson.encodeFacilityTypes(rule.facilityTypes)
            it[conditions] = PersistenceJson.encodeConditions(rule.conditions)
            it[action] = rule.action.name
            it[reason] = rule.reason
            it[effectiveFrom] = rule.effectiveFrom
            it[expiresAt] = rule.expiresAt
            it[publishedAt] = rule.publishedAt
            it[createdAt] = Instant.now(clock)
        }
        rule
    }

    fun nextVersion(ruleId: String): Int = transaction {
        val maxVersion = RulesTable.version.max()
        val row = RulesTable
            .select(maxVersion)
            .where { RulesTable.ruleId eq ruleId }
            .singleOrNull()
        (row?.get(maxVersion) ?: 0) + 1
    }

    fun findById(ruleId: String): List<Rule> = transaction {
        RulesTable.selectAll()
            .where { RulesTable.ruleId eq ruleId }
            .orderBy(RulesTable.version to SortOrder.ASC)
            .map { it.toRule() }
    }

    /**
     * Returns every rule that could apply to the facility, across all layers.
     * The domain layer decides what was published/effective at a given instant.
     */
    fun findForFacility(facilityId: String, regionCode: String): List<Rule> = transaction {
        RulesTable.selectAll().where {
            (RulesTable.layer eq RuleLayer.DEFAULT.name) or
                ((RulesTable.layer eq RuleLayer.REGION.name) and (RulesTable.regionCode eq regionCode)) or
                ((RulesTable.layer eq RuleLayer.FACILITY.name) and (RulesTable.facilityId eq facilityId)) or
                ((RulesTable.layer eq RuleLayer.MANUAL.name) and (RulesTable.facilityId eq facilityId))
        }.map { it.toRule() }
    }

    fun all(): List<Rule> = transaction {
        RulesTable.selectAll()
            .orderBy(RulesTable.ruleId to SortOrder.ASC, RulesTable.version to SortOrder.ASC)
            .map { it.toRule() }
    }

    private fun ResultRow.toRule(): Rule = Rule(
        ruleId = this[RulesTable.ruleId],
        version = this[RulesTable.version],
        layer = RuleLayer.valueOf(this[RulesTable.layer]),
        facilityId = this[RulesTable.facilityId],
        regionCode = this[RulesTable.regionCode],
        facilityTypes = PersistenceJson.decodeFacilityTypes(this[RulesTable.facilityTypes]),
        conditions = PersistenceJson.decodeConditions(this[RulesTable.conditions]),
        action = Action.valueOf(this[RulesTable.action]),
        reason = this[RulesTable.reason],
        effectiveFrom = this[RulesTable.effectiveFrom],
        expiresAt = this[RulesTable.expiresAt],
        publishedAt = this[RulesTable.publishedAt]
    )
}
