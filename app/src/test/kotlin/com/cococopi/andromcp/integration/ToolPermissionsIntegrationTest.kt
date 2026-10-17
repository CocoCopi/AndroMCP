package com.cococopi.andromcp.integration

import android.net.Uri
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.services.accessibility.AccessibilityNodeData
import com.cococopi.andromcp.services.accessibility.BoundsData
import com.cococopi.andromcp.services.accessibility.ScreenInfo
import com.cococopi.andromcp.services.camera.VideoRecordingResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Tool Permissions Integration Tests")
class ToolPermissionsIntegrationTest {
    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
            enabled = true,
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "node_btn",
                        className = "android.widget.Button",
                        text = "OK",
                        bounds = BoundsData(100, 200, 300, 260),
                        clickable = true,
                        visible = true,
                        enabled = true,
                    ),
                ),
        )

    private val sampleScreenInfo =
        ScreenInfo(
            width = 1080,
            height = 2400,
            densityDpi = 420,
            orientation = "portrait",
        )

    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `disabled tool absent from tool list`() =
        runTest {
            val perms = ToolPermissionsConfig(disabledTools = setOf("tap"))

            McpIntegrationTestHelper.withTestApplication(perms = perms) { client, _ ->
                val result = client.listTools()
                val toolNames = result.tools.map { it.name }.toSet()

                assertFalse(
                    toolNames.contains("andromcp_tap"),
                    "Disabled tool 'tap' should not be in tool list",
                )
                assertTrue(
                    toolNames.contains("andromcp_long_press"),
                    "Enabled tool 'long_press' should be in tool list",
                )
            }
        }

    @Test
    fun `all tools disabled returns empty tool list`() =
        runTest {
            val perms = ToolPermissionsConfig(disabledTools = ALL_TOOL_NAMES)

            McpIntegrationTestHelper.withTestApplication(perms = perms) { client, _ ->
                val result = client.listTools()
                assertEquals(
                    0,
                    result.tools.size,
                    "All tools disabled should result in empty tool list",
                )
            }
        }

    @Test
    fun `include_screenshot absent from schema when disabled`() =
        runTest {
            val perms =
                ToolPermissionsConfig(
                    disabledParams = mapOf("get_screen_state" to setOf("include_screenshot")),
                )

            McpIntegrationTestHelper.withTestApplication(perms = perms) { client, _ ->
                val result = client.listTools()
                val tool = result.tools.find { it.name == "andromcp_get_screen_state" }
                assertTrue(tool != null, "get_screen_state tool should be registered")

                val properties = tool!!.inputSchema.properties
                assertFalse(
                    properties?.containsKey("include_screenshot") == true,
                    "include_screenshot should be absent from schema when disabled",
                )
            }
        }

    @Test
    fun `audio absent from schema when disabled`() =
        runTest {
            val perms =
                ToolPermissionsConfig(
                    disabledParams = mapOf("save_camera_video" to setOf("audio")),
                )

            McpIntegrationTestHelper.withTestApplication(perms = perms) { client, _ ->
                val result = client.listTools()
                val tool = result.tools.find { it.name == "andromcp_save_camera_video" }
                assertTrue(tool != null, "save_camera_video tool should be registered")

                val properties = tool!!.inputSchema.properties
                assertFalse(
                    properties?.containsKey("audio") == true,
                    "audio should be absent from schema when disabled",
                )
                // Other properties should still be present
                assertTrue(
                    properties?.containsKey("camera_id") == true,
                    "camera_id should still be in schema",
                )
                assertTrue(
                    properties?.containsKey("duration") == true,
                    "duration should still be in schema",
                )
            }
        }

    @Test
    fun `disabled tool call returns error`() =
        runTest {
            val perms = ToolPermissionsConfig(disabledTools = setOf("tap"))

            McpIntegrationTestHelper.withTestApplication(perms = perms) { client, _ ->
                val result =
                    client.callTool(
                        name = "andromcp_tap",
                        arguments = mapOf("x" to 500, "y" to 800),
                    )
                assertEquals(true, result.isError, "Calling a disabled tool should return error")
            }
        }

    @Test
    fun `include_screenshot enforced at execution time`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(
                deps = deps,
                tree = sampleTree,
                screenInfo = sampleScreenInfo,
            )

            val perms =
                ToolPermissionsConfig(
                    disabledParams = mapOf("get_screen_state" to setOf("include_screenshot")),
                )

            McpIntegrationTestHelper.withTestApplication(deps, perms = perms) { client, _ ->
                val result =
                    client.callTool(
                        name = "andromcp_get_screen_state",
                        arguments = mapOf("include_screenshot" to true),
                    )
                assertNotEquals(true, result.isError)
                // Should return text-only (1 content item), not text+image (2 items)
                assertEquals(
                    1,
                    result.content.size,
                    "Should return text-only when include_screenshot is disabled",
                )
                assertTrue(result.content[0] is TextContent)

                // Verify screenshot capture was never called
                coVerify(exactly = 0) {
                    deps.screenCaptureProvider.captureScreenshotBitmap(any(), any())
                }
            }
        }

    @Test
    fun `audio enforced at execution time`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val mockUri = mockk<Uri>()
            coEvery {
                deps.fileOperationProvider.createFileUri("loc1", "video.mp4", "video/mp4")
            } returns mockUri

            coEvery {
                deps.cameraProvider.saveVideo(
                    cameraId = "0",
                    outputUri = mockUri,
                    durationSeconds = 5,
                    width = null,
                    height = null,
                    audio = false,
                    flashMode = any(),
                )
            } returns
                VideoRecordingResult(
                    fileSizeBytes = 54321L,
                    durationMs = 5000L,
                    thumbnailData = "thumbdata",
                    thumbnailWidth = 320,
                    thumbnailHeight = 240,
                )

            val perms =
                ToolPermissionsConfig(
                    disabledParams = mapOf("save_camera_video" to setOf("audio")),
                )

            McpIntegrationTestHelper.withTestApplication(deps, perms = perms) { client, _ ->
                val result =
                    client.callTool(
                        name = "andromcp_save_camera_video",
                        arguments =
                            mapOf(
                                "camera_id" to "0",
                                "location_id" to "loc1",
                                "path" to "video.mp4",
                                "duration" to 5,
                                "audio" to true,
                            ),
                    )
                assertNotEquals(true, result.isError)

                // Verify audio was forced to false despite client sending true
                coVerify {
                    deps.cameraProvider.saveVideo(
                        cameraId = "0",
                        outputUri = mockUri,
                        durationSeconds = 5,
                        width = null,
                        height = null,
                        audio = false,
                        flashMode = any(),
                    )
                }
            }
        }

    companion object {
        private val ALL_TOOL_NAMES =
            setOf(
                "append_file",
                "base64_decode",
                "base64_encode",
                "cancel_all_notifications",
                "cancel_notification",
                "check_notification_permission",
                "check_root",
                "check_tcp_port",
                "check_url_status",
                "click_node",
                "close_app",
                "compose_email",
                "compute_file_hash",
                "copy_path",
                "create_calendar_event",
                "create_contact",
                "create_notification_channel",
                "csv_to_json",
                "custom_gesture",
                "decrypt_text",
                "delete_calendar_event",
                "delete_file",
                "delete_media",
                "delete_notification_channel",
                "delete_path",
                "delete_recording",
                "dial_number",
                "diff_text",
                "dismiss_keyboard",
                "dns_lookup",
                "double_tap",
                "download_from_url",
                "download_to_workspace",
                "encrypt_text",
                "extract_links",
                "fetch_json",
                "fetch_webpage",
                "file_replace",
                "find_files",
                "find_nodes",
                "force_stop_app",
                "generate_password",
                "generate_uuid",
                "get_airplane_mode",
                "get_app_components",
                "get_app_info",
                "get_app_permissions",
                "get_app_signature",
                "get_app_usage_stats",
                "get_audio_devices",
                "get_battery_details",
                "get_battery_status",
                "get_bluetooth_bond_state",
                "get_bluetooth_scan_results",
                "get_bluetooth_state",
                "get_bluetooth_summary",
                "get_brightness",
                "get_clipboard",
                "get_connected_bluetooth_devices",
                "get_contact_details",
                "get_cpu_info",
                "get_device_info",
                "get_display_info",
                "get_dnd_state",
                "get_environment",
                "get_font_scale",
                "get_install_status",
                "get_location",
                "get_media_info",
                "get_memory_info",
                "get_microphone_info",
                "get_network_info",
                "get_network_interfaces",
                "get_nfc_state",
                "get_node_details",
                "get_notification_policy",
                "get_path_info",
                "get_playback_state",
                "get_public_ip",
                "get_recording_state",
                "get_ringer_mode",
                "get_rss_feed",
                "get_screen_state",
                "get_screen_timeout",
                "get_shared_content",
                "get_storage_usage",
                "get_system_properties",
                "get_thermal_status",
                "get_uptime",
                "get_usb_device_detail",
                "get_volume",
                "get_weather",
                "get_wifi_info",
                "hash_string",
                "hmac_sign",
                "http_request",
                "install_apk",
                "json_query",
                "keystore_delete_key",
                "keystore_generate_key",
                "keystore_list_keys",
                "keystore_sign",
                "keystore_verify",
                "launch_app",
                "list_apps",
                "list_bluetooth_devices",
                "list_calendar_events",
                "list_calendars",
                "list_call_log",
                "list_camera_photo_resolutions",
                "list_camera_video_resolutions",
                "list_cameras",
                "list_files",
                "list_installed_apps",
                "list_media",
                "list_media_directories",
                "list_notification_channels",
                "list_processes",
                "list_recordings",
                "list_sensors",
                "list_sms_threads",
                "list_storage_locations",
                "list_usb_devices",
                "list_wifi_networks",
                "list_workspace_files",
                "long_click_node",
                "long_press",
                "make_directory",
                "measure_noise_level",
                "media_control",
                "memory_clear",
                "memory_delete",
                "memory_get",
                "memory_list",
                "memory_search",
                "memory_set",
                "move_path",
                "notification_action",
                "notification_dismiss",
                "notification_list",
                "notification_open",
                "notification_reply",
                "notification_snooze",
                "open_app",
                "open_app_settings",
                "open_bluetooth_settings",
                "open_notification_settings",
                "open_notifications",
                "open_quick_settings",
                "open_settings_screen",
                "open_uri",
                "pair_bluetooth_device",
                "password_strength",
                "pinch",
                "ping_host",
                "play_media",
                "post_notification",
                "press_back",
                "press_home",
                "press_key",
                "press_recents",
                "query_intent_handlers",
                "random_number",
                "random_string",
                "read_file",
                "read_logcat",
                "read_sensor",
                "read_sms_thread",
                "read_system_file",
                "read_workspace_file",
                "regex_find",
                "regex_replace",
                "save_camera_photo",
                "save_camera_video",
                "scan_media_path",
                "scroll",
                "scroll_to_node",
                "search_apps",
                "search_contacts",
                "search_in_files",
                "search_media",
                "search_sms",
                "secret_delete",
                "secret_get",
                "secret_list",
                "secret_store",
                "send_intent",
                "send_sms",
                "set_alarm",
                "set_brightness",
                "set_clipboard",
                "set_dnd_mode",
                "set_flashlight",
                "set_font_scale",
                "set_ringer_mode",
                "set_rotation_lock",
                "set_screen_timeout",
                "set_stream_volume",
                "set_timer",
                "set_volume",
                "share_file_via_web",
                "shell_exec",
                "speak_text",
                "start_audio_recording",
                "start_bluetooth_scan",
                "stop_audio_recording",
                "stop_bluetooth_scan",
                "stop_media",
                "su_exec",
                "swipe",
                "take_camera_photo",
                "tap",
                "tap_node",
                "terminal_close",
                "terminal_list",
                "terminal_open",
                "terminal_read",
                "terminal_write",
                "text_stats",
                "type_append_text",
                "type_clear_text",
                "type_insert_text",
                "type_replace_text",
                "uninstall_app",
                "unpair_bluetooth_device",
                "unzip_file",
                "url_decode",
                "url_encode",
                "vibrate",
                "wait_for_idle",
                "wait_for_node",
                "write_file",
                "write_workspace_file",
                "zip_path",
            )
    }
}
