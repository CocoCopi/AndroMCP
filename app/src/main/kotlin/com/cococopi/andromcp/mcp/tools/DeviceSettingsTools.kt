@file:Suppress("MatchingDeclarationName")

package com.cococopi.andromcp.mcp.tools

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.mcp.McpToolException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Device settings tool bundle.
 *
 * Lets an agent read and change the settings a person actually fiddles with: screen brightness,
 * the screen-off timeout, rotation lock, font size, ringer mode, Do Not Disturb and the airplane
 * mode state. It also ships `open_settings_screen`, which deep-links to any system settings page
 * by name, so an agent that hits a missing grant can take the user straight to the screen that
 * fixes it instead of describing where to tap.
 *
 * The two escalating grants this app cannot request at runtime — "Modify system settings" and
 * "Do Not Disturb access" — are checked before every write and produce an actionable permission
 * error naming the screen to open, rather than a silent no-op.
 */

// ─────────────────────────────────────────────────────────────────────────────
// get_brightness
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_brightness`.
 *
 * **Input**: none.
 * **Output**: JSON object with the brightness level and mode.
 */
class GetBrightnessHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_brightness")
        val resolver = context.contentResolver
        val brightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, MISSING_INT)
        val mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, MISSING_INT)

        val json =
            buildJsonObject {
                put("brightness", brightness.takeIf { it != MISSING_INT })
                put("maximum_brightness", MAX_BRIGHTNESS)
                put(
                    "brightness_percent",
                    brightness.takeIf { it != MISSING_INT }?.let { it * PERCENT_SCALE / MAX_BRIGHTNESS },
                )
                put("auto_brightness", mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                put("can_change_settings", Settings.System.canWrite(context))
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
                "Reports the screen brightness: the raw level on the device's 0 to $MAX_BRIGHTNESS " +
                    "scale, the same value as a percentage, whether automatic brightness is on, and " +
                    "whether this app currently holds the special access needed to change it. Use " +
                    "it to check the screen state before turning it up, and to see whether " +
                    "set_brightness will be allowed to work at all. Reading requires no permission.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_brightness"
        private const val TAG = "MCP:GetBrightnessHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// set_brightness
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `set_brightness`.
 *
 * **Input**: `{ "brightness": <int>, "auto_brightness": <bool>? }`
 * **Output**: text confirmation.
 */
class SetBrightnessHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val brightness = McpToolUtils.requireInt(arguments, "brightness")
        if (brightness !in MIN_BRIGHTNESS..MAX_BRIGHTNESS) {
            throw McpToolException.InvalidParams(
                "Parameter 'brightness' must be between $MIN_BRIGHTNESS and $MAX_BRIGHTNESS",
            )
        }
        val autoBrightness = McpToolUtils.optionalBoolean(arguments, "auto_brightness", false)

        Log.d(TAG, "Executing set_brightness ($brightness, auto=$autoBrightness)")

        withContext(Dispatchers.IO) {
            putSystemInt(context, Settings.System.SCREEN_BRIGHTNESS, brightness)
            putSystemInt(
                context,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (autoBrightness) {
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                } else {
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                },
            )
        }

        return McpToolUtils.textResult(
            "Screen brightness set to $brightness of $MAX_BRIGHTNESS " +
                "(automatic brightness ${if (autoBrightness) "on" else "off"})",
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
                "Sets the screen brightness, and optionally switches automatic brightness on or " +
                    "off. Use it to make the screen legible in bright sun, to dim it at night, or " +
                    "to force full brightness before a screenshot or a camera capture. " +
                    "'brightness' is required and runs from $MIN_BRIGHTNESS to $MAX_BRIGHTNESS; " +
                    "'auto_brightness' defaults to false, and turning it off is what makes the " +
                    "chosen level stick. Requires the 'Modify system settings' special access; " +
                    "when it is missing the tool returns a permission error explaining how to " +
                    "grant it.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("brightness") {
                                put("type", "integer")
                                put("description", "Brightness level from $MIN_BRIGHTNESS to $MAX_BRIGHTNESS")
                            }
                            putJsonObject("auto_brightness") {
                                put("type", "boolean")
                                put("description", "Whether adaptive brightness is enabled (default false)")
                            }
                        },
                    required = listOf("brightness"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "set_brightness"
        private const val TAG = "MCP:SetBrightnessHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_screen_timeout
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_screen_timeout`.
 *
 * **Input**: none.
 * **Output**: JSON object with the screen-off timeout.
 */
class GetScreenTimeoutHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_screen_timeout")
        val timeoutMs =
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, MISSING_INT)
        val json =
            buildJsonObject {
                put("timeout_ms", timeoutMs.takeIf { it != MISSING_INT })
                put("timeout_seconds", timeoutMs.takeIf { it != MISSING_INT }?.let { it / MILLIS_PER_SECOND })
                put("can_change_settings", Settings.System.canWrite(context))
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
                "Reports how long the screen stays on without interaction, in milliseconds and in " +
                    "seconds, and whether this app may change it. Use it to work out whether the " +
                    "screen will switch off in the middle of a long automated sequence, which is " +
                    "the usual reason a multi-step task mysteriously fails halfway through. " +
                    "Reading requires no permission.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_screen_timeout"
        private const val TAG = "MCP:GetScreenTimeoutHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// set_screen_timeout
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `set_screen_timeout`.
 *
 * **Input**: `{ "timeout_ms": <int> }`
 * **Output**: text confirmation.
 */
class SetScreenTimeoutHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val timeoutMs = McpToolUtils.optionalLong(arguments, "timeout_ms", DEFAULT_SCREEN_TIMEOUT_MS)
        McpToolUtils.validatePositiveRange(timeoutMs, "timeout_ms", MAX_SCREEN_TIMEOUT_MS)
        if (timeoutMs < MIN_SCREEN_TIMEOUT_MS) {
            throw McpToolException.InvalidParams(
                "Parameter 'timeout_ms' must be at least $MIN_SCREEN_TIMEOUT_MS",
            )
        }

        Log.d(TAG, "Executing set_screen_timeout")

        withContext(Dispatchers.IO) {
            putSystemInt(context, Settings.System.SCREEN_OFF_TIMEOUT, timeoutMs.toInt())
        }

        return McpToolUtils.textResult(
            "Screen-off timeout set to ${timeoutMs}ms; the previous value is not remembered by this tool",
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
                "Changes how long the screen stays on without interaction, which is the most " +
                    "reliable way to stop the display switching off part-way through a long " +
                    "automated sequence. 'timeout_ms' defaults to 600000 (ten minutes) and must be " +
                    "between $MIN_SCREEN_TIMEOUT_MS and $MAX_SCREEN_TIMEOUT_MS. Requires the " +
                    "'Modify system settings' special access. Read the original value with " +
                    "get_screen_timeout first if it should be restored afterwards.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("timeout_ms") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Screen-off timeout in milliseconds (default 600000, " +
                                        "min $MIN_SCREEN_TIMEOUT_MS, max $MAX_SCREEN_TIMEOUT_MS)",
                                )
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "set_screen_timeout"
        private const val TAG = "MCP:SetScreenTimeoutHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// set_rotation_lock
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `set_rotation_lock`.
 *
 * **Input**: `{ "auto_rotate": <bool>? (default true), "rotation_degrees": <int>? }`
 * **Output**: text confirmation.
 */
class SetRotationLockHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val autoRotate = McpToolUtils.optionalBoolean(arguments, "auto_rotate", true)
        val rotationDegrees = McpToolUtils.optionalInt(arguments, "rotation_degrees", DEFAULT_ROTATION_DEGREES)
        if (!autoRotate && rotationDegrees !in SUPPORTED_ROTATIONS) {
            throw McpToolException.InvalidParams(
                "Parameter 'rotation_degrees' must be one of ${SUPPORTED_ROTATIONS.joinToString(", ")}",
            )
        }

        Log.d(TAG, "Executing set_rotation_lock (auto=$autoRotate)")

        withContext(Dispatchers.IO) {
            putSystemInt(
                context,
                Settings.System.ACCELEROMETER_ROTATION,
                if (autoRotate) ROTATION_ENABLED else ROTATION_DISABLED,
            )
            if (!autoRotate) {
                putSystemInt(context, Settings.System.USER_ROTATION, rotationDegrees)
            }
        }

        return McpToolUtils.textResult(
            if (autoRotate) {
                "Rotation unlocked: the screen now follows the device orientation"
            } else {
                "Rotation locked to $rotationDegrees degrees"
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
                "Locks or unlocks screen rotation. Pass 'auto_rotate': false with " +
                    "'rotation_degrees' set to one of ${SUPPORTED_ROTATIONS.joinToString(", ")} to hold " +
                    "the screen in a fixed orientation, or 'auto_rotate': true (the default) to let " +
                    "the device follow its sensors again. Use it to keep a landscape-only app " +
                    "upright while automating it, or to restore automatic rotation afterwards. " +
                    "Requires the 'Modify system settings' special access.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("auto_rotate") {
                                put("type", "boolean")
                                put("description", "true to follow the sensors, false to lock (default true)")
                            }
                            putJsonObject("rotation_degrees") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Locked orientation in degrees, one of " +
                                        SUPPORTED_ROTATIONS.joinToString(", ") +
                                        " (only used when auto_rotate is false)",
                                )
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "set_rotation_lock"
        private const val TAG = "MCP:SetRotationLockHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_font_scale
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_font_scale`.
 *
 * **Input**: none.
 * **Output**: JSON object with the configured and effective font scale.
 */
class GetFontScaleHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_font_scale")
        val configured =
            Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, DEFAULT_FONT_SCALE)
        val json =
            buildJsonObject {
                put("configured_font_scale", configured)
                put("effective_font_scale", context.resources.configuration.fontScale)
                put("can_change_settings", Settings.System.canWrite(context))
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
                "Reports the display font scale — the user's text-size setting — both as configured " +
                    "and as currently applied to this app's resources. Use it to understand why UI " +
                    "text may be larger or smaller than it looks in a screenshot, and therefore why " +
                    "on-screen coordinates shift between devices. Reading requires no permission.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_font_scale"
        private const val TAG = "MCP:GetFontScaleHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// set_font_scale
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `set_font_scale`.
 *
 * **Input**: `{ "scale": <number> }`
 * **Output**: text confirmation.
 */
class SetFontScaleHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val scale = McpToolUtils.requireFloat(arguments, "scale")
        if (scale < MIN_FONT_SCALE || scale > MAX_FONT_SCALE) {
            throw McpToolException.InvalidParams(
                "Parameter 'scale' must be between $MIN_FONT_SCALE and $MAX_FONT_SCALE",
            )
        }

        Log.d(TAG, "Executing set_font_scale")

        withContext(Dispatchers.IO) {
            putSystemFloat(context, Settings.System.FONT_SCALE, scale)
        }

        return McpToolUtils.textResult(
            "Font scale set to $scale. On Android 13 and later this takes effect when the " +
                "display setting is next applied; the user may need to reopen the app.",
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
                "Changes the user's text-size setting, which scales text across every app. " +
                    "'scale' runs from $MIN_FONT_SCALE (much smaller) through $DEFAULT_FONT_SCALE " +
                    "(the default) up to $MAX_FONT_SCALE (much larger). Use it to make small text " +
                    "readable before OCR-style reading of the screen, or to restore the default. " +
                    "Requires the 'Modify system settings' special access.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("scale") {
                                put("type", "number")
                                put(
                                    "description",
                                    "Font scale between $MIN_FONT_SCALE and $MAX_FONT_SCALE " +
                                        "(default is $DEFAULT_FONT_SCALE)",
                                )
                            }
                        },
                    required = listOf("scale"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "set_font_scale"
        private const val TAG = "MCP:SetFontScaleHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_ringer_mode
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_ringer_mode`.
 *
 * **Input**: none.
 * **Output**: JSON object with the ringer mode and Do Not Disturb state.
 */
class GetRingerModeHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_ringer_mode")
        val audioManager =
            context.getSystemService(AudioManager::class.java)
                ?: throw McpToolException.ActionFailed("Audio services are unavailable on this device")
        val json =
            buildJsonObject {
                put("ringer_mode", ringerModeLabel(audioManager.ringerMode))
                put("music_active", audioManager.isMusicActive)
                put("do_not_disturb_access", hasNotificationPolicyAccess(context))
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private fun ringerModeLabel(mode: Int): String =
        when (mode) {
            AudioManager.RINGER_MODE_SILENT -> "SILENT"
            AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            else -> "NORMAL"
        }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports whether the phone is currently set to ring, vibrate only or be silent, " +
                    "whether media is playing, and whether this app holds the Do Not Disturb " +
                    "access needed to silence the device. Use it before making the phone speak or " +
                    "play audio, so a loud action is not taken in a quiet place, and to see whether " +
                    "set_ringer_mode will be permitted. Reading requires no permission.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_ringer_mode"
        private const val TAG = "MCP:GetRingerModeHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// set_ringer_mode
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `set_ringer_mode`.
 *
 * **Input**: `{ "mode": <string> }`
 * **Output**: text confirmation.
 */
class SetRingerModeHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val requested = McpToolUtils.requireString(arguments, "mode").trim().uppercase()
        val mode =
            RINGER_MODES[requested]
                ?: throw McpToolException.InvalidParams(
                    "Unknown mode '$requested'. Valid values: ${RINGER_MODES.keys.joinToString(", ")}",
                )
        if (mode != AudioManager.RINGER_MODE_NORMAL) {
            requireNotificationPolicyAccess(context, TOOL_NAME)
        }

        Log.d(TAG, "Executing set_ringer_mode ($requested)")

        val audioManager =
            context.getSystemService(AudioManager::class.java)
                ?: throw McpToolException.ActionFailed("Audio services are unavailable on this device")
        try {
            audioManager.ringerMode = mode
        } catch (e: SecurityException) {
            throw McpToolException.PermissionDenied("Android refused the ringer change: ${e.message}")
        }

        return McpToolUtils.textResult("Ringer mode set to $requested")
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Switches the phone between ringing, vibrate-only and silent. 'mode' accepts " +
                    "NORMAL, VIBRATE or SILENT. Use VIBRATE or SILENT before playing audio, " +
                    "speaking or flashing the torch somewhere a loud phone would be unwelcome, and " +
                    "NORMAL afterwards to restore the previous behaviour. Silencing a device " +
                    "requires the Do Not Disturb special access, which the app cannot request at " +
                    "runtime; without it the tool returns a permission error naming the screen to " +
                    "open. This tool is intentionally dumb about what the previous mode was, so " +
                    "read it with get_ringer_mode first if it must be restored.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("mode") {
                                put("type", "string")
                                put("description", "NORMAL, VIBRATE or SILENT")
                            }
                        },
                    required = listOf("mode"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "set_ringer_mode"
        private const val TAG = "MCP:SetRingerModeHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_dnd_state
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_dnd_state`.
 *
 * **Input**: none.
 * **Output**: JSON object with the Do Not Disturb state.
 */
class GetDndStateHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_dnd_state")
        val manager = context.getSystemService(NotificationManager::class.java)
        val json =
            buildJsonObject {
                put("notifications_enabled", manager?.areNotificationsEnabled() ?: true)
                put("policy_access", hasNotificationPolicyAccess(context))
                put("interruption_filter", interruptionFilterLabel(manager?.currentInterruptionFilter ?: 0))
                put("interruption_filter_raw", manager?.currentInterruptionFilter)
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private fun interruptionFilterLabel(filter: Int): String =
        when (filter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE"
            else -> "UNKNOWN"
        }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports the Do Not Disturb state: whether this app's notifications are enabled, " +
                    "whether the app holds Do Not Disturb access, and the current interruption " +
                    "filter (ALL, PRIORITY, ALARMS or NONE, where NONE means total silence). Use it " +
                    "to find out whether the phone will make noise, and to see whether set_dnd_mode " +
                    "will be permitted. Reading requires no permission.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_dnd_state"
        private const val TAG = "MCP:GetDndStateHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// set_dnd_mode
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `set_dnd_mode`.
 *
 * **Input**: `{ "mode": <string> }`
 * **Output**: text confirmation.
 */
class SetDndModeHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        requireNotificationPolicyAccess(context, TOOL_NAME)

        val requested = McpToolUtils.requireString(arguments, "mode").trim().uppercase()
        val filter =
            INTERRUPTION_FILTERS[requested]
                ?: throw McpToolException.InvalidParams(
                    "Unknown mode '$requested'. Valid values: ${INTERRUPTION_FILTERS.keys.joinToString(", ")}",
                )

        Log.d(TAG, "Executing set_dnd_mode ($requested)")

        val manager =
            context.getSystemService(NotificationManager::class.java)
                ?: throw McpToolException.ActionFailed("Notification services are unavailable on this device")
        try {
            manager.setInterruptionFilter(filter)
        } catch (e: SecurityException) {
            throw McpToolException.PermissionDenied("Android refused the Do Not Disturb change: ${e.message}")
        }

        return McpToolUtils.textResult("Do Not Disturb interruption filter set to $requested")
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Controls what interruptions are allowed through, which is how an agent quietens a " +
                    "phone without muting media playback. 'mode' accepts ALL (everything gets " +
                    "through), PRIORITY (only allowed contacts and alarms), ALARMS (only alarms) or " +
                    "NONE (total silence). This is the right call before speaking aloud or taking " +
                    "photos in a quiet room, and ALL afterwards restores normal behaviour. Requires " +
                    "the Do Not Disturb special access, which the app cannot request at runtime; " +
                    "without it the tool returns a permission error naming the screen to open.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("mode") {
                                put("type", "string")
                                put("description", "ALL, PRIORITY, ALARMS or NONE")
                            }
                        },
                    required = listOf("mode"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "set_dnd_mode"
        private const val TAG = "MCP:SetDndModeHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_airplane_mode
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_airplane_mode`.
 *
 * **Input**: none.
 * **Output**: JSON object with the connectivity-suppression flags.
 */
class GetAirplaneModeHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_airplane_mode")
        val resolver = context.contentResolver
        val json =
            buildJsonObject {
                put(
                    "airplane_mode",
                    Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, AIRPLANE_MODE_OFF) ==
                        AIRPLANE_MODE_ON,
                )
                put(
                    "wifi_on",
                    Settings.Global.getInt(resolver, Settings.Global.WIFI_ON, AIRPLANE_MODE_OFF) ==
                        AIRPLANE_MODE_ON,
                )
                put(
                    "mobile_data_enabled",
                    Settings.Global.getInt(resolver, MOBILE_DATA_SETTING, AIRPLANE_MODE_OFF) ==
                        AIRPLANE_MODE_ON,
                )
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
                "Reports whether airplane mode is on, whether Wi-Fi is switched on, and whether the " +
                    "user has mobile data enabled. Use it to explain why nothing is reachable: " +
                    "airplane mode is a common cause of a device that looks connected but has no " +
                    "route out. This tool is intentionally read-only — Android grants write access " +
                    "to airplane mode only to system apps, so an agent's job here is to detect the " +
                    "state and ask the user to change it. Reading requires no permission.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_airplane_mode"
        private const val TAG = "MCP:GetAirplaneModeHandler"
        private const val MOBILE_DATA_SETTING = "mobile_data"
        private const val AIRPLANE_MODE_OFF = 0
        private const val AIRPLANE_MODE_ON = 1
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// open_settings_screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `open_settings_screen`.
 *
 * **Input**: `{ "page": <string> }`
 * **Output**: text confirmation.
 */
class OpenSettingsScreenHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val requested = McpToolUtils.requireString(arguments, "page").trim().lowercase()
        val action =
            SETTINGS_PAGES[requested]
                ?: throw McpToolException.InvalidParams(
                    "Unknown page '$requested'. Valid values: ${SETTINGS_PAGES.keys.sorted().joinToString(", ")}",
                )

        Log.d(TAG, "Executing open_settings_screen ($requested)")

        val intent = Intent(action)
        if (requested in PACKAGE_SCOPED_PAGES) {
            intent.data = Uri.fromParts("package", context.packageName, null)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw McpToolException.ActionFailed(
                "This device has no system screen for the '$requested' settings page",
            )
        }

        return McpToolUtils.textResult(
            "Opened the '$requested' settings screen. The user may need to interact with it before " +
                "the change takes effect.",
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
                "Opens one of the device's system settings screens by name, so an agent that needs " +
                    "a permission or a toggle can put the exact screen in front of the user instead " +
                    "of describing where to tap. Available pages: " +
                    "app_details, write_settings, notification_policy, usage_access, " +
                    "battery_optimization, wifi, bluetooth, location, display, sound, " +
                    "notification_listener, accessibility, developer, date_time, storage, nfc, " +
                    "home, apps, default_apps, privacy. Notable ones: 'app_details' and " +
                    "'write_settings' open scoped to this app, so the user lands directly on the " +
                    "toggle that unblocks the tool that just failed; 'notification_policy' grants " +
                    "Do Not Disturb access. 'page' is required and case-insensitive. No permission " +
                    "is required to open a settings screen.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("page") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Settings page name, for example write_settings or wifi",
                                )
                            }
                        },
                    required = listOf("page"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "open_settings_screen"
        private const val TAG = "MCP:OpenSettingsScreenHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Registration and shared helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Registers every device settings tool enabled by [perms]. */
fun registerDeviceSettingsTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    deviceSettingsRegistrations(registrar, context, toolNamePrefix).forEach { (toolName, register) ->
        if (perms.isToolEnabled(toolName)) register()
    }
}

