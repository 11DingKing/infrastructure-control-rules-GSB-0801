package com.gsb.control.persistence

import com.gsb.control.domain.Action
import com.gsb.control.domain.Rule
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

/** Outcome of attempting to publish a rule revision. Enumerable, never throws for expected cases. */
sealed interface PublishOutcome {
    data class Published(val rule: Rule) : PublishOutcome

    /** A revision with the same (ruleKey, version) already exists — concurrent/duplicate publish. */
    data class VersionConflict(val ruleKey: String, val version: Int) : PublishOutcome
}

/** Storage for versioned [Rule] revisions. Append-only: revisions are immutable once published. */
class RuleRepository(private val db: Db) {

    /**
     * Publish a new immutable revision. The unique (rule_key, version) index
     * makes concurrent attempts to publish the same version deterministic:
     * exactly one wins with [PublishOutcome.Published], the rest get
     * [PublishOutcome.VersionConflict]. No update or upsert — history is never
     * rewritten.
     */
    fun publish(rule: Rule): PublishOutcome = db.writeTx {
        val (kind, arg) = DomainCodec.encodeScope(rule.scope)
        try {
            RulesTable.insert {
                it[ruleKey] = rule.ruleKey
                it[version] = rule.version
                it[scopeKind] = kind
                it[scopeArg] = arg
                it[action] = DomainCodec.encodeAction(rule.action)
                it[conditionJson] = DomainCodec.encodeCondition(rule.condition)
                it[validFrom] = rule.validFrom
                it[validUntil] = rule.validUntil
                it[publishedAt] = rule.publishedAt
                it[description] = rule.description
            }
            PublishOutcome.Published(rule)
        } catch (e: ExposedSQLException) {
            // SQLite raises a constraint violation for the duplicate unique key.
            if (isUniqueViolation(e)) {
                PublishOutcome.VersionConflict(rule.ruleKey, rule.version)
            } else {
                throw e
            }
        }
    }

    /** All rule revisions, newest publish first. Used to feed the pure evaluator. */
    fun all(): List<Rule> = db.tx {
        RulesTable.selectAll()
            .orderBy(RulesTable.publishedAt to SortOrder.DESC)
            .map { it.toRule() }
    }

    fun byKey(ruleKey: String): List<Rule> = db.tx {
        RulesTable.selectAll().where { RulesTable.ruleKey eq ruleKey }
            .orderBy(RulesTable.version to SortOrder.ASC)
            .map { it.toRule() }
    }

    private fun isUniqueViolation(e: ExposedSQLException): Boolean {
        val msg = e.message?.lowercase() ?: return false
        return msg.contains("unique") || msg.contains("constraint")
    }

    private fun ResultRow.toRule(): Rule {
        val actionName = this[RulesTable.action]
        val action = Action.entries.firstOrNull { it.name == actionName }
            ?: throw IllegalStateException("Unknown action in storage: $actionName")
        return Rule(
            ruleKey = this[RulesTable.ruleKey],
            version = this[RulesTable.version],
            scope = DomainCodec.decodeScope(this[RulesTable.scopeKind], this[RulesTable.scopeArg]),
            condition = DomainCodec.decodeCondition(this[RulesTable.conditionJson]),
            action = action,
            validFrom = this[RulesTable.validFrom],
            validUntil = this[RulesTable.validUntil],
            publishedAt = this[RulesTable.publishedAt],
            description = this[RulesTable.description],
        )
    }
}
