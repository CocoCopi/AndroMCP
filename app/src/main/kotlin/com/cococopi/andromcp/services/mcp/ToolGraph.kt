@file:Suppress("LongParameterList")

package com.cococopi.andromcp.services.mcp

import android.content.Context
import com.cococopi.andromcp.BuildConfig
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.data.repository.ServerLogRepository
import com.cococopi.andromcp.mcp.tools.LoggedToolRegistrar
import com.cococopi.andromcp.mcp.tools.RecordedTool
import com.cococopi.andromcp.mcp.tools.ReferenceCountedToolCallIndicator
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
import com.cococopi.andromcp.privacy.PrivacyToolGate
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
import com.cococopi.andromcp.services.notifications.NotificationProvider
import com.cococopi.andromcp.services.screencapture.ScreenCaptureProvider
import com.cococopi.andromcp.services.screencapture.ScreenshotAnnotator
import com.cococopi.andromcp.services.screencapture.ScreenshotEncoder
import com.cococopi.andromcp.services.screencapture.ScreenshotRedactor
import com.cococopi.andromcp.services.sharing.EphemeralFileLinkService
import com.cococopi.andromcp.services.sharing.SharedContentInbox
import com.cococopi.andromcp.services.storage.FileOperationProvider
import com.cococopi.andromcp.services.storage.StorageLocationProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place where the MCP tool surface is assembled.
 *
 * Both consumers use it, so a tool added here is instantly available to both and can never exist on
 * one path only:
 * - [McpServerService] registers onto the real server that external clients connect to;
 * - [captureCatalog] registers onto a throwaway server with a [com.cococopi.andromcp.mcp.tools.ToolRecorder],
 *   handing the in-app agent the very same handlers, so the agent and an external client see
 *   identical names, schemas, permission gates and logging.
 *
 * The dependencies are the union of everything the bundles need; the server-only ones (tunnels,
 * certificates, OAuth) deliberately stay in the service.
 */
@Singleton
class ToolGraph
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val serverLogRepository: ServerLogRepository,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val screenCaptureProvider: ScreenCaptureProvider,
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val compactTreeFormatter: CompactTreeFormatter,
        private val screenshotAnnotator: ScreenshotAnnotator,
        private val screenshotEncoder: ScreenshotEncoder,
        private val storageLocationProvider: StorageLocationProvider,
        private val fileOperationProvider: FileOperationProvider,
        private val appManager: AppManager,
        private val typeInputController: TypeInputController,
        private val nodeCache: AccessibilityNodeCache,
        private val screenStateSnapshotCache: ScreenStateSnapshotCache,
        private val webViewNodeMerger: WebViewNodeMerger,
        private val cameraProvider: CameraProvider,
        private val intentDispatcher: IntentDispatcher,
        private val notificationProvider: NotificationProvider,
        private val locationProvider: LocationProvider,
        private val ephemeralFileLinkService: EphemeralFileLinkService,
        private val sharedContentInbox: SharedContentInbox,
        private val privacyToolGate: PrivacyToolGate,
        private val placeholderSubstitutor: PlaceholderSubstitutor,
        private val screenshotRedactor: ScreenshotRedactor,
    ) {
        /**
         * Registers every enabled tool onto [server].
         *
         * @param baseUrl public base URL for capability links, evaluated lazily per tool call.
         * @param recorder optional capture hook used by [captureCatalog]; the real server passes none.
         */
        fun registerAll(
            server: Server,
            toolNamePrefix: String,
            perms: ToolPermissionsConfig,
            fileSizeLimitMb: Int,
            toolCallIndicatorEnabled: Boolean,
            baseUrl: () -> String,
            recorder: com.cococopi.andromcp.mcp.tools.ToolRecorder? = null,
        ) {
            val toolCallIndicator = ReferenceCountedToolCallIndicator(AccessibilityToolCallIndicator())
            toolCallIndicator.setEnabled(toolCallIndicatorEnabled)
            val registrar =
                LoggedToolRegistrar(
                    server = server,
                    serverLog = serverLogRepository,
                    toolCallIndicator = toolCallIndicator,
                    permissions = perms,
                    recorder = recorder,
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
            registerSharingBundle(registrar, toolNamePrefix, perms, fileSizeLimitMb, baseUrl)
            registerDeviceServiceTools(registrar, context, intentDispatcher, toolNamePrefix, perms)
            registerCommsTools(registrar, context, toolNamePrefix, perms)
            registerNetworkTools(registrar, context, toolNamePrefix, perms)
            registerHardwareTools(registrar, context, toolNamePrefix, perms)
            registerDeviceSettingsTools(registrar, context, toolNamePrefix, perms)
            registerWorkspaceTools(registrar, context, toolNamePrefix, perms)
            registerDataTools(registrar, context, toolNamePrefix, perms)
            registerShellTools(registrar, context, toolNamePrefix, perms)
            registerTerminalTools(registrar, context, toolNamePrefix, perms)
            registerMediaTools(registrar, context, toolNamePrefix, perms)
            registerAudioCaptureTools(registrar, context, toolNamePrefix, perms)
            registerCryptoVaultTools(registrar, context, toolNamePrefix, perms)
            registerPackageTools(registrar, context, toolNamePrefix, perms)
            registerBluetoothExtrasTools(registrar, context, toolNamePrefix, perms)
            registerNotificationExtrasTools(registrar, context, toolNamePrefix, perms)
            registerWebTools(registrar, context, toolNamePrefix, perms)
            registerAgentMemoryTools(registrar, context, toolNamePrefix, perms)
        }

        /**
         * Captures the currently enabled tools keyed by their un-prefixed name, for in-process
         * callers.
         *
         * A throwaway [Server] satisfies the registrar without ever being connected: nothing is
         * listening, nothing is exposed, and the handlers captured here enforce the same permission
         * and privacy gates as the real ones.
         */
        fun captureCatalog(
            toolNamePrefix: String,
            perms: ToolPermissionsConfig,
            fileSizeLimitMb: Int,
            baseUrl: () -> String,
        ): Map<String, RecordedTool> {
            val captured = LinkedHashMap<String, RecordedTool>()
            registerAll(
                server = captureServer(),
                toolNamePrefix = toolNamePrefix,
                perms = perms,
                fileSizeLimitMb = fileSizeLimitMb,
                toolCallIndicatorEnabled = false,
                baseUrl = baseUrl,
                recorder = { tool -> captured[tool.name] = tool },
            )
            return captured
        }

        private fun captureServer(): Server =
            Server(
                serverInfo = Implementation(name = CAPTURE_SERVER_NAME, version = BuildConfig.VERSION_NAME),
                options =
                    ServerOptions(
                        capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
                    ),
            )

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
            baseUrl: () -> String,
        ) {
            registerSharingTools(
                registrar,
                sharedContentInbox,
                ephemeralFileLinkService,
                fileOperationProvider,
                fileSizeLimitMb,
                baseUrl,
                privacyToolGate,
                toolNamePrefix,
                perms,
            )
        }

        private companion object {
            const val CAPTURE_SERVER_NAME = "cococopi-agent-catalog"
        }
    }
