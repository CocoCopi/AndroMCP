@file:Suppress("MatchingDeclarationName")

package com.cococopi.andromcp.mcp.tools

import android.content.Context
import android.util.Log
import android.webkit.MimeTypeMap
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
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// Workspace file-operations tool bundle.
//
// Gives an agent a real, writable scratch filesystem of its own: a directory it can create
// folders in, copy, move, delete, stat, hash, search, zip and unzip, without ever touching the
// user's personal storage and without holding a single storage permission. Everything is
// confined to the app's own external files directory, so the files are also browsable by the
// user through any file manager, which makes the results verifiable.
//
// Every path parameter is relative to that root and is resolved canonically, so a path such as
// `../../etc/hosts` is rejected rather than followed. File contents read back are untrusted
// external data and carry the anti-prompt-injection warning.

// ─────────────────────────────────────────────────────────────────────────────
// list_workspace_files
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_workspace_files`.
 *
 * **Input**: `{ "path": <string>?, "limit": <int>? }`
 * **Output**: JSON object with the directory's entries.
 */
class ListWorkspaceFilesHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val path = McpToolUtils.optionalString(arguments, "path", "")
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_LIST_LIMIT)
        McpToolUtils.validatePositiveRange(limit.toLong(), "limit", MAX_LIST_LIMIT.toLong())

        Log.d(TAG, "Executing list_workspace_files")

        val json =
            withContext(Dispatchers.IO) {
                val directory = Workspace.resolve(context, path)
                requireDirectory(directory, path)
                val entries =
                    directory
                        .listFiles()
                        .orEmpty()
                        .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                        .take(limit)
                buildJsonObject {
                    put("workspace_root", Workspace.root(context).absolutePath)
                    put("path", Workspace.relative(context, directory))
                    put("entry_count", entries.size)
                    putJsonArray("entries") { entries.forEach { entry -> add(Workspace.describe(context, entry)) } }
                }
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private fun requireDirectory(
        directory: File,
        path: String,
    ) {
        if (!directory.exists()) {
            throw McpToolException.NodeNotFound("No workspace path exists at '$path'")
        }
        if (!directory.isDirectory) {
            throw McpToolException.InvalidParams("Workspace path '$path' is a file, not a directory")
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
                "Lists a directory inside the app's own workspace, directories first, with each " +
                    "entry's name, workspace-relative path, size, last-modified time and MIME type. " +
                    "'path' defaults to the workspace root and accepts a relative path or an empty " +
                    "string for the root; 'limit' (default 200, max 1000) caps the entries. The " +
                    "response also reports the workspace root's absolute path so the user can " +
                    "inspect the same files in a file manager. No storage permission is required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Workspace-relative directory path; empty or omitted means the root")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum number of entries to return (default 200, max 1000)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_workspace_files"
        private const val TAG = "MCP:ListWorkspaceFilesHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// make_directory
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `make_directory`.
 *
 * **Input**: `{ "path": <string> }`
 * **Output**: text confirmation.
 */
class MakeDirectoryHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val path = McpToolUtils.requireString(arguments, "path")
        Log.d(TAG, "Executing make_directory")

        val created = withContext(Dispatchers.IO) { Workspace.resolve(context, path).mkdirs() }
        return McpToolUtils.textResult(
            if (created) {
                "Created workspace directory '$path'"
            } else {
                "Workspace directory '$path' already exists"
            },
        )
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Creates a directory in the app workspace, including any missing parents, and " +
                    "reports whether it was created or already existed. Use it to set up a " +
                    "predictable structure before writing files into it — for example a folder per " +
                    "task, so a later listing shows exactly what an earlier step produced. Requires " +
                    "no storage permission and affects nothing outside the app workspace.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Workspace-relative directory path to create")
                            }
                        },
                    required = listOf("path"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "make_directory"
        private const val TAG = "MCP:MakeDirectoryHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// copy_path
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `copy_path`.
 *
 * **Input**: `{ "source": <string>, "destination": <string>, "overwrite": <bool>? }`
 * **Output**: text confirmation.
 */
class CopyPathHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val sourcePath = McpToolUtils.requireString(arguments, "source")
        val destinationPath = McpToolUtils.requireString(arguments, "destination")
        val overwrite = McpToolUtils.optionalBoolean(arguments, "overwrite", false)

        Log.d(TAG, "Executing copy_path")

        val copiedEntries =
            withContext(Dispatchers.IO) {
                val source = Workspace.resolve(context, sourcePath)
                val destination = Workspace.resolve(context, destinationPath)
                if (!source.exists()) {
                    throw McpToolException.NodeNotFound("No workspace path exists at '$sourcePath'")
                }
                if (destination.exists() && !overwrite) {
                    throw McpToolException.InvalidParams(
                        "Destination '$destinationPath' already exists; pass 'overwrite': true to replace it",
                    )
                }
                destination.parentFile?.mkdirs()
                if (destination.exists()) destination.deleteRecursively()
                val succeeded =
                    try {
                        source.copyRecursively(destination, overwrite = true)
                    } catch (e: IOException) {
                        throw McpToolException.ActionFailed("Copy failed: ${e.message}")
                    }
                if (!succeeded) {
                    throw McpToolException.ActionFailed("Nothing was copied from '$sourcePath'")
                }
                countEntries(source)
            }

        return McpToolUtils.textResult(
            "Copied '$sourcePath' to '$destinationPath' ($copiedEntries entries)",
        )
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Copies a file or a whole directory tree inside the app workspace, creating parent " +
                    "directories as needed. Both 'source' and 'destination' are workspace-relative. " +
                    "The copy is refused when the destination already exists unless 'overwrite' is " +
                    "true, so an accidental second run cannot silently destroy a previous result. " +
                    "Use it to keep a pristine copy of an input before transforming it, or to " +
                    "produce a differently named variant. No storage permission is required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("source") {
                                put("type", "string")
                                put("description", "Workspace-relative path to copy from")
                            }
                            putJsonObject("destination") {
                                put("type", "string")
                                put("description", "Workspace-relative path to copy to")
                            }
                            putJsonObject("overwrite") {
                                put("type", "boolean")
                                put("description", "Replace the destination if it exists (default false)")
                            }
                        },
                    required = listOf("source", "destination"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "copy_path"
        private const val TAG = "MCP:CopyPathHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// move_path
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `move_path`.
 *
 * **Input**: `{ "source": <string>, "destination": <string>, "overwrite": <bool>? }`
 * **Output**: text confirmation.
 */
class MovePathHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val sourcePath = McpToolUtils.requireString(arguments, "source")
        val destinationPath = McpToolUtils.requireString(arguments, "destination")
        val overwrite = McpToolUtils.optionalBoolean(arguments, "overwrite", false)

        Log.d(TAG, "Executing move_path")

        val strategy =
            withContext(Dispatchers.IO) {
                val source = Workspace.resolve(context, sourcePath)
                val destination = Workspace.resolve(context, destinationPath)
                if (!source.exists()) {
                    throw McpToolException.NodeNotFound("No workspace path exists at '$sourcePath'")
                }
                if (destination.exists() && !overwrite) {
                    throw McpToolException.InvalidParams(
                        "Destination '$destinationPath' already exists; pass 'overwrite': true to replace it",
                    )
                }
                if (destination.exists()) destination.deleteRecursively()
                destination.parentFile?.mkdirs()

                if (source.renameTo(destination)) {
                    "renamed in place"
                } else {
                    val succeeded =
                        try {
                            source.copyRecursively(destination, overwrite = true)
                        } catch (e: IOException) {
                            throw McpToolException.ActionFailed("Move failed: ${e.message}")
                        }
                    if (!succeeded) {
                        throw McpToolException.ActionFailed("Nothing was moved from '$sourcePath'")
                    }
                    if (!source.deleteRecursively()) {
                        throw McpToolException.ActionFailed(
                            "The copy succeeded but the original '$sourcePath' could not be removed",
                        )
                    }
                    "copied and the original removed"
                }
            }

        return McpToolUtils.textResult("Moved '$sourcePath' to '$destinationPath' ($strategy)")
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Moves or renames a file or directory tree inside the app workspace, which makes " +
                    "rename just a move within the same directory. A fast rename is attempted first " +
                    "and a copy-then-delete fallback is used when the two paths sit on different " +
                    "filesystems, so the move still succeeds. Refuses to overwrite unless " +
                    "'overwrite' is true. No storage permission is required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("source") {
                                put("type", "string")
                                put("description", "Workspace-relative path to move from")
                            }
                            putJsonObject("destination") {
                                put("type", "string")
                                put("description", "Workspace-relative path to move to, or the new name")
                            }
                            putJsonObject("overwrite") {
                                put("type", "boolean")
                                put("description", "Replace the destination if it exists (default false)")
                            }
                        },
                    required = listOf("source", "destination"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "move_path"
        private const val TAG = "MCP:MovePathHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// delete_path
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `delete_path`.
 *
 * **Input**: `{ "path": <string>, "recursive": <bool>? }`
 * **Output**: text confirmation.
 */
class DeletePathHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val path = McpToolUtils.requireString(arguments, "path")
        val recursive = McpToolUtils.optionalBoolean(arguments, "recursive", false)

        Log.d(TAG, "Executing delete_path")

        val deletedEntries =
            withContext(Dispatchers.IO) {
                val target = Workspace.resolve(context, path)
                if (target == Workspace.root(context).canonicalFile) {
                    throw McpToolException.InvalidParams("The workspace root cannot be deleted")
                }
                if (!target.exists()) {
                    throw McpToolException.NodeNotFound("No workspace path exists at '$path'")
                }
                if (target.isDirectory && !recursive) {
                    throw McpToolException.InvalidParams(
                        "'$path' is a directory; pass 'recursive': true to delete it and its contents",
                    )
                }
                val count = countEntries(target)
                val removed = if (target.isDirectory) target.deleteRecursively() else target.delete()
                if (!removed) {
                    throw McpToolException.ActionFailed("Deleting '$path' failed")
                }
                count
            }

        return McpToolUtils.textResult("Deleted '$path' ($deletedEntries entries removed)")
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Deletes a file or directory from the app workspace and reports how many entries " +
                    "went with it, so the deletion is observable rather than silent. Deleting a " +
                    "directory requires 'recursive': true, and the workspace root itself is never " +
                    "deletable, so a mistyped path cannot wipe the whole area. Use it to clean up " +
                    "after a task. No storage permission is required, and nothing outside the app " +
                    "workspace can be reached.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Workspace-relative path to delete")
                            }
                            putJsonObject("recursive") {
                                put("type", "boolean")
                                put("description", "Required to be true to delete a directory (default false)")
                            }
                        },
                    required = listOf("path"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "delete_path"
        private const val TAG = "MCP:DeletePathHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_path_info
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_path_info`.
 *
 * **Input**: `{ "path": <string>, "include_hash": <bool>? }`
 * **Output**: JSON object describing the path, or `exists: false`.
 */
class GetPathInfoHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val path = McpToolUtils.requireString(arguments, "path")
        val includeHash = McpToolUtils.optionalBoolean(arguments, "include_hash", false)

        Log.d(TAG, "Executing get_path_info")

        val json =
            withContext(Dispatchers.IO) {
                val target = Workspace.resolve(context, path)
                if (!target.exists()) {
                    buildJsonObject {
                        put("path", path)
                        put("exists", false)
                    }
                } else {
                    buildJsonObject {
                        put("path", Workspace.relative(context, target))
                        put("exists", true)
                        put("is_directory", target.isDirectory)
                        put("is_file", target.isFile)
                        put("size_bytes", if (target.isFile) target.length() else null)
                        put("last_modified_ms", target.lastModified())
                        put("readable", target.canRead())
                        put("writable", target.canWrite())
                        put("mime_type", if (target.isFile) mimeTypeOf(target) else null)
                        put("child_count", if (target.isDirectory) target.listFiles()?.size else null)
                        put(
                            "sha256",
                            if (includeHash && target.isFile) Workspace.hash(target, "SHA-256") else null,
                        )
                    }
                }
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports everything about one workspace path: whether it exists, whether it is a " +
                    "file or a directory, its size, last-modified time, readability, writability, " +
                    "MIME type and child count, plus optionally its SHA-256 digest. A missing path " +
                    "is reported as 'exists': false rather than an error, so it doubles as a " +
                    "presence check. Use the optional digest to verify that a copy or a move " +
                    "preserved content exactly. No storage permission is required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Workspace-relative path to inspect")
                            }
                            putJsonObject("include_hash") {
                                put("type", "boolean")
                                put("description", "Include the file's SHA-256 digest (default false)")
                            }
                        },
                    required = listOf("path"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_path_info"
        private const val TAG = "MCP:GetPathInfoHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// compute_file_hash
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `compute_file_hash`.
 *
 * **Input**: `{ "path": <string>, "algorithm": <string>? }`
 * **Output**: JSON object with the digest.
 */
class ComputeFileHashHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val path = McpToolUtils.requireString(arguments, "path")
        val algorithm = McpToolUtils.optionalString(arguments, "algorithm", DEFAULT_HASH_ALGORITHM).uppercase()
        val javaAlgorithm =
            HASH_ALGORITHMS[algorithm]
                ?: throw McpToolException.InvalidParams(
                    "Unknown algorithm '$algorithm'. Valid values: ${HASH_ALGORITHMS.keys.joinToString(", ")}",
                )

        Log.d(TAG, "Executing compute_file_hash")

        val json =
            withContext(Dispatchers.IO) {
                val target = Workspace.resolve(context, path)
                if (!target.isFile) {
                    throw McpToolException.NodeNotFound("No file exists at '$path'")
                }
                try {
                    buildJsonObject {
                        put("path", Workspace.relative(context, target))
                        put("algorithm", algorithm)
                        put("digest", Workspace.hash(target, javaAlgorithm))
                        put("size_bytes", target.length())
                    }
                } catch (e: NoSuchAlgorithmException) {
                    throw McpToolException.InternalError("Algorithm '$algorithm' is unavailable: ${e.message}")
                }
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Computes a cryptographic digest of a workspace file by streaming it, so large files " +
                    "do not have to be loaded into memory. 'algorithm' defaults to SHA-256 and also " +
                    "accepts MD5, SHA-1 and SHA-512. Use it to prove two files are identical after a " +
                    "copy or download, to detect that a file changed, or to fingerprint content the " +
                    "agent will refer to later. No storage permission is required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Workspace-relative path of the file to hash")
                            }
                            putJsonObject("algorithm") {
                                put("type", "string")
                                put("description", "MD5, SHA-1, SHA-256 (default) or SHA-512")
                            }
                        },
                    required = listOf("path"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "compute_file_hash"
        private const val TAG = "MCP:ComputeFileHashHandler"
        private const val DEFAULT_HASH_ALGORITHM = "SHA-256"
        private val HASH_ALGORITHMS =
            mapOf("MD5" to "MD5", "SHA-1" to "SHA-1", "SHA-256" to "SHA-256", "SHA-512" to "SHA-512")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// find_files
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `find_files`.
 *
 * **Input**: `{ "pattern": <string>, "path": <string>?, "max_depth": <int>?, "limit": <int>? }`
 * **Output**: JSON array of matching files.
 */
class FindFilesHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val pattern = McpToolUtils.requireString(arguments, "pattern")
        val path = McpToolUtils.optionalString(arguments, "path", "")
        val maxDepth = McpToolUtils.optionalInt(arguments, "max_depth", DEFAULT_MAX_DEPTH)
        McpToolUtils.validatePositiveRange(maxDepth.toLong(), "max_depth", MAX_MAX_DEPTH.toLong())
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_FIND_LIMIT)
        McpToolUtils.validatePositiveRange(limit.toLong(), "limit", MAX_FIND_LIMIT.toLong())

        Log.d(TAG, "Executing find_files")

        val json =
            withContext(Dispatchers.IO) {
                val start = Workspace.resolve(context, path)
                if (!start.exists()) {
                    throw McpToolException.NodeNotFound("No workspace path exists at '$path'")
                }
                val matcher = globToRegex(pattern)
                val matches =
                    start
                        .walkTopDown()
                        .maxDepth(maxDepth)
                        .filter { it.isFile && matcher.matches(it.name) }
                        .take(limit)
                        .map { Workspace.describe(context, it) }
                        .toList()
                buildJsonObject {
                    put("pattern", pattern)
                    put("match_count", matches.size)
                    putJsonArray("matches") { matches.forEach { add(it) } }
                }
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Finds files anywhere under a workspace directory whose name matches a glob " +
                    "pattern, where '*' matches any run of characters and '?' matches a single one " +
                    "(matching is case-insensitive). 'pattern' is required, for example '*.zip' or " +
                    "'report-?.txt'; 'path' narrows the search to a subdirectory. 'max_depth' " +
                    "(default 10, max 64) bounds how deep the walk goes and 'limit' (default 100, " +
                    "max 1000) bounds how many matches come back. No storage permission is required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("pattern") {
                                put("type", "string")
                                put("description", "Glob pattern matched against file names, for example *.zip")
                            }
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Workspace-relative directory to search under (default the root)")
                            }
                            putJsonObject("max_depth") {
                                put("type", "integer")
                                put("description", "Maximum directory depth to descend (default 10, max 64)")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum number of matches to return (default 100, max 1000)")
                            }
                        },
                    required = listOf("pattern"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "find_files"
        private const val TAG = "MCP:FindFilesHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// search_in_files
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `search_in_files`.
 *
 * **Input**: `{ "query": <string>, "path": <string>?, "extensions": <string>?,
 * "case_sensitive": <bool>?, "max_depth": <int>?, "limit": <int>? }`
 * **Output**: JSON array of matching lines with their file and line number.
 */
class SearchInFilesHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val query = McpToolUtils.requireString(arguments, "query")
        if (query.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'query' must not be empty")
        }
        val path = McpToolUtils.optionalString(arguments, "path", "")
        val extensions = McpToolUtils.optionalString(arguments, "extensions", "")
        val caseSensitive = McpToolUtils.optionalBoolean(arguments, "case_sensitive", false)
        val maxDepth = McpToolUtils.optionalInt(arguments, "max_depth", DEFAULT_MAX_DEPTH)
        McpToolUtils.validatePositiveRange(maxDepth.toLong(), "max_depth", MAX_MAX_DEPTH.toLong())
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_SEARCH_LIMIT)
        McpToolUtils.validatePositiveRange(limit.toLong(), "limit", MAX_SEARCH_LIMIT.toLong())

        Log.d(TAG, "Executing search_in_files")

        val json =
            withContext(Dispatchers.IO) {
                val start = Workspace.resolve(context, path)
                if (!start.exists()) {
                    throw McpToolException.NodeNotFound("No workspace path exists at '$path'")
                }
                val wantedExtensions =
                    extensions
                        .split(",")
                        .map { it.trim().removePrefix(".").lowercase() }
                        .filter { it.isNotEmpty() }
                val matches = collectMatches(start, query, wantedExtensions, caseSensitive, maxDepth, limit)
                buildJsonObject {
                    put("query", query)
                    put("match_count", matches.size)
                    putJsonArray("matches") { matches.forEach { add(it) } }
                }
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private fun collectMatches(
        start: File,
        query: String,
        extensions: List<String>,
        caseSensitive: Boolean,
        maxDepth: Int,
        limit: Int,
    ): List<JsonObject> {
        val matches = mutableListOf<JsonObject>()
        val candidates =
            if (start.isFile) {
                sequenceOf(start)
            } else {
                start.walkTopDown().maxDepth(maxDepth).filter { it.isFile }
            }
        for (file in candidates) {
            if (matches.size >= limit) break
            if (extensions.isNotEmpty() && file.extension.lowercase() !in extensions) continue
            if (file.length() > MAX_SEARCHED_FILE_BYTES) continue
            collectFileMatches(file, query, caseSensitive, matches, limit)
        }
        return matches
    }

    private fun collectFileMatches(
        file: File,
        query: String,
        caseSensitive: Boolean,
        matches: MutableList<JsonObject>,
        limit: Int,
    ) {
        val needle = if (caseSensitive) query else query.lowercase()
        try {
            file.useLines { lines ->
                for ((index, line) in lines.withIndex()) {
                    if (matches.size >= limit) break
                    val haystack = if (caseSensitive) line else line.lowercase()
                    if (haystack.contains(needle)) {
                        matches.add(
                            buildJsonObject {
                                put("path", Workspace.relative(context, file))
                                put("line_number", index + LINE_NUMBER_BASE)
                                put("line", line.take(MAX_MATCHED_LINE_CHARS))
                            },
                        )
                    }
                }
            }
        } catch (_: IOException) {
            // Unreadable files are skipped: a single bad file must not fail the whole search.
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
                "Searches the text of every file under a workspace directory for a substring and " +
                    "returns each matching line with its file and 1-based line number. 'query' is " +
                    "required and matched literally, case-insensitively unless 'case_sensitive' is " +
                    "true. 'extensions' restricts the search to a comma-separated list such as " +
                    "'txt,md,json', 'max_depth' (default 10, max 64) bounds the walk, and 'limit' " +
                    "(default 50, max 500) bounds the matches. Files larger than one megabyte are " +
                    "skipped. Unreadable files are skipped rather than failing the search. No " +
                    "storage permission is required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "Literal text to look for")
                            }
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Workspace-relative directory or file to search (default the root)")
                            }
                            putJsonObject("extensions") {
                                put("type", "string")
                                put("description", "Comma-separated extension filter, for example txt,md,json")
                            }
                            putJsonObject("case_sensitive") {
                                put("type", "boolean")
                                put("description", "Match case-sensitively (default false)")
                            }
                            putJsonObject("max_depth") {
                                put("type", "integer")
                                put("description", "Maximum directory depth to descend (default 10, max 64)")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum number of matching lines to return (default 50, max 500)")
                            }
                        },
                    required = listOf("query"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "search_in_files"
        private const val TAG = "MCP:SearchInFilesHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// zip_path
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `zip_path`.
 *
 * **Input**: `{ "source": <string>, "destination": <string>?, "overwrite": <bool>? }`
 * **Output**: text confirmation.
 */
class ZipPathHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val sourcePath = McpToolUtils.requireString(arguments, "source")
        val destinationPath = McpToolUtils.optionalString(arguments, "destination", "")
        val overwrite = McpToolUtils.optionalBoolean(arguments, "overwrite", false)

        Log.d(TAG, "Executing zip_path")

        val archived =
            withContext(Dispatchers.IO) {
                val source = Workspace.resolve(context, sourcePath)
                if (!source.exists()) {
                    throw McpToolException.NodeNotFound("No workspace path exists at '$sourcePath'")
                }
                val destination =
                    if (destinationPath.isEmpty()) {
                        File(source.parentFile, "${source.name}$ZIP_EXTENSION")
                    } else {
                        Workspace.resolve(context, destinationPath)
                    }
                if (destination.exists() && !overwrite) {
                    throw McpToolException.InvalidParams(
                        "'${Workspace.relative(context, destination)}' already exists; pass " +
                            "'overwrite': true to replace it",
                    )
                }
                destination.parentFile?.mkdirs()

                try {
                    archive(source, destination)
                } catch (e: IOException) {
                    throw McpToolException.ActionFailed("Compression failed: ${e.message}")
                }

                Triple(
                    Workspace.relative(context, destination),
                    destination.length(),
                    countEntries(source),
                )
            }

        return McpToolUtils.textResult(
            "Created '${archived.first}' from '$sourcePath' " +
                "(${archived.third} entries, ${archived.second} bytes)",
        )
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Compresses a file or a whole directory tree into a zip archive inside the app " +
                    "workspace, preserving the relative structure beneath the chosen source. " +
                    "'destination' defaults to the source's name with a .zip suffix next to it, so " +
                    "the common case needs no destination at all. An existing archive is only " +
                    "replaced when 'overwrite' is true. Use it to bundle many generated files into " +
                    "one artifact, which pairs with the file-sharing tools that can hand a single " +
                    "file to the user. No storage permission is required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("source") {
                                put("type", "string")
                                put("description", "Workspace-relative file or directory to compress")
                            }
                            putJsonObject("destination") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Workspace-relative path for the archive (default source name plus .zip)",
                                )
                            }
                            putJsonObject("overwrite") {
                                put("type", "boolean")
                                put("description", "Replace an existing archive (default false)")
                            }
                        },
                    required = listOf("source"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "zip_path"
        private const val TAG = "MCP:ZipPathHandler"
        private const val ZIP_EXTENSION = ".zip"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// unzip_file
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `unzip_file`.
 *
 * **Input**: `{ "source": <string>, "destination": <string>, "overwrite": <bool>? }`
 * **Output**: text confirmation.
 */
class UnzipFileHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val sourcePath = McpToolUtils.requireString(arguments, "source")
        val destinationPath = McpToolUtils.requireString(arguments, "destination")
        val overwrite = McpToolUtils.optionalBoolean(arguments, "overwrite", false)

        Log.d(TAG, "Executing unzip_file")

        val extracted =
            withContext(Dispatchers.IO) {
                val source = Workspace.resolve(context, sourcePath)
                if (!source.isFile) {
                    throw McpToolException.NodeNotFound("No zip file exists at '$sourcePath'")
                }
                val destination = Workspace.resolve(context, destinationPath)
                destination.mkdirs()
                try {
                    extract(source, destination, overwrite)
                } catch (e: IOException) {
                    throw McpToolException.ActionFailed("Extraction failed: ${e.message}")
                }
            }

        return McpToolUtils.textResult(
            "Extracted $extracted entries from '$sourcePath' into '$destinationPath'",
        )
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Extracts a zip archive from the app workspace into a workspace directory, " +
                    "recreating the stored directory structure and returning how many entries were " +
                    "written. Entries whose stored path would escape the destination directory are " +
                    "refused, so a hostile archive cannot write outside the workspace, and existing " +
                    "files are only replaced when 'overwrite' is true. Both 'source' and " +
                    "'destination' are required and workspace-relative. No storage permission is " +
                    "required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("source") {
                                put("type", "string")
                                put("description", "Workspace-relative path of the zip archive")
                            }
                            putJsonObject("destination") {
                                put("type", "string")
                                put("description", "Workspace-relative directory to extract into")
                            }
                            putJsonObject("overwrite") {
                                put("type", "boolean")
                                put("description", "Replace existing files (default false)")
                            }
                        },
                    required = listOf("source", "destination"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "unzip_file"
        private const val TAG = "MCP:UnzipFileHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// read_workspace_file
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `read_workspace_file`.
 *
 * **Input**: `{ "path": <string>, "offset": <int>?, "limit": <int>?, "as_base64": <bool>?,
 * "max_bytes": <int>? }`
 * **Output**: JSON object with text lines, or base64-encoded bytes.
 */
class ReadWorkspaceFileHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val path = McpToolUtils.requireString(arguments, "path")
        val offset = McpToolUtils.optionalInt(arguments, "offset", DEFAULT_READ_OFFSET)
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_READ_LINES)
        McpToolUtils.validatePositiveRange(offset.toLong(), "offset", MAX_READ_LINES.toLong())
        McpToolUtils.validatePositiveRange(limit.toLong(), "limit", MAX_READ_LINES.toLong())
        val asBase64 = McpToolUtils.optionalBoolean(arguments, "as_base64", false)
        val maxBytes = McpToolUtils.optionalLong(arguments, "max_bytes", DEFAULT_MAX_READ_BYTES)
        McpToolUtils.validatePositiveRange(maxBytes, "max_bytes", MAX_MAX_READ_BYTES)

        Log.d(TAG, "Executing read_workspace_file")

        val json =
            withContext(Dispatchers.IO) {
                val file = Workspace.resolve(context, path)
                if (!file.isFile) {
                    throw McpToolException.NodeNotFound("No file exists at '$path'")
                }
                if (asBase64) {
                    readAsBase64(context, file, maxBytes)
                } else {
                    readAsText(context, file, offset, limit)
                }
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private fun readAsBase64(
        context: Context,
        file: File,
        maxBytes: Long,
    ): JsonObject {
        if (file.length() > maxBytes) {
            throw McpToolException.InvalidParams(
                "'${Workspace.relative(context, file)}' is ${file.length()} bytes, above the " +
                    "'max_bytes' limit of $maxBytes; raise 'max_bytes' to read it",
            )
        }
        val bytes = file.readBytes()
        return buildJsonObject {
            put("path", Workspace.relative(context, file))
            put("encoding", "base64")
            put("size_bytes", bytes.size)
            put("content", Base64.getEncoder().encodeToString(bytes))
        }
    }

    private fun readAsText(
        context: Context,
        file: File,
        offset: Int,
        limit: Int,
    ): JsonObject {
        val selected = mutableListOf<String>()
        var totalLines = 0
        file.useLines { lines ->
            for ((index, line) in lines.withIndex()) {
                totalLines++
                val lineNumber = index + LINE_NUMBER_BASE
                if (lineNumber >= offset && selected.size < limit) {
                    selected.add(line)
                }
            }
        }
        return buildJsonObject {
            put("path", Workspace.relative(context, file))
            put("encoding", "text")
            put("total_lines", totalLines)
            put("start_line", offset)
            put("end_line", if (selected.isEmpty()) offset else offset + selected.size - 1)
            put("has_more", offset + selected.size - 1 < totalLines)
            putJsonArray("lines") { selected.forEach { add(it) } }
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
                "Reads a file from the app workspace. By default it returns text as numbered lines " +
                    "with pagination: 'offset' is the 1-based first line (default 1) and 'limit' is " +
                    "how many lines to return (default 200, max 2000), while 'total_lines' and " +
                    "'has_more' tell you whether to continue. Set 'as_base64': true to get the raw " +
                    "bytes base64-encoded instead, which works for binary files and is bounded by " +
                    "'max_bytes' (default 1048576, max 16777216). Use the base64 mode to feed a " +
                    "no-argument binary, such as an image or a zip, into another tool. No storage " +
                    "permission is required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Workspace-relative path of the file to read")
                            }
                            putJsonObject("offset") {
                                put("type", "integer")
                                put("description", "1-based first line to return (default 1)")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum number of lines to return (default 200, max 2000)")
                            }
                            putJsonObject("as_base64") {
                                put("type", "boolean")
                                put("description", "Return the raw bytes base64-encoded instead of text lines")
                            }
                            putJsonObject("max_bytes") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Byte ceiling for base64 reads (default 1048576, max 16777216)",
                                )
                            }
                        },
                    required = listOf("path"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "read_workspace_file"
        private const val TAG = "MCP:ReadWorkspaceFileHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// write_workspace_file
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `write_workspace_file`.
 *
 * **Input**: `{ "path": <string>, "content": <string>?, "base64_content": <string>?,
 * "append": <bool>? }`
 * **Output**: text confirmation.
 */
class WriteWorkspaceFileHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val path = McpToolUtils.requireString(arguments, "path")
        val content = McpToolUtils.optionalString(arguments, "content", "")
        val base64Content = McpToolUtils.optionalString(arguments, "base64_content", "")
        if (content.isEmpty() && base64Content.isEmpty()) {
            throw McpToolException.InvalidParams(
                "Provide 'content' for text or 'base64_content' for raw bytes",
            )
        }
        val append = McpToolUtils.optionalBoolean(arguments, "append", false)

        Log.d(TAG, "Executing write_workspace_file")

        val writtenBytes =
            withContext(Dispatchers.IO) {
                val file = Workspace.resolve(context, path)
                if (file.isDirectory) {
                    throw McpToolException.InvalidParams("'$path' is a directory, not a file")
                }
                file.parentFile?.mkdirs()
                try {
                    if (base64Content.isNotEmpty()) {
                        val bytes =
                            try {
                                Base64.getDecoder().decode(base64Content)
                            } catch (e: IllegalArgumentException) {
                                throw McpToolException.InvalidParams(
                                    "Parameter 'base64_content' is not valid base64: ${e.message}",
                                )
                            }
                        if (append) file.appendBytes(bytes) else file.writeBytes(bytes)
                        bytes.size.toLong()
                    } else {
                        if (append) file.appendText(content) else file.writeText(content)
                        content.toByteArray().size.toLong()
                    }
                } catch (e: IOException) {
                    throw McpToolException.ActionFailed("Writing '$path' failed: ${e.message}")
                }
            }

        return McpToolUtils.textResult(
            "Wrote $writtenBytes bytes to '$path' (${if (append) "appended" else "overwritten"})",
        )
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Writes a file into the app workspace, creating parent directories as needed. Pass " +
                    "text in 'content', or raw bytes base64-encoded in 'base64_content' to write " +
                    "binary data such as an image decoded from another tool's output. Set " +
                    "'append': true to add to the end of the existing file instead of replacing it, " +
                    "which is how a progress log is accumulated across several tool calls. The " +
                    "reported byte count makes the write verifiable. No storage permission is " +
                    "required, and only the app workspace can be written to.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Workspace-relative path of the file to write")
                            }
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "Text to write")
                            }
                            putJsonObject("base64_content") {
                                put("type", "string")
                                put("description", "Base64-encoded raw bytes to write, for binary files")
                            }
                            putJsonObject("append") {
                                put("type", "boolean")
                                put("description", "Append instead of replacing (default false)")
                            }
                        },
                    required = listOf("path"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "write_workspace_file"
        private const val TAG = "MCP:WriteWorkspaceFileHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Registration and shared helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Registers every workspace file tool enabled by [perms]. */
fun registerWorkspaceTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    workspaceRegistrations(registrar, context, toolNamePrefix).forEach { (toolName, register) ->
        if (perms.isToolEnabled(toolName)) register()
    }
}