/** Pairs each tool name with the closure that registers it, keeping registration branch-free. */
private fun deviceSettingsRegistrations(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
): List<Pair<String, () -> Unit>> =
    listOf(
        GetBrightnessHandler.TOOL_NAME to
            { GetBrightnessHandler(context).register(registrar, toolNamePrefix) },
        SetBrightnessHandler.TOOL_NAME to
            { SetBrightnessHandler(context).register(registrar, toolNamePrefix) },
        GetScreenTimeoutHandler.TOOL_NAME to
            { GetScreenTimeoutHandler(context).register(registrar, toolNamePrefix) },
        SetScreenTimeoutHandler.TOOL_NAME to
            { SetScreenTimeoutHandler(context).register(registrar, toolNamePrefix) },
        SetRotationLockHandler.TOOL_NAME to
            { SetRotationLockHandler(context).register(registrar, toolNamePrefix) },
        GetFontScaleHandler.TOOL_NAME to
            { GetFontScaleHandler(context).register(registrar, toolNamePrefix) },
        SetFontScaleHandler.TOOL_NAME to
            { SetFontScaleHandler(context).register(registrar, toolNamePrefix) },
        GetRingerModeHandler.TOOL_NAME to
            { GetRingerModeHandler(context).register(registrar, toolNamePrefix) },
        SetRingerModeHandler.TOOL_NAME to
            { SetRingerModeHandler(context).register(registrar, toolNamePrefix) },
        GetDndStateHandler.TOOL_NAME to
            { GetDndStateHandler(context).register(registrar, toolNamePrefix) },
        SetDndModeHandler.TOOL_NAME to
            { SetDndModeHandler(context).register(registrar, toolNamePrefix) },
        GetAirplaneModeHandler.TOOL_NAME to
            { GetAirplaneModeHandler(context).register(registrar, toolNamePrefix) },
        OpenSettingsScreenHandler.TOOL_NAME to
            { OpenSettingsScreenHandler(context).register(registrar, toolNamePrefix) },
    )

