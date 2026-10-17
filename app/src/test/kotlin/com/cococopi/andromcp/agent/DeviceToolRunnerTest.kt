package com.cococopi.andromcp.agent

import com.cococopi.andromcp.mcp.tools.RecordedTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceToolRunnerTest {
    private val schema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put("type", "object")
                    put("query", buildJsonObject { put("type", "string") })
                },
            required = listOf("query"),
        )

    @Test
    fun `the model sees the tool schema with its required list`() =
        runTest {
            val runner = DeviceToolRunner { mapOf("search" to tool("search") { ok("found") }) }

            val definition = runner.tools().single()

            assertEquals("search", definition.name)
            assertEquals("object", definition.parameters["type"]?.toString()?.trim('"'))
            assertTrue(definition.parameters["properties"].toString().contains("query"))
            assertEquals("[\"query\"]", definition.parameters["required"].toString())
        }

    @Test
    fun `tool text is handed back and errors are flagged`() =
        runTest {
            val failing = CallToolResult(content = listOf(TextContent(text = "nope")), isError = true)
            val runner =
                DeviceToolRunner {
                    mapOf(
                        "ok_tool" to tool("ok_tool") { ok("done") },
                        "bad_tool" to tool("bad_tool") { failing },
                    )
                }

            val okResult = runner.call(AgentToolCall("1", "ok_tool", JsonObject(emptyMap())))
            val badResult = runner.call(AgentToolCall("2", "bad_tool", JsonObject(emptyMap())))

            assertEquals("done", okResult.text)
            assertFalse(okResult.isError)
            assertEquals("nope", badResult.text)
            assertTrue(badResult.isError)
        }

    @Test
    fun `an unknown tool name is refused without a crash`() =
        runTest {
            val runner = DeviceToolRunner { mapOf("search" to tool("search") { ok("found") }) }

            val outcome = runner.call(AgentToolCall("1", "shell_exec", JsonObject(emptyMap())))

            assertTrue(outcome.isError)
            assertTrue(outcome.text.contains("Unknown tool"), "the model is told the name is not on offer")
        }

    @Test
    fun `a throwing handler becomes an error result carrying its message`() =
        runTest {
            val runner =
                DeviceToolRunner {
                    mapOf("boom" to tool("boom") { throw IllegalStateException("BLUETOOTH_CONNECT is not granted") })
                }

            val outcome = runner.call(AgentToolCall("1", "boom", JsonObject(emptyMap())))

            assertTrue(outcome.isError)
            assertEquals("BLUETOOTH_CONNECT is not granted", outcome.text)
        }

    @Test
    fun `the catalog is captured once per runner`() =
        runTest {
            var captures = 0
            val runner =
                DeviceToolRunner {
                    captures++
                    mapOf("search" to tool("search") { ok("found") })
                }

            runner.tools()
            runner.call(AgentToolCall("1", "search", JsonObject(emptyMap())))
            runner.tools()

            assertEquals(1, captures, "registration is expensive, so it happens once per runner")
        }

    private fun tool(
        name: String,
        handler: suspend () -> CallToolResult,
    ) = RecordedTool(
        name = name,
        description = "test tool",
        inputSchema = schema,
        handler = { handler() },
    )

    private fun ok(text: String) = CallToolResult(content = listOf(TextContent(text = text)), isError = false)
}
