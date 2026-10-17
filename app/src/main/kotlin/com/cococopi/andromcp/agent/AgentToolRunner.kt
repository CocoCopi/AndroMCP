package com.cococopi.andromcp.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val HTTP_METHOD_FIELD = "method"

/**
 * Everything the agent needs from the device: the tools it may call and the call itself.
 *
 * Implementations decide how a tool is reached (in-process registry, the app's own MCP server,
 * a remote one); the engine only sees this contract.
 */
interface AgentToolRunner {
    /** Tools advertised to the model for this turn. */
    suspend fun tools(): List<AgentToolDefinition>

    /** Executes [call] and returns text handed back to the model. */
    suspend fun call(call: AgentToolCall): ToolCallOutcome
}

/**
 * Classifies tool calls that need an explicit user confirmation before they run.
 *
 * The list favours the irreversible and the outgoing: anything that sends a message, spends
 * money, deletes or overwrites data, installs software, or escapes the app sandbox. Reads,
 * navigation, screenshots and queries stay unattended so multi-step browsing is not interrupted.
 */
object RiskyToolPolicy {
    private val RISKY_TOOLS =
        setOf(
            "send_sms",
            "compose_email",
            "dial_number",
            "notification_reply",
            "post_notification",
            "share_file_via_web",
            "send_intent",
            "speak_text",
            "install_apk",
            "uninstall_app",
            "force_stop_app",
            "delete_calendar_event",
            "keystore_delete_key",
            "secret_delete",
            "memory_delete",
            "memory_clear",
            "cancel_all_notifications",
            "move_path",
            "write_file",
            "append_file",
            "file_replace",
            "regex_replace",
            "terminal_write",
        )

    /** Call prefixes that are always destructive or shell-level, whatever the tool name. */
    private val RISKY_PREFIXES = listOf("delete_", "shell_", "su_", "terminal_")

    private const val HTTP_TOOL = "http_request"

    /** True when [toolName] must be confirmed by the user before it runs. */
    fun isRisky(
        toolName: String,
        arguments: JsonObject,
    ): Boolean =
        toolName in RISKY_TOOLS ||
            RISKY_PREFIXES.any { toolName.startsWith(it) } ||
            (toolName == HTTP_TOOL && isWritingHttpCall(arguments))

    /**
     * HTTP writes can exfiltrate data or trigger side effects; reads (GET/HEAD) are unrestricted. An
     * absent or unparseable method counts as writing, so an unexpected shape fails safe.
     */
    private fun isWritingHttpCall(arguments: JsonObject): Boolean {
        val method =
            (arguments[HTTP_METHOD_FIELD] as? JsonPrimitive)?.content?.uppercase()
                ?: return true
        return method != "GET" && method != "HEAD"
    }
}
