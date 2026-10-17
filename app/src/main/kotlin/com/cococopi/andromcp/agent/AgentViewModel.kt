package com.cococopi.andromcp.agent

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cococopi.andromcp.agent.llm.LlmClientFactory
import com.cococopi.andromcp.agent.llm.LlmConfig
import com.cococopi.andromcp.agent.llm.ModelProvider
import com.cococopi.andromcp.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One row in the agent chat. */
sealed interface AgentChatItem {
    data class UserMessage(
        val text: String,
    ) : AgentChatItem

    data class AssistantMessage(
        val text: String,
    ) : AgentChatItem

    /** A tool the agent ran, with its live state. */
    data class ToolStep(
        val name: String,
        val detail: String,
        val state: ToolStepState,
    ) : AgentChatItem

    data class Failure(
        val text: String,
    ) : AgentChatItem
}

enum class ToolStepState {
    RUNNING,
    OK,
    ERROR,
    DENIED,
}

/**
 * Drives the in-app agent: it reads the stored provider configuration, runs one turn through
 * [AgentEngine], streams the tool calls into the chat, and blocks on the user's approval whenever the
 * engine asks about an irreversible action.
 *
 * The transcript kept for the model is rebuilt from the streamed events, so a follow-up question sees
 * the earlier assistant prose *and* the tool results it produced. That list is trimmed, so a long
 * session cannot grow the prompt without bound.
 */
