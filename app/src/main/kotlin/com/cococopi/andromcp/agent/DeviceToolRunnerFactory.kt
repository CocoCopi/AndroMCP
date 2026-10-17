package com.cococopi.andromcp.agent

import android.content.Context
import com.cococopi.andromcp.data.model.ServerConfig
import com.cococopi.andromcp.data.repository.SettingsRepository
import com.cococopi.andromcp.mcp.tools.McpToolUtils
import com.cococopi.andromcp.services.mcp.RuntimeToolAccess
import com.cococopi.andromcp.services.mcp.ToolGraph
import com.cococopi.andromcp.utils.NetworkUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the in-app agent's tool access from the same settings that drive the MCP server: the device
 * slug prefixes the names, the stored tool permissions and the optional OS permissions decide what
 * exists at all, and the file-size limit carries over to file tools.
 *
 * The catalog is captured per runner, so a settings change takes effect on the next agent run
 * without restarting anything.
 */
@Singleton
class DeviceToolRunnerFactory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val toolGraph: ToolGraph,
        private val settingsRepository: SettingsRepository,
    ) {
        /** A runner whose catalog is captured on first use. */
        fun create(): DeviceToolRunner =
            DeviceToolRunner {
                val config = settingsRepository.getServerConfig()
                toolGraph.captureCatalog(
                    toolNamePrefix = McpToolUtils.buildToolNamePrefix(config.deviceSlug),
                    perms = RuntimeToolAccess.effectivePermissions(context, config),
                    fileSizeLimitMb = config.fileSizeLimitMb,
                    baseUrl = { capabilityBaseUrl(config) },
                )
            }

        /**
         * Base URL for capability links (share links, ephemeral file links). Reached over the host
         * addresses this device already owns; the tunnel URL is not needed because the agent runs on
         * the same phone.
         */
        private fun capabilityBaseUrl(config: ServerConfig): String {
            val scheme = if (config.httpsEnabled) "https" else "http"
            val host = NetworkUtils.getDeviceIpAddress(context) ?: config.bindingAddress.address
            return "$scheme://$host:${config.port}"
        }
    }
