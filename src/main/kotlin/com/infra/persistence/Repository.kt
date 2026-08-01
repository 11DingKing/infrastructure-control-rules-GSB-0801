package com.infra.persistence

import com.infra.domain.Action
import com.infra.domain.EvaluationResult
import com.infra.domain.ExplanationEntry
import com.infra.domain.Facility
import com.infra.domain.FacilityType
import com.infra.domain.ReasonCode
import com.infra.domain.RiskInput
import com.infra.domain.Rule
import com.infra.domain.RuleCondition
import com.infra.domain.RuleLayer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

class Repository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun createFacility(facility: Facility): Facility = transaction {
        FacilitiesTable.insert {
            it[id] = facility.id
            it[name] = facility.name
            it[type] = facility.type.name
            it[regionCode] = facility.regionCode
            it[location] = facility.location
            it[createdAt] = Instant.now()
        }
        facility
    }

    fun getFacility(facilityId: String): Facility? = transaction {
        FacilitiesTable.selectAll()
            .where { FacilitiesTable.id eq facilityId }
            .firstOrNull()
            ?.let { row ->
                Facility(
                    id = row[FacilitiesTable.id],
                    name = row[FacilitiesTable.name],
                    type = FacilityType.valueOf(row[FacilitiesTable.type]),
                    regionCode = row[FacilitiesTable.regionCode],
                    location = row[FacilitiesTable.location]
                )
            }
    }

    fun listFacilities(): List<Facility> = transaction {
        FacilitiesTable.selectAll()
            .map { row ->
                Facility(
                    id = row[FacilitiesTable.id],
                    name = row[FacilitiesTable.name],
                    type = FacilityType.valueOf(row[FacilitiesTable.type]),
                    regionCode = row[FacilitiesTable.regionCode],
                    location = row[FacilitiesTable.location]
                )
            }
    }

    fun publishRule(rule: Rule): Rule = transaction {
        val existingCount = RulesTable.selectAll()
            .where { (RulesTable.id eq rule.id) and (RulesTable.version eq rule.version) }
            .count()
        if (existingCount > 0) {
            throw IllegalStateException("Rule ${rule.id} version ${rule.version} already exists (concurrent publish conflict)")
        }
        RulesTable.insert {
            it[id] = rule.id
            it[layer] = rule.layer.name
            it[facilityId] = rule.facilityId
            it[regionCode] = rule.regionCode
            it[facilityType] = rule.facilityType?.name
            it[conditionJson] = json.encodeToString(rule.condition)
            it[action] = rule.action.name
            it[version] = rule.version
            it[publishedAt] = rule.publishedAt
            it[validFrom] = rule.validFrom
            it[validTo] = rule.validTo
            it[description] = rule.description
            it[createdAt] = Instant.now()
        }
        rule
    }

    fun getRule(ruleId: String, version: Int? = null): Rule? = transaction {
        val condition = if (version != null) {
            (RulesTable.id eq ruleId) and (RulesTable.version eq version)
        } else {
            RulesTable.id eq ruleId
        }
        RulesTable.selectAll()
            .where { condition }
            .map { it.toRule() }
            .maxByOrNull { it.version }
    }

    fun listRules(
        layer: RuleLayer? = null,
        facilityId: String? = null,
        regionCode: String? = null
    ): List<Rule> = transaction {
        var condition: Op<Boolean> = Op.TRUE
        layer?.let {
            condition = condition and (RulesTable.layer eq it.name)
        }
        facilityId?.let {
            condition = condition and (RulesTable.facilityId eq it)
        }
        regionCode?.let {
            condition = condition and (RulesTable.regionCode eq it)
        }
        RulesTable.selectAll()
            .where { condition }
            .map { it.toRule() }
    }

    fun listAllRules(): List<Rule> = transaction {
        RulesTable.selectAll().map { it.toRule() }
    }

    fun saveEvaluationResult(result: EvaluationResult): EvaluationResult = transaction {
        EvaluationResultsTable.insert {
            it[requestId] = result.requestId
            it[facilityId] = result.facilityId
            it[finalAction] = result.finalAction?.name
            it[reasonCode] = result.reasonCode.name
            it[hitRuleId] = result.hitRuleId
            it[hitRuleVersion] = result.hitRuleVersion
            it[hitRuleLayer] = result.hitRuleLayer?.name
            it[inputSnapshotJson] = json.encodeToString(result.inputSnapshot)
            it[explanationChainJson] = json.encodeToString(result.explanationChain)
            it[consideredRuleIdsJson] = json.encodeToString(result.consideredRuleIds)
            it[resultHash] = result.resultHash
            it[evaluatedAt] = result.evaluatedAt
            it[createdAt] = Instant.now()
        }
        result
    }

    fun getEvaluationResult(requestId: String): EvaluationResult? = transaction {
        EvaluationResultsTable.selectAll()
            .where { EvaluationResultsTable.requestId eq requestId }
            .firstOrNull()
            ?.let { row ->
                EvaluationResult(
                    requestId = row[EvaluationResultsTable.requestId],
                    facilityId = row[EvaluationResultsTable.facilityId],
                    finalAction = row[EvaluationResultsTable.finalAction]?.let { Action.valueOf(it) },
                    reasonCode = ReasonCode.valueOf(row[EvaluationResultsTable.reasonCode]),
                    hitRuleId = row[EvaluationResultsTable.hitRuleId],
                    hitRuleVersion = row[EvaluationResultsTable.hitRuleVersion],
                    hitRuleLayer = row[EvaluationResultsTable.hitRuleLayer]?.let { RuleLayer.valueOf(it) },
                    inputSnapshot = json.decodeFromString<RiskInput>(row[EvaluationResultsTable.inputSnapshotJson]),
                    evaluatedAt = row[EvaluationResultsTable.evaluatedAt],
                    explanationChain = json.decodeFromString<List<ExplanationEntry>>(row[EvaluationResultsTable.explanationChainJson]),
                    consideredRuleIds = json.decodeFromString<List<String>>(row[EvaluationResultsTable.consideredRuleIdsJson]),
                    resultHash = row[EvaluationResultsTable.resultHash]
                )
            }
    }

    fun listEvaluationResults(facilityId: String? = null): List<EvaluationResult> = transaction {
        val condition = if (facilityId != null) {
            EvaluationResultsTable.facilityId eq facilityId
        } else {
            Op.TRUE
        }
        EvaluationResultsTable.selectAll()
            .where { condition }
            .map { row ->
                EvaluationResult(
                    requestId = row[EvaluationResultsTable.requestId],
                    facilityId = row[EvaluationResultsTable.facilityId],
                    finalAction = row[EvaluationResultsTable.finalAction]?.let { Action.valueOf(it) },
                    reasonCode = ReasonCode.valueOf(row[EvaluationResultsTable.reasonCode]),
                    hitRuleId = row[EvaluationResultsTable.hitRuleId],
                    hitRuleVersion = row[EvaluationResultsTable.hitRuleVersion],
                    hitRuleLayer = row[EvaluationResultsTable.hitRuleLayer]?.let { RuleLayer.valueOf(it) },
                    inputSnapshot = json.decodeFromString<RiskInput>(row[EvaluationResultsTable.inputSnapshotJson]),
                    evaluatedAt = row[EvaluationResultsTable.evaluatedAt],
                    explanationChain = json.decodeFromString<List<ExplanationEntry>>(row[EvaluationResultsTable.explanationChainJson]),
                    consideredRuleIds = json.decodeFromString<List<String>>(row[EvaluationResultsTable.consideredRuleIdsJson]),
                    resultHash = row[EvaluationResultsTable.resultHash]
                )
            }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toRule(): Rule {
        return Rule(
            id = this[RulesTable.id],
            layer = RuleLayer.valueOf(this[RulesTable.layer]),
            facilityId = this[RulesTable.facilityId],
            regionCode = this[RulesTable.regionCode],
            facilityType = this[RulesTable.facilityType]?.let { FacilityType.valueOf(it) },
            condition = json.decodeFromString<RuleCondition>(this[RulesTable.conditionJson]),
            action = Action.valueOf(this[RulesTable.action]),
            version = this[RulesTable.version],
            publishedAt = this[RulesTable.publishedAt],
            validFrom = this[RulesTable.validFrom],
            validTo = this[RulesTable.validTo],
            description = this[RulesTable.description]
        )
    }
}
