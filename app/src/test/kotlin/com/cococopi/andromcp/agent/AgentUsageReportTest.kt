package com.cococopi.andromcp.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentUsageReportTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    private fun record(
        id: String,
        agentId: String,
        ageDays: Long,
        tokens: Int? = 1_000,
        cost: Double? = 0.02,
        status: AgentRunRecord.RunStatus = AgentRunRecord.RunStatus.COMPLETED,
        toolCalls: Int = 3,
        steps: Int = 4,
    ) = AgentRunRecord(
        id = id,
        agentId = agentId,
        startedAtMillis = now - ageDays * day,
        status = status,
        stepCount = steps,
        toolCalls = toolCalls,
        totalTokens = tokens,
        estimatedCostUsd = cost,
    )

    @Test
    fun `only the window counts`() {
        val records =
            listOf(
                record("recent", "ea", ageDays = 5),
                record("old", "ea", ageDays = 45),
            )

        val usage = AgentUsageReport.summarize(records, nowMillis = now)

        assertEquals(1, usage.runs)
        assertEquals(3, usage.toolCalls)
        assertEquals(1_000, usage.totalTokens)
        assertEquals(0.02, usage.estimatedCostUsd, 1e-9)
    }

    @Test
    fun `the window boundary is inclusive`() {
        val records = listOf(record("edge", "ea", ageDays = 30))

        assertEquals(1, AgentUsageReport.summarize(records, nowMillis = now).runs)
    }

    @Test
    fun `a per-agent filter excludes other agents`() {
        val records =
            listOf(
                record("a", "ops", ageDays = 1),
                record("b", "support", ageDays = 1),
                record("c", "support", ageDays = 2),
            )

        assertEquals(1, AgentUsageReport.summarize(records, setOf("ops"), now).runs)
        assertEquals(2, AgentUsageReport.summarize(records, setOf("support"), now).runs)
        assertEquals(3, AgentUsageReport.summarize(records, null, now).runs)
    }

    @Test
    fun `runs without reported cost or tokens are counted instead of guessed`() {
        val records =
            listOf(
                record("local", "ea", ageDays = 1, tokens = null, cost = null),
                record("paid", "ea", ageDays = 1),
            )

        val usage = AgentUsageReport.summarize(records, nowMillis = now)

        assertEquals(2, usage.runs)
        assertEquals(0.02, usage.estimatedCostUsd, 1e-9)
        assertEquals(1, usage.runsWithoutCost)
        assertEquals(1, usage.runsWithoutTokens)
        assertTrue(usage.costIsPartial, "a total with an unpriced run must be flagged as a floor")
    }

    @Test
    fun `a fully priced window is not flagged partial`() {
        val usage = AgentUsageReport.summarize(listOf(record("paid", "ea", ageDays = 1)), nowMillis = now)

        assertFalse(usage.costIsPartial)
        assertEquals(0, usage.runsWithoutCost)
    }

    @Test
    fun `failures and denials are reported separately from completions`() {
        val records =
            listOf(
                record("ok", "ea", ageDays = 1),
                record("bad", "ea", ageDays = 1, status = AgentRunRecord.RunStatus.FAILED),
                record("denied", "ea", ageDays = 1, status = AgentRunRecord.RunStatus.DENIED),
            )

        val usage = AgentUsageReport.summarize(records, nowMillis = now)

        assertEquals(3, usage.runs)
        assertEquals(1, usage.failedRuns)
        assertEquals(1, usage.deniedRuns)
    }

    @Test
    fun `a manager roll-up covers its whole subtree`() {
        val ea = AgentProfile(id = "ea", name = "Assistant", role = "EA")
        val ops = AgentProfile(id = "ops", name = "Ops", role = "Ops", managerId = "ea")
        val support = AgentProfile(id = "support", name = "Support", role = "Support", managerId = "ops")
        val directory = AgentDirectory(listOf(ea, ops, support))

        assertEquals(setOf("ops", "support"), AgentUsageReport.subtreeIds(directory, "ops"))
        assertEquals(setOf("ea", "ops", "support"), AgentUsageReport.subtreeIds(directory, "ea"))
        assertEquals(emptySet<String>(), AgentUsageReport.subtreeIds(directory, "ghost"))
    }
}
