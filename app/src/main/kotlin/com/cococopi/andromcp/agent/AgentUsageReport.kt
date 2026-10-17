package com.cococopi.andromcp.agent

import com.cococopi.andromcp.agent.AgentRunRecord.RunStatus

/**
 * Rolls the run ledger up into the numbers a human actually asks for: how much has this agent been
 * doing, on what, and what did it cost.
 *
 * Costs and tokens are optional by design. Most OpenAI-compatible providers report usage, some do
 * not, and local models cost nothing — so instead of inventing a number the report counts the runs
 * that had no reported cost ([Usage.runsWithoutCost]) and the UI can say "cost not known", the same
 * way a user-facing agent product should.
 *
 * Pure and allocation-only: given the same records it always returns the same numbers, which is what
 * makes the UI's 30-day tiles testable.
 */
object AgentUsageReport {
    /** Default reporting window, in days. */
    const val DEFAULT_WINDOW_DAYS = 30

    /** Aggregated usage for one agent, or for a set of agents (a manager and its reports). */
    data class Usage(
        val runs: Int = 0,
        val toolCalls: Int = 0,
        val steps: Int = 0,
        val totalTokens: Int = 0,
        val estimatedCostUsd: Double = 0.0,
        /** Runs the provider reported no cost for (local models, or a provider without pricing). */
        val runsWithoutCost: Int = 0,
        val runsWithoutTokens: Int = 0,
        val failedRuns: Int = 0,
        val deniedRuns: Int = 0,
    ) {
        /** True when at least one run in the window reported no cost, so the total is a floor. */
        val costIsPartial: Boolean get() = runsWithoutCost > 0
    }

    /**
     * Summarises [records] inside the window ending at [nowMillis].
     *
     * @param agentIds agents to include, or null for every agent (the Executive Assistant's view).
     * @param windowDays inclusive window length in days; runs older than the window are ignored.
     */
    fun summarize(
        records: List<AgentRunRecord>,
        agentIds: Set<String>? = null,
        nowMillis: Long,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): Usage {
        val windowStart = nowMillis - windowDays * MILLIS_PER_DAY

        return records
            .asSequence()
            .filter { it.startedAtMillis >= windowStart }
            .filter { agentIds == null || it.agentId in agentIds }
            .fold(Usage()) { usage, record ->
                usage.copy(
                    runs = usage.runs + 1,
                    toolCalls = usage.toolCalls + record.toolCalls,
                    steps = usage.steps + record.stepCount,
                    totalTokens = usage.totalTokens + (record.totalTokens ?: 0),
                    estimatedCostUsd = usage.estimatedCostUsd + (record.estimatedCostUsd ?: 0.0),
                    runsWithoutCost = usage.runsWithoutCost + if (record.estimatedCostUsd == null) 1 else 0,
                    runsWithoutTokens = usage.runsWithoutTokens + if (record.totalTokens == null) 1 else 0,
                    failedRuns = usage.failedRuns + if (record.status == RunStatus.FAILED) 1 else 0,
                    deniedRuns = usage.deniedRuns + if (record.status == RunStatus.DENIED) 1 else 0,
                )
            }
    }

    /**
     * The agents a manager's roll-up covers: itself plus everyone beneath it, so a manager tile and
     * the EA's whole-org tile are the same call with a different id set.
     */
    fun subtreeIds(
        directory: AgentDirectory,
        agentId: String,
    ): Set<String> {
        val agent = directory.find(agentId) ?: return emptySet()
        return setOf(agent.id) + directory.descendantsOf(agent.id).map { it.id }
    }

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
}
