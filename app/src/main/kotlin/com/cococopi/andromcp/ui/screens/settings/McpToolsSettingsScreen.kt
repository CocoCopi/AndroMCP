@file:Suppress("FunctionNaming", "LongMethod")

package com.cococopi.andromcp.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cococopi.andromcp.R
import com.cococopi.andromcp.data.model.OptionalToolPermission
import com.cococopi.andromcp.data.model.OptionalToolPermissions
import com.cococopi.andromcp.data.model.ServerStatus
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.ui.theme.WarningAmber
import com.cococopi.andromcp.ui.viewmodels.MainViewModel

private data class ParamEntry(
    val paramName: String,
    val displayName: String,
)

private data class ToolEntry(
    val toolName: String,
    val displayName: String,
    val params: List<ParamEntry> = emptyList(),
)

private data class ToolCategory(
    val header: String,
    val tools: List<ToolEntry>,
)

private val ALL_TOOL_CATEGORIES: List<ToolCategory> =
    listOf(
        ToolCategory(
            "Screen",
            listOf(
                ToolEntry(
                    "get_screen_state",
                    "Get screen state",
                    listOf(ParamEntry("include_screenshot", "Include screenshot")),
                ),
            ),
        ),
        ToolCategory(
            "System",
            listOf(
                ToolEntry("press_back", "Press Back"),
                ToolEntry("press_home", "Press Home"),
                ToolEntry("press_recents", "Press Recents"),
                ToolEntry("open_notifications", "Open Notifications"),
                ToolEntry("open_quick_settings", "Open Quick Settings"),
                ToolEntry("dismiss_keyboard", "Dismiss Keyboard"),
            ),
        ),
        ToolCategory(
            "Device Services",
            listOf(
                ToolEntry("speak_text", "Speak Text"),
                ToolEntry("get_volume", "Get Volume"),
                ToolEntry("set_volume", "Set Volume"),
                ToolEntry("media_control", "Media Control"),
                ToolEntry("get_battery_status", "Get Battery Status"),
                ToolEntry("set_flashlight", "Set Flashlight"),
                ToolEntry("get_device_info", "Get Device Info"),
                ToolEntry("set_alarm", "Set Alarm"),
                ToolEntry("set_timer", "Set Timer"),
                ToolEntry("dial_number", "Dial Number"),
            ),
        ),
        ToolCategory(
            "Touch",
            listOf(
                ToolEntry("tap", "Tap"),
                ToolEntry("long_press", "Long Press"),
                ToolEntry("double_tap", "Double Tap"),
                ToolEntry("swipe", "Swipe"),
                ToolEntry("scroll", "Scroll"),
            ),
        ),
        ToolCategory(
            "Gestures",
            listOf(
                ToolEntry("pinch", "Pinch"),
                ToolEntry("custom_gesture", "Custom Gesture"),
            ),
        ),
        ToolCategory(
            "Node Actions",
            listOf(
                ToolEntry("find_nodes", "Find Nodes"),
                ToolEntry("click_node", "Click Node"),
                ToolEntry("long_click_node", "Long Click Node"),
                ToolEntry("tap_node", "Tap Node"),
                ToolEntry("scroll_to_node", "Scroll to Node"),
            ),
        ),
        ToolCategory(
            "Text Input",
            listOf(
                ToolEntry("type_append_text", "Type Append Text"),
                ToolEntry("type_insert_text", "Type Insert Text"),
                ToolEntry("type_replace_text", "Type Replace Text"),
                ToolEntry("type_clear_text", "Type Clear Text"),
                ToolEntry("press_key", "Press Key"),
            ),
        ),
        ToolCategory(
            "Utility",
            listOf(
                ToolEntry("get_clipboard", "Get Clipboard"),
                ToolEntry("set_clipboard", "Set Clipboard"),
                ToolEntry("wait_for_node", "Wait for Node"),
                ToolEntry("wait_for_idle", "Wait for Idle"),
                ToolEntry("get_node_details", "Get Node Details"),
            ),
        ),
        ToolCategory(
            "File Operations",
            listOf(
                ToolEntry("list_storage_locations", "List Storage Locations"),
                ToolEntry("list_files", "List Files"),
                ToolEntry("read_file", "Read File"),
                ToolEntry("write_file", "Write File"),
                ToolEntry("append_file", "Append File"),
                ToolEntry("file_replace", "File Replace"),
                ToolEntry("download_from_url", "Download from URL"),
                ToolEntry("delete_file", "Delete File"),
            ),
        ),
        ToolCategory(
            "App Management",
            listOf(
                ToolEntry("open_app", "Open App"),
                ToolEntry("list_apps", "List Apps"),
                ToolEntry("close_app", "Close App"),
            ),
        ),
        ToolCategory(
            "Camera",
            listOf(
                ToolEntry("list_cameras", "List Cameras"),
                ToolEntry("list_camera_photo_resolutions", "List Camera Photo Resolutions"),
                ToolEntry("list_camera_video_resolutions", "List Camera Video Resolutions"),
                ToolEntry("take_camera_photo", "Take Camera Photo"),
                ToolEntry("save_camera_photo", "Save Camera Photo"),
                ToolEntry(
                    "save_camera_video",
                    "Save Camera Video",
                    listOf(ParamEntry("audio", "Include audio")),
                ),
            ),
        ),
        ToolCategory(
            "Intent",
            listOf(
                ToolEntry("send_intent", "Send Intent"),
                ToolEntry("open_uri", "Open URI"),
            ),
        ),
        ToolCategory(
            "Notifications",
            listOf(
                ToolEntry("notification_list", "Notification List"),
                ToolEntry("notification_open", "Notification Open"),
                ToolEntry("notification_dismiss", "Notification Dismiss"),
                ToolEntry("notification_snooze", "Notification Snooze"),
                ToolEntry("notification_action", "Notification Action"),
                ToolEntry("notification_reply", "Notification Reply"),
            ),
        ),
        ToolCategory(
            "Location",
            listOf(
                ToolEntry(
                    "get_location",
                    "Get Location",
                    listOf(ParamEntry("fresh_fix", "Allow fresh GPS fix")),
                ),
            ),
        ),
        ToolCategory(
            "Sharing",
            listOf(
                ToolEntry("get_shared_content", "Get Shared Content"),
                ToolEntry("share_file_via_web", "Share File via Web"),
            ),
        ),
        ToolCategory(
            "Communication",
            listOf(
                ToolEntry("send_sms", "Send SMS"),
                ToolEntry("list_sms_threads", "List SMS Threads"),
                ToolEntry("read_sms_thread", "Read SMS Thread"),
                ToolEntry("search_sms", "Search SMS"),
                ToolEntry("list_call_log", "List Call Log"),
                ToolEntry("search_contacts", "Search Contacts"),
                ToolEntry("get_contact_details", "Get Contact Details"),
                ToolEntry("create_contact", "Create Contact"),
                ToolEntry("list_calendar_events", "List Calendar Events"),
                ToolEntry("create_calendar_event", "Create Calendar Event"),
                ToolEntry("delete_calendar_event", "Delete Calendar Event"),
                ToolEntry("list_calendars", "List Calendars"),
                ToolEntry("compose_email", "Compose Email"),
            ),
        ),
        ToolCategory(
            "Network",
            listOf(
                ToolEntry("http_request", "HTTP Request"),
                ToolEntry("get_network_info", "Get Network Info"),
                ToolEntry("get_public_ip", "Get Public IP"),
                ToolEntry("dns_lookup", "DNS Lookup"),
                ToolEntry("ping_host", "Ping Host"),
                ToolEntry("check_tcp_port", "Check TCP Port"),
                ToolEntry("list_wifi_networks", "List Wi-Fi Networks"),
                ToolEntry("get_wifi_info", "Get Wi-Fi Info"),
                ToolEntry("get_network_interfaces", "Get Network Interfaces"),
            ),
        ),
        ToolCategory(
            "Hardware",
            listOf(
                ToolEntry("list_sensors", "List Sensors"),
                ToolEntry("read_sensor", "Read Sensor"),
                ToolEntry("get_battery_details", "Get Battery Details"),
                ToolEntry("get_thermal_status", "Get Thermal Status"),
                ToolEntry("get_memory_info", "Get Memory Info"),
                ToolEntry("get_storage_usage", "Get Storage Usage"),
                ToolEntry("get_cpu_info", "Get CPU Info"),
                ToolEntry("get_uptime", "Get Uptime"),
                ToolEntry("vibrate", "Vibrate"),
                ToolEntry("get_nfc_state", "Get NFC State"),
                ToolEntry("get_bluetooth_state", "Get Bluetooth State"),
                ToolEntry("list_bluetooth_devices", "List Bluetooth Devices"),
                ToolEntry("get_display_info", "Get Display Info"),
                ToolEntry("get_audio_devices", "Get Audio Devices"),
            ),
        ),
        ToolCategory(
            "Device Settings",
            listOf(
                ToolEntry("get_brightness", "Get Brightness"),
                ToolEntry("set_brightness", "Set Brightness"),
                ToolEntry("get_screen_timeout", "Get Screen Timeout"),
                ToolEntry("set_screen_timeout", "Set Screen Timeout"),
                ToolEntry("set_rotation_lock", "Set Rotation Lock"),
                ToolEntry("get_font_scale", "Get Font Scale"),
                ToolEntry("set_font_scale", "Set Font Scale"),
                ToolEntry("get_ringer_mode", "Get Ringer Mode"),
                ToolEntry("set_ringer_mode", "Set Ringer Mode"),
                ToolEntry("get_dnd_state", "Get DND State"),
                ToolEntry("set_dnd_mode", "Set DND Mode"),
                ToolEntry("get_airplane_mode", "Get Airplane Mode"),
                ToolEntry("open_settings_screen", "Open Settings Screen"),
            ),
        ),
        ToolCategory(
            "Workspace Files",
            listOf(
                ToolEntry("list_workspace_files", "List Workspace Files"),
                ToolEntry("make_directory", "Make Directory"),
                ToolEntry("copy_path", "Copy Path"),
                ToolEntry("move_path", "Move Path"),
                ToolEntry("delete_path", "Delete Path"),
                ToolEntry("get_path_info", "Get Path Info"),
                ToolEntry("compute_file_hash", "Compute File Hash"),
                ToolEntry("find_files", "Find Files"),
                ToolEntry("search_in_files", "Search in Files"),
                ToolEntry("zip_path", "Zip Path"),
                ToolEntry("unzip_file", "Unzip File"),
                ToolEntry("read_workspace_file", "Read Workspace File"),
                ToolEntry("write_workspace_file", "Write Workspace File"),
            ),
        ),
        ToolCategory(
            "Data & Text",
            listOf(
                ToolEntry("base64_encode", "Base64 Encode"),
                ToolEntry("base64_decode", "Base64 Decode"),
                ToolEntry("hash_string", "Hash String"),
                ToolEntry("hmac_sign", "HMAC Sign"),
                ToolEntry("generate_uuid", "Generate UUID"),
                ToolEntry("random_number", "Random Number"),
                ToolEntry("random_string", "Random String"),
                ToolEntry("json_query", "JSON Query"),
                ToolEntry("regex_find", "Regex Find"),
                ToolEntry("regex_replace", "Regex Replace"),
                ToolEntry("url_encode", "URL Encode"),
                ToolEntry("url_decode", "URL Decode"),
                ToolEntry("text_stats", "Text Stats"),
                ToolEntry("diff_text", "Diff Text"),
                ToolEntry("csv_to_json", "CSV to JSON"),
            ),
        ),
        ToolCategory(
            "Shell & Terminal",
            listOf(
                ToolEntry("shell_exec", "Shell Exec"),
                ToolEntry("su_exec", "Su Exec"),
                ToolEntry("check_root", "Check Root"),
                ToolEntry("list_processes", "List Processes"),
                ToolEntry("get_system_properties", "Get System Properties"),
                ToolEntry("get_environment", "Get Environment"),
                ToolEntry("read_logcat", "Read Logcat"),
                ToolEntry("read_system_file", "Read System File"),
                ToolEntry("terminal_open", "Terminal Open"),
                ToolEntry("terminal_write", "Terminal Write"),
                ToolEntry("terminal_read", "Terminal Read"),
                ToolEntry("terminal_list", "Terminal List"),
                ToolEntry("terminal_close", "Terminal Close"),
            ),
        ),
        ToolCategory(
            "Media Library",
            listOf(
                ToolEntry("list_media", "List Media"),
                ToolEntry("search_media", "Search Media"),
                ToolEntry("get_media_info", "Get Media Info"),
                ToolEntry("delete_media", "Delete Media"),
                ToolEntry("scan_media_path", "Scan Media Path"),
                ToolEntry("play_media", "Play Media"),
                ToolEntry("stop_media", "Stop Media"),
                ToolEntry("get_playback_state", "Get Playback State"),
                ToolEntry("set_stream_volume", "Set Stream Volume"),
                ToolEntry("list_media_directories", "List Media Directories"),
            ),
        ),
        ToolCategory(
            "Audio Capture",
            listOf(
                ToolEntry("start_audio_recording", "Start Audio Recording"),
                ToolEntry("stop_audio_recording", "Stop Audio Recording"),
                ToolEntry("get_recording_state", "Get Recording State"),
                ToolEntry("list_recordings", "List Recordings"),
                ToolEntry("delete_recording", "Delete Recording"),
                ToolEntry("measure_noise_level", "Measure Noise Level"),
                ToolEntry("get_microphone_info", "Get Microphone Info"),
            ),
        ),
        ToolCategory(
            "Security & Vault",
            listOf(
                ToolEntry("encrypt_text", "Encrypt Text"),
                ToolEntry("decrypt_text", "Decrypt Text"),
                ToolEntry("keystore_generate_key", "Keystore Generate Key"),
                ToolEntry("keystore_list_keys", "Keystore List Keys"),
                ToolEntry("keystore_delete_key", "Keystore Delete Key"),
                ToolEntry("keystore_sign", "Keystore Sign"),
                ToolEntry("keystore_verify", "Keystore Verify"),
                ToolEntry("secret_store", "Secret Store"),
                ToolEntry("secret_get", "Secret Get"),
                ToolEntry("secret_delete", "Secret Delete"),
                ToolEntry("secret_list", "Secret List"),
                ToolEntry("generate_password", "Generate Password"),
                ToolEntry("password_strength", "Password Strength"),
            ),
        ),
        ToolCategory(
            "App Management",
            listOf(
                ToolEntry("list_installed_apps", "List Installed Apps"),
                ToolEntry("search_apps", "Search Apps"),
                ToolEntry("get_app_info", "Get App Info"),
                ToolEntry("get_app_permissions", "Get App Permissions"),
                ToolEntry("get_app_components", "Get App Components"),
                ToolEntry("get_app_signature", "Get App Signature"),
                ToolEntry("get_app_usage_stats", "Get App Usage Stats"),
                ToolEntry("launch_app", "Launch App"),
                ToolEntry("force_stop_app", "Force Stop App"),
                ToolEntry("open_app_settings", "Open App Settings"),
                ToolEntry("uninstall_app", "Uninstall App"),
                ToolEntry("install_apk", "Install Apk"),
                ToolEntry("get_install_status", "Get Install Status"),
                ToolEntry("query_intent_handlers", "Query Intent Handlers"),
            ),
        ),
        ToolCategory(
            "Bluetooth & USB",
            listOf(
                ToolEntry("get_bluetooth_summary", "Get Bluetooth Summary"),
                ToolEntry("start_bluetooth_scan", "Start Bluetooth Scan"),
                ToolEntry("get_bluetooth_scan_results", "Get Bluetooth Scan Results"),
                ToolEntry("stop_bluetooth_scan", "Stop Bluetooth Scan"),
                ToolEntry("pair_bluetooth_device", "Pair Bluetooth Device"),
                ToolEntry("unpair_bluetooth_device", "Unpair Bluetooth Device"),
                ToolEntry("get_bluetooth_bond_state", "Get Bluetooth Bond State"),
                ToolEntry("get_connected_bluetooth_devices", "Get Connected Bluetooth Devices"),
                ToolEntry("open_bluetooth_settings", "Open Bluetooth Settings"),
                ToolEntry("list_usb_devices", "List Usb Devices"),
                ToolEntry("get_usb_device_detail", "Get Usb Device Detail"),
            ),
        ),
        ToolCategory(
            "Notifications",
            listOf(
                ToolEntry("post_notification", "Post Notification"),
                ToolEntry("cancel_notification", "Cancel Notification"),
                ToolEntry("cancel_all_notifications", "Cancel All Notifications"),
                ToolEntry("list_notification_channels", "List Notification Channels"),
                ToolEntry("create_notification_channel", "Create Notification Channel"),
                ToolEntry("delete_notification_channel", "Delete Notification Channel"),
                ToolEntry("get_notification_policy", "Get Notification Policy"),
                ToolEntry("check_notification_permission", "Check Notification Permission"),
                ToolEntry("open_notification_settings", "Open Notification Settings"),
            ),
        ),
        ToolCategory(
            "Web & Feeds",
            listOf(
                ToolEntry("fetch_webpage", "Fetch Webpage"),
                ToolEntry("extract_links", "Extract Links"),
                ToolEntry("fetch_json", "Fetch Json"),
                ToolEntry("check_url_status", "Check Url Status"),
                ToolEntry("get_rss_feed", "Get Rss Feed"),
                ToolEntry("get_weather", "Get Weather"),
                ToolEntry("download_to_workspace", "Download To Workspace"),
            ),
        ),
        ToolCategory(
            "Agent Memory",
            listOf(
                ToolEntry("memory_set", "Memory Set"),
                ToolEntry("memory_get", "Memory Get"),
                ToolEntry("memory_list", "Memory List"),
                ToolEntry("memory_search", "Memory Search"),
                ToolEntry("memory_delete", "Memory Delete"),
                ToolEntry("memory_clear", "Memory Clear"),
            ),
        ),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpToolsSettingsScreen(
    onBack: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val perms by viewModel.toolPermissionsConfig.collectAsStateWithLifecycle()
    val cameraGranted by viewModel.isCameraPermissionGranted.collectAsStateWithLifecycle()
    val locationGranted by viewModel.isLocationPermissionGranted.collectAsStateWithLifecycle()
    val notificationListenerGranted by viewModel.isNotificationListenerEnabled.collectAsStateWithLifecycle()
    val microphoneGranted by viewModel.isMicrophonePermissionGranted.collectAsStateWithLifecycle()
    val controlsEnabled = serverStatus !is ServerStatus.Running && serverStatus !is ServerStatus.Starting

    // Refresh permissions on ON_RESUME — SAME pattern as PermissionsSettingsScreen.
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshPermissionStatus(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isGranted: (OptionalToolPermission) -> Boolean = { permission ->
        isPermissionGranted(
            permission = permission,
            camera = cameraGranted,
            location = locationGranted,
            notificationListener = notificationListenerGranted,
            microphone = microphoneGranted,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_mcp_tools_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Text(
                    text = "Changes take effect on server restart",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            ALL_TOOL_CATEGORIES.forEach { category ->
                val categoryPermissions =
                    category.tools.mapNotNull { OptionalToolPermissions.permissionForTool(it.toolName) }.distinct()
                item(key = "header_${category.header}") {
                    ToolCategoryHeader(
                        header = category.header,
                        missingPermission = categoryPermissions.any { !isGranted(it) },
                        onNavigateToPermissions = onNavigateToPermissions,
                    )
                }
                items(category.tools, key = { it.toolName }) { tool ->
                    ToolRow(
                        tool = tool,
                        perms = perms,
                        controlsEnabled = controlsEnabled,
                        categoryGranted = categoryPermissions.all { isGranted(it) },
                        isGranted = isGranted,
                        onNavigateToPermissions = onNavigateToPermissions,
                        onToolToggle = viewModel::updateToolEnabled,
                        onParamToggle = viewModel::updateParamEnabled,
                    )
                }
            }
        }
    }
}

/** Resolves whether an optional permission is granted from the individual permission flags. */
private fun isPermissionGranted(
    permission: OptionalToolPermission,
    camera: Boolean,
    location: Boolean,
    notificationListener: Boolean,
    microphone: Boolean,
): Boolean =
    when (permission) {
        OptionalToolPermission.CAMERA -> camera
        OptionalToolPermission.LOCATION -> location
        OptionalToolPermission.NOTIFICATION_LISTENER -> notificationListener
        OptionalToolPermission.MICROPHONE -> microphone
    }

@Composable
private fun ToolCategoryHeader(
    header: String,
    missingPermission: Boolean,
    onNavigateToPermissions: () -> Unit,
) {
    // Match the warning triangle to the header text size (respects font scaling).
    val warningIconSize =
        with(LocalDensity.current) {
            MaterialTheme.typography.titleMedium.fontSize
                .toDp()
        }
    if (!missingPermission) {
        Text(
            text = header,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        return
    }
    Column(modifier = Modifier.clickable { onNavigateToPermissions() }) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = header,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = WarningAmber,
                modifier = Modifier.size(warningIconSize),
            )
        }
        Text(
            text = stringResource(R.string.settings_mcp_tools_missing_permission),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun ToolRow(
    tool: ToolEntry,
    perms: ToolPermissionsConfig,
    controlsEnabled: Boolean,
    categoryGranted: Boolean,
    isGranted: (OptionalToolPermission) -> Boolean,
    onNavigateToPermissions: () -> Unit,
    onToolToggle: (String, Boolean) -> Unit,
    onParamToggle: (String, String, Boolean) -> Unit,
) {
    val toolEnabled = perms.isToolEnabled(tool.toolName)
    ListItem(
        headlineContent = { Text(tool.displayName) },
        trailingContent = {
            Switch(
                checked = toolEnabled,
                onCheckedChange = { onToolToggle(tool.toolName, it) },
                enabled = controlsEnabled && categoryGranted,
            )
        },
    )
    if (toolEnabled) {
        tool.params.forEach { param ->
            ToolParamRow(
                toolName = tool.toolName,
                param = param,
                perms = perms,
                controlsEnabled = controlsEnabled,
                categoryGranted = categoryGranted,
                isGranted = isGranted,
                onNavigateToPermissions = onNavigateToPermissions,
                onParamToggle = onParamToggle,
            )
        }
    }
}

@Composable
private fun ToolParamRow(
    toolName: String,
    param: ParamEntry,
    perms: ToolPermissionsConfig,
    controlsEnabled: Boolean,
    categoryGranted: Boolean,
    isGranted: (OptionalToolPermission) -> Boolean,
    onNavigateToPermissions: () -> Unit,
    onParamToggle: (String, String, Boolean) -> Unit,
) {
    val paramPermission = OptionalToolPermissions.permissionForParam(toolName, param.paramName)
    val paramGranted = paramPermission == null || isGranted(paramPermission)
    val showParamNote = categoryGranted && !paramGranted
    val leadingIcon: (@Composable () -> Unit)? =
        if (showParamNote) {
            { Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = WarningAmber) }
        } else {
            null
        }
    val supportingNote: (@Composable () -> Unit)? =
        if (showParamNote) {
            {
                Text(
                    text = stringResource(R.string.settings_mcp_tools_param_missing_permission),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        }
    ListItem(
        headlineContent = { Text(param.displayName) },
        modifier =
            if (showParamNote) {
                Modifier.padding(start = 32.dp).clickable { onNavigateToPermissions() }
            } else {
                Modifier.padding(start = 32.dp)
            },
        leadingContent = leadingIcon,
        supportingContent = supportingNote,
        trailingContent = {
            Switch(
                checked = perms.isParamEnabled(toolName, param.paramName),
                onCheckedChange = { onParamToggle(toolName, param.paramName, it) },
                enabled = controlsEnabled && categoryGranted && paramGranted,
            )
        },
    )
}
