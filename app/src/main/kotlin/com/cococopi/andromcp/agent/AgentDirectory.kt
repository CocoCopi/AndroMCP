package com.cococopi.andromcp.agent

/**
 * The org chart: one Executive Assistant, specialists hanging off managers, at most
 * [AgentProfile.MAX_DEPTH_BELOW_EA] levels deep.
 *
 * Every relationship question the product needs is answered here rather than in the UI:
 * - who a manager may reach (its own reports; the EA may reach anyone),
 * - how a question escalates (only to the direct manager — never sideways, never past a manager),
 * - what a newly hired agent may be granted (never more than the hiring agent holds),
 * - and whether the chart is even valid (one root, no cycles, no orphans, bounded depth).
 *
 * The class is immutable and allocation-cheap, so the UI can rebuild it from stored profiles on
 * every change instead of maintaining a mutable graph that can drift out of sync.
 */
class AgentDirectory(
    profiles: List<AgentProfile>,
) {
    private val byId: Map<String, AgentProfile> = profiles.associateBy { it.id }
    private val byManager: Map<String?, List<AgentProfile>> = profiles.groupBy { it.managerId }

    /** Every agent, in insertion order of the source list. */
    fun all(): List<AgentProfile> = byId.values.toList()

    fun find(id: String): AgentProfile? = byId[id]

    /** Case-insensitive `@name` lookup used by chat mentions. */
    fun findByName(name: String): AgentProfile? {
        val wanted = name.removePrefix("@")
        return byId.values.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    }

    /** The single managerless agent, or null when the chart is invalid. */
    fun executiveAssistant(): AgentProfile? = byManager[null]?.singleOrNull()

    /** Direct reports of [id]. */
    fun reportsOf(id: String): List<AgentProfile> = byManager[id].orEmpty()

    /** Managers from the direct manager up to the EA (nearest first). Empty for the EA itself. */
    fun ancestorsOf(id: String): List<AgentProfile> {
        val chain = mutableListOf<AgentProfile>()
        val seen = mutableSetOf(id)
        var current = byId[id]?.managerId
        while (current != null && seen.add(current)) {
            val manager = byId[current] ?: break
            chain += manager
            current = manager.managerId
        }
        return chain
    }

    /** Everyone below [id] (breadth first), stopping safely if the stored data contains a cycle. */
    fun descendantsOf(id: String): List<AgentProfile> {
        val result = mutableListOf<AgentProfile>()
        val seen = mutableSetOf(id)
        val queue = ArrayDeque(reportsOf(id))
        while (queue.isNotEmpty()) {
            val agent = queue.removeFirst()
            if (!seen.add(agent.id)) continue
            result += agent
            queue.addAll(reportsOf(agent.id))
        }
        return result
    }

    /** Levels below the EA: the EA is 0, its reports 1. Unknown ids report -1. */
    fun depthOf(id: String): Int {
        if (byId[id] == null) return UNKNOWN_DEPTH
        return ancestorsOf(id).size
    }

    /**
     * Whether [actorId]'s position in the chart lets it act on [targetId]: the EA reaches anyone,
     * a manager reaches its direct reports. Used for hand-offs and inbox addressing.
     */
    fun canReach(
        actorId: String,
        targetId: String,
    ): Boolean {
        val actor = byId[actorId]
        return actorId == targetId ||
            actor?.isExecutiveAssistant == true ||
            (actor != null && reportsOf(actorId).any { it.id == targetId })
    }

    /** Whether [actorId] may escalate a question to [targetId] (only the direct manager). */
    fun canEscalate(
        actorId: String,
        targetId: String,
    ): Boolean = byId[actorId]?.managerId == targetId

    /**
     * Whether [actorId] may grant [toolName] to another agent. A hiring agent can only hand over
     * what it already holds, which is what stops privilege escalation through hiring.
     */
    fun canGrant(
        actorId: String,
        toolName: String,
    ): Boolean {
        val actor = byId[actorId]
        return actor != null && (actor.hasAllGrants || toolName in actor.grantedTools)
    }

    /** The stored list, including any duplicate ids that [all] would collapse. */
    internal val storedProfiles: List<AgentProfile> get() = byManager.values.flatten()

    companion object {
        private const val UNKNOWN_DEPTH = -1
    }
}

/** Whether every tool in [toolNames] may be granted by [actorId]. */
fun AgentDirectory.canGrantAll(
    actorId: String,
    toolNames: Set<String>,
): Boolean = toolNames.all { canGrant(actorId, it) }

/**
 * Structural problems with the stored chart, as human-readable sentences. Empty means valid.
 *
 * An extension so the class stays inside detekt's function budget; it needs only the public chart
 * API plus [AgentDirectory.storedProfiles], which is what lets duplicate ids be detected even though
 * [AgentDirectory.all] collapses them.
 */
fun AgentDirectory.validate(): List<String> {
    val problems = mutableListOf<String>()
    val profiles = storedProfiles

    if (profiles.size != all().size) {
        problems += "Two agents share an id; ids must be unique."
    }

    val roots = profiles.filter { it.managerId == null }
    when {
        roots.isEmpty() -> {
            problems += "No Executive Assistant: exactly one agent must have no manager."
        }

        roots.size > 1 -> {
            problems += "More than one agent has no manager (${roots.joinToString { it.name }}); " +
                "only the Executive Assistant may be root."
        }
    }

    profiles
        .groupBy { it.name.lowercase() }
        .filterValues { it.size > 1 }
        .forEach { (name, agents) ->
            problems += "Two agents share the name '$name' (${agents.joinToString { it.id }}); " +
                "@mentions would be ambiguous."
        }

    profiles.forEach { agent ->
        val managerId = agent.managerId
        if (managerId != null && find(managerId) == null) {
            problems += "'${agent.name}' reports to unknown manager '$managerId'."
        }
        val depth = depthOf(agent.id)
        if (depth > AgentProfile.MAX_DEPTH_BELOW_EA) {
            problems += "'${agent.name}' sits $depth levels below the EA, past the " +
                "${AgentProfile.MAX_DEPTH_BELOW_EA}-level limit."
        }
    }

    val executive = executiveAssistant()
    if (executive != null) {
        val reachable = (listOf(executive) + descendantsOf(executive.id)).map { it.id }.toSet()
        val stranded = profiles.filter { it.id !in reachable }
        if (stranded.isNotEmpty()) {
            problems += "Unreachable from the Executive Assistant (cycle or orphan): " +
                stranded.joinToString { it.name } + "."
        }
    }

    return problems
}