@HiltViewModel
class AgentViewModel
    @Inject
    constructor(
        private val settingsRepository: AgentSettingsRepository,
        private val toolRunnerFactory: DeviceToolRunnerFactory,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val config: StateFlow<LlmConfig> =
            settingsRepository.config.stateIn(viewModelScope, SharingStarted.Eagerly, LlmConfig(ModelProvider.OPENAI))

        private val _items = MutableStateFlow<List<AgentChatItem>>(emptyList())
        val items: StateFlow<List<AgentChatItem>> = _items.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _pendingApproval = MutableStateFlow<AgentToolCall?>(null)
        val pendingApproval: StateFlow<AgentToolCall?> = _pendingApproval.asStateFlow()

        /** Everything the model saw this session, oldest first. */
        private val transcript = mutableListOf<AgentMessage>()
        private var approvalDecision: CompletableDeferred<Boolean>? = null
        private var runJob: Job? = null

        /** Runs one user turn. Ignored while a turn is already running or the input is blank. */
        fun send(input: String) {
            val text = input.trim()
            if (text.isEmpty() || _isRunning.value) return

            _items.update { it + AgentChatItem.UserMessage(text) }
            _isRunning.value = true
            runJob = viewModelScope.launch { runTurn(text) }
        }

        /** Cancels the running turn; any pending approval is treated as a refusal. */
        fun stop() {
            approvalDecision?.complete(false)
            runJob?.cancel()
            runJob = null
            _isRunning.value = false
        }

        fun resolveApproval(allow: Boolean) {
            approvalDecision?.complete(allow)
        }

        fun clearChat() {
            _items.value = emptyList()
            transcript.clear()
        }

        fun saveProvider(
            provider: ModelProvider,
            model: String,
            baseUrl: String,
            apiKey: String,
        ) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateProvider(provider)
                settingsRepository.updateModel(model)
                settingsRepository.updateBaseUrl(baseUrl)
                if (apiKey.isNotBlank()) settingsRepository.updateApiKey(apiKey)
            }
        }

        private suspend fun runTurn(userText: String) {
            val configuration = withContext(ioDispatcher) { settingsRepository.current() }
            val client =
                try {
                    LlmClientFactory.create(configuration)
                } catch (e: LlmException) {
                    _items.update { it + AgentChatItem.Failure(e.message.orEmpty()) }
                    _isRunning.value = false
                    return
                }

            val runner = GrantedToolRunner(toolRunnerFactory.create(), executiveAssistant())
            val toolCount = runner.tools().size
            val prompt = AgentPrompt.build(deviceSummary(), toolCount)

            val assistantText = StringBuilder()
            val calls = mutableListOf<AgentToolCall>()
            val results = mutableListOf<AgentMessage.ToolResult>()

            try {
                AgentEngine(client, runner, approvalGate = ::awaitApproval)
                    .run(prompt, transcript.toList(), userText)
                    .collect { event -> handleEvent(event, assistantText, calls, results) }
            } catch (e: CancellationException) {
                _items.update { it + AgentChatItem.Failure("Stopped.") }
                throw e
            } finally {
                rememberTurn(userText, assistantText.toString(), calls, results)
                _pendingApproval.value = null
                _isRunning.value = false
            }
        }

        private suspend fun handleEvent(
            event: AgentEvent,
            assistantText: StringBuilder,
            calls: MutableList<AgentToolCall>,
            results: MutableList<AgentMessage.ToolResult>,
        ) {
            when (event) {
                is AgentEvent.Text -> {
                    assistantText.append(event.text)
                    _items.update { it + AgentChatItem.AssistantMessage(event.text) }
                }

                is AgentEvent.ToolRequested -> {
                    calls += event.call
                    _items.update { it + AgentChatItem.ToolStep(event.call.name, "running", ToolStepState.RUNNING) }
                }

                is AgentEvent.ToolCompleted -> {
                    results += AgentMessage.ToolResult(event.call.id, event.call.name, event.output, event.isError)
                    replaceLastStep(event.call.name, event.output, if (event.isError) ToolStepState.ERROR else ToolStepState.OK)
                }

                is AgentEvent.ToolDenied -> {
                    results +=
                        AgentMessage.ToolResult(
                            callId = event.call.id,
                            toolName = event.call.name,
                            text = "The user refused this action.",
                            isError = true,
                        )
                    replaceLastStep(event.call.name, "refused", ToolStepState.DENIED)
                }

                is AgentEvent.Failed -> {
                    _items.update { it + AgentChatItem.Failure(event.message) }
                }

                is AgentEvent.Finished -> {
                    Unit
                }
            }
        }

        /** Keeps the transcript the model sees next turn in step with what the user saw. */
        private fun rememberTurn(
            userText: String,
            assistantText: String,
            calls: List<AgentToolCall>,
            results: List<AgentMessage.ToolResult>,
        ) {
            if (assistantText.isBlank() && results.isEmpty()) return
            transcript += AgentMessage.User(userText)
            transcript += AgentMessage.Assistant(assistantText, calls)
            transcript += results
            while (transcript.size > MAX_TRANSCRIPT_MESSAGES) {
                transcript.removeAt(0)
            }
        }

        private fun replaceLastStep(
            toolName: String,
            output: String,
            state: ToolStepState,
        ) {
            val detail =
                output
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.take(MAX_STEP_DETAIL_CHARS)
                    .orEmpty()
            _items.update { items ->
                val index = items.indexOfLast { it is AgentChatItem.ToolStep && it.name == toolName }
                if (index < 0) {
                    items
                } else {
                    items.toMutableList().apply { this[index] = AgentChatItem.ToolStep(toolName, detail, state) }
                }
            }
        }

        /** Bridges the engine's approval request to the UI, then resumes the turn. */
        private suspend fun awaitApproval(call: AgentToolCall): Boolean {
            val decision = CompletableDeferred<Boolean>()
            approvalDecision = decision
            _pendingApproval.value = call
            return try {
                decision.await()
            } finally {
                _pendingApproval.value = null
                approvalDecision = null
            }
        }

        /** The single agent this build runs with, still wrapped in grant enforcement. */
        private fun executiveAssistant(): AgentProfile =
            AgentProfile(
                id = EXECUTIVE_ASSISTANT_ID,
                name = "Assistant",
                role = "Executive Assistant",
                grantedTools = setOf(AgentProfile.ALL_GRANTS),
            )

        private fun deviceSummary(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"

        private companion object {
            const val EXECUTIVE_ASSISTANT_ID = "ea"
            const val MAX_TRANSCRIPT_MESSAGES = 40
            const val MAX_STEP_DETAIL_CHARS = 160
        }
    }
