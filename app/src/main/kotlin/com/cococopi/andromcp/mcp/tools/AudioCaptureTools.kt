@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.cococopi.andromcp.mcp.tools

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.mcp.McpToolException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10

/**
 * Audio-capture tool bundle.
 *
 * Hearing is the half of the device an agent normally has no access to. These tools capture it:
 * record a meeting or a voice memo straight to a file, watch the current noise floor to decide
 * whether the room is quiet enough for speech, inspect which microphones the device actually
 * exposes, and clean up recordings afterwards.
 *
 * Recording goes to this app's own external files directory, so it needs no storage permission and
 * nothing is left behind in the user's library unless the agent explicitly moves or scans it.
 * Capture needs the `RECORD_AUDIO` runtime permission, which every tool checks before using the
 * microphone and names in its error when it is missing.
 */

/** Directory that holds recordings produced by this bundle. */
private fun recordingDirectory(context: Context): File {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return File(base, "recordings").apply { mkdirs() }
}

/** Turns a caller-supplied recording name into a safe file inside [recordingDirectory]. */
private fun recordingFile(
    context: Context,
    requestedName: String,
): File {
    val directory = recordingDirectory(context)
    val raw = requestedName.trim().ifEmpty { "recording" }
    val sanitized = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(MAX_NAME_CHARS)
    val withExtension = if (sanitized.endsWith(".m4a", ignoreCase = true)) sanitized else "$sanitized.m4a"
    return File(directory, withExtension)
}

/** The one recording in flight, if any. */
internal object AudioRecording {
    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAtMs: Long = 0L

    /** True while a recording is running. */
    val isActive: Boolean get() = recorder != null

    /** Starts recording into [file]; throws when a recording is already running. */
    @Suppress("TooGenericExceptionCaught")
    fun start(
        context: Context,
        file: File,
    ) {
        if (recorder != null) {
            throw McpToolException.ActionFailed(
                "A recording is already in progress (${target?.name ?: "unknown"}). Call " +
                    "stop_audio_recording before starting another one.",
            )
        }
        val created = MediaRecorder(context)
        try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioEncodingBitRate(AUDIO_BIT_RATE)
            created.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            created.setOutputFile(file.absolutePath)
            created.prepare()
            created.start()
        } catch (exception: Exception) {
            runCatching { created.release() }
            throw McpToolException.ActionFailed(
                "Could not start recording to '${file.absolutePath}': " +
                    (exception.message ?: exception.javaClass.simpleName) +
                    ". The microphone may be in use by another app, or RECORD_AUDIO may not be granted.",
            )
        }
        recorder = created
        target = file
        startedAtMs = System.currentTimeMillis()
    }

    /** Stops the running recording; returns null when nothing was running. */
    fun stop(): File? {
        val active = recorder ?: return null
        runCatching { active.stop() }
        runCatching { active.release() }
        recorder = null
        val file = target
        target = null
        startedAtMs = 0L
        return file
    }

    /** Status snapshot for `get_recording_state`. */
    fun status(): JsonObject =
        buildJsonObject {
            put("recording", recorder != null)
            put("file", target?.absolutePath.orEmpty())
            put("file_name", target?.name.orEmpty())
            put("elapsed_ms", if (startedAtMs == 0L) 0L else System.currentTimeMillis() - startedAtMs)
            put("bytes_written", target?.length() ?: 0L)
        }

    private const val AUDIO_BIT_RATE = 128_000
    private const val AUDIO_SAMPLE_RATE = 44_100
}

// ─────────────────────────────────────────────────────────────────────────────
// start_audio_recording
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `start_audio_recording`.
 * Input: `{ "name": <string>?, "duration_seconds": <int>? }`.
 */
class StartAudioRecordingHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val name = McpToolUtils.optionalString(arguments, "name", "")
        val durationSeconds = McpToolUtils.optionalInt(arguments, "duration_seconds", 0)
        RuntimePermissionGate.require(context, Manifest.permission.RECORD_AUDIO, TOOL_NAME)
        Log.d(TAG, "Executing start_audio_recording")

        val file = recordingFile(context, name)
        withContext(Dispatchers.IO) { AudioRecording.start(context, file) }
        val json =
            buildJsonObject {
                put("recording", true)
                put("file_name", file.name)
                put("path", file.absolutePath)
                put("note", "Call stop_audio_recording to finish the file, or list_recordings to find it later.")
            }
        val result = McpToolUtils.textResult(json.toString())
        if (durationSeconds <= 0) return result
        val waitMs = durationSeconds.toLong() * 1000L
        val longRecording = durationSeconds > MAX_INLINE_SECONDS
        val jsonWithDuration =
            buildJsonObject {
                put("recording", true)
                put("file_name", file.name)
                put("path", file.absolutePath)
                put("requested_duration_seconds", durationSeconds)
                put("long_recording", longRecording)
                put(
                    "note",
                    if (longRecording) {
                        "Recording started and left running: a $durationSeconds-second capture is too long " +
                            "to wait inside one call. Poll get_recording_state, then call stop_audio_recording."
                    } else {
                        "Recording started; it was stopped automatically after $durationSeconds seconds."
                    },
                )
            }
        if (longRecording) return McpToolUtils.textResult(jsonWithDuration.toString())
        delay(waitMs)
        val stopped = withContext(Dispatchers.IO) { AudioRecording.stop() }
        val finished =
            buildJsonObject {
                put("recording", false)
                put("file_name", stopped?.name.orEmpty())
                put("path", stopped?.absolutePath.orEmpty())
                put("size_bytes", stopped?.length() ?: 0L)
                put("duration_seconds", durationSeconds)
            }
        return McpToolUtils.textResult(finished.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Starts recording from the microphone into an AAC (.m4a) file and returns its path. " +
                    "By default it starts and returns immediately, leaving the recording running until " +
                    "stop_audio_recording — that is the right shape for a long capture you intend to " +
                    "poll. Pass 'duration_seconds' for a self-contained clip: short captures " +
                    "(up to $MAX_INLINE_SECONDS seconds) are stopped automatically before the call " +
                    "returns, and longer ones keep running so the call cannot time out. Needs the " +
                    "RECORD_AUDIO permission, and fails clearly if another app already holds the " +
                    "microphone. Only one recording can run at a time.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("name") {
                                put("type", "string")
                                put("description", "Base file name without extension (default \"recording\")")
                            }
                            putJsonObject("duration_seconds") {
                                put("type", "integer")
                                put("description", "Record for this many seconds (default: until stop_audio_recording)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "start_audio_recording"
        private const val TAG = "MCP:StartAudioRecordingHandler"

        /** Longest capture that is waited for inside the call instead of being left running. */
        private const val MAX_INLINE_SECONDS = 60
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// stop_audio_recording
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `stop_audio_recording`. Input: `{}`. */
class StopAudioRecordingHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing stop_audio_recording")
        val file = withContext(Dispatchers.IO) { AudioRecording.stop() }
        if (file == null) {
            return McpToolUtils.textResult(
                buildJsonObject {
                    put("stopped", false)
                    put("note", "No recording was running.")
                }.toString(),
            )
        }
        val json =
            buildJsonObject {
                put("stopped", true)
                put("file_name", file.name)
                put("path", file.absolutePath)
                put("size_bytes", file.length())
                put("duration_hint", "Compare with list_recordings to confirm the size looks right.")
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
                "Finishes the recording started by start_audio_recording and returns the final file " +
                    "path and size. Safe when nothing is recording: it reports 'stopped': false rather " +
                    "than failing. Call it before reading the file, sharing it, scanning it into the " +
                    "media library with scan_media_path, or starting another recording — an unstopped " +
                    "recorder keeps the microphone busy and leaves the file header incomplete.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "stop_audio_recording"
        private const val TAG = "MCP:StopAudioRecordingHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_recording_state
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_recording_state`. Input: `{}`. */
class GetRecordingStateHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_recording_state")
        return McpToolUtils.textResult(AudioRecording.status().toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports whether a recording is running, which file it is writing, how long it has " +
                    "been going and how many bytes have landed on disk. Use it to decide when to stop " +
                    "an open-ended recording, to confirm a capture is progressing at all (bytes should " +
                    "grow), or to check the microphone is free before starting something else that " +
                    "needs it, such as a voice call.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_recording_state"
        private const val TAG = "MCP:GetRecordingStateHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// list_recordings
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `list_recordings`. Input: `{ "limit": <int>? }`. */
class ListRecordingsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        Log.d(TAG, "Executing list_recordings")

        val directory = withContext(Dispatchers.IO) { recordingDirectory(context) }
        val files =
            withContext(Dispatchers.IO) {
                directory
                    .listFiles()
                    .orEmpty()
                    .filter { it.isFile }
                    .sortedByDescending { it.lastModified() }
                    .take(limit)
            }
        val json =
            buildJsonObject {
                put("directory", directory.absolutePath)
                put("count", files.size)
                putJsonArray("recordings") {
                    files.forEach { file ->
                        add(
                            buildJsonObject {
                                put("file_name", file.name)
                                put("path", file.absolutePath)
                                put("size_bytes", file.length())
                                put("modified_epoch_ms", file.lastModified())
                                put("size_human", humanSize(file.length()))
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
                "Lists the recordings this app has captured, newest first, with each file's path, " +
                    "size and last-modified time. Use it to find what you recorded earlier in the " +
                    "session, to confirm a capture actually produced bytes, and to pick a file to " +
                    "pass on to another tool. Only this app's own recordings appear — the user's own " +
                    "audio library lives in the media store, so search it with list_media instead.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum recordings to return (default $DEFAULT_LIMIT)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_recordings"
        private const val TAG = "MCP:ListRecordingsHandler"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 500
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// delete_recording
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `delete_recording`. Input: `{ "file_name": <string> }`. */
class DeleteRecordingHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val fileName = McpToolUtils.requireString(arguments, "file_name").trim()
        Log.d(TAG, "Executing delete_recording")

        val directory = withContext(Dispatchers.IO) { recordingDirectory(context) }
        val file = File(directory, File(fileName).name)
        if (!file.exists()) {
            throw McpToolException.InvalidParams(
                "No recording named '$fileName' in ${directory.absolutePath}. Use list_recordings to see them.",
            )
        }
        val size = file.length()
        val deleted = withContext(Dispatchers.IO) { file.delete() }
        if (!deleted) {
            throw McpToolException.ActionFailed("Could not delete '${file.absolutePath}'.")
        }
        val json =
            buildJsonObject {
                put("deleted", true)
                put("file_name", file.name)
                put("freed_bytes", size)
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
                "Deletes one recording produced by this app, freeing its bytes. Pass just the file " +
                    "name (path separators are ignored, so nothing outside the recordings directory " +
                    "can be touched). Use it to keep a long session from filling storage with drafts " +
                    "the agent no longer needs; keep anything worth preserving, because deletion is " +
                    "permanent and there is no undo.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("file_name") {
                                put("type", "string")
                                put("description", "File name as returned by list_recordings")
                            }
                        },
                    required = listOf("file_name"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "delete_recording"
        private const val TAG = "MCP:DeleteRecordingHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// measure_noise_level
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `measure_noise_level`.
 * Input: `{ "duration_ms": <int>? }`.
 */
class MeasureNoiseLevelHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val durationMs = McpToolUtils.optionalInt(arguments, "duration_ms", DEFAULT_DURATION_MS)
            .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        RuntimePermissionGate.require(context, Manifest.permission.RECORD_AUDIO, TOOL_NAME)
        Log.d(TAG, "Executing measure_noise_level")

        val samples =
            withContext(Dispatchers.IO) {
                AudioLevelSampler.captureSamples(durationMs)
            }
        val json =
            buildJsonObject {
                put("duration_ms", durationMs)
                put("sample_count", samples.size)
                put("peak_amplitude", samples.maxOrNull() ?: 0)
                put("average_amplitude", if (samples.isEmpty()) 0 else samples.average().toInt())
                put("peak_dbfs", round1(dbfs(samples.maxOrNull() ?: 0)))
                put("average_dbfs", round1(dbfs(if (samples.isEmpty()) 0 else samples.average())))
                put("interpretation", interpret(samples.maxOrNull() ?: 0))
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
                "Samples the microphone for a moment and reports how loud the room is, as peak and " +
                    "average amplitude plus dBFS (0 = loudest possible, lower is quieter) and a plain " +
                    "assessment such as \"quiet\" or \"loud\". Use it before a task that depends on " +
                    "hearing anything — dictating, recording a voice memo, waiting for a spoken " +
                    "answer — to check the room is workable, and afterwards to verify the microphone " +
                    "picked something up at all. Audio is measured and discarded, never saved; use " +
                    "start_audio_recording when you need the sound itself. Needs RECORD_AUDIO.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("duration_ms") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Sampling window in milliseconds (default $DEFAULT_DURATION_MS, " +
                                        "max $MAX_DURATION_MS)",
                                )
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "measure_noise_level"
        private const val TAG = "MCP:MeasureNoiseLevelHandler"
        private const val DEFAULT_DURATION_MS = 2000
        private const val MIN_DURATION_MS = 200
        private const val MAX_DURATION_MS = 30_000
    }
}

/** Converts a 16-bit PCM amplitude into a rough plain-language loudness band. */
private fun interpret(peakAmplitude: Int): String =
    when {
        peakAmplitude <= SILENT_CEILING -> "silent (microphone hears almost nothing)"
        peakAmplitude <= QUIET_CEILING -> "quiet"
        peakAmplitude <= NORMAL_CEILING -> "normal speech level"
        else -> "loud"
    }

/** Computes dBFS from a 16-bit PCM amplitude. */
private fun dbfs(amplitude: Double): Double {
    if (amplitude <= 0.0) return SILENCE_DBFS
    return 20.0 * log10(amplitude / MAX_PCM_AMPLITUDE)
}

private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

/** Formats a byte count the way the other bundles do. */
private fun humanSize(bytes: Long): String =
    when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GiB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.2f MiB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

/** Reads short chunks of microphone audio purely to measure loudness. */
internal object AudioLevelSampler {
    /** Peak amplitude of every 50 ms window captured during [durationMs]. */
    @Suppress("TooGenericExceptionCaught")
    fun captureSamples(durationMs: Int): List<Int> {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            throw McpToolException.ActionFailed(
                "This device does not support microphone capture at $SAMPLE_RATE Hz in mono 16-bit.",
            )
        }
        val bufferSize = maxOf(minBuffer, WINDOW_SAMPLES * 2)
        val recorder =
            try {
                AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
            } catch (exception: Exception) {
                throw McpToolException.ActionFailed(
                    "Could not open the microphone: ${exception.message ?: exception.javaClass.simpleName}. " +
                        "Another app may be using it, or RECORD_AUDIO may not be granted.",
                )
            }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            throw McpToolException.ActionFailed(
                "The microphone could not be initialized. RECORD_AUDIO may be missing, or the " +
                    "microphone is held by another app.",
            )
        }
        val samples = mutableListOf<Int>()
        val buffer = ShortArray(WINDOW_SAMPLES)
        try {
            recorder.startRecording()
            val deadline = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < deadline) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                var peak = 0
                for (index in 0 until read) {
                    val value = abs(buffer[index].toInt())
                    if (value > peak) peak = value
                }
                samples.add(peak)
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        return samples
    }

    private const val SAMPLE_RATE = 16_000
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val WINDOW_SAMPLES = 800
}

// ─────────────────────────────────────────────────────────────────────────────
// get_microphone_info
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_microphone_info`. Input: `{}`. */
class GetMicrophoneInfoHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_microphone_info")
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: throw McpToolException.ActionFailed("The audio service is unavailable on this device.")
        val inputs =
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type != AudioDeviceInfo.TYPE_UNKNOWN }
        val json =
            buildJsonObject {
                put("input_device_count", inputs.size)
                put(
                    "record_permission_granted",
                    RuntimePermissionGate.isGranted(context, Manifest.permission.RECORD_AUDIO),
                )
                put("unprocessed_audio_supported", audio.getProperty(UNPROCESSED_AUDIO_PROPERTY) != null)
                put("is_microphone_muted", audio.isMicrophoneMute)
                put("mode", audioModeName(audio.mode))
                putJsonArray("devices") {
                    inputs.forEach { device ->
                        add(
                            buildJsonObject {
                                put("id", device.id)
                                put("name", device.productName.toString())
                                put("type", "Type ${device.type}")
                                put("sample_rates", device.sampleRates.joinToString())
                                put("channel_counts", device.channelCounts.joinToString())
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
                "Describes audio capture on this device: every input device with its type, name and " +
                    "supported sample rates, whether RECORD_AUDIO is currently granted, whether the " +
                    "microphone is muted, and the current audio mode (normal, in call, in " +
                    "communication, ringtone). Use it when a capture fails and you need to know " +
                    "whether the problem is a missing grant, a muted microphone, a busy device, or " +
                    "the wrong audio mode — and to tell a headset microphone apart from the built-in " +
                    "one before recording.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_microphone_info"
        private const val TAG = "MCP:GetMicrophoneInfoHandler"
    }
}

/** AudioManager property that reports whether an unprocessed capture source is available. */
private const val UNPROCESSED_AUDIO_PROPERTY = AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED

/** Human-readable name of an [AudioManager] mode value. */
private fun audioModeName(mode: Int): String =
    when (mode) {
        AudioManager.MODE_NORMAL -> "normal"
        AudioManager.MODE_RINGTONE -> "ringtone"
        AudioManager.MODE_IN_CALL -> "in_call"
        AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
        else -> "unknown($mode)"
    }

/** Longest recording name accepted before it is trimmed to something filesystem-safe. */
private const val MAX_NAME_CHARS = 80

/** Amplitude below which the room counts as silent, in 16-bit PCM units. */
private const val SILENT_CEILING = 120

/** Amplitude below which the room counts as quiet. */
private const val QUIET_CEILING = 1_200

/** Amplitude below which the room counts as normal speech. */
private const val NORMAL_CEILING = 9_000

/** Reported dBFS floor when nothing at all was captured. */
private const val SILENCE_DBFS = -100.0

/** Full-scale 16-bit PCM amplitude. */
private const val MAX_PCM_AMPLITUDE = 32_768.0

/** Registers the audio-capture bundle: recording, inspection and loudness measurement. */
fun registerAudioCaptureTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    StartAudioRecordingHandler(context).register(registrar, toolNamePrefix)
    StopAudioRecordingHandler(context).register(registrar, toolNamePrefix)
    GetRecordingStateHandler(context).register(registrar, toolNamePrefix)
    ListRecordingsHandler(context).register(registrar, toolNamePrefix)
    DeleteRecordingHandler(context).register(registrar, toolNamePrefix)
    MeasureNoiseLevelHandler(context).register(registrar, toolNamePrefix)
    GetMicrophoneInfoHandler(context).register(registrar, toolNamePrefix)
}
