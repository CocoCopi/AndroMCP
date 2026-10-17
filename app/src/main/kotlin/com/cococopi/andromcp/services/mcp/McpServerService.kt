package com.cococopi.andromcp.services.mcp

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cococopi.andromcp.McpApplication
import com.cococopi.andromcp.R
import com.cococopi.andromcp.data.model.OptionalToolPermissions
import com.cococopi.andromcp.data.model.ServerConfig
import com.cococopi.andromcp.data.model.ServerLogEntry
import com.cococopi.andromcp.data.model.ServerStatus
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.data.model.TunnelStatus
import com.cococopi.andromcp.data.repository.OAuthClientRepository
import com.cococopi.andromcp.data.repository.ServerLogRepository
import com.cococopi.andromcp.data.repository.SettingsRepository
import com.cococopi.andromcp.geo.GeoIpResolver
import com.cococopi.andromcp.mcp.CertificateManager
import com.cococopi.andromcp.mcp.HttpsMaterial
import com.cococopi.andromcp.mcp.McpServer
import com.cococopi.andromcp.mcp.oauth.AuthorizationCodeStore
import com.cococopi.andromcp.mcp.oauth.JwtTokenService
import com.cococopi.andromcp.mcp.oauth.OAuthApprovalCoordinator
import com.cococopi.andromcp.mcp.oauth.OAuthServerDeps
import com.cococopi.andromcp.mcp.tools.LoggedToolRegistrar
import com.cococopi.andromcp.mcp.tools.McpToolUtils
import com.cococopi.andromcp.mcp.tools.ReferenceCountedToolCallIndicator
import com.cococopi.andromcp.mcp.tools.ToolCallIndicator
import com.cococopi.andromcp.mcp.tools.registerAgentMemoryTools
import com.cococopi.andromcp.mcp.tools.registerAppManagementTools
import com.cococopi.andromcp.mcp.tools.registerAudioCaptureTools
import com.cococopi.andromcp.mcp.tools.registerBluetoothExtrasTools
import com.cococopi.andromcp.mcp.tools.registerCameraTools
import com.cococopi.andromcp.mcp.tools.registerCommsTools
import com.cococopi.andromcp.mcp.tools.registerCryptoVaultTools
import com.cococopi.andromcp.mcp.tools.registerDataTools
import com.cococopi.andromcp.mcp.tools.registerDeviceServiceTools
import com.cococopi.andromcp.mcp.tools.registerDeviceSettingsTools
import com.cococopi.andromcp.mcp.tools.registerFileTools
import com.cococopi.andromcp.mcp.tools.registerGestureTools
import com.cococopi.andromcp.mcp.tools.registerHardwareTools
import com.cococopi.andromcp.mcp.tools.registerIntentTools
import com.cococopi.andromcp.mcp.tools.registerLocationTools
import com.cococopi.andromcp.mcp.tools.registerMediaTools
import com.cococopi.andromcp.mcp.tools.registerNetworkTools
import com.cococopi.andromcp.mcp.tools.registerNodeActionTools
import com.cococopi.andromcp.mcp.tools.registerNotificationExtrasTools
import com.cococopi.andromcp.mcp.tools.registerNotificationTools
import com.cococopi.andromcp.mcp.tools.registerPackageTools
import com.cococopi.andromcp.mcp.tools.registerScreenIntrospectionTools
import com.cococopi.andromcp.mcp.tools.registerSharingTools
import com.cococopi.andromcp.mcp.tools.registerShellTools
import com.cococopi.andromcp.mcp.tools.registerSystemActionTools
import com.cococopi.andromcp.mcp.tools.registerTerminalTools
import com.cococopi.andromcp.mcp.tools.registerTextInputTools
import com.cococopi.andromcp.mcp.tools.registerTouchActionTools
import com.cococopi.andromcp.mcp.tools.registerUtilityTools
import com.cococopi.andromcp.mcp.tools.registerWebTools
import com.cococopi.andromcp.mcp.tools.registerWorkspaceTools
import com.cococopi.andromcp.privacy.PlaceholderSubstitutor
import com.cococopi.andromcp.privacy.PrivacyModeManager
import com.cococopi.andromcp.privacy.PrivacyModeStatus
import com.cococopi.andromcp.privacy.PrivacyToolGate
import com.cococopi.andromcp.privacy.PseudonymStore
import com.cococopi.andromcp.privacy.ner.NerCache
import com.cococopi.andromcp.services.accessibility.AccessibilityNodeCache
import com.cococopi.andromcp.services.accessibility.AccessibilityServiceProvider
import com.cococopi.andromcp.services.accessibility.AccessibilityToolCallIndicator
import com.cococopi.andromcp.services.accessibility.AccessibilityTreeParser
import com.cococopi.andromcp.services.accessibility.ActionExecutor
import com.cococopi.andromcp.services.accessibility.CompactTreeFormatter
import com.cococopi.andromcp.services.accessibility.ElementFinder
import com.cococopi.andromcp.services.accessibility.ScreenStateSnapshotCache
import com.cococopi.andromcp.services.accessibility.TypeInputController
import com.cococopi.andromcp.services.accessibility.WebViewNodeMerger
import com.cococopi.andromcp.services.apps.AppManager
import com.cococopi.andromcp.services.camera.CameraProvider
import com.cococopi.andromcp.services.intents.IntentDispatcher
import com.cococopi.andromcp.services.location.LocationProvider
import com.cococopi.andromcp.services.notifications.McpNotificationListenerService
import com.cococopi.andromcp.services.notifications.NotificationProvider
import com.cococopi.andromcp.services.screencapture.ScreenCaptureProvider
import com.cococopi.andromcp.services.screencapture.ScreenshotAnnotator
import com.cococopi.andromcp.services.screencapture.ScreenshotEncoder
import com.cococopi.andromcp.services.screencapture.ScreenshotRedactor
import com.cococopi.andromcp.services.sharing.EphemeralFileLinkService
import com.cococopi.andromcp.services.sharing.SharedContentInbox
import com.cococopi.andromcp.services.storage.FileOperationProvider
import com.cococopi.andromcp.services.storage.StorageLocationProvider
import com.cococopi.andromcp.services.tunnel.TunnelManager
import com.cococopi.andromcp.ui.MainActivity
import com.cococopi.andromcp.utils.NetworkUtils
import com.cococopi.andromcp.utils.PermissionUtils
import dagger.hilt.android.AndroidEntryPoint
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Foreground service that runs the MCP server (HTTP by default, optional HTTPS).
 *
 * Lifecycle:
 * 1. Started via intent from MainActivity (start/stop button)
 * 2. Calls startForeground() with persistent notification
 * 3. Reads configuration from SettingsRepository
 * 4. Creates and starts McpServer (Ktor HTTP, optionally HTTPS)
 * 5. Updates ServerStatus via companion-level StateFlow (collected by MainViewModel)
 * 6. On stop: gracefully shuts down server, clears singleton
 */
