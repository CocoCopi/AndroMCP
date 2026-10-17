package com.cococopi.andromcp.agent

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cococopi.andromcp.agent.llm.LlmConfig
import com.cococopi.andromcp.agent.llm.ModelProvider
import com.cococopi.andromcp.di.AgentDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent's provider settings: which backend to use, which model, an optional endpoint override,
 * and the API key.
 *
 * The key is stored encrypted (see [AgentKeyStore]) — this repository only ever sees the encrypted
 * form, so nothing in the settings pipeline can leak it, and the UI reads it back solely to show
 * whether a key is present.
 */
interface AgentSettingsRepository {
    /** Current provider configuration; emits on every change. */
    val config: Flow<LlmConfig>

    /** One-shot read of the current configuration. */
    suspend fun current(): LlmConfig

    suspend fun updateProvider(provider: ModelProvider)

    suspend fun updateModel(model: String)

    suspend fun updateBaseUrl(baseUrl: String)

    /** Encrypts and stores [apiKey]; an empty string clears it. */
    suspend fun updateApiKey(apiKey: String)
}

@Singleton
class AgentSettingsRepositoryImpl
    @Inject
    constructor(
        @AgentDataStore private val dataStore: DataStore<Preferences>,
        private val keyStore: AgentKeyStore,
    ) : AgentSettingsRepository {
        override val config: Flow<LlmConfig> = dataStore.data.map { preferences -> preferences.toConfig() }

        override suspend fun current(): LlmConfig = dataStore.data.first().toConfig()

        override suspend fun updateProvider(provider: ModelProvider) {
            dataStore.edit { it[PROVIDER_KEY] = provider.name }
        }

        override suspend fun updateModel(model: String) {
            dataStore.edit { it[MODEL_KEY] = model }
        }

        override suspend fun updateBaseUrl(baseUrl: String) {
            dataStore.edit { it[BASE_URL_KEY] = baseUrl }
        }

        override suspend fun updateApiKey(apiKey: String) {
            val stored = if (apiKey.isEmpty()) "" else keyStore.encrypt(apiKey)
            dataStore.edit { it[API_KEY_KEY] = stored }
        }

        private fun Preferences.toConfig(): LlmConfig =
            LlmConfig(
                provider =
                    ModelProvider.entries.firstOrNull { it.name == this[PROVIDER_KEY] }
                        ?: ModelProvider.OPENAI,
                apiKey = keyStore.decrypt(this[API_KEY_KEY].orEmpty()),
                baseUrl = this[BASE_URL_KEY].orEmpty(),
                model = this[MODEL_KEY].orEmpty(),
            )

        private companion object {
            val PROVIDER_KEY = stringPreferencesKey("agent_provider")
            val MODEL_KEY = stringPreferencesKey("agent_model")
            val BASE_URL_KEY = stringPreferencesKey("agent_base_url")
            val API_KEY_KEY = stringPreferencesKey("agent_api_key_encrypted")
        }
    }
