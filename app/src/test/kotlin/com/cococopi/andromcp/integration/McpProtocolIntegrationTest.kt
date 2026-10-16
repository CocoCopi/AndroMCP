package com.cococopi.andromcp.integration

import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MCP Protocol Integration Tests")
class McpProtocolIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `client connects successfully and completes initialize handshake`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                // If connect() succeeds, the initialize handshake completed
                assertNotNull(client.serverCapabilities)
                assertNotNull(client.serverVersion)
                assertEquals("andromcp", client.serverVersion?.name) // No slug in default tests
            }
        }

    @Test
    fun `listTools returns all 57 registered tools`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result = client.listTools()
                assertEquals(EXPECTED_TOOL_COUNT, result.tools.size)
            }
        }

    @Test
    fun `listTools includes correct tool metadata for each tool`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result = client.listTools()

                result.tools.forEach { tool ->
                    assertNotNull(tool.name, "Tool missing name")
                    assertNotNull(tool.description, "Tool ${tool.name} missing description")
                    assertNotNull(tool.inputSchema, "Tool ${tool.name} missing inputSchema")
                }
            }
        }

    @Test
    fun `server capabilities include tools`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                assertNotNull(client.serverCapabilities?.tools)
            }
        }

    @Test
    fun `listTools contains expected tool names`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result = client.listTools()
                val toolNames = result.tools.map { it.name }.toSet()

                EXPECTED_TOOL_NAMES.forEach { expectedName ->
                    assertTrue(
                        toolNames.contains(expectedName),
                        "Expected tool '$expectedName' not found in: $toolNames",
                    )
                }
            }
        }

    @Test
    fun `listTools with device slug includes slug in tool names`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication(
                deviceSlug = "pixel7",
            ) { client, _ ->
                val result = client.listTools()
                result.tools.forEach { tool ->
                    assertTrue(
                        tool.name.startsWith("andromcp_pixel7_"),
                        "Tool '${tool.name}' should start with 'andromcp_pixel7_'",
                    )
                }
            }
        }

    @Test
    fun `server name includes device slug when set`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication(
                deviceSlug = "pixel7",
            ) { client, _ ->
                assertEquals("andromcp-pixel7", client.serverVersion?.name)
            }
        }

    @Test
    fun `server name without slug uses default`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                assertEquals("andromcp", client.serverVersion?.name)
            }
        }

    @Test
    fun `tool with slug can be called successfully`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.actionExecutor.tap(any(), any()) } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(
                deps = deps,
                deviceSlug = "pixel7",
            ) { client, _ ->
                val result =
                    client.callTool(
                        name = "andromcp_pixel7_tap",
                        arguments = mapOf("x" to 100, "y" to 200),
                    )
                assertNotEquals(true, result.isError)
            }
        }

    companion object {
        private const val EXPECTED_TOOL_COUNT = 234

        private val EXPECTED_TOOL_NAMES =
            setOf(
                // Touch actions
                "andromcp_tap",
                "andromcp_long_press",
                "andromcp_double_tap",
                "andromcp_swipe",
                "andromcp_scroll",
                // Gestures
                "andromcp_pinch",
                "andromcp_custom_gesture",
                // Node actions
                "andromcp_find_nodes",
                "andromcp_click_node",
                "andromcp_long_click_node",
                "andromcp_tap_node",
                "andromcp_scroll_to_node",
                // Screen introspection
                "andromcp_get_screen_state",
                // System actions
                "andromcp_press_back",
                "andromcp_press_home",
                "andromcp_press_recents",
                "andromcp_open_notifications",
                "andromcp_open_quick_settings",
                "andromcp_dismiss_keyboard",
                // Text input
                "andromcp_type_append_text",
                "andromcp_type_insert_text",
                "andromcp_type_replace_text",
                "andromcp_type_clear_text",
                "andromcp_press_key",
                // Utility
                "andromcp_get_clipboard",
                "andromcp_set_clipboard",
                "andromcp_wait_for_node",
                "andromcp_wait_for_idle",
                "andromcp_get_node_details",
                // File tools
                "andromcp_list_storage_locations",
                "andromcp_list_files",
                "andromcp_read_file",
                "andromcp_write_file",
                "andromcp_append_file",
                "andromcp_file_replace",
                "andromcp_download_from_url",
                "andromcp_delete_file",
                // App management
                "andromcp_open_app",
                "andromcp_list_apps",
                "andromcp_close_app",
                // Camera tools
                "andromcp_list_cameras",
                "andromcp_list_camera_photo_resolutions",
                "andromcp_list_camera_video_resolutions",
                "andromcp_take_camera_photo",
                "andromcp_save_camera_photo",
                "andromcp_save_camera_video",
                // Intent tools
                "andromcp_send_intent",
                "andromcp_open_uri",
                // Notification tools
                "andromcp_notification_list",
                "andromcp_notification_open",
                "andromcp_notification_dismiss",
                "andromcp_notification_snooze",
                "andromcp_notification_action",
                "andromcp_notification_reply",
                // Location tools
                "andromcp_get_location",
                // Sharing tools
                "andromcp_get_shared_content",
                "andromcp_share_file_via_web",
            )
    }
}
