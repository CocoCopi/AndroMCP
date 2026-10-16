@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.cococopi.andromcp.mcp.tools

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.mcp.McpToolException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// Persistent memory tool bundle.
//
// A tool call is stateless: whatever the agent learned — a package name, a half-finished checklist,
// a preference the user stated, the fact that the third button opens the right dialog — is gone
// when the call returns. That works for one-shot tasks and fails for everything longer. These tools
// give the agent somewhere to write it down, on the device, surviving both the call and a restart of
// the server.
//
// Entries are grouped into namespaces so unrelated tasks do not collide, and values are plain text
// of bounded size, which keeps the store inspectable with the same tools that wrote it. Secrets do
// not belong here: use secret_store, which keeps them encrypted.

/** Size ceiling for a single remembered value. */
private const val MAX_VALUE_CHARS = 64_000

/** Prefix that marks a key as metadata rather than content. */
private const val META_SEPARATOR = "\u0001meta\u0001"

/** Default namespace used when a caller does not name one. */
private const val DEFAULT_NAMESPACE = "default"

/** Storage for the agent-memory bundle. */
internal object AgentMemory {
    private const val PREFS = "andromcp_agent_memory"

    /** SharedPreferences backing the memory store. */
    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Contact identifier for one remembered entry. */
    fun entryKey(
        namespace: String,
        name: String,
    ): String = "$namespace::$name"

    /** Reads the metadata block for [namespace]/[name], or null when the entry does not exist. */
    fun meta(
        prefs: SharedPreferences,
        namespace: String,
        name: String,
    ): JsonObject? {
        val key = entryKey(namespace, name)
        val value = prefs.getString(key, null) ?: return null
        return buildJsonObject {
            put("namespace", namespace)
            put("key", name)
            put("chars", value.length)
            put("created_epoch_ms", prefs.getLong(key + META_SEPARATOR + "created", 0L))
            put("updated_epoch_ms", prefs.getLong(key + META_SEPARATOR + "updated", 0L))
            put("tags", prefs.getString(key + META_SEPARATOR + "tags", "").orEmpty())
            put("value", value)
        }
    }

    /** Every namespace currently in use. */
    fun namespaces(prefs: SharedPreferences): List<String> =
        prefs.all.keys
            .filterNot { it.contains(META_SEPARATOR) }
            .map { it.substringBefore("::") }
            .distinct()
            .sorted()

    /** Every content key, excluding metadata rows. */
    fun contentKeys(prefs: SharedPreferences): List<String> =
        prefs.all.keys
            .filterNot { it.contains(META_SEPARATOR) }
            .sorted()

    /** Deletes one entry and its metadata. */
    fun delete(
        prefs: SharedPreferences,
        namespace: String,
        name: String,
    ) {
        val key = entryKey(namespace, name)
        prefs
            .edit()
            .remove(key)
            .remove(key + META_SEPARATOR + "created")
            .remove(key + META_SEPARATOR + "updated")
            .remove(key + META_SEPARATOR + "tags")
            .apply()
    }

    /** Splits a stored key into namespace and name. */
    fun splitKey(combined: String): Pair<String, String> = combined.substringBefore("::") to combined.substringAfter("::", "")

    /** Normalises a caller-supplied namespace. */
    fun namespaceOf(raw: String): String = raw.trim().ifEmpty { DEFAULT_NAMESPACE }
}

// ─────────────────────────────────────────────────────────────────────────────
// memory_set
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `memory_set`.
 * Input: `{ "key": <string>, "value": <string>, "namespace": <string>?, "tags": <string>? }`.
 */
class MemorySetHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val key = McpToolUtils.requireString(arguments, "key").trim()
        val value = McpToolUtils.requireString(arguments, "value")
        val namespace = AgentMemory.namespaceOf(McpToolUtils.optionalString(arguments, "namespace", ""))
        val tags = McpToolUtils.optionalString(arguments, "tags", "")
        Log.d(TAG, "Executing memory_set")

        if (key.isEmpty()) throw McpToolException.InvalidParams("Parameter 'key' must not be empty.")
        if (value.length > MAX_VALUE_CHARS) {
            throw McpToolException.InvalidParams(
                "Parameter 'value' is ${value.length} characters; the limit is $MAX_VALUE_CHARS. Store a " +
                    "file instead (write_workspace_file) and remember its path here.",
            )
        }
        val prefs = AgentMemory.prefs(context)
        val combined = AgentMemory.entryKey(namespace, key)
        val now = System.currentTimeMillis()
        val created = prefs.getLong(combined + META_SEPARATOR + "created", now)
        val existed = prefs.contains(combined)
        withContext(Dispatchers.IO) {
            prefs
                .edit()
                .putString(combined, value)
                .putLong(combined + META_SEPARATOR + "created", created)
                .putLong(combined + META_SEPARATOR + "updated", now)
                .putString(combined + META_SEPARATOR + "tags", tags)
                .apply()
        }
        val json =
            buildJsonObject {
                put("namespace", namespace)
                put("key", key)
                put("stored", true)
                put("updated_existing", existed)
                put("chars", value.length)
                put("updated_epoch_ms", now)
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
                "Writes a named value into persistent memory on the device, where it survives both the " +
                    "tool call and a restart of the server. Use it for the things a stateless agent " +
                    "normally loses: a package name discovered by search_apps, an item in a multi-step " +
                    "task so a later call can resume it, a user preference stated in passing, or a " +
                    "conclusion worth reusing instead of recomputing. Group related entries with " +
                    "'namespace' so different tasks do not overwrite each other, and reuse the same key " +
                    "to update a value. Do not put credentials here — secret_store encrypts those.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("key") {
                                put("type", "string")
                                put("description", "Name of the entry, e.g. \"target_app\"")
                            }
                            putJsonObject("value") {
                                put("type", "string")
                                put("description", "Text to remember, up to $MAX_VALUE_CHARS characters")
                            }
                            putJsonObject("namespace") {
                                put("type", "string")
                                put("description", "Group for related entries (default \"$DEFAULT_NAMESPACE\")")
                            }
                            putJsonObject("tags") {
                                put("type", "string")
                                put("description", "Optional comma-separated tags for later filtering")
                            }
                        },
                    required = listOf("key", "value"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "memory_set"
        private const val TAG = "MCP:MemorySetHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// memory_get
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `memory_get`. Input: `{ "key": <string>, "namespace": <string>? }`. */
class MemoryGetHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val key = McpToolUtils.requireString(arguments, "key").trim()
        val namespace = AgentMemory.namespaceOf(McpToolUtils.optionalString(arguments, "namespace", ""))
        Log.d(TAG, "Executing memory_get")

        val entry =
            AgentMemory.meta(AgentMemory.prefs(context), namespace, key)
                ?: throw McpToolException.InvalidParams(
                    "No memory entry '$key' in namespace '$namespace'. Call memory_list or memory_search to " +
                        "see what is stored.",
                )
        return McpToolUtils.textResult(entry.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reads back one remembered value with its timestamps, tags and size. Use it to pick up " +
                    "where an earlier call left off — the next URL to fetch, the package name to act on, " +
                    "the count already processed — and to check whether something was already recorded " +
                    "before doing the work again. An unknown key is reported as a clear error naming the " +
                    "search tools rather than as an empty value, so \"nothing stored\" is never confused " +
                    "with \"stored nothing\".",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("key") {
                                put("type", "string")
                                put("description", "Name of the entry to read")
                            }
                            putJsonObject("namespace") {
                                put("type", "string")
                                put("description", "Namespace the entry lives in (default \"$DEFAULT_NAMESPACE\")")
                            }
                        },
                    required = listOf("key"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "memory_get"
        private const val TAG = "MCP:MemoryGetHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// memory_list
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `memory_list`.
 * Input: `{ "namespace": <string>?, "prefix": <string>?, "limit": <int>?, "include_values": <bool>? }`.
 */
class MemoryListHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val namespace = McpToolUtils.optionalString(arguments, "namespace", "").trim()
        val prefix = McpToolUtils.optionalString(arguments, "prefix", "")
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val includeValues = McpToolUtils.optionalBoolean(arguments, "include_values", false)
        Log.d(TAG, "Executing memory_list")

        val prefs = AgentMemory.prefs(context)
        val entries =
            AgentMemory
                .contentKeys(prefs)
                .map { AgentMemory.splitKey(it) }
                .filter { (entryNamespace, _) -> namespace.isEmpty() || entryNamespace == namespace }
                .filter { (_, name) -> prefix.isEmpty() || name.startsWith(prefix) }
        val shown = entries.take(limit)
        val rows =
            shown.mapNotNull { (entryNamespace, name) ->
                val meta = AgentMemory.meta(prefs, entryNamespace, name) ?: return@mapNotNull null
                if (includeValues) meta else stripValue(meta)
            }
        val json =
            buildJsonObject {
                put("namespace", namespace)
                put("prefix", prefix)
                put("total_matches", entries.size)
                put("returned", rows.size)
                put("include_values", includeValues)
                putJsonArray("namespaces") { AgentMemory.namespaces(prefs).forEach { add(it) } }
                putJsonArray("entries") { rows.forEach { add(it) } }
            }
        return McpToolUtils.textResult(json.toString())
    }

    /** Copy of [entry] without its value, for cheap listings. */
    private fun stripValue(entry: JsonObject): JsonObject =
        buildJsonObject {
            entry.forEach { (name, element) ->
                if (name != "value") put(name, element)
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
                "Lists what memory holds: the keys in a namespace, newest first by update time, with " +
                    "their sizes, tags and timestamps, plus every namespace that exists. Use it at the " +
                    "start of a task to see whether anything relevant is already stored, to find the key " +
                    "to read, and to spot entries that are stale. Values are omitted by default so the " +
                    "listing stays small; set 'include_values': true when you want the contents too.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("namespace") {
                                put("type", "string")
                                put("description", "Only list entries in this namespace (default all)")
                            }
                            putJsonObject("prefix") {
                                put("type", "string")
                                put("description", "Only list keys starting with this text")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum entries to return (default $DEFAULT_LIMIT)")
                            }
                            putJsonObject("include_values") {
                                put("type", "boolean")
                                put("description", "Include each entry's value (default false)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "memory_list"
        private const val TAG = "MCP:MemoryListHandler"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 500
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// memory_search
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `memory_search`. Input: `{ "query": <string>, "limit": <int>? }`. */
class MemorySearchHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val query = McpToolUtils.requireString(arguments, "query").trim()
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        Log.d(TAG, "Executing memory_search")

        val prefs = AgentMemory.prefs(context)
        val rows =
            AgentMemory
                .contentKeys(prefs)
                .mapNotNull { combined ->
                    val (namespace, name) = AgentMemory.splitKey(combined)
                    val meta = AgentMemory.meta(prefs, namespace, name) ?: return@mapNotNull null
                    val tags = meta["tags"]?.toString().orEmpty()
                    meta.takeIf {
                        name.contains(query, ignoreCase = true) ||
                            tags.contains(query, ignoreCase = true) ||
                            meta["value"]?.toString()?.contains(query, ignoreCase = true) == true
                    }
                }.take(limit)
        val json =
            buildJsonObject {
                put("query", query)
                put("count", rows.size)
                putJsonArray("entries") { rows.forEach { add(it) } }
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
                "Searches every namespace for a case-insensitive substring, matching against keys, " +
                    "tags and values, and returns the full matching entries. Use it when you know what " +
                    "you need but not where it was filed — \"which key held that Wi-Fi password\" or " +
                    "\"what did I record about the printer\" — instead of listing namespaces one by one. " +
                    "It is the fastest way to find something an earlier session stored.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "Substring to find in keys, tags or values")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum entries to return (default $DEFAULT_LIMIT)")
                            }
                        },
                    required = listOf("query"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "memory_search"
        private const val TAG = "MCP:MemorySearchHandler"
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 200
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// memory_delete
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `memory_delete`. Input: `{ "key": <string>, "namespace": <string>? }`. */
class MemoryDeleteHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val key = McpToolUtils.requireString(arguments, "key").trim()
        val namespace = AgentMemory.namespaceOf(McpToolUtils.optionalString(arguments, "namespace", ""))
        Log.d(TAG, "Executing memory_delete")

        val prefs = AgentMemory.prefs(context)
        val meta =
            AgentMemory.meta(prefs, namespace, key)
                ?: throw McpToolException.InvalidParams(
                    "No memory entry '$key' in namespace '$namespace'; nothing was deleted.",
                )
        withContext(Dispatchers.IO) { AgentMemory.delete(prefs, namespace, key) }
        val json =
            buildJsonObject {
                put("namespace", namespace)
                put("key", key)
                put("deleted", true)
                put("freed_chars", meta["chars"]?.toString()?.trim('"').orEmpty())
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
                "Removes one remembered entry. Use it to retire a fact that is no longer true — a " +
                    "password that changed, a plan that was abandoned, a task that finished — because " +
                    "stale memory is worse than none: it will be trusted later. Deleting an entry that " +
                    "does not exist fails loudly, which distinguishes \"already cleaned up\" from \"just " +
                    "deleted it\". memory_clear removes a whole namespace instead.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("key") {
                                put("type", "string")
                                put("description", "Name of the entry to delete")
                            }
                            putJsonObject("namespace") {
                                put("type", "string")
                                put("description", "Namespace the entry lives in (default \"$DEFAULT_NAMESPACE\")")
                            }
                        },
                    required = listOf("key"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "memory_delete"
        private const val TAG = "MCP:MemoryDeleteHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// memory_clear
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `memory_clear`. Input: `{ "namespace": <string> }`. */
class MemoryClearHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val raw = McpToolUtils.requireString(arguments, "namespace").trim()
        Log.d(TAG, "Executing memory_clear")

        if (raw.isEmpty()) {
            throw McpToolException.InvalidParams(
                "Parameter 'namespace' is required. Name the namespace to empty — use memory_list to see " +
                    "the namespaces that exist.",
            )
        }
        val prefs = AgentMemory.prefs(context)
        val keys =
            AgentMemory
                .contentKeys(prefs)
                .map { AgentMemory.splitKey(it) }
                .filter { (namespace, _) -> namespace == raw }
        if (keys.isEmpty()) {
            throw McpToolException.InvalidParams(
                "Namespace '$raw' holds nothing, so nothing was cleared. memory_list shows the namespaces " +
                    "that exist.",
            )
        }
        withContext(Dispatchers.IO) {
            keys.forEach { (namespace, name) -> AgentMemory.delete(prefs, namespace, name) }
        }
        val json =
            buildJsonObject {
                put("namespace", raw)
                put("cleared", true)
                put("removed_entries", keys.size)
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
                "Empties one namespace in a single call — the clean-up step when a multi-step task is " +
                    "done and its scratch notes would only mislead the next one. The namespace must be " +
                    "named explicitly and must currently hold something, so there is no way to wipe " +
                    "memory by accident or by sending empty parameters. Entries in other namespaces are " +
                    "left alone.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("namespace") {
                                put("type", "string")
                                put("description", "Namespace to empty, e.g. \"onboarding_demo\"")
                            }
                        },
                    required = listOf("namespace"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "memory_clear"
        private const val TAG = "MCP:MemoryClearHandler"
    }
}

/** Registers the memory bundle: store, read, list, search, delete and clear. */
fun registerAgentMemoryTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    MemorySetHandler(context).register(registrar, toolNamePrefix)
    MemoryGetHandler(context).register(registrar, toolNamePrefix)
    MemoryListHandler(context).register(registrar, toolNamePrefix)
    MemorySearchHandler(context).register(registrar, toolNamePrefix)
    MemoryDeleteHandler(context).register(registrar, toolNamePrefix)
    MemoryClearHandler(context).register(registrar, toolNamePrefix)
}
