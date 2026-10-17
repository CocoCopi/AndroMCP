package com.cococopi.andromcp.agent

import kotlinx.serialization.json.JsonObject

/** One tool the model may call, with the JSON schema of its arguments. */
data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** A model's request to invoke one tool. [id] is the provider's call id (synthesised when absent). */
data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

/** Ground truth for a conversation turn. */
sealed interface AgentMessage {
    data class System(
        val text: String,
    ) : AgentMessage

    data class User(
        val text: String,
    ) : AgentMessage

    data class Assistant(
        val text: String = "",
        val toolCalls: List<AgentToolCall> = emptyList(),
    ) : AgentMessage

    data class ToolResult(
        val callId: String,
        val toolName: String,
        val text: String,
        val isError: Boolean = false,
    ) : AgentMessage
}

/** What the model returned for one completion. */
data class AgentCompletion(
    val text: String = "",
    val toolCalls: List<AgentToolCall> = emptyList(),
)

/** Result of executing one tool call. */
data class ToolCallOutcome(
    val text: String,
    val isError: Boolean = false,
)

/** Streaming events emitted by [AgentEngine] while a turn runs. */
sealed interface AgentEvent {
    /** Assistant prose for this step (may arrive once per model call). */
    data class Text(
        val text: String,
    ) : AgentEvent

    /** The model asked for a tool; execution starts next unless approval is required. */
    data class ToolRequested(
        val call: AgentToolCall,
    ) : AgentEvent

    /** A tool finished; [output] is the (possibly truncated) text handed back to the model. */
    data class ToolCompleted(
        val call: AgentToolCall,
        val output: String,
        val isError: Boolean,
    ) : AgentEvent

    /** The call was blocked because the user (or policy) refused it. */
    data class ToolDenied(
        val call: AgentToolCall,
    ) : AgentEvent

    /** The turn ended. [reason] explains whether work completed or was cut short. */
    data class Finished(
        val reason: FinishReason,
    ) : AgentEvent

    /** The provider or transport failed; the turn is over. */
    data class Failed(
        val message: String,
    ) : AgentEvent
}

enum class FinishReason {
    /** The model produced a final answer with no pending tool calls. */
    COMPLETED,

    /** The step budget ran out before the model finished. */
    STEP_LIMIT,
}

/** Transport or provider failure with a message safe to show the user. */
class LlmException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** One model backend bound to a key, endpoint and model id. */
fun interface LlmClient {
    suspend fun complete(
        messages: List<AgentMessage>,
        tools: List<AgentToolDefinition>,
    ): AgentCompletion
}

/** Decides whether one tool call may run. */
fun interface ApprovalGate {
    suspend fun approve(call: AgentToolCall): Boolean
}
