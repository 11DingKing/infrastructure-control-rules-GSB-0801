package com.gsb.control.app

import com.gsb.control.domain.EvaluationResult

/**
 * A pure *consumer* of evaluation output. Notifiers observe decisions to raise
 * alerts, publish to message buses, etc. They receive the finished
 * [EvaluationResult] and must never influence judgement — the domain has
 * already decided by the time this is called.
 */
fun interface EvaluationNotifier {
    fun onEvaluated(stored: StoredNotification)
}

/** Minimal view handed to notifiers: the decision plus provenance. */
data class StoredNotification(
    val storedId: Int,
    val result: EvaluationResult,
)

/** Default notifier: records to the application log. Side effects only. */
class LoggingNotifier : EvaluationNotifier {
    override fun onEvaluated(stored: StoredNotification) {
        val r = stored.result
        val decision = r.decision?.name ?: "NO_ACTION"
        println(
            "[notify] facility=${r.facilityId} decision=$decision " +
                "layer=${r.decidingLayer?.name ?: "-"} rule=${r.decidingVersionRef ?: "-"} storedId=${stored.storedId}",
        )
    }
}
