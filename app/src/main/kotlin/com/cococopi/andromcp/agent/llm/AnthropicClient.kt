package com.cococopi.andromcp.agent.llm

import com.cococopi.andromcp.agent.AgentCompletion
import com.cococopi.andromcp.agent.AgentMessage
import com.cococopi.andromcp.agent.AgentToolCall
import com.cococopi.andromcp.agent.AgentToolDefinition
import com.cococopi.andromcp.agent.LlmClient
import com.cococopi.andromcp.agent.LlmException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Native Anthropic Messages API client (`POST {base}/messages`).
 *
 * Anthropic differs from the OpenAI shape in three ways this class handles: the system prompt is a
 * top-level field instead of a message, content is a list of typed blocks, and tool results are
 * `tool_result` blocks on a *user* message — consecutive results are grouped into one message so the
 * strict user/assistant alternation is preserved.
 */
class AnthropicClient(
    private val config: LlmConfig,
    private val transport: HttpTransport,
) : LlmClient {
    override suspend fun complete(
        messages: List<AgentMessage>,
        tools: List<AgentToolDefinition>,
    ): AgentCompletion {
        val payload =
            buildJsonObject {
                put("model", config.effectiveModel)
                put("max_tokens", MAX_OUTPUT_TOKENS)
                put("system", systemPrompt(messages))
                put("messages", wireMessages(messages))
                if (tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        tools.forEach { tool ->
                            addJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("input_schema", tool.parameters)
                            }
                        }
                    }
                }
            }

        val body = transport.post(endpoint(), headers(), payload.toString())
        return parse(body)
    }

    private fun endpoint(): String = "${config.effectiveBaseUrl.trimTrailingSlash()}/$MESSAGES_PATH"

    private fun headers(): Map<String, String> =
        mapOf(
            "x-api-key" to config.apiKey,
            "anthropic-version" to ANTHROPIC_VERSION,
            "Accept" to "application/json",
        )

    private fun systemPrompt(messages: List<AgentMessage>): String =
        messages.filterIsInstance<AgentMessage.System>().joinToString("\n\n") { it.text }

    private fun wireMessages(messages: List<AgentMessage>): JsonArray =
        buildJsonArray {
            var index = 0
            while (index < messages.size) {
                when (val message = messages[index]) {
                    is AgentMessage.System -> {
                        index++
                    }

                    is AgentMessage.User -> {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", TEXT_TYPE)
                                    put("text", message.text)
                                }
                            }
                        }
                        index++
                    }

                    is AgentMessage.Assistant -> {
                        addJsonObject {
                            put("role", "assistant")
                            putJsonArray("content") {
                                if (message.text.isNotBlank()) {
                                    addJsonObject {
                                        put("type", TEXT_TYPE)
                                        put("text", message.text)
                                    }
                                }
                                message.toolCalls.forEach { call ->
                                    addJsonObject {
                                        put("type", TOOL_USE_TYPE)
                                        put("id", call.id)
                                        put("name", call.name)
                                        put("input", call.arguments)
                                    }
                                }
                            }
                        }
                        index++
                    }

                    is AgentMessage.ToolResult -> {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                while (index < messages.size && messages[index] is AgentMessage.ToolResult) {
                                    val result = messages[index] as AgentMessage.ToolResult
                                    addJsonObject {
                                        put("type", TOOL_RESULT_TYPE)
                                        put("tool_use_id", result.callId)
                                        put("content", result.text)
                                        if (result.isError) put("is_error", true)
                                    }
                                    index++
                                }
                            }
                        }
                    }
                }
            }
        }

    private fun parse(raw: String): AgentCompletion {
        val root = LlmJson.parseObject(raw) ?: throw LlmException("Anthropic returned a non-JSON response")
        root.objectOrNull("error")?.let { error ->
            throw LlmException(error.stringOrNull("message") ?: "Anthropic returned an error")
        }

        val blocks =
            root.arrayOrNull("content")
                ?: throw LlmException("Anthropic returned no content blocks")

        val text = StringBuilder()
        val calls = mutableListOf<AgentToolCall>()
        blocks.forEachIndexed { index, element ->
            val block = element as? JsonObject ?: return@forEachIndexed
            when (block.stringOrNull("type")) {
                TEXT_TYPE -> {
                    text.append(block.stringOrNull("text").orEmpty())
                }

                TOOL_USE_TYPE -> {
                    val name = block.stringOrNull("name") ?: return@forEachIndexed
                    calls +=
                        AgentToolCall(
                            id = block.stringOrNull("id") ?: "call_$index",
                            name = name,
                            arguments = block.objectOrNull("input") ?: JsonObject(emptyMap()),
                        )
                }
            }
        }

        return AgentCompletion(text = text.toString(), toolCalls = calls)
    }

    companion object {
        private const val MESSAGES_PATH = "messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val TEXT_TYPE = "text"
        private const val TOOL_USE_TYPE = "tool_use"
        private const val TOOL_RESULT_TYPE = "tool_result"
        private const val MAX_OUTPUT_TOKENS = 4_096
    }
}
