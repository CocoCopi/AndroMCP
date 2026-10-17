package com.cococopi.andromcp.agent.llm

import com.cococopi.andromcp.agent.LlmException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON helpers shared by the provider clients.
 *
 * Two failure modes are treated differently on purpose. A response that is not JSON, or that is a
 * JSON error object, means the request failed — that throws [LlmException] with the provider's own
 * message and the parse cause attached. Tool *arguments* are different: they arrive as a JSON string
 * that a model may render badly, and the useful behaviour is an empty argument object, because the
 * tool's own validation then fails the call with a message the model can act on instead of aborting
 * the whole turn.
 */
internal object LlmJson {
    private const val ERROR_EXCERPT_CHARS = 200

    val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses [raw] into an object, or throws [LlmException] describing the [provider]'s payload.
     *
     * @throws LlmException when the body is not JSON, or is JSON that is not an object.
     */
    fun requireObject(
        raw: String,
        provider: String,
    ): JsonObject {
        val element =
            try {
                json.parseToJsonElement(raw)
            } catch (e: SerializationException) {
                throw LlmException(
                    "$provider returned a non-JSON response: ${e.message?.take(ERROR_EXCERPT_CHARS).orEmpty()}",
                    e,
                )
            }
        return element as? JsonObject
            ?: throw LlmException("$provider returned a JSON value that is not an object")
    }

    /** Throws when [root] is the provider's error envelope rather than a completion. */
    fun requireNoError(
        root: JsonObject,
        provider: String,
    ) {
        root.objectOrNull("error")?.let { error ->
            throw LlmException(error.stringOrNull("message") ?: "$provider returned an error")
        }
    }

    /** Parses a tool-argument string, tolerating malformed output as "no arguments". */
    fun parseArguments(raw: String?): JsonObject {
        if (raw == null) return JsonObject(emptyMap())
        val parsed = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        return parsed ?: JsonObject(emptyMap())
    }
}

/** String field, or null when absent, null or not a primitive. */
internal fun JsonObject.stringOrNull(key: String): String? {
    val value = this[key] as? JsonPrimitive ?: return null
    return if (value is JsonNull) null else value.content
}

/** Int field, or null when absent or not numeric. */
internal fun JsonObject.intOrNull(key: String): Int? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.content.toIntOrNull()
}

/** Boolean field, or null when absent or not a boolean. */
internal fun JsonObject.boolOrNull(key: String): Boolean? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.content.toBooleanStrictOrNull()
}

/** Nested object field, or null. */
internal fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

/** Array field, or null. */
internal fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

/** Object elements of an array field, or an empty list when absent or not an array. */
internal fun JsonObject.objectsOrEmpty(key: String): List<JsonObject> {
    val array = arrayOrNull(key) ?: return emptyList()
    return array.mapNotNull { it as? JsonObject }
}

/** Trims an optional trailing slash so endpoints concatenate predictably. */
internal fun String.trimTrailingSlash(): String = trimEnd('/')
