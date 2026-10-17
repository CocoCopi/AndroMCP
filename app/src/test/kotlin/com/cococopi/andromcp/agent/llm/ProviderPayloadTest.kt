package com.cococopi.andromcp.agent.llm

import com.cococopi.andromcp.agent.AgentMessage
import com.cococopi.andromcp.agent.AgentToolCall
import com.cococopi.andromcp.agent.AgentToolDefinition
import com.cococopi.andromcp.agent.LlmException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProviderPayloadTest {
    private val tapTool =
        AgentToolDefinition(
            name = "tap",
            description = "Tap a node",
            parameters = buildJsonObject { put("type", "object") },
        )

    @Test
    fun `openai request carries bearer auth and tools, and parses tool calls`() =
        runTest {
            val transport =
                RecordingTransport(
                    """
                    {"choices":[{"message":{"content":"on it","tool_calls":[
                      {"id":"call_9","type":"function","function":{"name":"tap","arguments":"{\"x\":42}"}}]}}]}
                    """.trimIndent(),
                )

            val client =
                LlmClientFactory.create(
                    LlmConfig(ModelProvider.OPENAI, apiKey = "sk-test"),
                    transport,
                )
            val history = listOf(AgentMessage.System("be brief"), AgentMessage.User("tap"))
            val result = client.complete(history, listOf(tapTool))

            assertEquals("https://api.openai.com/v1/chat/completions", transport.url)
            assertEquals("Bearer sk-test", transport.headers["Authorization"])
            assertEquals("gpt-4.1", LlmJson.parseObject(transport.body)?.stringOrNull("model"))

            val call = result.toolCalls.single()
            assertEquals("tap", call.name)
            assertEquals("call_9", call.id)
            assertEquals("42", call.arguments.stringOrNull("x"))
            assertEquals("on it", result.text)
        }

    @Test
    fun `nvidia nim uses the openai wire format at its own endpoint`() =
        runTest {
            val transport = RecordingTransport("""{"choices":[{"message":{"content":"hi"}}]}""")

            val client =
                LlmClientFactory.create(
                    LlmConfig(ModelProvider.NVIDIA_NIM, apiKey = "nvapi-test"),
                    transport,
                )
            client.complete(listOf(AgentMessage.User("hello")), emptyList())

            assertEquals("https://integrate.api.nvidia.com/v1/chat/completions", transport.url)
            assertEquals("Bearer nvapi-test", transport.headers["Authorization"])
            assertEquals(
                "meta/llama-3.3-70b-instruct",
                LlmJson.parseObject(transport.body)?.stringOrNull("model"),
            )
        }

    @Test
    fun `anthropic hoists the system prompt and groups tool results`() =
        runTest {
            val transport =
                RecordingTransport(
                    """
                    {"content":[{"type":"text","text":"Tapped."},
                                {"type":"tool_use","id":"toolu_1","name":"tap","input":{"x":1}}]}
                    """.trimIndent(),
                )

            val client =
                LlmClientFactory.create(
                    LlmConfig(ModelProvider.ANTHROPIC, apiKey = "sk-ant-test"),
                    transport,
                )

            val history =
                listOf(
                    AgentMessage.System("system rules"),
                    AgentMessage.User("tap it"),
                    AgentMessage.Assistant(
                        toolCalls =
                            listOf(
                                AgentToolCall("toolu_1", "tap", JsonObject(emptyMap())),
                                AgentToolCall("toolu_2", "tap", JsonObject(emptyMap())),
                            ),
                    ),
                    AgentMessage.ToolResult("toolu_1", "tap", "ok"),
                    AgentMessage.ToolResult("toolu_2", "tap", "ok"),
                )

            val result = client.complete(history, listOf(tapTool))

            assertEquals("https://api.anthropic.com/v1/messages", transport.url)
            assertEquals("sk-ant-test", transport.headers["x-api-key"])
            assertEquals("2023-06-01", transport.headers["anthropic-version"])

            val body = LlmJson.parseObject(transport.body)!!
            assertEquals("system rules", body.stringOrNull("system"))

            val messages = body.arrayOrNull("messages")!!
            assertEquals(
                3,
                messages.size,
                "user, assistant and ONE grouped user message carrying both tool_result blocks",
            )
            val grouped = messages.last() as JsonObject
            assertEquals(2, grouped.arrayOrNull("content")!!.size)

            assertEquals("tap", result.toolCalls.single().name)
            assertEquals("toolu_1", result.toolCalls.single().id)
            assertEquals("Tapped.", result.text)
        }

    @Test
    fun `gemini builds the generateContent url and parses function calls`() =
        runTest {
            val transport =
                RecordingTransport(
                    """
                    {"candidates":[{"content":{"role":"model","parts":[
                       {"text":"checking"},
                       {"functionCall":{"name":"tap","args":{"x":1}}}]}}]}
                    """.trimIndent(),
                )

            val client =
                LlmClientFactory.create(
                    LlmConfig(ModelProvider.GEMINI, apiKey = "AIza-test"),
                    transport,
                )
            val history = listOf(AgentMessage.System("rules"), AgentMessage.User("tap"))
            val result = client.complete(history, listOf(tapTool))

            assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent" +
                    "?key=AIza-test",
                transport.url,
            )
            val body = LlmJson.parseObject(transport.body)!!
            assertEquals(
                "rules",
                body
                    .objectOrNull("system_instruction")
                    ?.objectsOrEmpty("parts")
                    ?.first()
                    ?.stringOrNull("text"),
            )
            assertEquals("tap", result.toolCalls.single().name)
            assertEquals("checking", result.text)
        }

    @Test
    fun `provider errors surface as LlmException with the provider message`() =
        runTest {
            val transport = RecordingTransport("""{"error":{"message":"invalid api key"}}""")

            val client =
                LlmClientFactory.create(
                    LlmConfig(ModelProvider.OPENAI, apiKey = "sk-bad"),
                    transport,
                )

            val error =
                assertThrows<LlmException> {
                    client.complete(listOf(AgentMessage.User("hi")), emptyList())
                }
            assertEquals("invalid api key", error.message)
        }

    @Test
    fun `a missing api key is rejected before any request`() {
        val transport = RecordingTransport("{}")

        assertThrows<LlmException> {
            LlmClientFactory.create(LlmConfig(ModelProvider.OPENAI), transport)
        }
        assertTrue(transport.url.isEmpty(), "no request may be attempted without a key")
    }

    private class RecordingTransport(
        private val response: String,
    ) : HttpTransport {
        var url: String = ""
        var headers: Map<String, String> = emptyMap()
        var body: String = ""

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): String {
            this.url = url
            this.headers = headers
            this.body = body
            return response
        }
    }
}
