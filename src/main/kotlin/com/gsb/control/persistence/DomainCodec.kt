package com.gsb.control.persistence

import com.gsb.control.domain.Action
import com.gsb.control.domain.Comparator
import com.gsb.control.domain.Condition
import com.gsb.control.domain.RiskInput
import com.gsb.control.domain.RiskMetric
import com.gsb.control.domain.RuleScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stable, versionable wire/storage representations of the pure domain types
 * that are not themselves serializable (sealed hierarchies with behaviour).
 *
 * Keeping this bridge in the persistence layer preserves the rule that the
 * domain has zero framework dependencies. Encoding is deterministic:
 * [DomainCodec.json] disables pretty-printing and uses a fixed class
 * discriminator, so equal domain values encode to byte-identical strings.
 */
object DomainCodec {
    val json: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    // ---- Condition ----------------------------------------------------------

    @Serializable
    sealed interface ConditionDto {
        @Serializable
        @SerialName("threshold")
        data class Threshold(val metric: String, val comparator: String, val threshold: Double) : ConditionDto

        @Serializable
        @SerialName("always")
        data object Always : ConditionDto

        @Serializable
        @SerialName("and")
        data class And(val terms: List<ConditionDto>) : ConditionDto

        @Serializable
        @SerialName("or")
        data class Or(val terms: List<ConditionDto>) : ConditionDto
    }

    fun encodeCondition(condition: Condition): String = json.encodeToString(toDto(condition))

    fun decodeCondition(text: String): Condition = fromDto(json.decodeFromString<ConditionDto>(text))

    private fun toDto(c: Condition): ConditionDto = when (c) {
        is Condition.Threshold -> ConditionDto.Threshold(c.metric.key, c.comparator.name, c.threshold)
        Condition.Always -> ConditionDto.Always
        is Condition.And -> ConditionDto.And(c.terms.map { toDto(it) })
        is Condition.Or -> ConditionDto.Or(c.terms.map { toDto(it) })
    }

    private fun fromDto(dto: ConditionDto): Condition = when (dto) {
        is ConditionDto.Threshold -> Condition.Threshold(
            metric = RiskMetric.fromKey(dto.metric)
                ?: throw IllegalArgumentException("Unknown risk metric: ${dto.metric}"),
            comparator = Comparator.valueOf(dto.comparator),
            threshold = dto.threshold,
        )
        ConditionDto.Always -> Condition.Always
        is ConditionDto.And -> Condition.And(dto.terms.map { fromDto(it) })
        is ConditionDto.Or -> Condition.Or(dto.terms.map { fromDto(it) })
    }

    // ---- RuleScope ----------------------------------------------------------

    /** ("global"|"region"|"facility"|"manual", arg) — arg is null for global. */
    fun encodeScope(scope: RuleScope): Pair<String, String?> = when (scope) {
        RuleScope.Global -> "global" to null
        is RuleScope.Region -> "region" to scope.regionCode
        is RuleScope.FacilitySpecific -> "facility" to scope.facilityId
        is RuleScope.Manual -> "manual" to scope.facilityId
    }

    fun decodeScope(kind: String, arg: String?): RuleScope = when (kind) {
        "global" -> RuleScope.Global
        "region" -> RuleScope.Region(requireArg(kind, arg))
        "facility" -> RuleScope.FacilitySpecific(requireArg(kind, arg))
        "manual" -> RuleScope.Manual(requireArg(kind, arg))
        else -> throw IllegalArgumentException("Unknown scope kind: $kind")
    }

    private fun requireArg(kind: String, arg: String?): String =
        arg ?: throw IllegalArgumentException("Scope kind '$kind' requires an argument")

    // ---- RiskInput ----------------------------------------------------------

    fun encodeInput(input: RiskInput): String {
        // Deterministic: sort by metric key.
        val map = input.values.entries
            .sortedBy { it.key.key }
            .associate { it.key.key to it.value }
        return json.encodeToString(map)
    }

    fun decodeInput(text: String): RiskInput {
        val map = json.decodeFromString<Map<String, Double>>(text)
        val values = map.entries.associate { (k, v) ->
            (RiskMetric.fromKey(k) ?: throw IllegalArgumentException("Unknown risk metric: $k")) to v
        }
        return RiskInput(values)
    }

    fun encodeAction(action: Action): String = action.name
}
