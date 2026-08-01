package app.control.services

import app.control.db.Rules
import app.control.domain.Action
import app.control.domain.FacilityType
import app.control.domain.PublishCode
import app.control.domain.Rule
import app.control.domain.RuleCondition
import app.control.domain.RuleTier
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

data class PublishOutcome(
    val code: PublishCode,
    val rule: Rule? = null,
)

/**
 * 规则发布服务：只做校验与持久化，不做任何求值。
 * 发布失败一律返回可枚举的 [PublishCode]，不抛未分类异常。
 */
class RuleService(
    private val facilityService: FacilityService,
    private val clock: () -> Long,
) {

    fun publish(
        ruleId: String,
        version: Int,
        tier: RuleTier,
        scopeKey: String,
        facilityType: FacilityType?,
        condition: RuleCondition,
        action: Action,
        effectiveFrom: Long,
        effectiveTo: Long?,
        publishedAt: Long? = null,
    ): PublishOutcome {
        if (ruleId.isBlank()) return PublishOutcome(PublishCode.MISSING_SCOPE_KEY)
        if (condition.isEmpty()) return PublishOutcome(PublishCode.EMPTY_CONDITION)
        if (effectiveTo != null && effectiveTo <= effectiveFrom) {
            return PublishOutcome(PublishCode.INVALID_WINDOW)
        }
        if (tier != RuleTier.DEFAULT && scopeKey.isBlank()) {
            return PublishOutcome(PublishCode.MISSING_SCOPE_KEY)
        }
        if ((tier == RuleTier.FACILITY || tier == RuleTier.MANUAL) && !facilityService.exists(scopeKey)) {
            return PublishOutcome(PublishCode.UNKNOWN_SCOPE_FACILITY)
        }

        val published = publishedAt ?: clock()
        return transaction {
            val maxVersion = Rules.selectAll()
                .where { Rules.ruleId eq ruleId }
                .maxOfOrNull { row -> row[Rules.version] }
            if (maxVersion != null) {
                if (version < maxVersion) return@transaction PublishOutcome(PublishCode.VERSION_ROLLBACK)
                if (version == maxVersion) return@transaction PublishOutcome(PublishCode.DUPLICATE_VERSION)
            }
            val rule = Rule(
                ruleId = ruleId,
                version = version,
                tier = tier,
                scopeKey = if (tier == RuleTier.DEFAULT) "default" else scopeKey,
                facilityType = facilityType,
                condition = condition,
                action = action,
                effectiveFrom = effectiveFrom,
                effectiveTo = effectiveTo,
                publishedAt = published,
            )
            try {
                Rules.insert {
                    it[Rules.ruleId] = rule.ruleId
                    it[Rules.version] = rule.version
                    it[Rules.tier] = rule.tier.name
                    it[Rules.scopeKey] = rule.scopeKey
                    it[Rules.facilityType] = rule.facilityType?.name
                    it[Rules.precipitationMmAtLeast] = rule.condition.precipitationMmAtLeast
                    it[Rules.windLevelAtLeast] = rule.condition.windLevelAtLeast
                    it[Rules.waterDepthCmAtLeast] = rule.condition.waterDepthCmAtLeast
                    it[Rules.action] = rule.action.name
                    it[Rules.effectiveFrom] = rule.effectiveFrom
                    it[Rules.effectiveTo] = rule.effectiveTo
                    it[Rules.publishedAt] = rule.publishedAt
                }
                PublishOutcome(PublishCode.PUBLISHED, rule)
            } catch (e: ExposedSQLException) {
                // 并发发布同一版本：唯一约束是最终裁决者，只有一个赢家。
                if (isConstraintViolation(e)) {
                    PublishOutcome(PublishCode.DUPLICATE_VERSION)
                } else {
                    throw e
                }
            }
        }
    }

    /** 供求值读取：返回全部已发布规则，可见性过滤由纯引擎按 asOf 完成。 */
    fun loadAll(): List<Rule> = transaction {
        Rules.selectAll()
            .orderBy(Rules.ruleId to SortOrder.ASC, Rules.version to SortOrder.ASC)
            .map { it.toDomain() }
    }

    fun versionsOf(ruleId: String): List<Rule> = transaction {
        Rules.selectAll()
            .where { Rules.ruleId eq ruleId }
            .orderBy(Rules.version to SortOrder.ASC)
            .map { it.toDomain() }
    }

    private fun isConstraintViolation(e: ExposedSQLException): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            val message = cause.message ?: ""
            if (message.contains("CONSTRAINT") || message.contains("constraint failed")) return true
            cause = cause.cause
        }
        return false
    }

    private fun ResultRow.toDomain(): Rule = Rule(
        ruleId = this[Rules.ruleId],
        version = this[Rules.version],
        tier = RuleTier.valueOf(this[Rules.tier]),
        scopeKey = this[Rules.scopeKey],
        facilityType = this[Rules.facilityType]?.let { name -> FacilityType.valueOf(name) },
        condition = RuleCondition(
            precipitationMmAtLeast = this[Rules.precipitationMmAtLeast],
            windLevelAtLeast = this[Rules.windLevelAtLeast],
            waterDepthCmAtLeast = this[Rules.waterDepthCmAtLeast],
        ),
        action = Action.valueOf(this[Rules.action]),
        effectiveFrom = this[Rules.effectiveFrom],
        effectiveTo = this[Rules.effectiveTo],
        publishedAt = this[Rules.publishedAt],
    )
}