@AndroidEntryPoint
class McpServerService : Service() {
    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var certificateManager: CertificateManager

    @Inject lateinit var actionExecutor: ActionExecutor

    @Inject lateinit var accessibilityServiceProvider: AccessibilityServiceProvider

    @Inject lateinit var screenCaptureProvider: ScreenCaptureProvider

    @Inject lateinit var treeParser: AccessibilityTreeParser

    @Inject lateinit var elementFinder: ElementFinder

    @Inject lateinit var compactTreeFormatter: CompactTreeFormatter

    @Inject lateinit var screenshotAnnotator: ScreenshotAnnotator

    @Inject lateinit var screenshotEncoder: ScreenshotEncoder

    @Inject lateinit var tunnelManager: TunnelManager

    @Inject lateinit var storageLocationProvider: StorageLocationProvider

    @Inject lateinit var fileOperationProvider: FileOperationProvider

    @Inject lateinit var appManager: AppManager

    @Inject lateinit var typeInputController: TypeInputController

    @Inject lateinit var nodeCache: AccessibilityNodeCache

    @Inject lateinit var screenStateSnapshotCache: ScreenStateSnapshotCache

    @Inject lateinit var webViewNodeMerger: WebViewNodeMerger

    @Inject lateinit var cameraProvider: CameraProvider

