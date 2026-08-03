package com.infra.notification

import com.infra.domain.EvaluationResult
import org.slf4j.LoggerFactory

interface NotificationService {
    fun notify(result: EvaluationResult)
}

class LoggingNotificationService : NotificationService {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun notify(result: EvaluationResult) {
        logger.info(
            "Evaluation notification: requestId={}, facility={}, action={}, reason={}, hitRule={}:{}, layer={}, hash={}",
            result.requestId,
            result.facilityId,
            result.finalAction ?: "NONE",
            result.reasonCode,
            result.hitRuleId ?: "-",
            result.hitRuleVersion ?: "-",
            result.hitRuleLayer ?: "-",
            result.resultHash
        )
    }
}
