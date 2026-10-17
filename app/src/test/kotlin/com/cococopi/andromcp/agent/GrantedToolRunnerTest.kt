package com.cococopi.andromcp.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GrantedToolRunnerTest {
    private val readFile = tool("read_file")
    private val sendSms = tool("send_sms")
    private val shell = tool("shell_exec")

    private val supportAgent =
        AgentProfile(
            id = "support",
            name = "Support",
            role = "Support",
            grantedTools = setOf("read_file"),
        )

    @Test
    fun `ungranted tools are never advertised`() =
        runTest {
            val delegate = StubRunner(listOf(readFile, sendSms, shell))
            val runner = GrantedToolRunner(delegate, supportAgent)

            assertEquals(listOf("read_file"), runner.tools().map { it.name })
        }

    @Test
    fun `an invented tool name is refused without reaching the device`() =
        runTest {
            val delegate = StubRunner(listOf(readFile, sendSms, shell))
            val runner = GrantedToolRunner(delegate, supportAgent)

            val outcome = runner.call(AgentToolCall("1", "shell_exec", JsonObject(emptyMap())))

            assertTrue(outcome.isError, "the refusal is an error result the model can read")
            assertTrue(outcome.text.contains("Not granted"), "the message names the boundary")
            assertTrue(delegate.calls.isEmpty(), "the delegate must not run an ungranted call")
        }

    @Test
    fun `a granted call reaches the delegate`() =
        runTest {
            val delegate = StubRunner(listOf(readFile))
            val runner = GrantedToolRunner(delegate, supportAgent)

            val outcome = runner.call(AgentToolCall("1", "read_file", JsonObject(emptyMap())))

            assertFalse(outcome.isError)
            assertEquals(listOf("read_file"), delegate.calls.map { it.name })
        }

    @Test
    fun `the executive assistant's all-grants marker opens every tool`() =
        runTest {
            val delegate = StubRunner(listOf(readFile, sendSms, shell))
            val ea =
                AgentProfile(
                    id = "ea",
                    name = "Assistant",
                    role = "Executive Assistant",
                    grantedTools = setOf(AgentProfile.ALL_GRANTS),
                )
            val runner = GrantedToolRunner(delegate, ea)

            assertEquals(3, runner.tools().size)
            assertFalse(runner.call(AgentToolCall("1", "shell_exec", JsonObject(emptyMap()))).isError)
        }

    private fun tool(name: String): AgentToolDefinition =
        AgentToolDefinition(
            name = name,
            description = "test tool",
            parameters = buildJsonObject { put("type", "object") },
        )

    private class StubRunner(
        private val definitions: List<AgentToolDefinition>,
    ) : AgentToolRunner {
        val calls = mutableListOf<AgentToolCall>()

        override suspend fun tools(): List<AgentToolDefinition> = definitions

        override suspend fun call(call: AgentToolCall): ToolCallOutcome {
            calls += call
            return ToolCallOutcome("ok")
        }
    }
}
