package com.cococopi.andromcp.agent

/**
 * Wraps a [AgentToolRunner] so an agent only ever sees and runs what a human granted it.
 *
 * Two independent gates, because a prompt-injected model can name a tool that was never offered:
 * [tools] hides ungranted capabilities from the model, and [call] refuses them anyway even if the
 * model invents the name. The refusal is returned as an error result — not thrown — so the model
 * learns the boundary and can ask the owner instead of blindly retrying.
 */
class GrantedToolRunner(
    private val delegate: AgentToolRunner,
    private val agent: AgentProfile,
) : AgentToolRunner {
    override suspend fun tools(): List<AgentToolDefinition> = delegate.tools().filter { agent.canUse(it.name) }

    override suspend fun call(call: AgentToolCall): ToolCallOutcome {
        if (!agent.canUse(call.name)) return ToolCallOutcome(text = refusal(call.name), isError = true)
        return delegate.call(call)
    }

    private fun refusal(toolName: String): String =
        "Not granted: '$toolName' is outside what a human granted ${agent.name}. " +
            "Do not retry it or look for another unapproved tool; ask the owner to grant it if the task needs it."
}
