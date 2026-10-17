package com.cococopi.andromcp.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Runs one agent turn as a plan → call tools → observe → repeat loop.
 *
 * The engine owns no Android dependencies: it drives any [LlmClient] (see the providers in
 * `agent.llm`) against any [AgentToolRunner], which keeps the loop unit-testable and the
 * transport/UI concerns outside it.
 *
 * Every model step appends its assistant message (prose plus tool calls) and one tool result per
 * call to the transcript, so the next completion sees exactly what happened. Risky calls are
 * confirmed through [approvalGate] before they run; a refusal is reported back to the model as an
 * error result instead of aborting the turn, which lets it pick a safer route. Tool output is
 * truncated to [maxToolOutputChars] so one verbose tool cannot blow the context window.
 */
class AgentEngine(
    private val client: LlmClient,
    private val tools: AgentToolRunner,
    private val approvalGate: ApprovalGate = ApprovalGate { true },
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val maxToolOutputChars: Int = DEFAULT_MAX_TOOL_OUTPUT_CHARS,
) {
    @Suppress("LongMethod")
    fun run(
        systemPrompt: String,
        history: List<AgentMessage>,
        userInput: String,
    ): Flow<AgentEvent> =
        flow {
            val definitions = tools.tools()
            val transcript = mutableListOf<AgentMessage>()
            transcript += AgentMessage.System(systemPrompt)
            transcript += history
            transcript += AgentMessage.User(userInput)

            repeat(maxSteps) {
                val completion =
                    try {
                        client.complete(transcript, definitions)
                    } catch (e: LlmException) {
                        emit(AgentEvent.Failed(e.message ?: DEFAULT_FAILURE_MESSAGE))
                        return@flow
                    }

                if (completion.text.isNotBlank()) emit(AgentEvent.Text(completion.text))
                transcript += AgentMessage.Assistant(completion.text, completion.toolCalls)

                if (completion.toolCalls.isEmpty()) {
                    emit(AgentEvent.Finished(FinishReason.COMPLETED))
                    return@flow
                }

                for (call in completion.toolCalls) {
                    val result = execute(call)
                    transcript += result
                }
            }

            emit(AgentEvent.Finished(FinishReason.STEP_LIMIT))
        }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.execute(call: AgentToolCall): AgentMessage {
        emit(AgentEvent.ToolRequested(call))

        val needsApproval = RiskyToolPolicy.isRisky(call.name, call.arguments)
        if (needsApproval && !approvalGate.approve(call)) {
            emit(AgentEvent.ToolDenied(call))
            return AgentMessage.ToolResult(
                callId = call.id,
                toolName = call.name,
                text = DENIED_RESULT,
                isError = true,
            )
        }

        val outcome =
            try {
                tools.call(call)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // A cancelled turn must stay cancelled; everything else becomes a result the model can read.
                if (e is CancellationException) throw e
                ToolCallOutcome(text = "Tool crashed: ${e.message ?: "unknown error"}", isError = true)
            }

        val text = outcome.text.take(maxToolOutputChars)
        emit(AgentEvent.ToolCompleted(call, text, outcome.isError))
        return AgentMessage.ToolResult(call.id, call.name, text, outcome.isError)
    }

    companion object {
        /** Upper bound on model steps per turn, so a looping model cannot run forever. */
        const val DEFAULT_MAX_STEPS = 20

        /** Characters of one tool result kept in the transcript. */
        const val DEFAULT_MAX_TOOL_OUTPUT_CHARS = 12_000

        private const val DEFAULT_FAILURE_MESSAGE = "The model request failed"
        private const val DENIED_RESULT =
            "The user refused this action. Do not retry it; ask what to do instead or use a non-destructive approach."
    }
}
