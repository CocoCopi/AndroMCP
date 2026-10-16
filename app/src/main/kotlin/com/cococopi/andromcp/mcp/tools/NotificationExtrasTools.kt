@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.cococopi.andromcp.mcp.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
import kotlin.random.Random

// Notification tool bundle for the app's own notifications.
//
// The screen is not always the right place for an answer. A notification survives leaving the app,
// shows up on a watch and on the lock screen, and can carry a title, body and priority that the user
// reads at a glance — which makes it the right channel for a long task finishing, a reminder, or a
// result worth keeping. These tools post, cancel and manage notifications on behalf of this app,
// and let an agent inspect and shape the channels they arrive through.
//
// Posting needs the `POST_NOTIFICATIONS` grant on Android 13+, and the user can switch any channel
// off afterwards; both are reported rather than assumed. Only this app's own notifications can be
// posted or cancelled — other apps' notifications are read-only through the notification-listener
// tools.

/** Channel used when a caller does not name one. */
private const val DEFAULT_CHANNEL_ID = "andromcp_agent"

/** Name shown for the default channel in Android's notification settings. */
private const val DEFAULT_CHANNEL_NAME = "Agent messages"

/** Importance levels a caller may ask for, mapped to the platform constants. */
private val IMPORTANCE_LEVELS =
    mapOf(
        "min" to NotificationManager.IMPORTANCE_MIN,
        "low" to NotificationManager.IMPORTANCE_LOW,
        "default" to NotificationManager.IMPORTANCE_DEFAULT,
        "high" to NotificationManager.IMPORTANCE_HIGH,
    )

/** Resolves the notification manager, failing clearly when the service is missing. */
private fun notificationManager(context: Context): NotificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        ?: throw McpToolException.ActionFailed("This device does not expose a notification manager.")

/** Parses an importance name, defaulting to `default`. */
private fun importanceOf(raw: String): Int =
    IMPORTANCE_LEVELS[raw.lowercase()]
        ?: throw McpToolException.InvalidParams(
            "Parameter 'importance' must be one of: ${IMPORTANCE_LEVELS.keys.joinToString()}. Got '$raw'.",
        )

/** Name of an importance level, for reporting back. */
private fun importanceName(importance: Int): String =
    IMPORTANCE_LEVELS.entries.firstOrNull { it.value == importance }?.key ?: "unknown($importance)"

// ─────────────────────────────────────────────────────────────────────────────
// post_notification
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `post_notification`.
 * Input: `{ "title": <string>, "text": <string>, "id": <int>?, "channel_id": <string>?, "importance": <string>? }`.
 */
class PostNotificationHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val title = McpToolUtils.requireString(arguments, "title")
        val text = McpToolUtils.optionalString(arguments, "text", "")
        val id = McpToolUtils.optionalInt(arguments, "id", Random.nextInt(1, MAX_NOTIFICATION_ID))
        val channelId = McpToolUtils.optionalString(arguments, "channel_id", DEFAULT_CHANNEL_ID)
        val importance = importanceOf(McpToolUtils.optionalString(arguments, "importance", "default"))
        RuntimePermissionGate.require(context, Manifest.permission.POST_NOTIFICATIONS, TOOL_NAME)
        Log.d(TAG, "Executing post_notification")

        val manager = notificationManager(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            throw McpToolException.PermissionDenied(
                "Notifications are switched off for this app, so nothing would appear. Enable them in " +
                    "Settings > Apps > this app > Notifications (open_notification_settings opens that " +
                    "screen), then call the tool again.",
            )
        }
        withContext(Dispatchers.IO) {
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(channelId, channelId, importance).apply {
                        description = "Notifications posted by the device-control agent"
                    },
                )
            }
            val notification =
                NotificationCompat
                    .Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setPriority(priorityFor(importance))
                    .build()
            try {
                NotificationManagerCompat.from(context).notify(id, notification)
            } catch (exception: Exception) {
                throw McpToolException.ActionFailed(
                    "Could not post the notification: " +
                        (exception.message ?: exception.javaClass.simpleName),
                )
            }
        }
        val json =
            buildJsonObject {
                put("posted", true)
                put("id", id)
                put("channel_id", channelId)
                put("importance", importanceName(importance))
                put("note", "Cancel it with cancel_notification using the same 'id'.")
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
                "Posts a notification from this app with a title and body, and returns the numeric id " +
                    "needed to cancel it. Use it to report something after the fact — a long task " +
                    "finished, a download is ready, a reminder the user asked for — because a " +
                    "notification reaches the user even when they are not looking at the screen, on a " +
                    "watch, or on the lock screen. Set 'importance' to 'high' when it must interrupt, " +
                    "'low' or 'min' when it should be quiet. Needs the POST_NOTIFICATIONS grant and " +
                    "notifications enabled for this app; both are checked and reported.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("title") {
                                put("type", "string")
                                put("description", "Notification title")
                            }
                            putJsonObject("text") {
                                put("type", "string")
                                put("description", "Notification body text")
                            }
                            putJsonObject("id") {
                                put("type", "integer")
                                put("description", "Notification id, reused when updating (default random)")
                            }
                            putJsonObject("channel_id") {
                                put("type", "string")
                                put("description", "Notification channel to post on (default $DEFAULT_CHANNEL_ID)")
                            }
                            putJsonObject("importance") {
                                put("type", "string")
                                put("description", "min, low, default or high (default default)")
                            }
                        },
                    required = listOf("title"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "post_notification"
        private const val TAG = "MCP:PostNotificationHandler"
        private const val MAX_NOTIFICATION_ID = 100_000
    }
}

