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
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder

/**
 * Native Gemini `generateContent` client.
 *
 * Gemini uses `contents` with `user`/`model` roles, `functionCall`/`functionResponse` parts, and a
 * `functionDeclarations` tool list keyed by `parameters` JSON schema. Function responses must be an
 * object, so a tool's text result is wrapped as `{"result": "..."}`. Gemini does not issue call ids;
 * stable per-response ids are synthesised so the transcript stays addressable.
 */
class GeminiClient(
    private val config: LlmConfig,
    private val transport: HttpTransport,
) : LlmClient {
    override suspend fun complete(
        messages: List<AgentMessage>,
        tools: List<AgentToolDefinition>,
    ): AgentCompletion {
        val payload =
            buildJsonObject {
                val system = systemPrompt(messages)
                if (system.isNotBlank()) {
                    putJsonObject("system_instruction") {
                        putJsonArray("parts") { addJsonObject { put("text", system) } }
                    }
                }
                put("contents", wireContents(messages))
                if (tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        addJsonObject {
                            putJsonArray("functionDeclarations") {
                                tools.forEach { tool ->
                                    addJsonObject {
                                        put("name", tool.name)
                                        put("description", tool.description)
                                        put("parameters", tool.parameters)
                                    }
                                }
                            }
                        }
                    }
                }
                putJsonObject("generationConfig") { put("maxOutputTokens", MAX_OUTPUT_TOKENS) }
            }

        val body = transport.post(endpoint(), headers(), payload.toString())
        return parse(body)
    }

    private fun endpoint(): String {
        val base = config.effectiveBaseUrl.trimTrailingSlash()
        val model = URLEncoder.encode(config.effectiveModel, Charsets.UTF_8.name())
        val key = if (config.apiKey.isBlank()) "" else "?key=${URLEncoder.encode(config.apiKey, Charsets.UTF_8.name())}"
        return "$base/models/$model:$GENERATE_PATH$key"
    }

    private fun headers(): Map<String, String> = mapOf("Accept" to "application/json")

    private fun systemPrompt(messages: List<AgentMessage>): String =
        messages.filterIsInstance<AgentMessage.System>().joinToString("\n\n") { it.text }

    private fun wireContents(messages: List<AgentMessage>): JsonArray =
        buildJsonArray {
            var index = 0
            while (index < messages.size) {
                when (val message = messages[index]) {
                    is AgentMessage.System -> {
                        index++
                    }

                    is AgentMessage.User -> {
                        addJsonObject {
                            put("role", USER_ROLE)
                            putJsonArray("parts") { addJsonObject { put("text", message.text) } }
                        }
                        index++
                    }

                    is AgentMessage.Assistant -> {
                        addJsonObject {
                            put("role", MODEL_ROLE)
                            putJsonArray("parts") {
                                if (message.text.isNotBlank()) addJsonObject { put("text", message.text) }
                                message.toolCalls.forEach { call ->
                                    addJsonObject {
                                        putJsonObject("functionCall") {
                                            put("name", call.name)
                                            put("args", call.arguments)
                                        }
                                    }
                                }
                            }
                        }
                        index++
                    }

                    is AgentMessage.ToolResult -> {
                        addJsonObject {
                            put("role", USER_ROLE)
                            putJsonArray("parts") {
                                while (index < messages.size && messages[index] is AgentMessage.ToolResult) {
                                    val result = messages[index] as AgentMessage.ToolResult
                                    addJsonObject {
                                        putJsonObject("functionResponse") {
                                            put("name", result.toolName)
                                            putJsonObject("response") { put("result", result.text) }
                                        }
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
        val root = LlmJson.requireObject(raw, PROVIDER)
        LlmJson.requireNoError(root, PROVIDER)

        val candidate =
            root.objectsOrEmpty("candidates").firstOrNull()
                ?: throw LlmException("Gemini returned no candidates")
        val parts = candidate.objectOrNull("content")?.arrayOrNull("parts").orEmpty()

        val text = StringBuilder()
        val calls = mutableListOf<AgentToolCall>()
        parts.forEachIndexed { index, element ->
            val part = element as? JsonObject ?: return@forEachIndexed
            part.stringOrNull("text")?.let { text.append(it) }
            part.objectOrNull("functionCall")?.let { call ->
                val name = call.stringOrNull("name") ?: return@forEachIndexed
                calls +=
                    AgentToolCall(
                        id = "gemini_$index",
                        name = name,
                        arguments = call.objectOrNull("args") ?: JsonObject(emptyMap()),
                    )
            }
        }

        return AgentCompletion(text = text.toString(), toolCalls = calls)
    }

    companion object {
        private const val PROVIDER = "Gemini"
        private const val GENERATE_PATH = "generateContent"
        private const val USER_ROLE = "user"
        private const val MODEL_ROLE = "model"
        private const val MAX_OUTPUT_TOKENS = 4_096
    }
}