/** True when this app holds the "Modify system settings" special access. */
private fun hasSystemWriteAccess(context: Context): Boolean = Settings.System.canWrite(context)

/** True when this app holds the Do Not Disturb (notification policy) special access. */
private fun hasNotificationPolicyAccess(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

/** Fails with an actionable error when "Modify system settings" has not been granted. */
private fun requireSystemWriteAccess(context: Context) {
    if (!hasSystemWriteAccess(context)) {
        throw McpToolException.PermissionDenied(
            "Changing this setting needs the 'Modify system settings' special access, which cannot " +
                "be requested at runtime. Open the 'write_settings' page with open_settings_screen " +
                "and grant it there, then retry.",
        )
    }
}

/** Fails with an actionable error when Do Not Disturb access has not been granted. */
private fun requireNotificationPolicyAccess(
    context: Context,
    toolName: String,
) {
    if (!hasNotificationPolicyAccess(context)) {
        throw McpToolException.PermissionDenied(
            "Tool '$toolName' needs Do Not Disturb access, which cannot be requested at runtime. " +
                "Open the 'notification_policy' page with open_settings_screen and grant it there, " +
                "then retry.",
        )
    }
}

/** Writes one integer system setting, converting a refused write into a permission error. */
private fun putSystemInt(
    context: Context,
    name: String,
    value: Int,
) {
    requireSystemWriteAccess(context)
    try {
        Settings.System.putInt(context.contentResolver, name, value)
    } catch (e: SecurityException) {
        throw McpToolException.PermissionDenied("Android refused the write to '$name': ${e.message}")
    }
}

/** Writes one floating-point system setting, converting a refused write into a permission error. */
private fun putSystemFloat(
    context: Context,
    name: String,
    value: Float,
) {
    requireSystemWriteAccess(context)
    try {
        Settings.System.putFloat(context.contentResolver, name, value)
    } catch (e: SecurityException) {
        throw McpToolException.PermissionDenied("Android refused the write to '$name': ${e.message}")
    }
}

/** Ringer mode names accepted by `set_ringer_mode`. */
private val RINGER_MODES: Map<String, Int> =
    mapOf(
        "NORMAL" to AudioManager.RINGER_MODE_NORMAL,
        "VIBRATE" to AudioManager.RINGER_MODE_VIBRATE,
        "SILENT" to AudioManager.RINGER_MODE_SILENT,
    )

/** Interruption filter names accepted by `set_dnd_mode`. */
private val INTERRUPTION_FILTERS: Map<String, Int> =
    mapOf(
        "ALL" to NotificationManager.INTERRUPTION_FILTER_ALL,
        "PRIORITY" to NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        "ALARMS" to NotificationManager.INTERRUPTION_FILTER_ALARMS,
        "NONE" to NotificationManager.INTERRUPTION_FILTER_NONE,
    )

/** Settings pages exposed by `open_settings_screen`, mapped to their system actions. */
private val SETTINGS_PAGES: Map<String, String> =
    mapOf(
        "app_details" to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "write_settings" to Settings.ACTION_MANAGE_WRITE_SETTINGS,
        "notification_policy" to Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
        "usage_access" to Settings.ACTION_USAGE_ACCESS_SETTINGS,
        "battery_optimization" to Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "notification_listener" to Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
        "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "developer" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "date_time" to Settings.ACTION_DATE_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "nfc" to Settings.ACTION_NFC_SETTINGS,
        "home" to Settings.ACTION_HOME_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "default_apps" to Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
        "privacy" to Settings.ACTION_PRIVACY_SETTINGS,
    )

/** Pages whose intent must carry this app's package name as its data URI. */
private val PACKAGE_SCOPED_PAGES = setOf("app_details", "write_settings")

/** Orientations accepted when locking rotation. */
private val SUPPORTED_ROTATIONS = listOf(0, ROTATION_QUARTER_TURN, ROTATION_HALF_TURN, ROTATION_THREE_QUARTER_TURN)

private const val MIN_BRIGHTNESS = 0
private const val MAX_BRIGHTNESS = 255
private const val PERCENT_SCALE = 100
private const val MISSING_INT = Int.MIN_VALUE
private const val MILLIS_PER_SECOND = 1_000L
private const val DEFAULT_SCREEN_TIMEOUT_MS = 600_000L
private const val MIN_SCREEN_TIMEOUT_MS = 5_000L
private const val MAX_SCREEN_TIMEOUT_MS = 86_400_000L
private const val ROTATION_QUARTER_TURN = 90
private const val ROTATION_HALF_TURN = 180
private const val ROTATION_THREE_QUARTER_TURN = 270
private const val DEFAULT_ROTATION_DEGREES = 0
private const val ROTATION_ENABLED = 1
private const val ROTATION_DISABLED = 0
private const val MIN_FONT_SCALE = 0.5f
private const val MAX_FONT_SCALE = 2.0f
private const val DEFAULT_FONT_SCALE = 1.0f
