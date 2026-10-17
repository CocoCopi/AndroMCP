package com.cococopi.andromcp.agent.llm

/** Wire protocol a provider speaks. Each one maps the agent's messages/tools natively. */
enum class ApiStyle {
    /** `POST {base}/chat/completions` with `Authorization: Bearer`. */
    OPENAI,

    /** `POST {base}/messages` with `x-api-key` and `anthropic-version`. */
    ANTHROPIC,

    /** `POST {base}/models/{model}:generateContent?key=`. */
    GEMINI,
}

/**
 * Every backend the in-app agent can talk to, each with its own endpoint, auth style and wire
 * format. [defaultBaseUrl] and [defaultModel] are starting points the settings screen pre-fills and
 * the user can override (model ids move fast; endpoints rarely do).
 */
enum class ModelProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val apiStyle: ApiStyle,
    val requiresApiKey: Boolean = true,
) {
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4.1", ApiStyle.OPENAI),
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com/v1", "claude-sonnet-4-5", ApiStyle.ANTHROPIC),
    GEMINI(
        "Google Gemini",
        "https://generativelanguage.googleapis.com/v1beta",
        "gemini-2.5-pro",
        ApiStyle.GEMINI,
    ),
    XAI("xAI Grok", "https://api.x.ai/v1", "grok-4", ApiStyle.OPENAI),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", ApiStyle.OPENAI),
    MISTRAL("Mistral", "https://api.mistral.ai/v1", "mistral-large-latest", ApiStyle.OPENAI),
    GROQ("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", ApiStyle.OPENAI),
    CEREBRAS("Cerebras", "https://api.cerebras.ai/v1", "llama-3.3-70b", ApiStyle.OPENAI),
    TOGETHER(
        "Together AI",
        "https://api.together.xyz/v1",
        "meta-llama/Llama-3.3-70B-Instruct-Turbo",
        ApiStyle.OPENAI,
    ),
    FIREWORKS(
        "Fireworks AI",
        "https://api.fireworks.ai/inference/v1",
        "accounts/fireworks/models/llama-v3p3-70b-instruct",
        ApiStyle.OPENAI,
    ),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4.5", ApiStyle.OPENAI),
    PERPLEXITY("Perplexity", "https://api.perplexity.ai", "sonar-pro", ApiStyle.OPENAI),
    MOONSHOT("Moonshot Kimi", "https://api.moonshot.ai/v1", "kimi-k2-0905-preview", ApiStyle.OPENAI),
    ZHIPU("Zhipu GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4.6", ApiStyle.OPENAI),
    QWEN(
        "Alibaba Qwen",
        "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
        "qwen3-max",
        ApiStyle.OPENAI,
    ),
    NVIDIA_NIM(
        "NVIDIA NIM",
        "https://integrate.api.nvidia.com/v1",
        "meta/llama-3.3-70b-instruct",
        ApiStyle.OPENAI,
    ),
    OLLAMA("Ollama (device or LAN)", "http://127.0.0.1:11434/v1", "llama3.2", ApiStyle.OPENAI, requiresApiKey = false),
    LM_STUDIO("LM Studio (LAN)", "http://127.0.0.1:1234/v1", "local-model", ApiStyle.OPENAI, requiresApiKey = false),
    CUSTOM("Custom endpoint", "", "", ApiStyle.OPENAI, requiresApiKey = false),
}

/** User-chosen backend: provider plus optional endpoint/model overrides. */
data class LlmConfig(
    val provider: ModelProvider,
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
) {
    /** Endpoint actually used, falling back to the provider default. */
    val effectiveBaseUrl: String
        get() = baseUrl.ifBlank { provider.defaultBaseUrl }

    /** Model id actually used, falling back to the provider default. */
    val effectiveModel: String
        get() = model.ifBlank { provider.defaultModel }
}
