@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.cococopi.andromcp.mcp.tools

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.mcp.McpToolException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Media-library and playback tool bundle.
 *
 * Everything in the media library — photos, videos, recordings, podcasts — is indexed by
 * [MediaStore], which is a far better handle than a filesystem path: an entry has a stable id, a
 * MIME type, dimensions, duration and a bucket, and the same entry is addressable as a `content://`
 * URI that other apps accept. These tools let an agent browse, search, inspect, delete, rescan and
 * play media by id, and control playback and per-stream volume.
 *
 * Reading requires the matching Android 13+ media permission (`READ_MEDIA_IMAGES` /
 * `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO`); each tool reports the exact missing permission instead
 * of failing silently. Changing media uses adaptive permissions, so deleting an item another app
 * owns needs the user's confirmation and reports an actionable error when that is required.
 */

/** Which MediaStore collection a media tool should look in. */
private enum class MediaKind(
    val value: String,
    val permission: String,
    val collection: Uri,
) {
    IMAGE("image", Manifest.permission.READ_MEDIA_IMAGES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
    VIDEO("video", Manifest.permission.READ_MEDIA_VIDEO, MediaStore.Video.Media.EXTERNAL_CONTENT_URI),
    AUDIO("audio", Manifest.permission.READ_MEDIA_AUDIO, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI),
    ;

    companion object {
        /** Parses the `type` parameter; `null` means "every kind the app may read". */
        fun parseOrNull(raw: String?): MediaKind? {
            if (raw.isNullOrBlank() || raw.equals("any", ignoreCase = true)) return null
            return entries.firstOrNull { it.value.equals(raw, ignoreCase = true) }
                ?: throw McpToolException.InvalidParams(
                    "Parameter 'type' must be one of: image, video, audio, any. Got '$raw'.",
                )
        }
    }
}

/** Shared MediaStore access helpers for the media bundle. */
private object MediaLibrary {
    /** Columns requested for a listing — enough to identify and describe an entry, nothing more. */
    val LIST_PROJECTION =
        arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )

    /** Resolves the collections to query and checks the permission each one needs. */
    fun resolveKinds(
        context: Context,
        raw: String?,
        toolName: String,
    ): List<MediaKind> {
        val kind = MediaKind.parseOrNull(raw)
        val kinds = if (kind != null) listOf(kind) else MediaKind.entries.toList()
        kinds.forEach { RuntimePermissionGate.require(context, it.permission, toolName) }
        return kinds
    }

    /** Maps a cursor row to a JSON object. */
    fun rowToJson(
        cursor: android.database.Cursor,
        kind: MediaKind,
    ): JsonObject {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
        val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
        val added = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
        return buildJsonObject {
            put("id", id)
            put("kind", kind.value)
            put("name", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)))
            put("size_bytes", size)
            put("mime_type", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)))
            put("uri", ContentUris.withAppendedId(kind.collection, id).toString())
            put("added_epoch_seconds", added)
            put("relative_path", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)))
        }
    }

    /** Runs a query, collecting at most [limit] rows across every kind in [kinds]. */
    fun query(
        context: Context,
        kinds: List<MediaKind>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String,
        limit: Int,
    ): Pair<List<JsonObject>, Boolean> {
        val items = mutableListOf<JsonObject>()
        var truncated = false
        for (kind in kinds) {
            if (items.size >= limit) {
                truncated = true
                break
            }
            context.contentResolver
                .query(
                    kind.collection,
                    LIST_PROJECTION,
                    selection,
                    selectionArgs,
                    sortOrder,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (items.size >= limit) {
                            truncated = true
                            break
                        }
                        items.add(rowToJson(cursor, kind))
                    }
                }
        }
        return items.toList() to truncated
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// list_media
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_media`.
 * Input: `{ "type": <string>?, "limit": <int>?, "sort": <string>? }`.
 */
class ListMediaHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val type = McpToolUtils.optionalString(arguments, "type", "any")
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val sort = McpToolUtils.optionalString(arguments, "sort", "newest")
        Log.d(TAG, "Executing list_media")

        val kinds = MediaLibrary.resolveKinds(context, type, TOOL_NAME)
        val order =
            when (sort.lowercase()) {
                "newest" -> "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                "oldest" -> "${MediaStore.MediaColumns.DATE_ADDED} ASC"
                "largest" -> "${MediaStore.MediaColumns.SIZE} DESC"
                "smallest" -> "${MediaStore.MediaColumns.SIZE} ASC"
                "name" -> "${MediaStore.MediaColumns.DISPLAY_NAME} ASC"
                else -> throw McpToolException.InvalidParams(
                    "Parameter 'sort' must be one of: newest, oldest, largest, smallest, name. Got '$sort'.",
                )
            }
        val (items, truncated) =
            withContext(Dispatchers.IO) {
                MediaLibrary.query(context, kinds, null, null, order, limit)
            }
        val json =
            buildJsonObject {
                put("type", type)
                put("sort", sort)
                put("count", items.size)
                put("truncated", truncated)
                putJsonArray("items") { items.forEach { add(it) } }
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
                "Lists items from the device media library — photos, videos and audio — with id, " +
                    "name, size, MIME type, relative path and a ready-to-use content:// URI. Filter " +
                    "with 'type' (image, video, audio, or any) and order with 'sort' (newest, oldest, " +
                    "largest, smallest, name). Use it to answer \"what did I take today?\", to find a " +
                    "candidate before get_media_info or delete_media, or to pick something to play. " +
                    "Needs the Android 13+ media permission for the kinds requested; the error names " +
                    "the missing one.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("type") {
                                put("type", "string")
                                put("description", "image, video, audio or any (default any)")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum items to return (default $DEFAULT_LIMIT)")
                            }
                            putJsonObject("sort") {
                                put("type", "string")
                                put("description", "newest, oldest, largest, smallest or name (default newest)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_media"
        private const val TAG = "MCP:ListMediaHandler"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 500
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// search_media
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `search_media`.
 * Input: `{ "query": <string>, "type": <string>?, "limit": <int>? }`.
 */
class SearchMediaHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val query = McpToolUtils.requireString(arguments, "query")
        val type = McpToolUtils.optionalString(arguments, "type", "any")
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        Log.d(TAG, "Executing search_media")

        val kinds = MediaLibrary.resolveKinds(context, type, TOOL_NAME)
        val name = MediaStore.MediaColumns.DISPLAY_NAME
        val path = MediaStore.MediaColumns.RELATIVE_PATH
        val selection = "$name LIKE ? OR $path LIKE ?"
        val args = arrayOf("%$query%", "%$query%")
        val order = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val (items, truncated) =
            withContext(Dispatchers.IO) {
                MediaLibrary.query(context, kinds, selection, args, order, limit)
            }
        val json =
            buildJsonObject {
                put("query", query)
                put("type", type)
                put("count", items.size)
                put("truncated", truncated)
                putJsonArray("items") { items.forEach { add(it) } }
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
                "Finds media whose file name or folder path contains 'query', newest first. Use it " +
                    "when you know what the item is called or where it lives (\"holiday\", \"DCIM/" +
                    "Camera\") but not its id — for example to locate the screenshot just taken, the " +
                    "recording just produced by start_audio_recording, or a photo to attach to a " +
                    "message. Narrow it with 'type' when the name could match several kinds. Results " +
                    "are the same shape as list_media, with a content:// URI ready for playback or " +
                    "sharing.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "Substring of the file name or folder path")
                            }
                            putJsonObject("type") {
                                put("type", "string")
                                put("description", "image, video, audio or any (default any)")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum items to return (default $DEFAULT_LIMIT)")
                            }
                        },
                    required = listOf("query"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "search_media"
        private const val TAG = "MCP:SearchMediaHandler"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 500
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_media_info
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_media_info`.
 * Input: `{ "uri": <string> }` or `{ "id": <int>, "type": <string> }`.
 */
class GetMediaInfoHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val uriArg = McpToolUtils.optionalString(arguments, "uri", "")
        val type = McpToolUtils.optionalString(arguments, "type", "any")
        val target = resolveTarget(uriArg, arguments, type)
        Log.d(TAG, "Executing get_media_info")

        val info =
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver
                        .query(
                            target,
                            FULL_PROJECTION,
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            if (!cursor.moveToFirst()) return@use null
                            buildJsonObject {
                                put("uri", target.toString())
                                for (column in FULL_PROJECTION) {
                                    val index = cursor.getColumnIndex(column)
                                    if (index < 0 || cursor.isNull(index)) continue
                                    put(column.substringAfterLast('.').lowercase(), cursor.getString(index))
                                }
                            }
                        }
                } catch (exception: Exception) {
                    throw McpToolException.ActionFailed(
                        "Could not read media metadata for '$target': " +
                            (exception.message ?: exception.javaClass.simpleName),
                    )
                }
            }
        if (info == null) {
            throw McpToolException.InvalidParams("No media entry found for '$target'.")
        }
        return McpToolUtils.textResult(info.toString())
    }

    /** Accepts either a full media URI or an id plus kind. */
    private fun resolveTarget(
        uriArg: String,
        arguments: JsonObject?,
        type: String,
    ): Uri {
        if (uriArg.isNotEmpty()) return Uri.parse(uriArg)
        val id = McpToolUtils.optionalInt(arguments, "id", -1)
        if (id < 0) {
            throw McpToolException.InvalidParams("Provide either 'uri', or 'id' together with 'type'.")
        }
        val kind =
            MediaKind.parseOrNull(type)
                ?: throw McpToolException.InvalidParams(
                    "Parameter 'type' is required with 'id' and must be image, video or audio.",
                )
        return ContentUris.withAppendedId(kind.collection, id.toLong())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Returns every metadata column MediaStore holds for one media item: display name, " +
                    "size, MIME type, duration for audio and video, width and height for images and " +
                    "video, date taken and added, relative and absolute path, and the owning package. " +
                    "Address the item by its content:// 'uri' (from list_media, search_media or any " +
                    "other tool) or by 'id' plus 'type'. Use it to answer factual questions about a " +
                    "file, to check a recording actually has audio, or to get dimensions before " +
                    "resizing or cropping.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("uri") {
                                put("type", "string")
                                put("description", "content:// media URI returned by list_media or search_media")
                            }
                            putJsonObject("id") {
                                put("type", "integer")
                                put("description", "MediaStore id (use together with 'type' instead of 'uri')")
                            }
                            putJsonObject("type") {
                                put("type", "string")
                                put("description", "image, video or audio — required when using 'id'")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_media_info"
        private const val TAG = "MCP:GetMediaInfoHandler"

        /** Superset of columns; missing ones are skipped, so this works across API levels. */
        private val FULL_PROJECTION =
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.TITLE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.IS_PENDING,
            )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// delete_media
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `delete_media`. Input: `{ "uri": <string> }`. */
class DeleteMediaHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val uri = McpToolUtils.requireString(arguments, "uri")
        Log.d(TAG, "Executing delete_media")

        val target = Uri.parse(uri)
        val deleted =
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.delete(target, null, null)
                } catch (exception: Exception) {
                    throw McpToolException.ActionFailed(
                        "The system refused to delete '$uri': " +
                            (exception.message ?: exception.javaClass.simpleName) +
                            ". Media owned by another app needs the user's confirmation, which this " +
                            "tool cannot show; delete it in the gallery app, or use su_exec on a " +
                            "rooted device.",
                    )
                }
            }
        if (deleted <= 0) {
            throw McpToolException.ActionFailed(
                "Nothing was deleted for '$uri'. The item may already be gone, or this app may not " +
                    "own it. Confirm it still exists with get_media_info.",
            )
        }
        val json =
            buildJsonObject {
                put("uri", uri)
                put("deleted_rows", deleted)
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
                "Deletes a media item from the library by its content:// URI. Fails with an " +
                    "actionable message when the item belongs to another app, because Android then " +
                    "requires a user-confirmed deletion dialog that a tool cannot raise. Always " +
                    "resolve the exact item first with search_media or get_media_info and delete by " +
                    "id rather than by a partially matching name — this is not reversible and there " +
                    "is no trash for it.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("uri") {
                                put("type", "string")
                                put("description", "content:// media URI of the item to delete")
                            }
                        },
                    required = listOf("uri"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "delete_media"
        private const val TAG = "MCP:DeleteMediaHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// scan_media_path
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `scan_media_path`. Input: `{ "path": <string> }`. */
class ScanMediaPathHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val path = McpToolUtils.requireString(arguments, "path").trim()
        Log.d(TAG, "Executing scan_media_path")

        val result =
            withContext(Dispatchers.IO) {
                val scanned = CompletableDeferred<String?>()
                MediaScannerConnection.scanFile(context, arrayOf(path), null) { scannedPath, uri ->
                    scanned.complete(if (uri != null) "$scannedPath|$uri" else null)
                }
                scanned.await()
            }
        val json =
            buildJsonObject {
                put("path", path)
                put("indexed", result != null)
                put("media_uri", result?.substringAfter('|').orEmpty())
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
                "Asks the media scanner to index a file at an absolute path, so it appears in the " +
                    "gallery, the music app and MediaStore queries. Use it after writing a file " +
                    "somewhere the scanner does not watch (for example with shell_exec or " +
                    "write_workspace_file) and before looking that file up with search_media. " +
                    "'indexed': false means the system could not recognize the file as media — check " +
                    "that the path exists and has a recognizable extension.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Absolute path of the media file, e.g. /sdcard/Download/clip.mp4")
                            }
                        },
                    required = listOf("path"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "scan_media_path"
        private const val TAG = "MCP:ScanMediaPathHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// play_media
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `play_media`.
 * Input: `{ "source": <string>, "loop": <bool>?, "volume": <float>? }`.
 */
class PlayMediaHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val source = McpToolUtils.requireString(arguments, "source").trim()
        val loop = McpToolUtils.optionalBoolean(arguments, "loop", false)
        val volume = McpToolUtils.optionalFloat(arguments, "volume", 1.0f).coerceIn(0.0f, 1.0f)
        Log.d(TAG, "Executing play_media")

        val info = MediaPlayback.play(context, source, loop, volume)
        return McpToolUtils.textResult(info.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Plays one audio or video track and says what started. 'source' may be a content:// " +
                    "media URI from list_media, an http(s) URL for streaming, or an absolute file " +
                    "path for something this app can read. Only one track plays at a time: a second " +
                    "play_media call replaces the first, so call stop_media (or check " +
                    "get_playback_state) rather than stacking calls. Pass 'loop': true for ambience " +
                    "such as white noise, and 'volume' between 0 and 1 to override the media volume " +
                    "for this playback only.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("source") {
                                put("type", "string")
                                put("description", "content:// URI, http(s) URL or absolute file path")
                            }
                            putJsonObject("loop") {
                                put("type", "boolean")
                                put("description", "Repeat the track until stopped (default false)")
                            }
                            putJsonObject("volume") {
                                put("type", "number")
                                put("description", "Playback volume from 0.0 to 1.0 (default 1.0)")
                            }
                        },
                    required = listOf("source"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "play_media"
        private const val TAG = "MCP:PlayMediaHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// stop_media
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `stop_media`. Input: `{}`. */
class StopMediaHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing stop_media")
        val info = MediaPlayback.stop()
        return McpToolUtils.textResult(info.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Stops whatever this app is currently playing and releases the player. Safe to call " +
                    "when nothing is playing — it reports 'was_playing': false instead of failing, so " +
                    "it can be used unconditionally to guarantee silence before speaking, recording " +
                    "audio, or making a call.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "stop_media"
        private const val TAG = "MCP:StopMediaHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_playback_state
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_playback_state`. Input: `{}`. */
class GetPlaybackStateHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_playback_state")
        val json = MediaPlayback.state()
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
                "Reports whether this app is playing audio or video right now: the source, whether " +
                    "it is playing, paused or completed, position and duration in milliseconds, the " +
                    "volume, and whether it loops. Use it to decide whether a track has finished " +
                    "before starting the next one, or to confirm sound is actually coming out after " +
                    "play_media — a useful check when the media volume or a focus conflict could " +
                    "silently swallow playback.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_playback_state"
        private const val TAG = "MCP:GetPlaybackStateHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// set_stream_volume
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `set_stream_volume`.
 * Input: `{ "stream": <string>, "percent": <int> }`.
 */
class SetStreamVolumeHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val streamName = McpToolUtils.requireString(arguments, "stream")
        val percent = McpToolUtils.requireInt(arguments, "percent").coerceIn(0, 100)
        Log.d(TAG, "Executing set_stream_volume")

        val stream =
            STREAM_NAMES[streamName.lowercase()]
                ?: throw McpToolException.InvalidParams(
                    "Parameter 'stream' must be one of: ${STREAM_NAMES.keys.joinToString()}. Got '$streamName'.",
                )
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: throw McpToolException.ActionFailed("The audio service is unavailable on this device.")
        val max = audio.getStreamMaxVolume(stream)
        val index = ((percent.toDouble() / 100.0) * max).toInt().coerceIn(0, max)
        try {
            audio.setStreamVolume(stream, index, 0)
        } catch (exception: Exception) {
            throw McpToolException.ActionFailed(
                "Could not set the '$streamName' volume: " +
                    (exception.message ?: exception.javaClass.simpleName) +
                    ". Ring and notification volumes may be controlled by Do Not Disturb access.",
            )
        }
        val json =
            buildJsonObject {
                put("stream", streamName)
                put("requested_percent", percent)
                put("index", index)
                put("max_index", max)
                put("applied_percent", if (max > 0) index * 100 / max else 0)
                put("current_index", audio.getStreamVolume(stream))
                put("muted", audio.isStreamMute(stream))
                put("volume_changed", true)
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
                "Sets one audio stream to a percentage of its maximum and reports the resulting " +
                    "index. 'stream' selects which one: media, ring, notification, alarm, call, " +
                    "system or dtmf — the distinction matters, because muting 'media' does not mute " +
                    "an incoming call and silencing 'ring' is not the same as DND. Use it when you " +
                    "need a specific stream changed (set_volume works on the media stream by " +
                    "default), for example before recording audio to avoid feedback, or to make an " +
                    "alarm audible. Percentage 0 mutes that stream.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("stream") {
                                put("type", "string")
                                put("description", "media, ring, notification, alarm, call, system or dtmf")
                            }
                            putJsonObject("percent") {
                                put("type", "integer")
                                put("description", "Target volume as a percentage of the maximum, 0-100")
                            }
                        },
                    required = listOf("stream", "percent"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "set_stream_volume"
        private const val TAG = "MCP:SetStreamVolumeHandler"

        /** Caller-facing stream names mapped to the platform constants. */
        private val STREAM_NAMES =
            mapOf(
                "media" to AudioManager.STREAM_MUSIC,
                "ring" to AudioManager.STREAM_RING,
                "notification" to AudioManager.STREAM_NOTIFICATION,
                "alarm" to AudioManager.STREAM_ALARM,
                "call" to AudioManager.STREAM_VOICE_CALL,
                "system" to AudioManager.STREAM_SYSTEM,
                "dtmf" to AudioManager.STREAM_DTMF,
            )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// list_media_directories
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_media_directories`.
 * Input: `{ "type": <string>?, "limit": <int>? }`.
 */
class ListMediaDirectoriesHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val type = McpToolUtils.optionalString(arguments, "type", "any")
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        Log.d(TAG, "Executing list_media_directories")

        val kinds = MediaLibrary.resolveKinds(context, type, TOOL_NAME)
        val buckets =
            withContext(Dispatchers.IO) {
                val found = mutableMapOf<String, Int>()
                for (kind in kinds) {
                    context.contentResolver
                        .query(
                            kind.collection,
                            arrayOf(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns._ID),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            val bucketIndex = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                            if (bucketIndex < 0) return@use
                            while (cursor.moveToNext()) {
                                val bucket = cursor.getString(bucketIndex) ?: continue
                                found[bucket] = (found[bucket] ?: 0) + 1
                            }
                        }
                }
                found
            }
        val entries = buckets.entries.sortedByDescending { it.value }.take(limit)
        val json =
            buildJsonObject {
                put("type", type)
                put("directory_count", buckets.size)
                put("returned", entries.size)
                putJsonArray("directories") {
                    entries.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("name", entry.key)
                                put("item_count", entry.value)
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
                "Summarizes the media library by folder: each directory name with how many items it " +
                    "holds, busiest first. Use it for orientation before a large search — it shows at " +
                    "a glance which folders actually contain media, so you can aim " +
                    "search_media at the right one instead of paging through everything, and it " +
                    "confirms that a folder the agent wrote to was indexed at all.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("type") {
                                put("type", "string")
                                put("description", "image, video, audio or any (default any)")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum directories to return (default $DEFAULT_LIMIT)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_media_directories"
        private const val TAG = "MCP:ListMediaDirectoriesHandler"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 500
    }
}

/**
 * Single shared media player plus the current playback state.
 *
 * [android.media.MediaPlayer] builds a message handler on the calling thread, so every player call
 * is bounced onto the main dispatcher; keeping one instance in a process-wide object is what makes
 * stop_media and get_playback_state meaningful from separate tool calls.
 */
internal object MediaPlayback {
    private var player: MediaPlayer? = null
    private var currentSource: String = ""
    private var loopEnabled: Boolean = false
    private var requestedVolume: Float = 1.0f

    /** Starts [source] (URI, URL or path), replacing any previous playback. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun play(
        context: Context,
        source: String,
        loop: Boolean,
        volume: Float,
    ): JsonObject =
        withContext(Dispatchers.Main) {
            releaseQuietly()
            val mediaPlayer = MediaPlayer()
            try {
                mediaPlayer.setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                mediaPlayer.isLooping = loop
                mediaPlayer.setVolume(volume, volume)
                when {
                    source.startsWith("http://") || source.startsWith("https://") -> mediaPlayer.setDataSource(source)
                    source.startsWith("content://") || source.startsWith("file://") ->
                        mediaPlayer.setDataSource(context, Uri.parse(source))
                    else -> mediaPlayer.setDataSource(source)
                }
                mediaPlayer.prepare()
                mediaPlayer.start()
            } catch (exception: Exception) {
                runCatching { mediaPlayer.release() }
                throw McpToolException.ActionFailed(
                    "Could not play '$source': ${exception.message ?: exception.javaClass.simpleName}. " +
                        "Check that the URI resolves, the URL is reachable, and for a plain path that " +
                        "this app can read the file (search_media returns a content:// URI that always works).",
                )
            }
            player = mediaPlayer
            currentSource = source
            loopEnabled = loop
            requestedVolume = volume
            buildJsonObject {
                put("playing", true)
                put("source", source)
                put("loop", loop)
                put("volume", volume.toDouble())
                put("duration_ms", mediaPlayer.duration.toLong())
            }
        }

    /** Stops and releases the player; returns a small status object rather than throwing. */
    suspend fun stop(): JsonObject =
        withContext(Dispatchers.Main) {
            val wasPlaying = runCatching { player?.isPlaying == true }.getOrDefault(false)
            val source = currentSource
            releaseQuietly()
            buildJsonObject {
                put("was_playing", wasPlaying)
                put("stopped_source", source)
                put("playing", false)
            }
        }

    /** Current playback state, without changing it. */
    suspend fun state(): JsonObject =
        withContext(Dispatchers.Main) {
            val mediaPlayer = player
            buildJsonObject {
                put("has_player", mediaPlayer != null)
                put("playing", runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false))
                put("source", currentSource)
                put("loop", loopEnabled)
                put("volume", requestedVolume.toDouble())
                put("position_ms", mediaPlayer?.currentPosition?.toLong() ?: 0L)
                put("duration_ms", mediaPlayer?.duration?.toLong() ?: 0L)
            }
        }

    private fun releaseQuietly() {
        val mediaPlayer = player ?: return
        runCatching { if (mediaPlayer.isPlaying) mediaPlayer.stop() }
        runCatching { mediaPlayer.reset() }
        runCatching { mediaPlayer.release() }
        player = null
        currentSource = ""
        loopEnabled = false
        requestedVolume = 1.0f
    }
}

/** Registers the media bundle: library browsing, metadata, deletion, rescan, playback and volume. */
fun registerMediaTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    ListMediaHandler(context).register(registrar, toolNamePrefix)
    SearchMediaHandler(context).register(registrar, toolNamePrefix)
    GetMediaInfoHandler(context).register(registrar, toolNamePrefix)
    DeleteMediaHandler(context).register(registrar, toolNamePrefix)
    ScanMediaPathHandler(context).register(registrar, toolNamePrefix)
    PlayMediaHandler(context).register(registrar, toolNamePrefix)
    StopMediaHandler(context).register(registrar, toolNamePrefix)
    GetPlaybackStateHandler(context).register(registrar, toolNamePrefix)
    SetStreamVolumeHandler(context).register(registrar, toolNamePrefix)
    ListMediaDirectoriesHandler(context).register(registrar, toolNamePrefix)
}
