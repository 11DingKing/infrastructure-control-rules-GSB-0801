package com.gsb.control.app

import com.gsb.control.domain.EvaluationResult
import com.gsb.control.domain.RiskInput
import com.gsb.control.domain.Rule
import com.gsb.control.domain.RuleEvaluator
import com.gsb.control.persistence.EvaluationResultRepository
import com.gsb.control.persistence.FacilityRepository
import com.gsb.control.persistence.PublishOutcome
import com.gsb.control.persistence.RuleRepository
import com.gsb.control.persistence.StoredEvaluation
import java.time.Clock
import java.time.Instant

/** Enumerable failure reasons for orchestration calls that the pure domain cannot express alone. */
sealed interface ServiceError {
    data class FacilityNotFound(val facilityId: String) : ServiceError
    data class VersionConflict(val ruleKey: String, val version: Int) : ServiceError
}

/** Result wrapper: either a value or an enumerable [ServiceError]. Avoids exceptions for expected failures. */
sealed interface ServiceResult<out T> {
    data class Ok<T>(val value: T) : ServiceResult<T>
    data class Err(val error: ServiceError) : ServiceResult<Nothing>
}

/**
 * Application service: orchestrates the pure evaluator with persistence and
 * notification.
 *
 * Invariant enforced here: judgement is produced *only* by [RuleEvaluator]
 * (pure). Persistence and notifiers run strictly *after* the result exists and
 * only consume it. The [clock] is the single source of wall-clock time so the
 * pure layer stays deterministic — the service reads the clock once and passes
 * explicit instants down.
 */
class ControlService(
    private val facilities: FacilityRepository,
    private val rules: RuleRepository,
    private val results: EvaluationResultRepository,
    private val notifier: EvaluationNotifier,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun upsertFacility(facility: com.gsb.control.domain.Facility) = facilities.upsert(facility)

    fun publishRule(rule: Rule): ServiceResult<Rule> =
        when (val outcome = rules.publish(rule)) {
            is PublishOutcome.Published -> ServiceResult.Ok(outcome.rule)
            is PublishOutcome.VersionConflict ->
                ServiceResult.Err(ServiceError.VersionConflict(outcome.ruleKey, outcome.version))
        }

    /**
     * Evaluate a facility now and persist + notify. [asOf] defaults to the
     * evaluation instant; pass an earlier instant to reproduce a historical
     * decision (rules published after [asOf] are ignored by the pure layer).
     * [persist] and [notify] toggles let batch benchmarks run the identical
     * pure path without side effects.
     */
    fun evaluateAndRecord(
        facilityId: String,
        input: RiskInput,
        evaluatedAt: Instant = clock.instant(),
        asOf: Instant? = null,
        persist: Boolean = true,
        notify: Boolean = true,
    ): ServiceResult<EvaluationOutcome> {
        val facility = facilities.findById(facilityId)
            ?: return ServiceResult.Err(ServiceError.FacilityNotFound(facilityId))

        val allRules: List<Rule> = rules.all()
        val effectiveAsOf = asOf ?: evaluatedAt

        // ---- PURE JUDGEMENT (no I/O) -------------------------------------
        val result: EvaluationResult = RuleEvaluator.evaluate(
            facility = facility,
            rules = allRules,
            input = input,
            evaluatedAt = evaluatedAt,
            asOf = effectiveAsOf,
        )
        // ------------------------------------------------------------------

        // Consumers only from here on.
        var stored: StoredEvaluation? = null
        if (persist) {
            val s = results.save(result)
            stored = s
            if (notify) {
                notifier.onEvaluated(StoredNotification(s.id, result))
            }
        }

        return ServiceResult.Ok(EvaluationOutcome(result, stored?.id, stored?.canonicalDigest))
    }

    fun facility(facilityId: String) = facilities.findById(facilityId)
    fun result(id: Int): StoredEvaluation? = results.findById(id)
    fun resultsForFacility(facilityId: String) = results.findByFacility(facilityId)
    fun rulesForKey(ruleKey: String) = rules.byKey(ruleKey)
    fun allRules() = rules.all()
}

/** The evaluation output surfaced to callers: the pure result plus storage provenance. */
data class EvaluationOutcome(
    val result: EvaluationResult,
    val storedId: Int?,
    val canonicalDigest: String?,
)
