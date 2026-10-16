@file:Suppress("MatchingDeclarationName")

package com.cococopi.andromcp.mcp.tools

import android.content.Context
import android.util.Log
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.mcp.McpToolException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Data, encoding and text-processing tool bundle.
//
// The deterministic half of an agent's toolbox: encode and decode base64, compute digests and
// HMAC signatures, generate identifiers and random values, query JSON, work with regular
// expressions and URLs, and parse or diff text and CSV. These tools touch no device state, hold no
// permission and are safe to call in any order, which makes them the cheapest way for an agent to
// prepare or verify a payload before it hands that payload to a device-facing tool.

// ─────────────────────────────────────────────────────────────────────────────
// base64_encode
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `base64_encode`. Input: `{ "content": <string>, "url_safe": <bool>? }`. */
class Base64EncodeHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val content = McpToolUtils.requireString(arguments, "content")
        val urlSafe = McpToolUtils.optionalBoolean(arguments, "url_safe", false)
        Log.d(TAG, "Executing base64_encode")

        val bytes = content.toByteArray()
        val encoder = if (urlSafe) Base64.getUrlEncoder() else Base64.getEncoder()
        val encoded = encoder.encodeToString(bytes)

        val json =
            buildJsonObject {
                put("url_safe", urlSafe)
                put("input_bytes", bytes.size)
                put("output_chars", encoded.length)
                put("encoded", encoded)
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Encodes text as base64, using the URL-safe alphabet when 'url_safe' is true. Use " +
                    "it to embed text in a query parameter or a JSON string field, to prepare a " +
                    "body for a tool that takes base64, or to make binary-ish content safe to " +
                    "print. Returns the encoded value with the input byte count and the output " +
                    "character count. Purely computational: no device access, no permission, no " +
                    "side effects.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "Text to encode")
                            }
                            putJsonObject("url_safe") {
                                put("type", "boolean")
                                put("description", "Use the URL-safe alphabet (+/ become -_) (default false)")
                            }
                        },
                    required = listOf("content"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "base64_encode"
        private const val TAG = "MCP:Base64EncodeHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// base64_decode
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `base64_decode`. Input: `{ "content": <string>, "url_safe": <bool>?, "as_hex": <bool>? }`. */
class Base64DecodeHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val content = McpToolUtils.requireString(arguments, "content").trim()
        val urlSafe = McpToolUtils.optionalBoolean(arguments, "url_safe", false)
        val asHex = McpToolUtils.optionalBoolean(arguments, "as_hex", false)
        Log.d(TAG, "Executing base64_decode")

        val decoder = if (urlSafe) Base64.getUrlDecoder() else Base64.getDecoder()
        val bytes =
            try {
                decoder.decode(content)
            } catch (e: IllegalArgumentException) {
                throw McpToolException.InvalidParams("Parameter 'content' is not valid base64: ${e.message}")
            }

        val json =
            buildJsonObject {
                put("url_safe", urlSafe)
                put("decoded_bytes", bytes.size)
                put("hex", hexOf(bytes))
                if (asHex) {
                    put("content", hexOf(bytes))
                } else {
                    put("content", String(bytes, Charsets.UTF_8))
                }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Decodes base64 back into text, or into hexadecimal when 'as_hex' is true. The " +
                    "response always includes the decoded byte count, the hexadecimal form and the " +
                    "UTF-8 interpretation, so binary payloads can be inspected even when they are " +
                    "not printable. Use it to read a token, a header or a body that another tool " +
                    "handed back base64-encoded. Invalid input is rejected with a clear error rather " +
                    "than returning garbage. Purely computational: no device access, no permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "Base64 text to decode")
                            }
                            putJsonObject("url_safe") {
                                put("type", "boolean")
                                put("description", "Input uses the URL-safe alphabet (default false)")
                            }
                            putJsonObject("as_hex") {
                                put("type", "boolean")
                                put("description", "Report hexadecimal as the content instead of UTF-8 (default false)")
                            }
                        },
                    required = listOf("content"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "base64_decode"
        private const val TAG = "MCP:Base64DecodeHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// hash_string
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `hash_string`. Input: `{ "content": <string>, "algorithm": <string>? }`. */
class HashStringHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val content = McpToolUtils.requireString(arguments, "content")
        val algorithm = McpToolUtils.optionalString(arguments, "algorithm", DEFAULT_HASH_ALGORITHM).uppercase()
        val javaAlgorithm =
            HASH_ALGORITHMS[algorithm]
                ?: throw McpToolException.InvalidParams(
                    "Unknown algorithm '$algorithm'. Valid values: ${HASH_ALGORITHMS.keys.joinToString(", ")}",
                )
        Log.d(TAG, "Executing hash_string")

        val digest =
            try {
                MessageDigest.getInstance(javaAlgorithm)
            } catch (e: NoSuchAlgorithmException) {
                throw McpToolException.InternalError("Algorithm '$algorithm' is unavailable: ${e.message}")
            }
        val bytes = digest.digest(content.toByteArray())

        val json =
            buildJsonObject {
                put("algorithm", algorithm)
                put("input_bytes", content.toByteArray().size)
                put("digest", hexOf(bytes))
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Computes a hexadecimal digest of a string. 'algorithm' defaults to SHA-256 and " +
                    "also accepts MD5, SHA-1 and SHA-512. Use it to fingerprint a value, to compare " +
                    "two pieces of text without printing them, or to produce the digest form a " +
                    "service expects. For hashing a file, use compute_file_hash, which streams the " +
                    "bytes instead of holding them in memory. Purely computational: no device " +
                    "access, no permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "Text to hash, encoded as UTF-8")
                            }
                            putJsonObject("algorithm") {
                                put("type", "string")
                                put("description", "MD5, SHA-1, SHA-256 (default) or SHA-512")
                            }
                        },
                    required = listOf("content"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "hash_string"
        private const val TAG = "MCP:HashStringHandler"
        private const val DEFAULT_HASH_ALGORITHM = "SHA-256"
        private val HASH_ALGORITHMS =
            mapOf("MD5" to "MD5", "SHA-1" to "SHA-1", "SHA-256" to "SHA-256", "SHA-512" to "SHA-512")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// hmac_sign
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `hmac_sign`.
 *
 * Input: `{ "content": <string>, "key": <string>, "algorithm": <string>?, "base64": <bool>? }`.
 */
class HmacSignHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val content = McpToolUtils.requireString(arguments, "content")
        val key = McpToolUtils.requireString(arguments, "key")
        if (key.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'key' must not be empty")
        }
        val algorithm = McpToolUtils.optionalString(arguments, "algorithm", DEFAULT_HMAC_ALGORITHM).uppercase()
        val javaAlgorithm =
            HMAC_ALGORITHMS[algorithm]
                ?: throw McpToolException.InvalidParams(
                    "Unknown algorithm '$algorithm'. Valid values: ${HMAC_ALGORITHMS.keys.joinToString(", ")}",
                )
        val asBase64 = McpToolUtils.optionalBoolean(arguments, "base64", false)

        Log.d(TAG, "Executing hmac_sign")

        val signature =
            try {
                val mac = Mac.getInstance(javaAlgorithm)
                mac.init(SecretKeySpec(key.toByteArray(), javaAlgorithm))
                mac.doFinal(content.toByteArray())
            } catch (e: NoSuchAlgorithmException) {
                throw McpToolException.InternalError("Algorithm '$algorithm' is unavailable: ${e.message}")
            } catch (e: java.security.InvalidKeyException) {
                throw McpToolException.InvalidParams("The supplied key is not usable for '$algorithm': ${e.message}")
            }

        val json =
            buildJsonObject {
                put("algorithm", algorithm)
                put("encoding", if (asBase64) "base64" else "hex")
                put(
                    "signature",
                    if (asBase64) Base64.getEncoder().encodeToString(signature) else hexOf(signature),
                )
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Computes an HMAC signature of a message with a shared secret, which is how most " +
                    "webhooks and signed APIs prove authenticity. 'content' and 'key' are required; " +
                    "'algorithm' defaults to HMACSHA256 and also accepts HMACSHA1 and HMACSHA512, " +
                    "and 'base64' switches the output from hexadecimal to base64. The secret is " +
                    "never logged and never echoed back. Use it to sign a webhook body before " +
                    "posting it with http_request. Purely computational: no device access, no " +
                    "permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "Message to sign, encoded as UTF-8")
                            }
                            putJsonObject("key") {
                                put("type", "string")
                                put("description", "Shared secret, encoded as UTF-8")
                            }
                            putJsonObject("algorithm") {
                                put("type", "string")
                                put("description", "HMACSHA1, HMACSHA256 (default) or HMACSHA512")
                            }
                            putJsonObject("base64") {
                                put("type", "boolean")
                                put("description", "Return the signature base64-encoded instead of hexadecimal")
                            }
                        },
                    required = listOf("content", "key"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "hmac_sign"
        private const val TAG = "MCP:HmacSignHandler"
        private const val DEFAULT_HMAC_ALGORITHM = "HMACSHA256"
        private val HMAC_ALGORITHMS =
            mapOf("HMACSHA1" to "HmacSHA1", "HMACSHA256" to "HmacSHA256", "HMACSHA512" to "HmacSHA512")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// generate_uuid
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `generate_uuid`. Input: `{ "count": <int>? }`. */
class GenerateUuidHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val count = McpToolUtils.optionalInt(arguments, "count", DEFAULT_UUID_COUNT)
        McpToolUtils.validatePositiveRange(count.toLong(), "count", MAX_UUID_COUNT.toLong())
        Log.d(TAG, "Executing generate_uuid")

        val identifiers = List(count) { UUID.randomUUID().toString() }
        val json =
            buildJsonObject {
                put("count", identifiers.size)
                putJsonArray("uuids") { identifiers.forEach { add(it) } }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Generates one or more random version-4 UUIDs from the platform's secure random " +
                    "source. 'count' defaults to 1 and caps at $MAX_UUID_COUNT. Use it to name files, " +
                    "build idempotency keys for retried requests, or create unique identifiers for " +
                    "rows the agent is about to write. Purely computational: no device access, no " +
                    "permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("count") {
                                put("type", "integer")
                                put("description", "How many UUIDs to generate (default 1, max $MAX_UUID_COUNT)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "generate_uuid"
        private const val TAG = "MCP:GenerateUuidHandler"
        private const val DEFAULT_UUID_COUNT = 1
        private const val MAX_UUID_COUNT = 100
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// random_number
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `random_number`.
 *
 * Input: `{ "minimum": <number>?, "maximum": <number>?, "count": <int>?, "integer": <bool>? }`.
 */
class RandomNumberHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val minimum = McpToolUtils.optionalInt(arguments, "minimum", DEFAULT_MINIMUM)
        val maximum = McpToolUtils.optionalInt(arguments, "maximum", DEFAULT_MAXIMUM)
        if (maximum < minimum) {
            throw McpToolException.InvalidParams("'maximum' must be greater than or equal to 'minimum'")
        }
        val count = McpToolUtils.optionalInt(arguments, "count", DEFAULT_RANDOM_COUNT)
        McpToolUtils.validatePositiveRange(count.toLong(), "count", MAX_RANDOM_COUNT.toLong())
        val integerOnly = McpToolUtils.optionalBoolean(arguments, "integer", true)
        if (integerOnly && maximum.toLong() - minimum.toLong() + 1 > Int.MAX_VALUE.toLong()) {
            throw McpToolException.InvalidParams("The requested integer range is too large")
        }

        Log.d(TAG, "Executing random_number")

        val random = SecureRandom()
        val span = maximum.toLong() - minimum.toLong()
        val values: List<Double> =
            List(count) {
                if (integerOnly) {
                    (minimum + random.nextInt(span.toInt() + 1)).toDouble()
                } else {
                    minimum + random.nextDouble() * span
                }
            }

        val json =
            buildJsonObject {
                put("minimum", minimum)
                put("maximum", maximum)
                put("integer", integerOnly)
                put("count", values.size)
                putJsonArray("values") { values.forEach { add(it) } }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Produces random numbers from the platform's secure random source, either whole " +
                    "numbers or decimals. 'minimum' defaults to $DEFAULT_MINIMUM and 'maximum' to " +
                    "$DEFAULT_MAXIMUM; 'count' (default 1, max $MAX_RANDOM_COUNT) is how many values " +
                    "to return, and 'integer': false yields decimals in the same range. Use it to " +
                    "pick a random item, generate a test value, or add jitter to a delay. Purely " +
                    "computational: no device access, no permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("minimum") {
                                put("type", "integer")
                                put("description", "Inclusive lower bound (default $DEFAULT_MINIMUM)")
                            }
                            putJsonObject("maximum") {
                                put("type", "integer")
                                put("description", "Inclusive upper bound (default $DEFAULT_MAXIMUM)")
                            }
                            putJsonObject("count") {
                                put("type", "integer")
                                put("description", "How many values to return (default 1, max $MAX_RANDOM_COUNT)")
                            }
                            putJsonObject("integer") {
                                put("type", "boolean")
                                put("description", "Return whole numbers rather than decimals (default true)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "random_number"
        private const val TAG = "MCP:RandomNumberHandler"
        private const val DEFAULT_MINIMUM = 0
        private const val DEFAULT_MAXIMUM = 100
        private const val DEFAULT_RANDOM_COUNT = 1
        private const val MAX_RANDOM_COUNT = 1_000
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// random_string
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `random_string`.
 *
 * Input: `{ "length": <int>?, "count": <int>?, "alphabet": <string>? }`.
 */
class RandomStringHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val length = McpToolUtils.optionalInt(arguments, "length", DEFAULT_STRING_LENGTH)
        McpToolUtils.validatePositiveRange(length.toLong(), "length", MAX_STRING_LENGTH.toLong())
        val count = McpToolUtils.optionalInt(arguments, "count", DEFAULT_RANDOM_COUNT)
        McpToolUtils.validatePositiveRange(count.toLong(), "count", MAX_RANDOM_COUNT.toLong())
        val alphabetName = McpToolUtils.optionalString(arguments, "alphabet", DEFAULT_ALPHABET)
        val characters = charactersFor(alphabetName)

        Log.d(TAG, "Executing random_string")

        val random = SecureRandom()
        val values = List(count) { randomString(random, characters, length) }
        val json =
            buildJsonObject {
                put("length", length)
                put("count", values.size)
                put("alphabet", alphabetName)
                put("alphabet_size", characters.length)
                putJsonArray("values") { values.forEach { add(it) } }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Generates random strings from the platform's secure random source. 'length' " +
                    "defaults to $DEFAULT_STRING_LENGTH (max $MAX_STRING_LENGTH) and 'count' to 1 " +
                    "(max $MAX_RANDOM_COUNT). 'alphabet' selects the character set by name — " +
                    "alphanumeric, alpha, numeric, hex or base64 — or passes a literal set of " +
                    "characters to draw from, so a passphrase style alphabet is possible. Use it to " +
                    "mint a password, a device name or a random filename. Purely computational: no " +
                    "device access, no permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("length") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Characters per string (default $DEFAULT_STRING_LENGTH, " +
                                        "max $MAX_STRING_LENGTH)",
                                )
                            }
                            putJsonObject("count") {
                                put("type", "integer")
                                put("description", "How many strings to return (default 1, max $MAX_RANDOM_COUNT)")
                            }
                            putJsonObject("alphabet") {
                                put("type", "string")
                                put(
                                    "description",
                                    "alphanumeric (default), alpha, numeric, hex, base64, or a literal character set",
                                )
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "random_string"
        private const val TAG = "MCP:RandomStringHandler"
        private const val DEFAULT_STRING_LENGTH = 32
        private const val MAX_STRING_LENGTH = 4_096
        private const val DEFAULT_RANDOM_COUNT = 1
        private const val MAX_RANDOM_COUNT = 100
        private const val DEFAULT_ALPHABET = "alphanumeric"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// json_query
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `json_query`. Input: `{ "json": <string>, "path": <string> }`. */
class JsonQueryHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val document = McpToolUtils.requireString(arguments, "json")
        val path = McpToolUtils.requireString(arguments, "path")
        Log.d(TAG, "Executing json_query")

        val root =
            try {
                Json.parseToJsonElement(document)
            } catch (e: kotlinx.serialization.SerializationException) {
                throw McpToolException.InvalidParams("Parameter 'json' is not valid JSON: ${e.message}")
            } catch (e: IllegalArgumentException) {
                throw McpToolException.InvalidParams("Parameter 'json' is not valid JSON: ${e.message}")
            }

        val found = queryJsonPath(root, path)
        val json =
            buildJsonObject {
                put("path", path)
                put("found", found != null)
                put("value_type", found?.let { jsonValueType(it) })
                put("value", found?.toString())
            }
        return McpToolUtils.textResult(json.toString())
    }

    /** Names the JSON type of [element] for reporting. */
    private fun jsonValueType(element: JsonElement): String =
        when (element) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonPrimitive -> if (element.isString) "string" else "primitive"
        }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Extracts one value out of a JSON document using a simple path. 'path' walks " +
                    "objects with dots and arrays with numeric segments or brackets, so " +
                    "'user.name', 'items.0.id' and 'items[0].id' all select the same thing; an " +
                    "empty path returns the whole document. The response reports whether the path " +
                    "was found, the value's JSON type and the value itself. Use it to pull a token " +
                    "or an id out of an API response returned by http_request without having to " +
                    "re-read the whole body. Invalid JSON is rejected with a clear error. Purely " +
                    "computational: no device access, no permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("json") {
                                put("type", "string")
                                put("description", "JSON document to query")
                            }
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Dot or bracket path such as user.name, items.0.id or items[0].id")
                            }
                        },
                    required = listOf("json", "path"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "json_query"
        private const val TAG = "MCP:JsonQueryHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// regex_find
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `regex_find`.
 *
 * Input: `{ "pattern": <string>, "input": <string>, "group": <int>?, "limit": <int>?,
 * "ignore_case": <bool>?, "multiline": <bool>? }`.
 */
class RegexFindHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val pattern = McpToolUtils.requireString(arguments, "pattern")
        val input = McpToolUtils.requireString(arguments, "input")
        ensureInputWithinLimit(input)
        val group = McpToolUtils.optionalInt(arguments, "group", DEFAULT_REGEX_GROUP)
        McpToolUtils.validateNonNegative(group.toFloat(), "group")
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_REGEX_LIMIT)
        McpToolUtils.validatePositiveRange(limit.toLong(), "limit", MAX_REGEX_LIMIT.toLong())

        Log.d(TAG, "Executing regex_find")

        val regex = buildRegex(arguments, pattern)
        val matches =
            regex
                .findAll(input)
                .take(limit)
                .map { match ->
                    buildJsonObject {
                        put("match", match.value)
                        put("group", match.groups[group.coerceAtMost(match.groups.size - 1)]?.value)
                        put("start", match.range.first)
                        put("end", match.range.last + RANGE_END_OFFSET)
                    }
                }.toList()

        val json =
            buildJsonObject {
                put("pattern", pattern)
                put("group", group)
                put("match_count", matches.size)
                putJsonArray("matches") { matches.forEach { add(it) } }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Finds every regular-expression match in a string and returns each match with its " +
                    "captured group, its start offset and its end offset. 'group' picks which " +
                    "capture group to report (0 is the whole match, the default), 'limit' (default " +
                    "$DEFAULT_REGEX_LIMIT, max $MAX_REGEX_LIMIT) caps the matches, and 'ignore_case' " +
                    "and 'multiline' switch on the usual matching flags. Use it to pull structured " +
                    "fields out of messy text: phone numbers from a page, ids from a log, values " +
                    "from a config file. Inputs longer than $MAX_REGEX_INPUT_CHARS characters are " +
                    "rejected so a pathological pattern cannot hang the server. Purely " +
                    "computational: no device access, no permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("pattern") {
                                put("type", "string")
                                put("description", "Regular expression to search with")
                            }
                            putJsonObject("input") {
                                put("type", "string")
                                put("description", "Text to search")
                            }
                            putJsonObject("group") {
                                put("type", "integer")
                                put("description", "Capture group to report, 0 for the whole match (default 0)")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Maximum number of matches (default $DEFAULT_REGEX_LIMIT, " +
                                        "max $MAX_REGEX_LIMIT)",
                                )
                            }
                            putJsonObject("ignore_case") {
                                put("type", "boolean")
                                put("description", "Match case-insensitively (default false)")
                            }
                            putJsonObject("multiline") {
                                put("type", "boolean")
                                put("description", "Let ^ and $ match at line boundaries (default false)")
                            }
                        },
                    required = listOf("pattern", "input"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "regex_find"
        private const val TAG = "MCP:RegexFindHandler"
        private const val DEFAULT_REGEX_GROUP = 0
        private const val DEFAULT_REGEX_LIMIT = 20
        private const val MAX_REGEX_LIMIT = 500
        private const val RANGE_END_OFFSET = 1
        private const val MAX_REGEX_INPUT_CHARS = 200_000
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// regex_replace
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `regex_replace`.
 *
 * Input: `{ "pattern": <string>, "input": <string>, "replacement": <string>,
 * "replace_all": <bool>?, "ignore_case": <bool>?, "multiline": <bool>? }`.
 */
class RegexReplaceHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val pattern = McpToolUtils.requireString(arguments, "pattern")
        val input = McpToolUtils.requireString(arguments, "input")
        ensureInputWithinLimit(input)
        val replacement = McpToolUtils.requireString(arguments, "replacement")
        val replaceAll = McpToolUtils.optionalBoolean(arguments, "replace_all", true)

        Log.d(TAG, "Executing regex_replace")

        val regex = buildRegex(arguments, pattern)
        val result = if (replaceAll) regex.replace(input, replacement) else regex.replaceFirst(input, replacement)
        val json =
            buildJsonObject {
                put("pattern", pattern)
                put("replace_all", replaceAll)
                put("changed", result != input)
                put("result_length", result.length)
                put("result", result)
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Rewrites text with a regular expression, replacing either every occurrence " +
                    "('replace_all': true, the default) or only the first. Back-references such as " +
                    "$1 in 'replacement' work as usual, and 'ignore_case' and 'multiline' switch on " +
                    "the matching flags. The response reports whether anything actually changed " +
                    "alongside the new text, which makes a no-op replacement visible instead of " +
                    "silent. Use it to normalise messy input before storing or sending it. Inputs " +
                    "longer than $MAX_REGEX_INPUT_CHARS characters are rejected. Purely " +
                    "computational: no device access, no permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("pattern") {
                                put("type", "string")
                                put("description", "Regular expression to match")
                            }
                            putJsonObject("input") {
                                put("type", "string")
                                put("description", "Text to rewrite")
                            }
                            putJsonObject("replacement") {
                                put("type", "string")
                                put("description", "Replacement text, where $1 and friends refer to capture groups")
                            }
                            putJsonObject("replace_all") {
                                put("type", "boolean")
                                put("description", "Replace every occurrence rather than the first (default true)")
                            }
                            putJsonObject("ignore_case") {
                                put("type", "boolean")
                                put("description", "Match case-insensitively (default false)")
                            }
                            putJsonObject("multiline") {
                                put("type", "boolean")
                                put("description", "Let ^ and $ match at line boundaries (default false)")
                            }
                        },
                    required = listOf("pattern", "input", "replacement"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "regex_replace"
        private const val TAG = "MCP:RegexReplaceHandler"
        private const val MAX_REGEX_INPUT_CHARS = 200_000
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// url_encode
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `url_encode`. Input: `{ "content": <string>, "space_as_plus": <bool>? }`. */
class UrlEncodeHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val content = McpToolUtils.requireString(arguments, "content")
        val spaceAsPlus = McpToolUtils.optionalBoolean(arguments, "space_as_plus", false)
        Log.d(TAG, "Executing url_encode")

        val encoded =
            try {
                URLEncoder.encode(content, Charsets.UTF_8.name())
            } catch (_: java.io.UnsupportedEncodingException) {
                throw McpToolException.InternalError("UTF-8 is unavailable on this device")
            }
        val result = if (spaceAsPlus) encoded else encoded.replace("+", "%20")

        val json =
            buildJsonObject {
                put("space_as_plus", spaceAsPlus)
                put("output_chars", result.length)
                put("encoded", result)
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Percent-encodes text for safe use inside a URL. Spaces become %20 by default, or " +
                    "'+' when 'space_as_plus' is true, which is what form-encoded bodies expect. Use " +
                    "it to build a query string around a value that contains spaces, ampersands or " +
                    "non-ASCII characters before passing the URL to http_request. Purely " +
                    "computational: no device access, no permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "Text to percent-encode")
                            }
                            putJsonObject("space_as_plus") {
                                put("type", "boolean")
                                put("description", "Encode spaces as + instead of %20 (default false)")
                            }
                        },
                    required = listOf("content"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "url_encode"
        private const val TAG = "MCP:UrlEncodeHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// url_decode
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `url_decode`. Input: `{ "content": <string>, "plus_as_space": <bool>? }`. */
class UrlDecodeHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val content = McpToolUtils.requireString(arguments, "content")
        val plusAsSpace = McpToolUtils.optionalBoolean(arguments, "plus_as_space", true)
        Log.d(TAG, "Executing url_decode")

        val prepared = if (plusAsSpace) content else content.replace("+", "%2B")
        val decoded =
            try {
                URLDecoder.decode(prepared, Charsets.UTF_8.name())
            } catch (e: IllegalArgumentException) {
                throw McpToolException.InvalidParams("Parameter 'content' is not valid percent-encoding: ${e.message}")
            } catch (_: java.io.UnsupportedEncodingException) {
                throw McpToolException.InternalError("UTF-8 is unavailable on this device")
            }

        val json =
            buildJsonObject {
                put("plus_as_space", plusAsSpace)
                put("output_chars", decoded.length)
                put("decoded", decoded)
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reverses percent-encoding. A '+' is treated as a space by default, matching how " +
                    "form-encoded data is written; pass 'plus_as_space': false when decoding a " +
                    "path segment where a literal '+' is expected. Use it to read a value out of a " +
                    "URL or a query string that another tool returned. Malformed input is rejected " +
                    "with a clear error. Purely computational: no device access, no permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "Percent-encoded text to decode")
                            }
                            putJsonObject("plus_as_space") {
                                put("type", "boolean")
                                put("description", "Treat + as a space (default true)")
                            }
                        },
                    required = listOf("content"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "url_decode"
        private const val TAG = "MCP:UrlDecodeHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// text_stats
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `text_stats`. Input: `{ "text": <string>, "top_words": <int>? }`. */
class TextStatsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val text = McpToolUtils.requireString(arguments, "text")
        val topWords = McpToolUtils.optionalInt(arguments, "top_words", DEFAULT_TOP_WORDS)
        McpToolUtils.validatePositiveRange(topWords.toLong(), "top_words", MAX_TOP_WORDS.toLong())
        Log.d(TAG, "Executing text_stats")

        val words = WORD_PATTERN.findAll(text).map { it.value.lowercase() }.toList()
        val frequencies =
            words
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(topWords)
        val json =
            buildJsonObject {
                put("characters", text.length)
                put("characters_without_whitespace", text.count { !it.isWhitespace() })
                put("words", words.size)
                put("unique_words", words.distinct().size)
                put("lines", text.lines().size)
                put(
                    "sentences",
                    SENTENCE_PATTERN.findAll(text).count(),
                )
                putJsonArray("top_words") {
                    frequencies.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("word", entry.key)
                                put("count", entry.value)
                            },
                        )
                    }
                }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Measures a piece of text: characters with and without whitespace, word count, " +
                    "unique word count, line count, sentence count, and the most frequent words " +
                    "with their counts ('top_words', default $DEFAULT_TOP_WORDS, max $MAX_TOP_WORDS). " +
                    "Use it to tell whether something will fit a length limit, to summarise a " +
                    "document's shape, or to spot what a text is about before reading it in full. " +
                    "Purely computational: no device access, no permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("text") {
                                put("type", "string")
                                put("description", "Text to measure")
                            }
                            putJsonObject("top_words") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "How many frequent words to return (default $DEFAULT_TOP_WORDS, " +
                                        "max $MAX_TOP_WORDS)",
                                )
                            }
                        },
                    required = listOf("text"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "text_stats"
        private const val TAG = "MCP:TextStatsHandler"
        private const val DEFAULT_TOP_WORDS = 10
        private const val MAX_TOP_WORDS = 100
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// diff_text
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `diff_text`. Input: `{ "left": <string>, "right": <string>, "limit": <int>? }`. */
class DiffTextHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val left = McpToolUtils.requireString(arguments, "left")
        val right = McpToolUtils.requireString(arguments, "right")
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_DIFF_LINES)
        McpToolUtils.validatePositiveRange(limit.toLong(), "limit", MAX_DIFF_LINES.toLong())
        Log.d(TAG, "Executing diff_text")

        val leftLines = left.lines()
        val rightLines = right.lines()
        val leftCounts = leftLines.groupingBy { it }.eachCount()
        val rightCounts = rightLines.groupingBy { it }.eachCount()
        val added = rightLines.filter { rightCounts.getValue(it) > (leftCounts[it] ?: 0) }.distinct()
        val removed = leftLines.filter { leftCounts.getValue(it) > (rightCounts[it] ?: 0) }.distinct()

        val json =
            buildJsonObject {
                put("identical", left == right)
                put("left_lines", leftLines.size)
                put("right_lines", rightLines.size)
                put("added_line_count", added.size)
                put("removed_line_count", removed.size)
                putJsonArray("added_lines") { added.take(limit).forEach { add(it) } }
                putJsonArray("removed_lines") { removed.take(limit).forEach { add(it) } }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Compares two texts and lists which lines appear in one but not the other, plus " +
                    "whether they are identical and how many lines each has. Each distinct line is " +
                    "reported once, and 'limit' (default $DEFAULT_DIFF_LINES, max $MAX_DIFF_LINES) " +
                    "caps how many lines of each kind are listed. This is a content comparison " +
                    "rather than a positional one, so it answers 'what is different' without " +
                    "claiming a specific hunk order. Use it to check that an edit changed only what " +
                    "it was meant to. Purely computational: no device access, no permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("left") {
                                put("type", "string")
                                put("description", "Original text")
                            }
                            putJsonObject("right") {
                                put("type", "string")
                                put("description", "Changed text")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Maximum lines listed per side (default $DEFAULT_DIFF_LINES, " +
                                        "max $MAX_DIFF_LINES)",
                                )
                            }
                        },
                    required = listOf("left", "right"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "diff_text"
        private const val TAG = "MCP:DiffTextHandler"
        private const val DEFAULT_DIFF_LINES = 100
        private const val MAX_DIFF_LINES = 1_000
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// csv_to_json
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `csv_to_json`.
 *
 * Input: `{ "csv": <string>, "delimiter": <string>?, "has_header": <bool>?, "limit": <int>? }`.
 */
class CsvToJsonHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val csv = McpToolUtils.requireString(arguments, "csv")
        val delimiter = McpToolUtils.optionalString(arguments, "delimiter", DEFAULT_DELIMITER)
        if (delimiter.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'delimiter' must not be empty")
        }
        val hasHeader = McpToolUtils.optionalBoolean(arguments, "has_header", true)
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_CSV_ROWS)
        McpToolUtils.validatePositiveRange(limit.toLong(), "limit", MAX_CSV_ROWS.toLong())

        Log.d(TAG, "Executing csv_to_json")

        val rows = parseDelimited(csv, delimiter.first()).filter { it.any { cell -> cell.isNotBlank() } }
        if (rows.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'csv' contains no rows")
        }
        val header = if (hasHeader) rows.first() else emptyList()
        val dataRows = if (hasHeader) rows.drop(1) else rows

        val json =
            buildJsonObject {
                put("delimiter", delimiter)
                put("has_header", hasHeader)
                put("total_rows", dataRows.size)
                put("returned_rows", minOf(limit, dataRows.size))
                putJsonArray("rows") {
                    dataRows.take(limit).forEach { row ->
                        add(rowToJson(header, row))
                    }
                }
            }
        return McpToolUtils.textResult(json.toString())
    }

    private fun rowToJson(
        header: List<String>,
        row: List<String>,
    ): JsonObject {
        if (header.isEmpty()) {
            return buildJsonObject {
                putJsonArray("values") { row.forEach { add(it) } }
            }
        }
        return buildJsonObject {
            header.forEachIndexed { index, name ->
                put(name.ifBlank { "column_${index + COLUMN_NUMBER_BASE}" }, row.getOrNull(index).orEmpty())
            }
        }
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Parses CSV text into JSON. Quoted fields, embedded delimiters and embedded " +
                    "newlines are handled properly, so a real spreadsheet export will not be " +
                    "misread. With 'has_header' true (the default) each row becomes an object keyed " +
                    "by the header cells, with blank or duplicate-free placeholder names for " +
                    "missing columns; with it false each row becomes an array of values. " +
                    "'delimiter' defaults to a comma and 'limit' (default $DEFAULT_CSV_ROWS, max " +
                    "$MAX_CSV_ROWS) caps the rows returned. Use it to turn a pasted table into " +
                    "something other tools can query. Purely computational: no device access, no " +
                    "permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("csv") {
                                put("type", "string")
                                put("description", "CSV text to parse")
                            }
                            putJsonObject("delimiter") {
                                put("type", "string")
                                put("description", "Field delimiter, a single character (default a comma)")
                            }
                            putJsonObject("has_header") {
                                put("type", "boolean")
                                put("description", "Treat the first row as column names (default true)")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Maximum rows to return (default $DEFAULT_CSV_ROWS, " +
                                        "max $MAX_CSV_ROWS)",
                                )
                            }
                        },
                    required = listOf("csv"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "csv_to_json"
        private const val TAG = "MCP:CsvToJsonHandler"
        private const val DEFAULT_DELIMITER = ","
        private const val DEFAULT_CSV_ROWS = 200
        private const val MAX_CSV_ROWS = 5_000
        private const val COLUMN_NUMBER_BASE = 1
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Registration and shared helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Registers every data and text tool enabled by [perms]. */
fun registerDataTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    dataRegistrations(registrar, context, toolNamePrefix).forEach { (toolName, register) ->
        if (perms.isToolEnabled(toolName)) register()
    }
}

