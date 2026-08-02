package com.gsb.infra.service

import com.gsb.infra.domain.EvaluationResult
import com.gsb.infra.domain.Facility
import com.gsb.infra.domain.RiskInput
import com.gsb.infra.domain.Rule
import com.gsb.infra.domain.RuleEvaluator
import com.gsb.infra.persistence.EvaluationRepository
import com.gsb.infra.persistence.FacilityRepository
import com.gsb.infra.persistence.RuleRepository
import com.gsb.infra.persistence.StoredEvaluation
import com.gsb.infra.persistence.VersionConflictException
import java.time.Clock
import java.time.Instant

class FacilityNotFoundException(val facilityId: String) :
    RuntimeException("Facility not found: $facilityId")

class InvalidRuleException(message: String) : RuntimeException(message)

/**
 * Application service that wires the pure domain evaluator to persistence and
 * notification.
 *
 * Architectural invariant: the decision is produced by [RuleEvaluator] (a pure
 * function). This service only:
 *   1. loads the facility and candidate rules,
 *   2. calls the pure evaluator,
 *   3. persists the returned [EvaluationResult] and snapshot,
 *   4. dispatches notifications as a side-effect-free observer of the result.
 *
 * Persistence and notifications never influence the outcome.
 */
class ControlService(
    private val facilityRepository: FacilityRepository,
    private val ruleRepository: RuleRepository,
    private val evaluationRepository: EvaluationRepository,
    private val notifier: Notifier = LoggingNotifier,
    private val clock: Clock = Clock.systemUTC()
) {

    fun registerFacility(facility: Facility): Facility {
        return facilityRepository.upsert(facility)
    }

    fun findFacility(id: String): Facility? = facilityRepository.findById(id)

    fun listFacilities(): List<Facility> = facilityRepository.all()

    /**
     * Publishes a new rule version. If [Rule.version] is <= 0 the next version is
     * assigned automatically. Same-version concurrent publishes raise
     * [VersionConflictException] (surfaced as HTTP 409).
     */
    fun publishRule(rule: Rule): Rule {
        val version = if (rule.version <= 0) {
            ruleRepository.nextVersion(rule.ruleId)
        } else {
            rule.version
        }
        val toSave = rule.copy(version = version)
        return try {
            ruleRepository.insert(toSave)
        } catch (e: VersionConflictException) {
            throw e
        }
    }

    fun listRules(): List<Rule> = ruleRepository.all()

    fun listRulesForFacility(facilityId: String): List<Rule> {
        val facility = facilityRepository.findById(facilityId)
            ?: throw FacilityNotFoundException(facilityId)
        return ruleRepository.findForFacility(facility.id, facility.regionCode)
    }

    /**
     * Evaluates one facility at [evaluatedAt] (defaults to now) and persists the
     * result. The clock is injected so tests and batch baselines are reproducible.
     */
    fun evaluate(
        facilityId: String,
        input: RiskInput,
        evaluatedAt: Instant = Instant.now(clock)
    ): StoredEvaluation {
        val facility = facilityRepository.findById(facilityId)
            ?: throw FacilityNotFoundException(facilityId)
        val rules = ruleRepository.findForFacility(facility.id, facility.regionCode)

        val result = RuleEvaluator.evaluate(
            RuleEvaluator.EvaluationRequest(
                facility = facility,
                rules = rules,
                input = input,
                evaluatedAt = evaluatedAt
            )
        )

        val stored = evaluationRepository.save(result, input)
        notifier.onEvaluated(stored)
        return stored
    }

    /**
     * Evaluates multiple facilities with the same risk input and evaluation time.
     * Each evaluation runs through the same pure domain path; there is no fast path
     * that bypasses the domain layer, so batch results are identical to individual
     * evaluations and produce repeatable baseline data.
     */
    fun evaluateBatch(
        facilityIds: List<String>,
        input: RiskInput,
        evaluatedAt: Instant = Instant.now(clock)
    ): List<StoredEvaluation> {
        return facilityIds.map { evaluate(it, input, evaluatedAt) }
    }

    fun findResult(id: Long): StoredEvaluation? = evaluationRepository.findById(id)

    fun history(facilityId: String, limit: Int = 50): List<StoredEvaluation> =
        evaluationRepository.historyForFacility(facilityId, limit)
}

/**
 * Observer for evaluation outcomes. Implementations must not feed back into the
 * decision; they only react to the already-computed [EvaluationResult].
 */
interface Notifier {
    fun onEvaluated(stored: StoredEvaluation)
}

object LoggingNotifier : Notifier {
    override fun onEvaluated(stored: StoredEvaluation) {
        val r = stored.result
        println(
            "[NOTIFY] facility=${r.facilityId} action=${r.finalAction} " +
                "winner=${r.winningRuleId ?: "-"}#v${r.winningVersion ?: "-"} " +
                "resultId=${stored.id}"
        )
    }
}