    @Inject lateinit var intentDispatcher: IntentDispatcher

    @Inject lateinit var notificationProvider: NotificationProvider

    @Inject lateinit var locationProvider: LocationProvider

    @Inject lateinit var ephemeralFileLinkService: EphemeralFileLinkService

    @Inject lateinit var sharedContentInbox: SharedContentInbox

    @Inject lateinit var jwtTokenService: JwtTokenService

    @Inject lateinit var oauthClientRepository: OAuthClientRepository

    @Inject lateinit var authorizationCodeStore: AuthorizationCodeStore

    @Inject lateinit var approvalCoordinator: OAuthApprovalCoordinator

    @Inject lateinit var geoIpResolver: GeoIpResolver

    @Inject lateinit var serverLogRepository: ServerLogRepository

    @Inject lateinit var privacyToolGate: PrivacyToolGate

    @Inject lateinit var placeholderSubstitutor: PlaceholderSubstitutor

    @Inject lateinit var screenshotRedactor: ScreenshotRedactor

    @Inject lateinit var privacyModeManager: PrivacyModeManager

    @Inject lateinit var pseudonymStore: PseudonymStore

    @Inject lateinit var nerCache: NerCache

    /** Config of the currently running server; used to build capability-link base URLs. */
    @Volatile
    private var activeConfig: ServerConfig? = null

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverActive = AtomicBoolean(false)
    private var mcpServer: McpServer? = null
    private var tunnelObserverJob: Job? = null
    private var approvalObserverJob: Job? = null

