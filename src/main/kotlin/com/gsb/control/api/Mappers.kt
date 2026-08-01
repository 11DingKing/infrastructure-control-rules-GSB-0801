package com.gsb.control.api

import com.gsb.control.domain.Action
import com.gsb.control.domain.Comparator
import com.gsb.control.domain.Condition
import com.gsb.control.domain.EvaluationResult
import com.gsb.control.domain.Facility
import com.gsb.control.domain.FacilityKind
import com.gsb.control.domain.RiskInput
import com.gsb.control.domain.RiskMetric
import com.gsb.control.domain.Rule
import com.gsb.control.domain.RuleScope
import java.time.Instant

/** Enumerable request-validation failures, so parsing never leaks raw exceptions to clients. */
sealed interface ParseError {
    val reason: String
    val detail: String

    data class UnknownEnum(override val detail: String, val field: String) : ParseError {
        override val reason: String get() = "UNKNOWN_ENUM"
    }

    data class BadTimestamp(override val detail: String) : ParseError {
        override val reason: String get() = "BAD_TIMESTAMP"
    }

    data class MissingField(val fieldName: String) : ParseError {
        override val reason: String get() = "MISSING_FIELD"
        override val detail: String get() = "Required field missing: $fieldName"
    }

    data class BadValue(override val detail: String) : ParseError {
        override val reason: String get() = "BAD_VALUE"
    }
}

/** Either a parsed value or an enumerable [ParseError]. */
sealed interface Parsed<out T> {
    data class Ok<T>(val value: T) : Parsed<T>
    data class Err(val error: ParseError) : Parsed<Nothing>
}

object Mappers {

    fun parseInstant(text: String): Parsed<Instant> = try {
        Parsed.Ok(Instant.parse(text))
    } catch (e: Exception) {
        Parsed.Err(ParseError.BadTimestamp("Not an ISO-8601 instant: '$text'"))
    }

    fun toFacility(req: FacilityRequest): Parsed<Facility> {
        val kind = FacilityKind.entries.firstOrNull { it.code == req.kind }
            ?: return Parsed.Err(ParseError.UnknownEnum("Unknown facility kind: '${req.kind}'", "kind"))
        return Parsed.Ok(Facility(id = req.id, kind = kind, regionCode = req.regionCode, name = req.name))
    }

    fun toRiskInput(map: Map<String, Double>): Parsed<RiskInput> {
        val values = LinkedHashMap<RiskMetric, Double>()
        for ((k, v) in map) {
            val metric = RiskMetric.fromKey(k)
                ?: return Parsed.Err(ParseError.UnknownEnum("Unknown risk metric: '$k'", "input"))
            values[metric] = v
        }
        return Parsed.Ok(RiskInput(values))
    }

    fun toCondition(dto: ConditionDto): Parsed<Condition> {
        return when (dto.type) {
        "always" -> Parsed.Ok(Condition.Always)
        "threshold" -> {
            val metricKey = dto.metric ?: return Parsed.Err(ParseError.MissingField("metric"))
            val metric = RiskMetric.fromKey(metricKey)
                ?: return Parsed.Err(ParseError.UnknownEnum("Unknown risk metric: '$metricKey'", "metric"))
            val comparatorName = dto.comparator ?: return Parsed.Err(ParseError.MissingField("comparator"))
            val comparator = Comparator.entries.firstOrNull { it.name == comparatorName }
                ?: return Parsed.Err(ParseError.UnknownEnum("Unknown comparator: '$comparatorName'", "comparator"))
            val threshold = dto.threshold ?: return Parsed.Err(ParseError.MissingField("threshold"))
            Parsed.Ok(Condition.Threshold(metric, comparator, threshold))
        }
        "and", "or" -> {
            val terms = dto.terms ?: return Parsed.Err(ParseError.MissingField("terms"))
            val parsed = ArrayList<Condition>(terms.size)
            for (t in terms) {
                when (val p = toCondition(t)) {
                    is Parsed.Ok -> parsed.add(p.value)
                    is Parsed.Err -> return p
                }
            }
            Parsed.Ok(if (dto.type == "and") Condition.And(parsed) else Condition.Or(parsed))
        }
        else -> Parsed.Err(ParseError.UnknownEnum("Unknown condition type: '${dto.type}'", "type"))
        }
    }

