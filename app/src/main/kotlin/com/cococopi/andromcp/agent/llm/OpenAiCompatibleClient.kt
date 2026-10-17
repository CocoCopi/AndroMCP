package com.cococopi.andromcp.agent.llm

import com.cococopi.andromcp.agent.AgentCompletion
import com.cococopi.andromcp.agent.AgentMessage
import com.cococopi.andromcp.agent.AgentToolCall
import com.cococopi.andromcp.agent.AgentToolDefinition
import com.cococopi.andromcp.agent.LlmClient
import com.cococopi.andromcp.agent.LlmException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `POST {base}/chat/completions` client.
 *
 * Serves OpenAI itself and every provider that mirrors its schema (xAI, DeepSeek, Mistral, Groq,
 * Cerebras, Together, Fireworks, OpenRouter, Perplexity, Moonshot, Zhipu, Qwen, NVIDIA NIM, Ollama,
 * LM Studio and any custom endpoint). Tool calls travel as `tool_calls` entries whose `arguments`
 * is a JSON *string*; results come back as `role: "tool"` messages keyed by `tool_call_id`.
 */
class OpenAiCompatibleClient(
    private val config: LlmConfig,
    private val transport: HttpTransport,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : LlmClient {
    override suspend fun complete(
        messages: List<AgentMessage>,
        tools: List<AgentToolDefinition>,
    ): AgentCompletion {
        val payload =
            buildJsonObject {
                put("model", config.effectiveModel)
                put("max_tokens", MAX_OUTPUT_TOKENS)
                putJsonArray("messages") { messages.forEach { add(wireMessage(it)) } }
                if (tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        tools.forEach { tool ->
                            addJsonObject {
                                put("type", FUNCTION_TYPE)
                                putJsonObject("function") {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.parameters)
                                }
                            }
                        }
                    }
                }
            }

        val body = transport.post(endpoint(), headers(), payload.toString())
        return parse(body)
    }

    private fun endpoint(): String = "${config.effectiveBaseUrl.trimTrailingSlash()}/$CHAT_PATH"

    private fun headers(): Map<String, String> =
        buildMap {
            put("Accept", "application/json")
            if (config.apiKey.isNotBlank()) put("Authorization", "Bearer ${config.apiKey}")
            putAll(extraHeaders)
        }

    private fun wireMessage(message: AgentMessage): JsonObject =
        when (message) {
            is AgentMessage.System -> {
                buildJsonObject {
                    put("role", "system")
                    put("content", message.text)
                }
            }

            is AgentMessage.User -> {
                buildJsonObject {
                    put("role", "user")
                    put("content", message.text)
                }
            }

            is AgentMessage.Assistant -> {
                buildJsonObject {
                    put("role", "assistant")
                    put("content", message.text)
                    if (message.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            message.toolCalls.forEach { call ->
                                addJsonObject {
                                    put("id", call.id)
                                    put("type", FUNCTION_TYPE)
                                    putJsonObject("function") {
                                        put("name", call.name)
                                        put("arguments", call.arguments.toString())
                                    }
                                }
                            }
                        }
                    }
                }
            }

            is AgentMessage.ToolResult -> {
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", message.callId)
                    put("content", message.text)
                }
            }
        }

    private fun parse(raw: String): AgentCompletion {
        val root = LlmJson.requireObject(raw, PROVIDER)
        LlmJson.requireNoError(root, PROVIDER)

        val choice =
            root.objectsOrEmpty("choices").firstOrNull()
                ?: throw LlmException("The provider returned no choices")
        val message =
            choice.objectOrNull("message")
                ?: throw LlmException("The provider returned a choice without a message")

        val calls =
            message.objectsOrEmpty("tool_calls").mapIndexedNotNull { index, call ->
                val function = call.objectOrNull("function") ?: return@mapIndexedNotNull null
                val name = function.stringOrNull("name") ?: return@mapIndexedNotNull null
                AgentToolCall(
                    id = call.stringOrNull("id") ?: "call_$index",
                    name = name,
                    arguments = LlmJson.parseArguments(function.stringOrNull("arguments")),
                )
            }

        return AgentCompletion(text = message.stringOrNull("content").orEmpty(), toolCalls = calls)
    }

    companion object {
        private const val PROVIDER = "The provider"
        private const val CHAT_PATH = "chat/completions"
        private const val FUNCTION_TYPE = "function"
        private const val MAX_OUTPUT_TOKENS = 4_096
    }
}
