package com.cococopi.andromcp.agent.llm

import com.cococopi.andromcp.agent.LlmClient
import com.cococopi.andromcp.agent.LlmException

/** Binds a [LlmConfig] to the client that speaks its provider's wire format. */
object LlmClientFactory {
    /**
     * Creates the client for [config].
     *
     * @throws LlmException when the configuration cannot work (no endpoint, no model, missing key),
     *   so the UI can show the reason before a request is attempted.
     */
    fun create(
        config: LlmConfig,
        transport: HttpTransport = UrlConnectionTransport(),
    ): LlmClient {
        if (config.effectiveBaseUrl.isBlank()) {
            throw LlmException("Set an endpoint URL for ${config.provider.displayName}")
        }
        if (config.effectiveModel.isBlank()) {
            throw LlmException("Set a model id for ${config.provider.displayName}")
        }
        if (config.provider.requiresApiKey && config.apiKey.isBlank()) {
            throw LlmException("Add your ${config.provider.displayName} API key")
        }

        return when (config.provider.apiStyle) {
            ApiStyle.OPENAI -> OpenAiCompatibleClient(config, transport, openAiExtras(config.provider))
            ApiStyle.ANTHROPIC -> AnthropicClient(config, transport)
            ApiStyle.GEMINI -> GeminiClient(config, transport)
        }
    }

    /**
     * OpenRouter attributes traffic by referer/title; sending them keeps the app on the free tier's
     * good side. Other OpenAI-compatible providers ignore extra headers.
     */
    private fun openAiExtras(provider: ModelProvider): Map<String, String> =
        when (provider) {
            ModelProvider.OPENROUTER -> {
                mapOf(
                    "HTTP-Referer" to "https://github.com/CocoCopi/AndroMCP",
                    "X-Title" to "CocoCopi MCP",
                )
            }

            else -> {
                emptyMap()
            }
        }
}
