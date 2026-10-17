package com.cococopi.andromcp.agent

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentEngineTest {
    private val tapTool =
        AgentToolDefinition(
            name = "tap",
            description = "Tap a node",
            parameters = buildJsonObject { put("type", "object") },
        )

    @Test
    fun `executes the tool call and finishes with the model answer`() =
        runTest {
            val runner = RecordingRunner(listOf(tapTool))
            var secondCallMessages: List<AgentMessage> = emptyList()

            val client =
                LlmClient { messages, _ ->
                    if (messages.none { it is AgentMessage.ToolResult }) {
                        AgentCompletion(toolCalls = listOf(AgentToolCall("c1", "tap", JsonObject(emptyMap()))))
                    } else {
                        secondCallMessages = messages
                        AgentCompletion(text = "Tapped the button.")
                    }
                }

            val events = AgentEngine(client, runner).run("system", emptyList(), "tap the button").toList()

            assertEquals(listOf("tap"), runner.calls.map { it.name })
            assertEquals(
                FinishReason.COMPLETED,
                (events.last() as AgentEvent.Finished).reason,
                "a turn with no pending tool calls ends as completed",
            )
            assertTrue(
                events.any { it is AgentEvent.ToolCompleted },
                "the tool completion is streamed back to the UI",
            )
            assertTrue(
                secondCallMessages.any { it is AgentMessage.ToolResult },
                "the model must see the tool result on the next step",
            )
        }

    @Test
    fun `risky tool is confirmed and denial is returned to the model`() =
        runTest {
            val smsTool = tapTool.copy(name = "send_sms")
            val runner = RecordingRunner(listOf(smsTool))
            var secondCallMessages: List<AgentMessage> = emptyList()

            val client =
                LlmClient { messages, _ ->
                    if (messages.none { it is AgentMessage.ToolResult }) {
                        AgentCompletion(
                            toolCalls = listOf(AgentToolCall("c1", "send_sms", JsonObject(emptyMap()))),
                        )
                    } else {
                        secondCallMessages = messages
                        AgentCompletion(text = "Okay, I will not send it.")
                    }
                }

            val events =
                AgentEngine(client, runner, approvalGate = ApprovalGate { false })
                    .run("system", emptyList(), "text mum")
                    .toList()

            assertTrue(runner.calls.isEmpty(), "a refused call must never reach the device")
            assertTrue(events.any { it is AgentEvent.ToolDenied })
            val denial =
                secondCallMessages.filterIsInstance<AgentMessage.ToolResult>().single()
            assertTrue(denial.isError, "a refusal is reported as an error result")
            assertEquals(
                FinishReason.COMPLETED,
                (events.last() as AgentEvent.Finished).reason,
            )
        }

    @Test
    fun `a looping model stops at the step budget`() =
        runTest {
            val runner = RecordingRunner(listOf(tapTool))
            val client =
                LlmClient { _, _ ->
                    AgentCompletion(toolCalls = listOf(AgentToolCall("c", "tap", JsonObject(emptyMap()))))
                }

            val events =
                AgentEngine(client, runner, maxSteps = 3)
                    .run("system", emptyList(), "loop")
                    .toList()

            assertEquals(3, runner.calls.size, "one tool call per permitted step")
            assertEquals(
                FinishReason.STEP_LIMIT,
                (events.last() as AgentEvent.Finished).reason,
            )
        }

    @Test
    fun `risky policy flags outgoing, destructive and writing calls only`() {
        val noArgs = JsonObject(emptyMap())

        assertTrue(RiskyToolPolicy.isRisky("send_sms", noArgs), "sending a message needs confirmation")
        assertTrue(RiskyToolPolicy.isRisky("shell_exec", noArgs), "shell access needs confirmation")
        assertTrue(RiskyToolPolicy.isRisky("delete_file", noArgs), "deleting needs confirmation")
        assertTrue(
            RiskyToolPolicy.isRisky("http_request", buildJsonObject { put("method", "POST") }),
            "a writing HTTP call can exfiltrate data",
        )
        assertFalse(RiskyToolPolicy.isRisky("tap", noArgs))
        assertFalse(
            RiskyToolPolicy.isRisky("http_request", buildJsonObject { put("method", "GET") }),
            "a read-only fetch runs unattended",
        )
    }

    private class RecordingRunner(
        private val definitions: List<AgentToolDefinition>,
    ) : AgentToolRunner {
        val calls = mutableListOf<AgentToolCall>()

        override suspend fun tools(): List<AgentToolDefinition> = definitions

        override suspend fun call(call: AgentToolCall): ToolCallOutcome {
            calls += call
            return ToolCallOutcome("done")
        }
    }
}