/** Pairs each tool name with the closure that registers it, keeping registration branch-free. */
private fun dataRegistrations(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
): List<Pair<String, () -> Unit>> =
    listOf(
        Base64EncodeHandler.TOOL_NAME to
            { Base64EncodeHandler(context).register(registrar, toolNamePrefix) },
        Base64DecodeHandler.TOOL_NAME to
            { Base64DecodeHandler(context).register(registrar, toolNamePrefix) },
        HashStringHandler.TOOL_NAME to
            { HashStringHandler(context).register(registrar, toolNamePrefix) },
        HmacSignHandler.TOOL_NAME to
            { HmacSignHandler(context).register(registrar, toolNamePrefix) },
        GenerateUuidHandler.TOOL_NAME to
            { GenerateUuidHandler(context).register(registrar, toolNamePrefix) },
        RandomNumberHandler.TOOL_NAME to
            { RandomNumberHandler(context).register(registrar, toolNamePrefix) },
        RandomStringHandler.TOOL_NAME to
            { RandomStringHandler(context).register(registrar, toolNamePrefix) },
        JsonQueryHandler.TOOL_NAME to
            { JsonQueryHandler(context).register(registrar, toolNamePrefix) },
        RegexFindHandler.TOOL_NAME to
            { RegexFindHandler(context).register(registrar, toolNamePrefix) },
        RegexReplaceHandler.TOOL_NAME to
            { RegexReplaceHandler(context).register(registrar, toolNamePrefix) },
        UrlEncodeHandler.TOOL_NAME to
            { UrlEncodeHandler(context).register(registrar, toolNamePrefix) },
        UrlDecodeHandler.TOOL_NAME to
            { UrlDecodeHandler(context).register(registrar, toolNamePrefix) },
        TextStatsHandler.TOOL_NAME to
            { TextStatsHandler(context).register(registrar, toolNamePrefix) },
        DiffTextHandler.TOOL_NAME to
            { DiffTextHandler(context).register(registrar, toolNamePrefix) },
        CsvToJsonHandler.TOOL_NAME to
            { CsvToJsonHandler(context).register(registrar, toolNamePrefix) },
    )