    /**
     * Tracks whether the LAST [onStartCommand] action was an explicit stop. Lets [onDestroy] re-commit
     * `server_running=false` when the user explicitly stopped (a second bounded attempt in case the
     * first write in [onStartCommand] timed out), WITHOUT clearing the flag on an OEM/system kill —
     * where the last action was a start, so this stays `false` and the flag is left `true` for restart.
     */
    @Volatile
    private var lastIntentWasStop = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "McpServerService created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        when (intent?.action) {
            ACTION_STOP -> {
                lastIntentWasStop = true
                persistServerRunning(settingsRepository, false)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                lastIntentWasStop = false
                persistServerRunning(settingsRepository, true)
                if (!serverActive.compareAndSet(false, true)) {
                    Log.w(TAG, "Server already starting or running, ignoring duplicate start request")
                } else {
                    coroutineScope.launch {
                        startServer()
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        restartMcpServerIfForeground(this, _serverStatus.value is ServerStatus.Running)
        super.onTaskRemoved(rootIntent)
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod")
    private suspend fun startServer() {
        try {
            updateStatus(ServerStatus.Starting)

            // getServerConfig() guarantees ensureAuthModelMigrated() has run before the server reads
            // the auth model (prevents the cleared-token-user open-server regression).
            val config = settingsRepository.getServerConfig()
            activeConfig = config
            val toolNamePrefix = McpToolUtils.buildToolNamePrefix(config.deviceSlug)
            Log.i(
                TAG,
                "Starting MCP server with config: port=${config.port}, " +
                    "binding=${config.bindingAddress.address}, toolNamePrefix=$toolNamePrefix",
            )

            // Only get/create SSL keystore when HTTPS is enabled
            val keyStore =
                if (config.httpsEnabled) {
                    certificateManager.getOrCreateKeyStore(config)
                } else {
                    null
                }
            val keyStorePassword =
                if (config.httpsEnabled) {
                    certificateManager.getKeyStorePassword()
                } else {
                    null
                }

            // Create SDK Server instance and register all tools
            val sdkServer =
                Server(
                    serverInfo =
                        Implementation(
                            name = McpToolUtils.buildServerName(config.deviceSlug),
                            version = com.cococopi.andromcp.BuildConfig.VERSION_NAME,
                        ),
                    options =
                        ServerOptions(
                            capabilities =
                                ServerCapabilities(
                                    tools = ServerCapabilities.Tools(listChanged = false),
                                ),
                        ),
                )
            // ALL four signals MUST come from PermissionUtils — the SAME source the UI uses
            // (MainViewModel.refreshPermissionStatus) — so the server-start gate and the settings grey-out
            // never diverge. Do NOT use cameraProvider.isCameraPermissionGranted()/isMicrophonePermissionGranted()
            // or notificationProvider.isReady() here.
            val grantedOptionalPermissions =
                OptionalToolPermissions.grantedPermissions(
                    camera = PermissionUtils.isCameraPermissionGranted(this@McpServerService),
                    microphone = PermissionUtils.isMicrophonePermissionGranted(this@McpServerService),
                    location = PermissionUtils.isLocationPermissionGranted(this@McpServerService),
                    notificationListener =
                        PermissionUtils.isNotificationListenerEnabled(
                            this@McpServerService,
                            McpNotificationListenerService::class.java,
                        ),
                )
            val effectivePerms =
                OptionalToolPermissions.effectivePermissions(config.toolPermissionsConfig, grantedOptionalPermissions)
            registerAllTools(
                sdkServer,
                toolNamePrefix,
                effectivePerms,
                config.fileSizeLimitMb,
                config.toolCallIndicatorEnabled,
            )

            // Create and start the Ktor server
            mcpServer =
                McpServer(
                    config = config,
                    httpsMaterial = buildHttpsMaterial(keyStore, keyStorePassword),
                    mcpSdkServer = sdkServer,
                    ephemeralFileLinkService = ephemeralFileLinkService,
                    oauth =
                        OAuthServerDeps(
                            jwtTokenService = jwtTokenService,
                            oauthClientRepository = oauthClientRepository,
                            authorizationCodeStore = authorizationCodeStore,
                            approvalCoordinator = approvalCoordinator,
                            geoIpResolver = geoIpResolver,
                        ),
                    serverLog = serverLogRepository,
                )
            mcpServer?.start()

            // Warm the geolocation DB off the request path so the first /authorize doesn't pay the
            // one-time gzip-inflate + mmap cost. Best-effort; a failure just leaves it lazy.
            coroutineScope.launch { geoIpResolver.resolve("8.8.8.8") }

            // Surface Privacy Mode readiness at start so the user learns of a failure now, not at the
            // first data-returning tool call. Off the request path; the result updates the manager status.
            if (config.privacyModeConfig.enabled) {
                coroutineScope.launch {
                    val message = privacyStatusLogMessage(privacyModeManager.selfCheck())
                    serverLogRepository.log(ServerLogEntry.Type.PRIVACY, message)
                }
            }

            updateStatus(
                ServerStatus.Running(
                    port = config.port,
                    bindingAddress = config.bindingAddress.address,
                ),
            )

            // Start tunnel if remote access is enabled. A tunnel always targets an
            // http://localhost origin, so it MUST NOT run while the server serves HTTPS.
            if (config.httpsEnabled) {
                Log.i(TAG, "Remote access tunnel disabled while HTTPS is enabled")
                tunnelManager.stop()
            } else {
                @Suppress("TooGenericExceptionCaught")
                try {
                    tunnelManager.start(config.port)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start tunnel (server continues without tunnel)", e)
                }
            }

            // Observe tunnel status for logging
            tunnelObserverJob =
                coroutineScope.launch {
                    tunnelManager.tunnelStatus.collect { status ->
                        tunnelStatusLogMessage(status)?.let {
                            serverLogRepository.log(ServerLogEntry.Type.TUNNEL, it)
                        }
                        when (status) {
                            is TunnelStatus.Connected -> {
                                Log.i(
                                    TAG,
                                    "Tunnel connected: ${status.endpoints.joinToString { it.url }} " +
                                        "(provider: ${status.providerType})",
                                )
                            }

                            is TunnelStatus.Error -> {
                                Log.w(TAG, "Tunnel error: ${status.message}")
                            }

                            is TunnelStatus.Connecting -> {
                                Log.i(TAG, "Tunnel connecting...")
                            }

                            is TunnelStatus.Disconnected -> {
                                // No-op for initial state; logged at stop time
                            }
                        }
                    }
                }

            // Observe pending OAuth approvals and surface a single heads-up notification (the service
            // is already foregrounded by onStartCommand; this is a separate, collapsed notification).
            approvalObserverJob =
                coroutineScope.launch {
                    approvalCoordinator.observePending().collect { pending ->
                        if (pending.isEmpty()) {
                            OAuthApprovalNotifier.cancel(this@McpServerService)
                        } else {
                            OAuthApprovalNotifier.post(this@McpServerService, pending.size)
                        }
                    }
                }

            Log.i(TAG, "MCP server started successfully on ${config.bindingAddress.address}:${config.port}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MCP server", e)
            updateStatus(ServerStatus.Error(e.message ?: "Unknown error starting server"))
            serverActive.set(false)
        }
    }

    /**
     * Externally-reachable base URL for capability links: the tunnel URL when a tunnel is connected,
     * otherwise the device LAN URL (`scheme://<device-ip>:<port>`).
     */
    private val currentBaseUrl: () -> String = {
        val tunnel = tunnelManager.tunnelStatus.value
        val tunnelUrl = (tunnel as? TunnelStatus.Connected)?.endpoints?.firstOrNull { it.valid }?.url
        if (tunnelUrl != null) {
            tunnelUrl
        } else {
            val cfg = activeConfig
            val scheme = if (cfg?.httpsEnabled == true) "https" else "http"
            val host =
                NetworkUtils.getDeviceIpAddress(applicationContext) ?: cfg?.bindingAddress?.address ?: "127.0.0.1"
            val port = cfg?.port ?: ServerConfig.DEFAULT_PORT
            "$scheme://$host:$port"
        }
    }

    private fun registerAllTools(
        server: Server,
        toolNamePrefix: String,
        perms: ToolPermissionsConfig,
        fileSizeLimitMb: Int,
        toolCallIndicatorEnabled: Boolean,
    ) {
        val toolCallIndicator = ReferenceCountedToolCallIndicator(AccessibilityToolCallIndicator())
        toolCallIndicator.setEnabled(toolCallIndicatorEnabled)
        val registrar =
            LoggedToolRegistrar(
                server,
                serverLogRepository,
                toolCallIndicator,
                perms,
            )
        registerAccessibilityToolBundle(registrar, toolNamePrefix, perms)
        registerFileTools(registrar, storageLocationProvider, fileOperationProvider, toolNamePrefix, perms)
        registerAppManagementTools(registrar, appManager, privacyToolGate, toolNamePrefix, perms)
        registerCameraTools(registrar, cameraProvider, fileOperationProvider, toolNamePrefix, perms)
        registerIntentTools(registrar, intentDispatcher, toolNamePrefix, perms)
        registerNotificationTools(
            registrar,
            notificationProvider,
            privacyToolGate,
            placeholderSubstitutor,
            toolNamePrefix,
            perms,
        )
        registerLocationTools(registrar, locationProvider, privacyToolGate, toolNamePrefix, perms)
        registerSharingBundle(registrar, toolNamePrefix, perms, fileSizeLimitMb)
        registerDeviceServiceTools(registrar, applicationContext, intentDispatcher, toolNamePrefix, perms)
        registerCommsTools(registrar, applicationContext, toolNamePrefix, perms)
        registerNetworkTools(registrar, applicationContext, toolNamePrefix, perms)
        registerHardwareTools(registrar, applicationContext, toolNamePrefix, perms)
        registerDeviceSettingsTools(registrar, applicationContext, toolNamePrefix, perms)
        registerWorkspaceTools(registrar, applicationContext, toolNamePrefix, perms)
        registerDataTools(registrar, applicationContext, toolNamePrefix, perms)
        registerShellTools(registrar, applicationContext, toolNamePrefix, perms)
        registerTerminalTools(registrar, applicationContext, toolNamePrefix, perms)
        registerMediaTools(registrar, applicationContext, toolNamePrefix, perms)
        registerAudioCaptureTools(registrar, applicationContext, toolNamePrefix, perms)
        registerCryptoVaultTools(registrar, applicationContext, toolNamePrefix, perms)
        registerPackageTools(registrar, applicationContext, toolNamePrefix, perms)
        registerBluetoothExtrasTools(registrar, applicationContext, toolNamePrefix, perms)
        registerNotificationExtrasTools(registrar, applicationContext, toolNamePrefix, perms)
        registerWebTools(registrar, applicationContext, toolNamePrefix, perms)
        registerAgentMemoryTools(registrar, applicationContext, toolNamePrefix, perms)
    }

    private fun registerAccessibilityToolBundle(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
        perms: ToolPermissionsConfig,
    ) {
        registerScreenIntrospectionTools(
            registrar,
            treeParser,
            accessibilityServiceProvider,
            screenCaptureProvider,
            compactTreeFormatter,
            screenshotAnnotator,
            screenshotEncoder,
            nodeCache,
            screenStateSnapshotCache,
            webViewNodeMerger,
            privacyToolGate,
            screenshotRedactor,
            toolNamePrefix,
            perms,
        )
        registerSystemActionTools(registrar, actionExecutor, accessibilityServiceProvider, toolNamePrefix, perms)
        registerTouchActionTools(registrar, actionExecutor, toolNamePrefix, perms)
        registerGestureTools(registrar, actionExecutor, toolNamePrefix, perms)
        registerNodeActionTools(
            registrar,
            treeParser,
            elementFinder,
            actionExecutor,
            accessibilityServiceProvider,
            nodeCache,
            privacyToolGate,
            placeholderSubstitutor,
            toolNamePrefix,
            perms,
        )
        registerTextInputTools(
            registrar,
            treeParser,
            actionExecutor,
            accessibilityServiceProvider,
            typeInputController,
            nodeCache,
            privacyToolGate,
            placeholderSubstitutor,
            toolNamePrefix,
            perms,
        )
        registerUtilityTools(
            registrar,
            treeParser,
            elementFinder,
            accessibilityServiceProvider,
            nodeCache,
            privacyToolGate,
            placeholderSubstitutor,
            toolNamePrefix,
            perms,
        )
    }

    private fun registerSharingBundle(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
        perms: ToolPermissionsConfig,
        fileSizeLimitMb: Int,
    ) {
        registerSharingTools(
            registrar,
            sharedContentInbox,
            ephemeralFileLinkService,
            fileOperationProvider,
            fileSizeLimitMb,
            currentBaseUrl,
            privacyToolGate,
            toolNamePrefix,
            perms,
        )
    }

    override fun onDestroy() {
        screenStateSnapshotCache.clear()
        // Release the Privacy Mode model and clear session-scoped pseudonym/NER caches (never persisted).
        privacyModeManager.shutdown()
        pseudonymStore.clear()
        nerCache.clear()
        Log.i(TAG, "McpServerService destroying")
        updateStatus(ServerStatus.Stopping)

        // Second, later attempt to durably commit an explicit stop, in case the first bounded write in
        // onStartCommand timed out. Gated on lastIntentWasStop so an OEM/system kill (last action was a
        // start) never clears the flag — there the flag MUST stay true for restart-if-running.
        if (lastIntentWasStop) {
            persistServerRunning(settingsRepository, false)
        }

        // Cancel tunnel status observer before stopping the tunnel
        tunnelObserverJob?.cancel()
        tunnelObserverJob = null

        // Cancel the OAuth approval observer and clear any pending approval notification.
        approvalObserverJob?.cancel()
        approvalObserverJob = null
        OAuthApprovalNotifier.cancel(this)

        // Stop tunnel first (with ANR-safe timeout).
        // Worst-case blocking time: TUNNEL_STOP_TIMEOUT_MS (3s) + SHUTDOWN_GRACE_PERIOD_MS (1s)
        // + SHUTDOWN_TIMEOUT_MS (5s) = ~9s total. This is well within the Android service
        // onDestroy ANR threshold (~200s), so blocking the main thread here is acceptable.
        @Suppress("TooGenericExceptionCaught")
        try {
            runBlocking {
                withTimeout(TUNNEL_STOP_TIMEOUT_MS) {
                    tunnelManager.stop()
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Tunnel stop timed out after ${TUNNEL_STOP_TIMEOUT_MS}ms, proceeding with shutdown", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tunnel", e)
        }

        // Stop the Ktor server gracefully
        @Suppress("TooGenericExceptionCaught")
        try {
            mcpServer?.stop(
                gracePeriodMillis = SHUTDOWN_GRACE_PERIOD_MS,
                timeoutMillis = SHUTDOWN_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during server shutdown", e)
        }
        mcpServer = null
        serverActive.set(false)

        // Cancel coroutine scope
        coroutineScope.cancel()

        // Clear singleton
        instance = null

        updateStatus(ServerStatus.Stopped)
        Log.i(TAG, "McpServerService destroyed")

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateStatus(status: ServerStatus) {
        _serverStatus.value = status
        serverLogRepository.log(ServerLogEntry.Type.SERVER, serverStatusLogMessage(status))
    }

    private fun createNotification(): Notification {
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return NotificationCompat
            .Builder(this, McpApplication.MCP_SERVER_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_mcp_server_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MCP:ServerService"
        const val ACTION_START = "com.cococopi.andromcp.ACTION_START_MCP_SERVER"
        const val ACTION_STOP = "com.cococopi.andromcp.ACTION_STOP_MCP_SERVER"
        const val NOTIFICATION_ID = 1001
        const val SHUTDOWN_GRACE_PERIOD_MS = 1000L
        const val SHUTDOWN_TIMEOUT_MS = 5000L
        const val TUNNEL_STOP_TIMEOUT_MS = 3_000L

        /**
         * Shared server status flow. Collected by MainViewModel to update the UI.
         * Uses a companion-level StateFlow so it survives service rebinding and is
         * accessible without requiring a bound service reference.
         */
        private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
        val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

        @Volatile
        var instance: McpServerService? = null
            private set
    }
}

/** Builds the HTTPS [HttpsMaterial] when both the keystore and its password are present, else null. */
private fun buildHttpsMaterial(
    keyStore: KeyStore?,
    keyStorePassword: CharArray?,
): HttpsMaterial? =
    if (keyStore != null && keyStorePassword != null) {
        HttpsMaterial(keyStore, keyStorePassword)
    } else {
        null
    }

/** Human-readable server-log message for a [ServerStatus] transition. */
internal fun serverStatusLogMessage(status: ServerStatus): String =
    when (status) {
        ServerStatus.Starting -> "Server starting"
        is ServerStatus.Running -> "Server started on ${status.bindingAddress}:${status.port}"
        ServerStatus.Stopping -> "Server stopping"
        ServerStatus.Stopped -> "Server stopped"
        is ServerStatus.Error -> "Server error: ${status.message}"
    }

/** Server-log message for a Privacy Mode self-check result at server start. */
internal fun privacyStatusLogMessage(status: PrivacyModeStatus): String =
    when (status) {
        is PrivacyModeStatus.Ready -> {
            "Privacy mode ready (model)"
        }

        is PrivacyModeStatus.ReadyDeterministicOnly -> {
            "Privacy mode ready (deterministic only)"
        }

        is PrivacyModeStatus.Unavailable -> {
            "Privacy mode UNAVAILABLE: ${status.reason} — data-returning tools are blocked"
        }

        is PrivacyModeStatus.Disabled -> {
            "Privacy mode disabled"
        }
    }

/** Server-log message for a tunnel status transition; null when not logged by the observer. */
internal fun tunnelStatusLogMessage(status: TunnelStatus): String? =
    when (status) {
        TunnelStatus.Connecting -> "Tunnel connecting…"
        is TunnelStatus.Connected -> "Tunnel connected: ${status.endpoints.joinToString { it.url }}"
        is TunnelStatus.Error -> "Tunnel error: ${status.message}"
        TunnelStatus.Disconnected -> null // Logged by TunnelManager.stop()
    }
