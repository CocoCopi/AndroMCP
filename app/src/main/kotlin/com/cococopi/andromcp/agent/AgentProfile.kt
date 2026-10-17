package com.cococopi.andromcp.agent

import com.cococopi.andromcp.agent.llm.LlmConfig

/**
 * One configured worker: what it is called, who it answers to, which model it thinks with and
 * exactly which capabilities a human has granted it.
 *
 * [managerId] is null for the single Executive Assistant at the top of the tree; every other agent
 * has exactly one manager. [grantedTools] stores un-prefixed tool names (the same ids the MCP
 * surface and the tool-permission settings use); [ALL_GRANTS] means "every tool this build has",
 * which is how the EA starts and how a human can grant a specialist everything at once.
 *
 * Nothing here is implied by role: a "research" agent with no grants can call nothing. Access is
 * only ever the intersection of what a human granted and what the manager relationship allows.
 */
data class AgentProfile(
    val id: String,
    val name: String,
    val role: String,
    val managerId: String? = null,
    val persona: String = "",
    val guardrails: String = "",
    val model: LlmConfig? = null,
    val grantedTools: Set<String> = emptySet(),
    val enabled: Boolean = true,
) {
    /** True for the single agent with no manager. */
    val isExecutiveAssistant: Boolean get() = managerId == null

    /** True when this agent was granted the whole tool surface. */
    val hasAllGrants: Boolean get() = ALL_GRANTS in grantedTools

    /** Whether [toolName] may be offered to (and run for) this agent. */
    fun canUse(toolName: String): Boolean = hasAllGrants || toolName in grantedTools

    companion object {
        /** Grant marker meaning "every capability in this build". */
        const val ALL_GRANTS = "*"

        /** Deepest allowed chain below the Executive Assistant. */
        const val MAX_DEPTH_BELOW_EA = 4
    }
}

/** A run's audit record: who ran, on which model, for how long, and how it ended. */
data class AgentRunRecord(
    val id: String,
    val agentId: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val status: RunStatus = RunStatus.RUNNING,
    val stepCount: Int = 0,
    val toolCalls: Int = 0,
    /** Provider-reported tokens, or null when the provider does not report usage. */
    val totalTokens: Int? = null,
    /** Estimated cost in USD, or null when the provider/model has no known price. */
    val estimatedCostUsd: Double? = null,
) {
    enum class RunStatus {
        RUNNING,
        COMPLETED,
        FAILED,
        DENIED,
        CANCELLED,
    }
}