/** Lowercase hexadecimal rendering of a byte array. */
private fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

/** Builds the character set for `random_string` from a name or a literal set. */
private fun charactersFor(alphabetName: String): String = ALPHABETS[alphabetName.lowercase()] ?: alphabetName

/** Draws [length] characters from [characters] using [random]. */
private fun randomString(
    random: SecureRandom,
    characters: String,
    length: Int,
): String =
    buildString(length) {
        repeat(length) {
            append(characters[random.nextInt(characters.length)])
        }
    }

/** Rejects out-of-bounds input before handing it to a regular expression. */
private fun ensureInputWithinLimit(input: String) {
    if (input.length > MAX_REGEX_INPUT_CHARS) {
        throw McpToolException.InvalidParams(
            "Input is ${input.length} characters, above the $MAX_REGEX_INPUT_CHARS character " +
                "limit for regular-expression tools",
        )
    }
}

/** Builds the [Regex] for a regex tool from [arguments]' optional flags. */
private fun buildRegex(
    arguments: JsonObject?,
    pattern: String,
): Regex {
    val options = mutableSetOf<RegexOption>()
    if (McpToolUtils.optionalBoolean(arguments, "ignore_case", false)) {
        options.add(RegexOption.IGNORE_CASE)
    }
    if (McpToolUtils.optionalBoolean(arguments, "multiline", false)) {
        options.add(RegexOption.MULTILINE)
    }
    return try {
        Regex(pattern, options)
    } catch (e: IllegalArgumentException) {
        throw McpToolException.InvalidParams("Parameter 'pattern' is not a valid regular expression: ${e.message}")
    }
}