/** Pairs each tool name with the closure that registers it, keeping registration branch-free. */
private fun workspaceRegistrations(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
): List<Pair<String, () -> Unit>> =
    listOf(
        ListWorkspaceFilesHandler.TOOL_NAME to
            { ListWorkspaceFilesHandler(context).register(registrar, toolNamePrefix) },
        MakeDirectoryHandler.TOOL_NAME to
            { MakeDirectoryHandler(context).register(registrar, toolNamePrefix) },
        CopyPathHandler.TOOL_NAME to
            { CopyPathHandler(context).register(registrar, toolNamePrefix) },
        MovePathHandler.TOOL_NAME to
            { MovePathHandler(context).register(registrar, toolNamePrefix) },
        DeletePathHandler.TOOL_NAME to
            { DeletePathHandler(context).register(registrar, toolNamePrefix) },
        GetPathInfoHandler.TOOL_NAME to
            { GetPathInfoHandler(context).register(registrar, toolNamePrefix) },
        ComputeFileHashHandler.TOOL_NAME to
            { ComputeFileHashHandler(context).register(registrar, toolNamePrefix) },
        FindFilesHandler.TOOL_NAME to
            { FindFilesHandler(context).register(registrar, toolNamePrefix) },
        SearchInFilesHandler.TOOL_NAME to
            { SearchInFilesHandler(context).register(registrar, toolNamePrefix) },
        ZipPathHandler.TOOL_NAME to
            { ZipPathHandler(context).register(registrar, toolNamePrefix) },
        UnzipFileHandler.TOOL_NAME to
            { UnzipFileHandler(context).register(registrar, toolNamePrefix) },
        ReadWorkspaceFileHandler.TOOL_NAME to
            { ReadWorkspaceFileHandler(context).register(registrar, toolNamePrefix) },
        WriteWorkspaceFileHandler.TOOL_NAME to
            { WriteWorkspaceFileHandler(context).register(registrar, toolNamePrefix) },
    )

