@file:Suppress("MatchingDeclarationName")

package com.cococopi.andromcp.mcp.tools

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.mcp.McpToolException
import com.cococopi.andromcp.services.intents.IntentDispatcher
import com.cococopi.andromcp.services.intents.SendIntentRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Device Services tool bundle.
//
// Turns the phone's own hardware and system services into MCP tools: speaking aloud through the
// TTS engine, controlling media playback and volume, reading battery state, driving the torch,
// reporting device identity, and setting alarms/timers/phone numbers via the system clock and
// dialer apps.
//
// None of these tools require a runtime permission: they use public system APIs and delegate to
// the stock Clock and Dialer apps through [IntentDispatcher]. The only pre-existing exceptions are
// streams that the device may restrict to the notification-policy grant (e.g. the `ring` and
// `alarm` streams on Stock Android); those surface as a permission error rather than a silent
// no-op.

// ─────────────────────────────────────────────────────────────────────────────
// speak_text
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `speak_text`.
 *
 * Speaks [text] aloud on the device through the platform text-to-speech engine so a remote agent
 * can talk to whoever is holding the phone, hands-free.
 *
 * **Input**: `{ "text": "<string>", "interrupt": <bool>?, "timeout_ms": <int>? }`
 * **Output**: text confirmation.
 */
class SpeakTextHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val text = McpToolUtils.requireString(arguments, "text")
        if (text.isBlank()) {
            throw McpToolException.InvalidParams("Parameter 'text' must not be blank")
        }
        val interrupt = McpToolUtils.optionalBoolean(arguments, "interrupt", true)
        val timeoutMs = McpToolUtils.optionalLong(arguments, "timeout_ms", DEFAULT_TIMEOUT_MS)
        McpToolUtils.validatePositiveRange(timeoutMs, "timeout_ms", MAX_TIMEOUT_MS)

        Log.d(TAG, "Executing speak_text (${text.length} chars, interrupt=$interrupt)")

        val initialised = CompletableDeferred<Boolean>()
        // The init listener must be supplied at construction; it only signals this deferred, so it
        // never needs to reference the (not-yet-assigned) engine instance.
        val tts =
            TextToSpeech(context.applicationContext) { status ->
                initialised.complete(status == TextToSpeech.SUCCESS)
            }

        try {
            val engineReady = withTimeoutOrNull(INIT_TIMEOUT_MS) { initialised.await() } ?: false
            if (!engineReady) {
                throw McpToolException.ActionFailed(
                    "No text-to-speech engine is available on this device",
                )
            }

            val queueMode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD

            val finished = CompletableDeferred<Boolean>()
            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        finished.complete(true)
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        finished.complete(false)
                    }

                    override fun onError(
                        utteranceId: String?,
                        errorCode: Int,
                    ) {
                        finished.complete(false)
                    }
                },
            )

            tts.speak(text, queueMode, null, UTTERANCE_ID)
            val spoken = withTimeoutOrNull(timeoutMs) { finished.await() } ?: false
            if (!spoken) {
                throw McpToolException.Timeout("Speech did not finish within ${timeoutMs}ms")
            }
            return McpToolUtils.textResult("Spoken ${text.length} characters")
        } finally {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
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
                "Speaks text aloud on the device using the platform text-to-speech engine. Use it " +
                    "to have the phone talk to the person holding it, to read something out loud, or " +
                    "to give spoken confirmation. Parameter 'interrupt': if true (default) any speech " +
                    "already playing is cut off, otherwise the text is queued behind it. The call " +
                    "returns once speech finishes, bounded by 'timeout_ms' (default 60000, max " +
                    "120000). Requires a text-to-speech engine to be installed and enabled on the " +
                    "device (present on all standard Android builds).",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("text") {
                                put("type", "string")
                                put("description", "The text to speak aloud")
                            }
                            putJsonObject("interrupt") {
                                put("type", "boolean")
                                put(
                                    "description",
                                    "If true (default), stop any speech already in progress. " +
                                        "If false, queue this text behind it.",
                                )
                            }
                            putJsonObject("timeout_ms") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Maximum time to wait for speech to finish, in milliseconds " +
                                        "(default 60000, range 1-120000).",
                                )
                            }
                        },
                    required = listOf("text"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "speak_text"
        private const val TAG = "MCP:SpeakTextHandler"
        private const val UTTERANCE_ID = "andromcp-speak"
        private const val INIT_TIMEOUT_MS = 10_000L
        private const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val MAX_TIMEOUT_MS = 120_000L
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_volume
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_volume`.
 *
 * **Input**: `{ "stream": "<string>"? }`
 * **Output**: JSON object with `stream`, `index`, `max_index` and `percent`.
 */
class GetVolumeHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val streamName = McpToolUtils.optionalString(arguments, "stream", DEFAULT_STREAM)
        val streamType = STREAM_TYPES[streamName] ?: throw invalidStream(streamName)
        val audioManager = audioManager()

        val index = audioManager.getStreamVolume(streamType)
        val maxIndex = audioManager.getStreamMaxVolume(streamType)
        val percent = if (maxIndex > 0) index * PERCENT_SCALE / maxIndex else 0

        Log.d(TAG, "Executing get_volume for stream=$streamName")
        val json =
            buildJsonObject {
                put("stream", streamName)
                put("index", index)
                put("max_index", maxIndex)
                put("percent", percent)
                put("muted", index == 0)
            }
        return McpToolUtils.textResult(json.toString())
    }

    private fun audioManager(): AudioManager =
        context.getSystemService(AudioManager::class.java)
            ?: throw McpToolException.InternalError("AudioManager is unavailable on this device")

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reads the current volume of an audio stream. Returns the absolute index, the " +
                    "stream's maximum index, a 0-100 percentage and whether the stream is muted. " +
                    "Parameter 'stream' selects the stream: 'music' (default), 'ring', 'alarm', " +
                    "'notification', 'system' or 'voice_call'. No permission required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("stream") {
                                put("type", "string")
                                put("description", STREAM_DESCRIPTION)
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_volume"
        private const val TAG = "MCP:GetVolumeHandler"
        private const val PERCENT_SCALE = 100
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// set_volume
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `set_volume`.
 *
 * **Input**: `{ "level": <int>, "stream": "<string>"?, "show_ui": <bool>? }`
 * **Output**: text confirmation.
 */
class SetVolumeHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val level = McpToolUtils.requireInt(arguments, "level")
        if (level !in 0..PERCENT_SCALE) {
            throw McpToolException.InvalidParams(
                "Parameter 'level' must be a percentage between 0 and 100, got: $level",
            )
        }
        val streamName = McpToolUtils.optionalString(arguments, "stream", DEFAULT_STREAM)
        val streamType = STREAM_TYPES[streamName] ?: throw invalidStream(streamName)
        val showUi = McpToolUtils.optionalBoolean(arguments, "show_ui", false)

        val audioManager =
            context.getSystemService(AudioManager::class.java)
                ?: throw McpToolException.InternalError("AudioManager is unavailable on this device")

        val maxIndex = audioManager.getStreamMaxVolume(streamType)
        val targetIndex = Math.round(level * maxIndex / PERCENT_SCALE.toFloat())
        val flags = if (showUi) AudioManager.FLAG_SHOW_UI else 0

        Log.d(TAG, "Executing set_volume: stream=$streamName, level=$level")
        try {
            audioManager.setStreamVolume(streamType, targetIndex.coerceIn(0, maxIndex), flags)
        } catch (e: SecurityException) {
            throw McpToolException.PermissionDenied(
                "Setting the '$streamName' stream is restricted on this device " +
                    "(Do Not Disturb access may be required): ${e.message}",
            )
        }
        return McpToolUtils.textResult(
            "Set '$streamName' volume to ${targetIndex.coerceIn(0, maxIndex)}/$maxIndex ($level%)",
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
                "Sets the volume of an audio stream. Parameter 'level' is a percentage from 0 to " +
                    "100. Parameter 'stream' selects the stream: 'music' (default), 'ring', 'alarm', " +
                    "'notification', 'system' or 'voice_call'. Parameter 'show_ui': if true, the " +
                    "system volume panel is shown (default false). Setting the 'ring', " +
                    "'notification' or 'alarm' stream may require Do Not Disturb access on some " +
                    "devices.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("level") {
                                put("type", "integer")
                                put("description", "Target volume as a percentage from 0 to 100")
                            }
                            putJsonObject("stream") {
                                put("type", "string")
                                put("description", STREAM_DESCRIPTION)
                            }
                            putJsonObject("show_ui") {
                                put("type", "boolean")
                                put(
                                    "description",
                                    "If true, show the system volume panel while changing the " +
                                        "volume (default false).",
                                )
                            }
                        },
                    required = listOf("level"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "set_volume"
        private const val TAG = "MCP:SetVolumeHandler"
        private const val PERCENT_SCALE = 100
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// media_control
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `media_control`.
 *
 * Dispatches a media key event so the phone's active media session reacts, regardless of which
 * app owns playback.
 *
 * **Input**: `{ "action": "play|pause|play_pause|next|previous|stop|fast_forward|rewind" }`
 * **Output**: text confirmation.
 */
class MediaControlHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val action = McpToolUtils.requireString(arguments, "action")
        val keyCode =
            MEDIA_KEY_CODES[action]
                ?: throw McpToolException.InvalidParams(
                    "Parameter 'action' must be one of ${MEDIA_KEY_CODES.keys.sorted()}, got: '$action'",
                )

        val audioManager =
            context.getSystemService(AudioManager::class.java)
                ?: throw McpToolException.InternalError("AudioManager is unavailable on this device")

        Log.d(TAG, "Executing media_control: action=$action")
        try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (e: SecurityException) {
            throw McpToolException.PermissionDenied(
                "Dispatching media keys is not allowed on this device: ${e.message}",
            )
        }
        return McpToolUtils.textResult("Dispatched media action '$action'")
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Controls media playback on the device by dispatching a media key event to the " +
                    "active media session. Works no matter which app is playing. Parameter " +
                    "'action' is one of: 'play', 'pause', 'play_pause', 'next', 'previous', " +
                    "'stop', 'fast_forward', 'rewind'. No permission required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("action") {
                                put("type", "string")
                                put(
                                    "description",
                                    "The media action to dispatch: 'play', 'pause', 'play_pause', " +
                                        "'next', 'previous', 'stop', 'fast_forward' or 'rewind'.",
                                )
                            }
                        },
                    required = listOf("action"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "media_control"
        private const val TAG = "MCP:MediaControlHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_battery_status
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_battery_status`.
 *
 * **Input**: none
 * **Output**: JSON object with `level_percent` and `is_charging`.
 */
class GetBatteryStatusHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val batteryManager =
            context.getSystemService(BatteryManager::class.java)
                ?: throw McpToolException.InternalError("BatteryManager is unavailable on this device")

        val capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val levelPercent = capacity.takeIf { it != Int.MIN_VALUE }

        Log.d(TAG, "Executing get_battery_status")
        val json =
            buildJsonObject {
                put("level_percent", levelPercent)
                put("is_charging", batteryManager.isCharging)
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
                "Reports battery state: 'level_percent' (0-100, or null if the device does not " +
                    "expose it) and 'is_charging'. Useful before long-running device work, or for " +
                    "an assistant answering 'how much battery do I have?'. No permission required.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_battery_status"
        private const val TAG = "MCP:GetBatteryStatusHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// set_flashlight
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `set_flashlight`.
 *
 * **Input**: `{ "enabled": <bool> }`
 * **Output**: text confirmation.
 */
class SetFlashlightHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val enabled = McpToolUtils.optionalBoolean(arguments, "enabled", true)
        val cameraManager =
            context.getSystemService(CameraManager::class.java)
                ?: throw McpToolException.InternalError("CameraManager is unavailable on this device")

        Log.d(TAG, "Executing set_flashlight: enabled=$enabled")
        try {
            val cameraId =
                cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager
                        .getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: throw McpToolException.ActionFailed(
                    "This device has no camera with a flash unit",
                )
            cameraManager.setTorchMode(cameraId, enabled)
        } catch (e: CameraAccessException) {
            throw McpToolException.ActionFailed(
                "Could not control the flashlight: ${e.message ?: "camera access failed"}",
            )
        } catch (e: SecurityException) {
            throw McpToolException.PermissionDenied(
                "Controlling the flashlight is not allowed on this device: ${e.message}",
            )
        }
        return McpToolUtils.textResult("Flashlight ${if (enabled) "on" else "off"}")
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Turns the device torch (camera flash) on or off. Parameter 'enabled': true turns " +
                    "the torch on, false turns it off (default true). Fails if the device has no " +
                    "camera with a flash unit. No permission required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("enabled") {
                                put("type", "boolean")
                                put("description", "True to turn the torch on, false to turn it off")
                            }
                        },
                    required = listOf("enabled"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "set_flashlight"
        private const val TAG = "MCP:SetFlashlightHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_device_info
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_device_info`.
 *
 * **Input**: none
 * **Output**: JSON object describing the device and Android build.
 */
class GetDeviceInfoHandler {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_device_info")
        val json =
            buildJsonObject {
                put("manufacturer", Build.MANUFACTURER)
                put("brand", Build.BRAND)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("product", Build.PRODUCT)
                put("hardware", Build.HARDWARE)
                put("android_version", Build.VERSION.RELEASE)
                put("sdk_int", Build.VERSION.SDK_INT)
                put("supported_abis", JsonArray(Build.SUPPORTED_ABIS.map { JsonPrimitive(it) }))
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
                "Reports device and Android build identity: manufacturer, brand, model, device, " +
                    "product, hardware, Android version, SDK level and supported ABIs. Lets a " +
                    "remote agent adapt to the exact hardware it is driving. No permission required.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_device_info"
        private const val TAG = "MCP:GetDeviceInfoHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// set_alarm
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `set_alarm`.
 *
 * **Input**: `{ "hour": <int>, "minutes": <int>, "label": "<string>"?, "skip_ui": <bool>? }`
 * **Output**: text confirmation.
 */
class SetAlarmHandler(
    private val intentDispatcher: IntentDispatcher,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val hour = McpToolUtils.requireInt(arguments, "hour")
        val minutes = McpToolUtils.requireInt(arguments, "minutes")
        if (hour !in 0..MAX_HOUR) {
            throw McpToolException.InvalidParams("Parameter 'hour' must be between 0 and 23, got: $hour")
        }
        if (minutes !in 0..MAX_MINUTE) {
            throw McpToolException.InvalidParams(
                "Parameter 'minutes' must be between 0 and 59, got: $minutes",
            )
        }
        val label = McpToolUtils.optionalString(arguments, "label", "").ifBlank { null }
        val skipUi = McpToolUtils.optionalBoolean(arguments, "skip_ui", true)

        val extras =
            buildMap<String, Any?> {
                put(AlarmClock.EXTRA_HOUR, hour)
                put(AlarmClock.EXTRA_MINUTES, minutes)
                if (skipUi) put(AlarmClock.EXTRA_SKIP_UI, true)
                label?.let { put(AlarmClock.EXTRA_MESSAGE, it) }
            }

        Log.d(TAG, "Executing set_alarm: hour=$hour, minutes=$minutes, skipUi=$skipUi")
        val result =
            intentDispatcher.sendIntent(
                SendIntentRequest(
                    type = TYPE_ACTIVITY,
                    action = AlarmClock.ACTION_SET_ALARM,
                    extras = extras,
                ),
            )
        return McpToolUtils.handleActionResult(
            result,
            "Alarm set for ${formatTime(hour, minutes)}",
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
                "Creates an alarm in the device's Clock app. Parameters 'hour' (0-23) and " +
                    "'minutes' (0-59) are required; 'label' is an optional message shown with the " +
                    "alarm. Parameter 'skip_ui': if true (default) the alarm is created without " +
                    "opening the Clock UI, so it works unattended; if false the Clock app opens " +
                    "for the user to confirm. Requires a Clock app that supports " +
                    "android.intent.action.SET_ALARM (present on all standard Android builds). " +
                    "No permission required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("hour") {
                                put("type", "integer")
                                put("description", "Hour of the alarm in 24-hour time (0-23)")
                            }
                            putJsonObject("minutes") {
                                put("type", "integer")
                                put("description", "Minutes past the hour (0-59)")
                            }
                            putJsonObject("label") {
                                put("type", "string")
                                put("description", "Optional label shown with the alarm")
                            }
                            putJsonObject("skip_ui") {
                                put("type", "boolean")
                                put(
                                    "description",
                                    "If true (default), create the alarm without opening the " +
                                        "Clock app. If false, open the Clock app to confirm.",
                                )
                            }
                        },
                    required = listOf("hour", "minutes"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "set_alarm"
        private const val TAG = "MCP:SetAlarmHandler"
        private const val TYPE_ACTIVITY = "activity"
        private const val MAX_HOUR = 23
        private const val MAX_MINUTE = 59

        private fun formatTime(
            hour: Int,
            minutes: Int,
        ): String = "%02d:%02d".format(hour, minutes)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// set_timer
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `set_timer`.
 *
 * **Input**: `{ "seconds": <int>, "label": "<string>"?, "skip_ui": <bool>? }`
 * **Output**: text confirmation.
 */
class SetTimerHandler(
    private val intentDispatcher: IntentDispatcher,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val seconds = McpToolUtils.requireInt(arguments, "seconds")
        McpToolUtils.validatePositiveRange(seconds.toLong(), "seconds", MAX_SECONDS)
        val label = McpToolUtils.optionalString(arguments, "label", "").ifBlank { null }
        val skipUi = McpToolUtils.optionalBoolean(arguments, "skip_ui", true)

        val extras =
            buildMap<String, Any?> {
                put(AlarmClock.EXTRA_LENGTH, seconds)
                put(AlarmClock.EXTRA_SKIP_UI, skipUi)
                label?.let { put(AlarmClock.EXTRA_MESSAGE, it) }
            }

        Log.d(TAG, "Executing set_timer: seconds=$seconds")
        val result =
            intentDispatcher.sendIntent(
                SendIntentRequest(
                    type = TYPE_ACTIVITY,
                    action = AlarmClock.ACTION_SET_TIMER,
                    extras = extras,
                ),
            )
        return McpToolUtils.handleActionResult(result, "Timer set for ${seconds}s")
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Starts a countdown timer in the device's Clock app. Parameter 'seconds' is the " +
                    "duration (1-86400). Parameter 'label' is an optional name shown with the " +
                    "timer. Parameter 'skip_ui': if true (default) the timer starts without opening " +
                    "the Clock UI; if false the Clock app opens for confirmation. Requires a Clock " +
                    "app that supports android.intent.action.SET_TIMER. No permission required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("seconds") {
                                put("type", "integer")
                                put("description", "Timer duration in seconds (1-86400)")
                            }
                            putJsonObject("label") {
                                put("type", "string")
                                put("description", "Optional label shown with the timer")
                            }
                            putJsonObject("skip_ui") {
                                put("type", "boolean")
                                put(
                                    "description",
                                    "If true (default), start the timer without opening the Clock " +
                                        "app. If false, open the Clock app to confirm.",
                                )
                            }
                        },
                    required = listOf("seconds"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "set_timer"
        private const val TAG = "MCP:SetTimerHandler"
        private const val TYPE_ACTIVITY = "activity"
        private const val MAX_SECONDS = 86_400L
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// dial_number
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `dial_number`.
 *
 * Opens the system dialer with [number] pre-filled. Deliberately does NOT place the call: that
 * would require the CALL_PHONE runtime permission, and keeping the final tap with the user is the
 * safer default for a remotely-driven device.
 *
 * **Input**: `{ "number": "<string>" }`
 * **Output**: text confirmation.
 */
class DialNumberHandler(
    private val intentDispatcher: IntentDispatcher,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val number = McpToolUtils.requireString(arguments, "number").trim()
        if (number.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'number' must not be empty")
        }
        if (!number.all { it.isDigit() || it in ALLOWED_DIAL_CHARS }) {
            throw McpToolException.InvalidParams(
                "Parameter 'number' may only contain digits and the characters $ALLOWED_DIAL_CHARS",
            )
        }

        Log.d(TAG, "Executing dial_number")
        // fromParts keeps a leading '+' intact, which Uri.encode would turn into '%2B'.
        val result = intentDispatcher.openUri(Uri.fromParts("tel", number, null).toString())
        return McpToolUtils.handleActionResult(result, "Dialer opened with $number")
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Opens the system dialer with a phone number pre-filled, ready for the user to " +
                    "start the call. This does NOT place the call by itself: placing a call needs " +
                    "the CALL_PHONE runtime permission, so the final tap stays with the user. " +
                    "Parameter 'number' accepts digits plus '+', '*', '#', '-' and spaces. No " +
                    "permission required.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("number") {
                                put("type", "string")
                                put("description", "The phone number to pre-fill in the dialer")
                            }
                        },
                    required = listOf("number"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "dial_number"
        private const val TAG = "MCP:DialNumberHandler"
        private const val ALLOWED_DIAL_CHARS = "+*#- "
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Audio stream names accepted by `get_volume` / `set_volume`. */
private val STREAM_TYPES: Map<String, Int> =
    mapOf(
        DEFAULT_STREAM to AudioManager.STREAM_MUSIC,
        "ring" to AudioManager.STREAM_RING,
        "alarm" to AudioManager.STREAM_ALARM,
        "notification" to AudioManager.STREAM_NOTIFICATION,
        "system" to AudioManager.STREAM_SYSTEM,
        "voice_call" to AudioManager.STREAM_VOICE_CALL,
    )

private const val DEFAULT_STREAM = "music"

private const val STREAM_DESCRIPTION =
    "Audio stream: 'music' (default), 'ring', 'alarm', 'notification', 'system' or 'voice_call'"

/** Media actions accepted by `media_control`, mapped to their key codes. */
private val MEDIA_KEY_CODES: Map<String, Int> =
    mapOf(
        "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
        "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
        "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
        "fast_forward" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        "rewind" to KeyEvent.KEYCODE_MEDIA_REWIND,
    )

private fun invalidStream(streamName: String): McpToolException =
    McpToolException.InvalidParams(
        "Parameter 'stream' must be one of ${STREAM_TYPES.keys.sorted()}, got: '$streamName'",
    )

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

fun registerDeviceServiceTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    intentDispatcher: IntentDispatcher,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(SpeakTextHandler.TOOL_NAME)) {
        SpeakTextHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(GetVolumeHandler.TOOL_NAME)) {
        GetVolumeHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(SetVolumeHandler.TOOL_NAME)) {
        SetVolumeHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(MediaControlHandler.TOOL_NAME)) {
        MediaControlHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(GetBatteryStatusHandler.TOOL_NAME)) {
        GetBatteryStatusHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(SetFlashlightHandler.TOOL_NAME)) {
        SetFlashlightHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(GetDeviceInfoHandler.TOOL_NAME)) {
        GetDeviceInfoHandler().register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(SetAlarmHandler.TOOL_NAME)) {
        SetAlarmHandler(intentDispatcher).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(SetTimerHandler.TOOL_NAME)) {
        SetTimerHandler(intentDispatcher).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(DialNumberHandler.TOOL_NAME)) {
        DialNumberHandler(intentDispatcher).register(registrar, toolNamePrefix)
    }
}
