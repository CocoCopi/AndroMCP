package com.cococopi.andromcp.agent

import com.cococopi.andromcp.mcp.tools.RecordedTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Runs the in-app agent's tool calls through the app's own tool handlers — no HTTP server, no port,
 * no token. The catalog is captured from [com.cococopi.andromcp.services.mcp.ToolGraph], so the
 * agent sees the same names, schemas and permission gates an external MCP client sees.
 *
 * Capture is lazy and happens once per runner: building 234 handlers costs a few milliseconds, and
 * doing it per call would be wasteful, while caching across runs would ignore settings changes the
 * user just made.
 */
class DeviceToolRunner(
    private val captureCatalog: suspend () -> Map<String, RecordedTool>,
) : AgentToolRunner {
    private var cached: Map<String, RecordedTool>? = null

    override suspend fun tools(): List<AgentToolDefinition> =
        catalog().values.map { tool ->
            AgentToolDefinition(
                name = tool.name,
                description = tool.description,
                parameters = schemaJson(tool.inputSchema),
            )
        }

    override suspend fun call(call: AgentToolCall): ToolCallOutcome {
        val tool =
            catalog()[call.name]
                ?: return ToolCallOutcome(
                    text = "Unknown tool '${call.name}'. Use only the tools you were offered.",
                    isError = true,
                )

        return try {
            val result =
                tool.handler(
                    buildCallToolRequest {
                        name = tool.name
                        arguments(call.arguments)
                    },
                )
            ToolCallOutcome(text = result.text(), isError = result.isError == true)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Tool handlers signal failure by throwing (McpToolException carries the actionable
            // message: which permission is missing, which argument is wrong). Hand the model that
            // message instead of a stack trace so it can correct itself.
            ToolCallOutcome(text = e.message ?: "The tool failed without a message.", isError = true)
        }
    }

    private suspend fun catalog(): Map<String, RecordedTool> = cached ?: captureCatalog().also { cached = it }

    /** The schema the model needs: an object schema carrying the tool's properties and required list. */
    private fun schemaJson(schema: ToolSchema): JsonObject =
        buildJsonObject {
            put("type", SCHEMA_TYPE)
            schema.properties?.let { properties -> put("properties", properties) }
            val required = schema.required.orEmpty()
            if (required.isNotEmpty()) {
                putJsonArray("required") { required.forEach { add(it) } }
            }
        }

    private fun CallToolResult.text(): String {
        val text = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        if (text.isNotBlank()) return text
        return structuredContent?.toString() ?: NO_OUTPUT
    }

    private companion object {
        const val SCHEMA_TYPE = "object"
        const val NO_OUTPUT = "(the tool returned no output)"
    }
}
