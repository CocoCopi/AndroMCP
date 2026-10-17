package com.cococopi.andromcp.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentDirectoryTest {
    private val ea =
        AgentProfile(
            id = "ea",
            name = "Assistant",
            role = "Executive Assistant",
            grantedTools = setOf(AgentProfile.ALL_GRANTS),
        )
    private val ops =
        AgentProfile(
            id = "ops",
            name = "Ops",
            role = "Operations",
            managerId = "ea",
            grantedTools = setOf("send_sms", "read_file"),
        )
    private val support =
        AgentProfile(
            id = "support",
            name = "Support",
            role = "Support",
            managerId = "ops",
            grantedTools = setOf("read_file"),
        )
    private val directory = AgentDirectory(listOf(ea, ops, support))

    @Test
    fun `a well formed chart validates and knows its depths`() {
        assertEquals(emptyList<String>(), directory.validate())
        assertEquals(0, directory.depthOf("ea"))
        assertEquals(1, directory.depthOf("ops"))
        assertEquals(2, directory.depthOf("support"))
        assertEquals("ea", directory.executiveAssistant()?.id)
        assertEquals(listOf("support"), directory.reportsOf("ops").map { it.id })
    }

    @Test
    fun `the EA reaches anyone and a manager only its own reports`() {
        assertTrue(directory.canReach("ea", "support"), "the EA reaches any agent")
        assertTrue(directory.canReach("ops", "support"), "a manager reaches its direct report")
        assertFalse(directory.canReach("support", "ops"), "a report cannot act on its manager")
        assertFalse(directory.canReach("ops", "ea"), "a manager cannot act on the EA")
        assertFalse(directory.canReach("support", "ea"), "a specialist cannot reach up the chain")
    }

    @Test
    fun `questions escalate only to the direct manager`() {
        assertTrue(directory.canEscalate("support", "ops"))
        assertFalse(directory.canEscalate("support", "ea"), "never past the manager")
        assertFalse(directory.canEscalate("ops", "support"), "never sideways or downward")
    }

    @Test
    fun `a hiring agent cannot grant more than it holds`() {
        assertTrue(directory.canGrant("ea", "shell_exec"), "the EA starts with everything")
        assertTrue(directory.canGrant("ops", "send_sms"), "ops may hand over its own tools")
        assertFalse(directory.canGrant("ops", "shell_exec"), "ops cannot hand over what it lacks")
        assertFalse(directory.canGrantAll("ops", setOf("read_file", "install_apk")))
        assertTrue(directory.canGrantAll("ops", setOf("read_file", "send_sms")))
    }

    @Test
    fun `at mentions resolve case insensitively`() {
        assertEquals("support", directory.findByName("@Support")?.id)
        assertEquals("ops", directory.findByName("ops")?.id)
        assertEquals(null, directory.findByName("@nobody"))
    }

    @Test
    fun `chains deeper than the limit are rejected`() {
        val deep =
            (1..5).map { level ->
                AgentProfile(
                    id = "a$level",
                    name = "Agent $level",
                    role = "Worker",
                    managerId = if (level == 1) "ea" else "a${level - 1}",
                )
            }
        val problems = AgentDirectory(listOf(ea) + deep).validate()

        assertTrue(
            problems.any { it.contains("past the") },
            "a 5-deep chain must be reported, got: $problems",
        )
    }

    @Test
    fun `cycles and orphaned agents are reported`() {
        val loopA = AgentProfile(id = "x", name = "X", role = "Worker", managerId = "y")
        val loopB = AgentProfile(id = "y", name = "Y", role = "Worker", managerId = "x")
        val orphan = AgentProfile(id = "orphan", name = "Orphan", role = "Worker", managerId = "ghost")

        val problems = AgentDirectory(listOf(ea, loopA, loopB, orphan)).validate()

        assertTrue(problems.any { it.contains("Unreachable") }, "cycles strand agents, got: $problems")
        assertTrue(problems.any { it.contains("unknown manager") }, "a dangling manager is reported")
    }

    @Test
    fun `two roots and duplicate names are rejected`() {
        val secondRoot = AgentProfile(id = "ea2", name = "Assistant", role = "Second EA")
        val problems = AgentDirectory(listOf(ea, secondRoot)).validate()

        assertTrue(problems.any { it.contains("More than one agent has no manager") })
        assertTrue(problems.any { it.contains("share the name") })
    }
}