/** Resolves a dot/bracket path against a parsed JSON document. */
private fun queryJsonPath(
    root: JsonElement,
    path: String,
): JsonElement? {
    if (path.isBlank()) return root
    val segments =
        path
            .replace("[", ".")
            .replace("]", "")
            .split(".")
            .filter { it.isNotBlank() }
    var current: JsonElement? = root
    segments.forEach { segment ->
        val node = current
        current =
            when (node) {
                is JsonObject -> node[segment]
                is JsonArray -> segment.toIntOrNull()?.let { index -> node.getOrNull(index) }
                else -> null
            }
    }
    return current
}

/**
 * Splits [text] into rows of cells on [delimiter], honouring double-quoted fields, escaped quotes
 * (`""`) and newlines inside quotes.
 */
private fun parseDelimited(
    text: String,
    delimiter: Char,
): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var inQuotes = false
    var index = 0
    while (index < text.length) {
        val character = text[index]
        when {
            inQuotes && character == '"' && text.getOrNull(index + 1) == '"' -> {
                cell.append('"')
                index++
            }

            character == '"' -> {
                inQuotes = !inQuotes
            }

            !inQuotes && character == delimiter -> {
                row.add(cell.toString())
                cell.clear()
            }

            !inQuotes && (character == '\n' || character == '\r') -> {
                if (character == '\r' && text.getOrNull(index + 1) == '\n') index++
                row.add(cell.toString())
                cell.clear()
                rows.add(row)
                row = mutableListOf()
            }

            else -> {
                cell.append(character)
            }
        }
        index++
    }
    row.add(cell.toString())
    rows.add(row)
    return rows
}

/** Character sets accepted by name in `random_string`. */
private val ALPHABETS: Map<String, String> =
    mapOf(
        "alphanumeric" to "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789",
        "alpha" to "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
        "numeric" to "0123456789",
        "hex" to "0123456789abcdef",
        "base64" to "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/",
    )

private val WORD_PATTERN = Regex("[\\p{L}\\p{N}']+")
private val SENTENCE_PATTERN = Regex("[.!?]+")
private const val MAX_REGEX_INPUT_CHARS = 200_000