/** Maps a channel importance onto the matching notification priority. */
private fun priorityFor(importance: Int): Int =
    when (importance) {
        NotificationManager.IMPORTANCE_MIN -> NotificationCompat.PRIORITY_MIN
        NotificationManager.IMPORTANCE_LOW -> NotificationCompat.PRIORITY_LOW
        NotificationManager.IMPORTANCE_HIGH -> NotificationCompat.PRIORITY_HIGH
        else -> NotificationCompat.PRIORITY_DEFAULT
    }

// ─────────────────────────────────────────────────────────────────────────────
// cancel_notification
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `cancel_notification`. Input: `{ "id": <int> }`. */
class CancelNotificationHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val id = McpToolUtils.requireInt(arguments, "id")
        Log.d(TAG, "Executing cancel_notification")

        withContext(Dispatchers.IO) { NotificationManagerCompat.from(context).cancel(id) }
        val json =
            buildJsonObject {
                put("id", id)
                put("cancelled", true)
                put("note", "Only notifications this app posted can be cancelled; it is a no-op otherwise.")
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
                "Dismisses one of this app's notifications by id — the id returned when it was posted. " +
                    "Use it to clear a progress or reminder notification once it is no longer true, and " +
                    "to replace stale information instead of stacking a second notification next to it. " +
                    "It only affects notifications this app posted, so other apps' notifications are " +
                    "untouched; cancel_all_notifications clears every one of ours at once.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("id") {
                                put("type", "integer")
                                put("description", "Notification id returned by post_notification")
                            }
                        },
                    required = listOf("id"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "cancel_notification"
        private const val TAG = "MCP:CancelNotificationHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// cancel_all_notifications
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `cancel_all_notifications`. Input: `{}`. */
class CancelAllNotificationsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing cancel_all_notifications")
        withContext(Dispatchers.IO) { NotificationManagerCompat.from(context).cancelAll() }
        val json =
            buildJsonObject {
                put("cancelled_all", true)
                put("scope", "notifications posted by this app")
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
                "Clears every notification this app has posted. Use it to tidy up when a session ends, " +
                    "or before posting a fresh batch that would otherwise duplicate what is already in " +
                    "the shade. It cannot touch other apps' notifications, so it never clears something " +
                    "the user still needs from elsewhere.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "cancel_all_notifications"
        private const val TAG = "MCP:CancelAllNotificationsHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// list_notification_channels
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `list_notification_channels`. Input: `{}`. */
class ListNotificationChannelsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing list_notification_channels")
        val manager = notificationManager(context)
        val channels = withContext(Dispatchers.IO) { manager.notificationChannels }
        val rows =
            channels.map { channel ->
                buildJsonObject {
                    put("id", channel.id)
                    put("name", channel.name.toString())
                    put("description", channel.description.orEmpty())
                    put("importance", importanceName(channel.importance))
                    put("importance_value", channel.importance)
                    put("sound", channel.sound?.toString().orEmpty())
                    put("vibration_enabled", channel.shouldVibrate())
                    put("badge_enabled", channel.canShowBadge())
                    put("blockable", channel.isBlockable)
                    put("group", channel.group.orEmpty())
                }
            }
        val json =
            buildJsonObject {
                put("count", rows.size)
                put("default_channel_id", DEFAULT_CHANNEL_ID)
                putJsonArray("channels") { rows.forEach { add(it) } }
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
                "Lists this app's notification channels with their importance, sound, vibration, badge " +
                    "and blockability. Use it before posting to pick the right channel, and afterwards " +
                    "to diagnose a silent notification — an importance the user lowered, or a channel " +
                    "they switched off, explains why nothing appeared. Channels are the unit users " +
                    "actually control, so reporting on them is how you explain notification behaviour " +
                    "back to a human.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_notification_channels"
        private const val TAG = "MCP:ListNotificationChannelsHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// create_notification_channel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `create_notification_channel`.
 *
 * Input: `{ "channel_id": <string>, "name": <string>, "description": <string>?,
 * "importance": <string>?, "vibration": <bool>? }`.
 */
class CreateNotificationChannelHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val channelId = McpToolUtils.requireString(arguments, "channel_id").trim()
        val name = McpToolUtils.requireString(arguments, "name")
        val description = McpToolUtils.optionalString(arguments, "description", "")
        val importance = importanceOf(McpToolUtils.optionalString(arguments, "importance", "default"))
        val vibration = McpToolUtils.optionalBoolean(arguments, "vibration", true)
        Log.d(TAG, "Executing create_notification_channel")

        val manager = notificationManager(context)
        val existed = withContext(Dispatchers.IO) { manager.getNotificationChannel(channelId) != null }
        if (!existed) {
            withContext(Dispatchers.IO) {
                try {
                    manager.createNotificationChannel(
                        NotificationChannel(channelId, name, importance).apply {
                            this.description = description
                            enableVibration(vibration)
                        },
                    )
                } catch (exception: Exception) {
                    throw McpToolException.ActionFailed(
                        "Could not create channel '$channelId': " +
                            (exception.message ?: exception.javaClass.simpleName),
                    )
                }
            }
        }
        val json =
            buildJsonObject {
                put("channel_id", channelId)
                put("already_existed", existed)
                put("created", !existed)
                put("importance", importanceName(importance))
                put(
                    "note",
                    if (existed) {
                        "The channel already existed and was left untouched: Android ignores changes to " +
                            "the importance of an existing channel, because only the user may lower or " +
                            "raise it after creation."
                    } else {
                        "Post with this channel via post_notification's 'channel_id'."
                    },
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
                "Creates a notification channel so different kinds of message can be told apart and " +
                    "silenced independently — for example a 'high' importance channel for things worth " +
                    "interrupting for and a 'low' one for progress. Important Android behaviour: " +
                    "importance is fixed at creation, so re-creating an existing channel changes " +
                    "nothing and this tool reports 'already_existed': true instead of pretending " +
                    "otherwise. To change importance, use a new channel id or delete the channel first.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("channel_id") {
                                put("type", "string")
                                put("description", "Unique channel id, e.g. \"task_results\"")
                            }
                            putJsonObject("name") {
                                put("type", "string")
                                put("description", "Name shown to the user in notification settings")
                            }
                            putJsonObject("description") {
                                put("type", "string")
                                put("description", "Explanation shown under the channel name")
                            }
                            putJsonObject("importance") {
                                put("type", "string")
                                put("description", "min, low, default or high (default default)")
                            }
                            putJsonObject("vibration") {
                                put("type", "boolean")
                                put("description", "Vibrate for this channel (default true)")
                            }
                        },
                    required = listOf("channel_id", "name"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "create_notification_channel"
        private const val TAG = "MCP:CreateNotificationChannelHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// delete_notification_channel
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `delete_notification_channel`. Input: `{ "channel_id": <string> }`. */
class DeleteNotificationChannelHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val channelId = McpToolUtils.requireString(arguments, "channel_id").trim()
        Log.d(TAG, "Executing delete_notification_channel")

        val manager = notificationManager(context)
        val existed = withContext(Dispatchers.IO) { manager.getNotificationChannel(channelId) != null }
        if (!existed) {
            throw McpToolException.InvalidParams(
                "No notification channel '$channelId'. Use list_notification_channels to see them.",
            )
        }
        withContext(Dispatchers.IO) { manager.deleteNotificationChannel(channelId) }
        val json =
            buildJsonObject {
                put("channel_id", channelId)
                put("deleted", true)
                put("note", "Notifications already posted on this channel stay in the shade until dismissed.")
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
                "Deletes a notification channel this app created. Use it to retune a channel whose " +
                    "importance turned out wrong — create a new one with the wanted importance, then " +
                    "delete the old — or to clean up channels that no longer serve a purpose. Posted " +
                    "notifications already sitting in the shade are unaffected and keep working until " +
                    "dismissed.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("channel_id") {
                                put("type", "string")
                                put("description", "Channel id to delete")
                            }
                        },
                    required = listOf("channel_id"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "delete_notification_channel"
        private const val TAG = "MCP:DeleteNotificationChannelHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_notification_policy
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_notification_policy`. Input: `{}`. */
class GetNotificationPolicyHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_notification_policy")
        val manager = notificationManager(context)
        val filter = withContext(Dispatchers.IO) { runCatching { manager.currentInterruptionFilter }.getOrNull() }
        val fullScreenAllowed =
            withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        manager.canUseFullScreenIntent()
                    } else {
                        true
                    }
                }.getOrNull()
            }
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val granted = RuntimePermissionGate.isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        val channelCount = withContext(Dispatchers.IO) { manager.notificationChannels.size }
        val json =
            buildJsonObject {
                put("notifications_enabled_for_app", notificationsEnabled)
                put("post_notifications_granted", granted)
                put("dnd_policy_access_granted", manager.isNotificationPolicyAccessGranted)
                put("interruption_filter", interruptionFilterName(filter ?: -1))
                put("may_use_full_screen_intent", fullScreenAllowed ?: true)
                put("channel_count", channelCount)
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
                "Reports whether this app may notify at all and how much of a disturbance it is allowed " +
                    "to make: whether notifications are enabled, whether POST_NOTIFICATIONS is granted, " +
                    "whether Do Not Disturb access is available, the current interruption filter " +
                    "(everything, priority only, alarms only, none) and whether a full-screen intent is " +
                    "permitted. Call it when a notification did not appear, or before relying on one " +
                    "being seen, so the reason is data rather than a guess.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_notification_policy"
        private const val TAG = "MCP:GetNotificationPolicyHandler"
    }
}

/** Human-readable name of a notification interruption filter. */
private fun interruptionFilterName(filter: Int): String =
    when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "all"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
        else -> "unknown($filter)"
    }

// ─────────────────────────────────────────────────────────────────────────────
// check_notification_permission
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `check_notification_permission`. Input: `{}`. */
class CheckNotificationPermissionHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing check_notification_permission")
        val granted = RuntimePermissionGate.isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val hint =
            if (granted && enabled) {
                "Notifications can be posted; use post_notification."
            } else {
                "Grant notifications for this app in Android Settings (open_notification_settings " +
                    "opens the exact screen), then post_notification will work."
            }
        val json =
            buildJsonObject {
                put("post_notifications_granted", granted)
                put("notifications_enabled", enabled)
                put("may_post", granted && enabled)
                put("hint", hint)
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
                "A focused yes/no on whether this app may post a notification right now, with the " +
                    "reason and the exact next step when the answer is no. Use it before an operation " +
                    "whose whole point is to notify — so the agent can tell the user what to enable " +
                    "instead of reporting a success that never reached them.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "check_notification_permission"
        private const val TAG = "MCP:CheckNotificationPermissionHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// open_notification_settings
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `open_notification_settings`. Input: `{}`. */
class OpenNotificationSettingsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing open_notification_settings")
        val intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened =
            withContext(Dispatchers.IO) {
                try {
                    context.startActivity(intent)
                    true
                } catch (_: android.content.ActivityNotFoundException) {
                    false
                }
            }
        val json =
            buildJsonObject {
                put("opened", opened)
                put("screen", "Notification settings for this app")
                put(
                    "note",
                    "Per-channel switches and the app-wide notification permission live on this " +
                        "screen; the user has to make the change.",
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
                "Opens this app's notification settings screen, where the notification permission and " +
                    "each channel's switches can be turned on. Use it when check_notification_permission " +
                    "says notifications are blocked, or when a channel the agent needs was silenced — it " +
                    "saves the user from hunting through Settings, and the screen tools can then read " +
                    "the page so the agent can explain exactly what to flip.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "open_notification_settings"
        private const val TAG = "MCP:OpenNotificationSettingsHandler"
    }
}

/** Registers the notification bundle: posting, cancelling, channels and policy. */
fun registerNotificationExtrasTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    PostNotificationHandler(context).register(registrar, toolNamePrefix)
    CancelNotificationHandler(context).register(registrar, toolNamePrefix)
    CancelAllNotificationsHandler(context).register(registrar, toolNamePrefix)
    ListNotificationChannelsHandler(context).register(registrar, toolNamePrefix)
    CreateNotificationChannelHandler(context).register(registrar, toolNamePrefix)
    DeleteNotificationChannelHandler(context).register(registrar, toolNamePrefix)
    GetNotificationPolicyHandler(context).register(registrar, toolNamePrefix)
    CheckNotificationPermissionHandler(context).register(registrar, toolNamePrefix)
    OpenNotificationSettingsHandler(context).register(registrar, toolNamePrefix)
}
