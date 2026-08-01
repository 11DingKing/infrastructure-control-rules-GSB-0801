package com.infra.persistence

import com.infra.domain.Action
import com.infra.domain.Facility
import com.infra.domain.FacilityType
import com.infra.domain.Rule
import com.infra.domain.RuleCondition
import com.infra.domain.RuleLayer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryTest {

    private lateinit var repository: Repository
    private lateinit var dbFile: File

    @BeforeEach
    fun setUp(@TempDir tempDir: File) {
        dbFile = File(tempDir, "test.db")
        DatabaseFactory.init("jdbc:sqlite:${dbFile.absolutePath}")
        repository = Repository()
    }

    @AfterEach
    fun tearDown() {
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    @Test
    fun `facility CRUD operations`() {
        val facility = Facility(
            id = "test-1",
            name = "Test Facility",
            type = FacilityType.TUNNEL,
            regionCode = "440800",
            location = "Test Location"
        )

        repository.createFacility(facility)
        val retrieved = repository.getFacility("test-1")
        assertNotNull(retrieved)
        assertEquals("Test Facility", retrieved.name)
        assertEquals(FacilityType.TUNNEL, retrieved.type)

        val all = repository.listFacilities()
        assertEquals(1, all.size)
    }

    @Test
    fun `rule publish and version conflict`() {
        val rule = Rule(
            id = "test-rule",
            layer = RuleLayer.DEFAULT,
            condition = RuleCondition(minRainfallMm = 50.0),
            action = Action.MONITOR,
            version = 1,
            publishedAt = 0,
            validFrom = 0,
            description = "Test"
        )

        repository.publishRule(rule)
        val retrieved = repository.getRule("test-rule", 1)
        assertNotNull(retrieved)
        assertEquals(1, retrieved.version)

        val duplicate = rule.copy(description = "Duplicate")
        val exception = assertFailsWith<IllegalStateException> {
            repository.publishRule(duplicate)
        }
        assertTrue(exception.message?.contains("already exists") == true)
    }

    @Test
    fun `rule versioning retrieves latest by default`() {
        val v1 = Rule(
            id = "versioned-rule",
            layer = RuleLayer.DEFAULT,
            condition = RuleCondition(),
            action = Action.MONITOR,
            version = 1,
            publishedAt = 100,
            validFrom = 100,
            description = "v1"
        )
        val v2 = v1.copy(version = 2, action = Action.RESTRICT, publishedAt = 200, validFrom = 200, description = "v2")
        val v3 = v2.copy(version = 3, action = Action.CLOSE, publishedAt = 300, validFrom = 300, description = "v3")

        repository.publishRule(v1)
        repository.publishRule(v2)
        repository.publishRule(v3)

        val latest = repository.getRule("versioned-rule")
        assertNotNull(latest)
        assertEquals(3, latest.version)
        assertEquals(Action.CLOSE, latest.action)

        val specific = repository.getRule("versioned-rule", 2)
        assertNotNull(specific)
        assertEquals(2, specific.version)
        assertEquals(Action.RESTRICT, specific.action)
    }

    @Test
    fun `list rules by layer and filters`() {
        repository.publishRule(
            Rule(
                id = "default-1",
                layer = RuleLayer.DEFAULT,
                condition = RuleCondition(),
                action = Action.MONITOR,
                version = 1,
                publishedAt = 0,
                validFrom = 0,
                description = "default"
            )
        )
        repository.publishRule(
            Rule(
                id = "region-1",
                layer = RuleLayer.REGION,
                regionCode = "440800",
                condition = RuleCondition(),
                action = Action.RESTRICT,
                version = 1,
                publishedAt = 0,
                validFrom = 0,
                description = "region"
            )
        )

        val defaults = repository.listRules(layer = RuleLayer.DEFAULT)
        assertEquals(1, defaults.size)
        assertEquals("default-1", defaults[0].id)

        val regions = repository.listRules(regionCode = "440800")
        assertEquals(1, regions.size)
        assertEquals("region-1", regions[0].id)

        val all = repository.listAllRules()
        assertEquals(2, all.size)
    }

    @Test
    fun `evaluation result persistence and retrieval`() {
        val facility = Facility(
            id = "tunnel-test",
            name = "Test",
            type = FacilityType.TUNNEL,
            regionCode = "440800"
        )
        repository.createFacility(facility)

        val rules = listOf(
            Rule(
                id = "r1",
                layer = RuleLayer.DEFAULT,
                facilityType = FacilityType.TUNNEL,
                condition = RuleCondition(minRainfallMm = 50.0),
                action = Action.MONITOR,
                version = 1,
                publishedAt = 0,
                validFrom = 0,
                description = "test"
            )
        )
        rules.forEach { repository.publishRule(it) }

        val input = com.infra.domain.RiskInput(
            facilityId = "tunnel-test",
            hourlyRainfallMm = 72.0,
            windLevel = 7,
            waterDepthCm = 18.0,
            observedAt = 1000,
            requestId = "persist-test-1"
        )

        val result = com.infra.engine.RuleEngine.evaluate(
            com.infra.engine.RuleEngine.EvaluationContext(
                facility = facility,
                rules = repository.listAllRules(),
                input = input,
                evaluationTime = 2000
            )
        )

        repository.saveEvaluationResult(result)

        val retrieved = repository.getEvaluationResult("persist-test-1")
        assertNotNull(retrieved)
        assertEquals(result.resultHash, retrieved.resultHash)
        assertEquals(result.finalAction, retrieved.finalAction)
        assertEquals(result.hitRuleId, retrieved.hitRuleId)
        assertEquals(result.explanationChain.size, retrieved.explanationChain.size)
        assertEquals(result.inputSnapshot.hourlyRainfallMm, retrieved.inputSnapshot.hourlyRainfallMm)
    }

    @Test
    fun `evaluation result preserves input snapshot immutably`() {
        val input = com.infra.domain.RiskInput(
            facilityId = "tunnel-test",
            hourlyRainfallMm = 72.0,
            windLevel = 7,
            waterDepthCm = 18.0,
            observedAt = 1000,
            requestId = "snapshot-test"
        )

        val facility = Facility("tunnel-test", "Test", FacilityType.TUNNEL, "440800")
        repository.createFacility(facility)
        repository.publishRule(
            Rule(
                id = "r1",
                layer = RuleLayer.DEFAULT,
                facilityType = FacilityType.TUNNEL,
                condition = RuleCondition(),
                action = Action.MONITOR,
                version = 1,
                publishedAt = 0,
                validFrom = 0,
                description = "test"
            )
        )

        val result = com.infra.engine.RuleEngine.evaluate(
            com.infra.engine.RuleEngine.EvaluationContext(
                facility = facility,
                rules = repository.listAllRules(),
                input = input,
                evaluationTime = 2000
            )
        )
        repository.saveEvaluationResult(result)

        val retrieved = repository.getEvaluationResult("snapshot-test")
        assertNotNull(retrieved)
        assertEquals(72.0, retrieved.inputSnapshot.hourlyRainfallMm)
        assertEquals(7, retrieved.inputSnapshot.windLevel)
        assertEquals(18.0, retrieved.inputSnapshot.waterDepthCm)
    }
}
