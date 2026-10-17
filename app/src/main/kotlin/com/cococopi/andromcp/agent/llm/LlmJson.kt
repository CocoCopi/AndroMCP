package com.cococopi.andromcp.agent.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Defensive JSON helpers shared by the provider clients.
 *
 * Provider payloads vary: fields are null, numbers arrive as strings, and tool arguments are a
 * JSON *string* that may itself be malformed. These helpers return null (or an empty object)
 * instead of throwing, so a quirky response degrades to "the model said nothing usable" rather than
 * crashing the turn.
 */
internal object LlmJson {
    val json = Json { ignoreUnknownKeys = true }

    /** Parses [text] as an object, or returns null when it is not one. */
    fun parseObject(text: String): JsonObject? =
        try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            null
        }

    /** Parses a tool-argument string, defaulting to an empty object. */
    fun parseArguments(raw: String?): JsonObject = raw?.let { parseObject(it) } ?: JsonObject(emptyMap())
}

/** String field, or null when absent, null or not a primitive. */
internal fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** Int field, or null when absent or not numeric. */
internal fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

/** Boolean field, or null when absent or not a boolean. */
internal fun JsonObject.boolOrNull(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

/** Nested object field, or null. */
internal fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

/** Array field, or null. */
internal fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

/** Object elements of an array field, or an empty list when absent or not an array. */
internal fun JsonObject.objectsOrEmpty(key: String): List<JsonObject> = arrayOrNull(key)?.mapNotNull { it as? JsonObject }.orEmpty()

/** Trims an optional trailing slash so endpoints concatenate predictably. */
internal fun String.trimTrailingSlash(): String = trimEnd('/')