/**
 * The app's private workspace: a canonical-root sandbox that rejects any path which would resolve
 * outside it, so every tool here is confined to files the app itself owns.
 */
private object Workspace {
    fun root(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    fun resolve(
        context: Context,
        path: String,
    ): File {
        val rootDirectory = root(context).canonicalFile
        val relativePath = path.trim().removePrefix(File.separator).removePrefix("/")
        val candidate = File(rootDirectory, relativePath).canonicalFile
        if (candidate != rootDirectory && !candidate.path.startsWith(rootDirectory.path + File.separator)) {
            throw McpToolException.InvalidParams(
                "Path '$path' resolves outside the app workspace, which is not allowed",
            )
        }
        return candidate
    }

    fun relative(
        context: Context,
        file: File,
    ): String {
        val rootPath = root(context).canonicalPath
        val filePath = file.canonicalPath
        return if (filePath == rootPath) "" else filePath.removePrefix(rootPath + File.separator)
    }

    fun describe(
        context: Context,
        file: File,
    ): JsonObject =
        buildJsonObject {
            put("name", file.name)
            put("path", relative(context, file))
            put("is_directory", file.isDirectory)
            put("size_bytes", if (file.isFile) file.length() else null)
            put("last_modified_ms", file.lastModified())
            put("mime_type", if (file.isFile) mimeTypeOf(file) else null)
        }

    fun hash(
        file: File,
        algorithm: String,
    ): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { stream ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Total number of files and directories beneath [file], counting [file] itself as one. */
private fun countEntries(file: File): Int =
    if (file.isDirectory) {
        file.walkTopDown().count()
    } else {
        1
    }

/** Guesses a MIME type from the file's extension. */
private fun mimeTypeOf(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        ?: DEFAULT_MIME_TYPE

/** Builds the archive at [destination] containing [source], preserving relative structure. */
private fun archive(
    source: File,
    destination: File,
) {
    ZipOutputStream(destination.outputStream().buffered()).use { zip ->
        val rootDirectory = if (source.isDirectory) source else source.parentFile ?: source
        val entries =
            if (source.isDirectory) {
                source.walkTopDown().toList()
            } else {
                listOf(source)
            }
        entries.forEach { entry ->
            val entryName = relativeZipName(rootDirectory, entry, source)
            zip.putNextEntry(ZipEntry(if (entry.isDirectory) "$entryName/" else entryName))
            if (entry.isFile) {
                entry.inputStream().use { it.copyTo(zip) }
            }
            zip.closeEntry()
        }
    }
}

/** Computes a forward-slash entry name for [entry] relative to the archive root. */
private fun relativeZipName(
    rootDirectory: File,
    entry: File,
    source: File,
): String {
    if (!source.isDirectory && entry == source) return source.name
    return rootDirectory
        .toURI()
        .relativize(entry.toURI())
        .path
        .ifEmpty { entry.name }
}

/** Extracts [source] into [destination], refusing entries that would escape it. */
private fun extract(
    source: File,
    destination: File,
    overwrite: Boolean,
): Int {
    val destinationPath = destination.canonicalPath
    var entryCount = 0
    ZipInputStream(source.inputStream().buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val target = File(destination, entry.name).canonicalFile
            if (target != destination && !target.path.startsWith(destinationPath + File.separator)) {
                throw McpToolException.ActionFailed(
                    "Archive entry '${entry.name}' would be written outside the destination directory",
                )
            }
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                if (overwrite || !target.exists()) {
                    target.outputStream().buffered().use { output -> zip.copyTo(output) }
                    entryCount++
                }
            }
            zip.closeEntry()
        }
    }
    return entryCount
}

/** Converts a simple glob (`*` and `?`) into a case-insensitive regular expression. */
private fun globToRegex(pattern: String): Regex {
    val builder = StringBuilder("^")
    pattern.forEach { character ->
        when (character) {
            '*' -> builder.append("[^/]*")
            '?' -> builder.append("[^/]")
            else -> builder.append(Regex.escape(character.toString()))
        }
    }
    builder.append('$')
    return Regex(builder.toString(), RegexOption.IGNORE_CASE)
}

private const val DEFAULT_MIME_TYPE = "application/octet-stream"
private const val DEFAULT_LIST_LIMIT = 200
private const val MAX_LIST_LIMIT = 1_000
private const val DEFAULT_FIND_LIMIT = 100
private const val MAX_FIND_LIMIT = 1_000
private const val DEFAULT_SEARCH_LIMIT = 50
private const val MAX_SEARCH_LIMIT = 500
private const val DEFAULT_MAX_DEPTH = 10
private const val MAX_MAX_DEPTH = 64
private const val DEFAULT_READ_OFFSET = 1
private const val DEFAULT_READ_LINES = 200
private const val MAX_READ_LINES = 2_000
private const val DEFAULT_MAX_READ_BYTES = 1_048_576L
private const val MAX_MAX_READ_BYTES = 16_777_216L
private const val MAX_SEARCHED_FILE_BYTES = 1_048_576L
private const val MAX_MATCHED_LINE_CHARS = 400
private const val HASH_BUFFER_BYTES = 16_384
private const val LINE_NUMBER_BASE = 1