    fun toScope(kind: String, arg: String?): Parsed<RuleScope> = when (kind) {
        "global" -> Parsed.Ok(RuleScope.Global)
        "region" -> arg?.let { Parsed.Ok(RuleScope.Region(it)) }
            ?: Parsed.Err(ParseError.MissingField("scopeArg"))
        "facility" -> arg?.let { Parsed.Ok(RuleScope.FacilitySpecific(it)) }
            ?: Parsed.Err(ParseError.MissingField("scopeArg"))
        "manual" -> arg?.let { Parsed.Ok(RuleScope.Manual(it)) }
            ?: Parsed.Err(ParseError.MissingField("scopeArg"))
        else -> Parsed.Err(ParseError.UnknownEnum("Unknown scope kind: '$kind'", "scopeKind"))
    }

    fun toRule(req: RuleRequest, fallbackPublishedAt: Instant): Parsed<Rule> {
        val action = Action.entries.firstOrNull { it.name == req.action }
            ?: return Parsed.Err(ParseError.UnknownEnum("Unknown action: '${req.action}'", "action"))
        val scope = when (val p = toScope(req.scopeKind, req.scopeArg)) {
            is Parsed.Ok -> p.value
            is Parsed.Err -> return p
        }
        val condition = when (val p = toCondition(req.condition)) {
            is Parsed.Ok -> p.value
            is Parsed.Err -> return p
        }
        val validFrom = when (val p = parseInstant(req.validFrom)) {
            is Parsed.Ok -> p.value
            is Parsed.Err -> return p
        }
        val validUntil = req.validUntil?.let {
            when (val p = parseInstant(it)) {
                is Parsed.Ok -> p.value
                is Parsed.Err -> return p
            }
        }
        val publishedAt = req.publishedAt?.let {
            when (val p = parseInstant(it)) {
                is Parsed.Ok -> p.value
                is Parsed.Err -> return p
            }
        } ?: fallbackPublishedAt

        if (validUntil != null && !validUntil.isAfter(validFrom)) {
            return Parsed.Err(ParseError.BadValue("validUntil must be after validFrom"))
        }

        return Parsed.Ok(
            Rule(
                ruleKey = req.ruleKey,
                version = req.version,
                scope = scope,
                condition = condition,
                action = action,
                validFrom = validFrom,
                validUntil = validUntil,
                publishedAt = publishedAt,
                description = req.description,
            ),
        )
    }

    // ---- Domain -> response -------------------------------------------------

    fun ruleResponse(rule: Rule): RuleResponse {
        val (kind, arg) = when (val s = rule.scope) {
            RuleScope.Global -> "global" to null
            is RuleScope.Region -> "region" to s.regionCode
            is RuleScope.FacilitySpecific -> "facility" to s.facilityId
            is RuleScope.Manual -> "manual" to s.facilityId
        }
        return RuleResponse(
            ruleKey = rule.ruleKey,
            version = rule.version,
            versionRef = rule.versionRef,
            layer = rule.layer.name,
            scopeKind = kind,
            scopeArg = arg,
            action = rule.action.name,
            validFrom = rule.validFrom.toString(),
            validUntil = rule.validUntil?.toString(),
            publishedAt = rule.publishedAt.toString(),
            description = rule.description,
        )
    }

    fun facilityResponse(f: Facility) = FacilityResponse(f.id, f.kind.code, f.regionCode, f.name)

    fun evaluationResponse(result: EvaluationResult, storedId: Int?, digest: String?): EvaluationResponse =
        EvaluationResponse(
            storedId = storedId,
            facilityId = result.facilityId,
            decision = result.decision?.name,
            decidingLayer = result.decidingLayer?.name,
            decidingVersionRef = result.decidingVersionRef,
            input = result.input.values.entries.sortedBy { it.key.key }.associate { it.key.key to it.value },
            evaluatedAt = result.evaluatedAt.toString(),
            asOf = result.asOf.toString(),
            firedVersionRefs = result.firedVersionRefs,
            canonicalDigest = digest,
            contentHash = result.contentHash,
            explanation = result.trace.map {
                TraceLineResponse(
                    versionRef = it.versionRef,
                    ruleKey = it.ruleKey,
                    version = it.version,
                    layer = it.layer.name,
                    action = it.action.name,
                    outcome = it.outcome.code,
                    decisive = it.decisive,
                    detail = it.detail,
                    breakdown = TraceBreakdownResponse(
                        visibility = it.breakdown.visibility.name,
                        scope = it.breakdown.scope.name,
                        window = it.breakdown.window.name,
                        versionSelection = it.breakdown.versionSelection.name,
                        condition = it.breakdown.condition.name,
                        missingMetric = it.breakdown.missingMetric,
                        priority = it.breakdown.priority.name,
                    ),
                )
            },
        )
}
