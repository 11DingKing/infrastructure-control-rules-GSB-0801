package com.gsb.infra.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.security.MessageDigest

/**
 * Produces a canonical, order-independent SHA-256 content hash for an
 * [EvaluationResult].
 *
 * The hash is computed over a canonical JSON form in which every JSON object's
 * keys are sorted lexicographically. Arrays retain their (already deterministic)
 * order. This guarantees that a shuffled input rule list — which is sorted by the
 * evaluator before producing traces — yields identical canonical JSON and an
 * identical [EvaluationResult.contentHash].
 *
 * The [EvaluationResult.contentHash] field itself is excluded from the hashed
 * payload to avoid a self-referential dependency.
 */
object CanonicalHasher {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun hash(result: EvaluationResult): String {
        val element: JsonElement = json.encodeToJsonElement(EvaluationResult.serializer(), result)
        val canonical = canonicalize(element)
        val bytes = canonical.toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> {
            val sorted = element.entries
                .filter { it.key != "contentHash" }
                .sortedBy { it.key }
                .associate { it.key to canonicalize(it.value) }
            JsonObject(sorted)
        }
        is JsonArray -> JsonArray(element.map { canonicalize(it) })
        is JsonPrimitive -> element
    }
}
