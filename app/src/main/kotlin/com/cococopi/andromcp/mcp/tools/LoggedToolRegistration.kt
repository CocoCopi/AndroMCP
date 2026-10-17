package com.cococopi.andromcp.mcp.tools

import com.cococopi.andromcp.data.model.ServerLogEntry
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.data.repository.ServerLogRepository
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

private const val NANOS_PER_MILLI = 1_000_000L
private const val FAILED_MARKER = "failed"

/**
 * One registered tool, captured while it is being added to a [Server].
 *
 * The in-app agent calls tools through exactly the same handlers the MCP clients call, which is what
 * keeps both paths honest: same argument validation, same permission gates, same logging.
 */
data class RecordedTool(
    val name: String,
    val description: String,
    val inputSchema: ToolSchema,
    val handler: suspend (CallToolRequest) -> CallToolResult,
)

/** Receives every tool that passes the permission gate during one registration pass. */
fun interface ToolRecorder {
    fun record(tool: RecordedTool)
}

/**
 * Wraps a tool handler so every invocation records a TOOL_CALL server-log entry: un-prefixed tool
 * name + duration, plus the constant NON-SENSITIVE [FAILED_MARKER] on failure (isError result or
 * thrown exception). Parameters and free-form error text are NEVER logged (tool errors can echo
 * device-derived data such as URLs). try/finally keeps the wrapper fully transparent — NO
 * exception is caught (and no linting suppression is needed); a cancelled invocation is not
 * logged (its coroutine is no longer active in the finally block).
 */
internal fun loggedToolHandler(
    serverLog: ServerLogRepository,
    toolName: String,
    toolCallIndicator: ToolCallIndicator = ToolCallIndicator.NONE,
    handler: suspend (CallToolRequest) -> CallToolResult,
): suspend (CallToolRequest) -> CallToolResult =
    { request ->
        val startNs = System.nanoTime()
        var completed: CallToolResult? = null
        runCatching { toolCallIndicator.onToolCallStarted(toolName) }
        try {
            val result = handler(request)
            completed = result
            result
        } finally {
            runCatching { toolCallIndicator.onToolCallFinished(toolName) }
            val durationMs = (System.nanoTime() - startNs) / NANOS_PER_MILLI
            val result = completed
            when {
                result != null -> {
                    serverLog.log(
                        type = ServerLogEntry.Type.TOOL_CALL,
                        message = if (result.isError == true) FAILED_MARKER else "",
                        toolName = toolName,
                        durationMs = durationMs,
                    )
                }

                coroutineContext.isActive -> {
                    serverLog.log(
                        type = ServerLogEntry.Type.TOOL_CALL,
                        message = FAILED_MARKER,
                        toolName = toolName,
                        durationMs = durationMs,
                    )
                }
            }
        }
    }

/**
 * Registers tools on [server], wrapping every handler with per-call TOOL_CALL logging.
 * A class method is used instead of a `Server` extension so the registration signature stays
 * within detekt's default `LongParameterList` function cap (5): `addTool` below has exactly 5
 * value parameters, and swapping `server` → `registrar` keeps every `registerXxxTools` function
 * at its current parameter count.
 *
 * [permissions] is the single enable/disable gate for the whole tool surface. Bundles that predate
 * the central gate also check it themselves; checking here as well keeps the newer bundles — which
 * pass the config straight through — behaving identically, so a tool the user turned off is never
 * advertised to a client and never callable.
 */
class LoggedToolRegistrar(
    private val server: Server,
    private val serverLog: ServerLogRepository,
    private val toolCallIndicator: ToolCallIndicator = ToolCallIndicator.NONE,
    private val permissions: ToolPermissionsConfig? = null,
    private val recorder: ToolRecorder? = null,
) {
    /** [Server.addTool] with per-call TOOL_CALL logging. [toolName] is the un-prefixed display name. */
    fun addTool(
        toolName: String,
        name: String,
        description: String,
        inputSchema: ToolSchema,
        handler: suspend (CallToolRequest) -> CallToolResult,
    ) {
        if (permissions?.isToolEnabled(toolName) == false) return
        val wrapped = loggedToolHandler(serverLog, toolName, toolCallIndicator, handler)
        recorder?.record(RecordedTool(toolName, description, inputSchema, wrapped))
        server.addTool(name = name, description = description, inputSchema = inputSchema) { request ->
            wrapped(request)
        }
    }
}
