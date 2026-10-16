package com.cococopi.andromcp.integration

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cococopi.andromcp.data.model.PrivacyModeConfig
import com.cococopi.andromcp.data.model.ServerConfig
import com.cococopi.andromcp.data.model.ServerLogEntry
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.data.repository.OAuthClientRepository
import com.cococopi.andromcp.data.repository.OAuthClientRepositoryImpl
import com.cococopi.andromcp.data.repository.SettingsRepository
import com.cococopi.andromcp.mcp.effectiveBaseUrl
import com.cococopi.andromcp.mcp.installMcpBasePlugins
import com.cococopi.andromcp.mcp.installMcpStatelessTransport
import com.cococopi.andromcp.mcp.oauth.AuthorizationCodeStoreImpl
import com.cococopi.andromcp.mcp.oauth.JwtTokenService
import com.cococopi.andromcp.mcp.oauth.JwtTokenServiceImpl
import com.cococopi.andromcp.mcp.oauth.OAuthAccessValidator
import com.cococopi.andromcp.mcp.oauth.OAuthApprovalCoordinator
import com.cococopi.andromcp.mcp.oauth.OAuthApprovalCoordinatorImpl
import com.cococopi.andromcp.mcp.oauth.OAuthRouteDeps
import com.cococopi.andromcp.mcp.oauth.OAuthServerDeps
import com.cococopi.andromcp.mcp.oauth.installOAuthRoutes
import com.cococopi.andromcp.mcp.tools.LoggedToolRegistrar
import com.cococopi.andromcp.mcp.tools.McpToolUtils
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
import com.cococopi.andromcp.privacy.ContextExtractor
import com.cococopi.andromcp.privacy.DeterministicEngine
import com.cococopi.andromcp.privacy.PlaceholderSubstitutor
import com.cococopi.andromcp.privacy.PrivacyModeManager
import com.cococopi.andromcp.privacy.PrivacyModeStatus
import com.cococopi.andromcp.privacy.PrivacyPipelineImpl
import com.cococopi.andromcp.privacy.PrivacyToolGate
import com.cococopi.andromcp.privacy.PseudonymStore
import com.cococopi.andromcp.privacy.RedactionEngine
import com.cococopi.andromcp.privacy.Redactor
import com.cococopi.andromcp.privacy.detectors.CardDetector
import com.cococopi.andromcp.privacy.detectors.CredentialDetector
import com.cococopi.andromcp.privacy.detectors.EmailDetector
import com.cococopi.andromcp.privacy.detectors.IbanDetector
import com.cococopi.andromcp.privacy.detectors.NationalIdDetector
import com.cococopi.andromcp.privacy.detectors.PhoneDetector
import com.cococopi.andromcp.privacy.ner.NerCache
import com.cococopi.andromcp.privacy.ner.NerEngine
import com.cococopi.andromcp.privacy.ner.PiiModelInference
import com.cococopi.andromcp.services.accessibility.AccessibilityNodeCache
import com.cococopi.andromcp.services.accessibility.AccessibilityNodeData
import com.cococopi.andromcp.services.accessibility.AccessibilityServiceProvider
import com.cococopi.andromcp.services.accessibility.AccessibilityTreeParser
import com.cococopi.andromcp.services.accessibility.ActionExecutor
import com.cococopi.andromcp.services.accessibility.CompactTreeFormatter
import com.cococopi.andromcp.services.accessibility.ElementFinder
import com.cococopi.andromcp.services.accessibility.ScreenInfo
import com.cococopi.andromcp.services.accessibility.ScreenStateSnapshotCache
import com.cococopi.andromcp.services.accessibility.ScreenStateSnapshotCacheImpl
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
import com.cococopi.andromcp.testutil.RecordingServerLogRepository
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Integration test helper that configures a Ktor [testApplication] with the same
 * plugin configuration as [com.cococopi.andromcp.mcp.McpServer].
 *
 * Uses the MCP Kotlin SDK [Server] and [Client] with [StreamableHttpClientTransport]
 * for full-stack integration testing through the Streamable HTTP endpoint at `/mcp`.
 *
 * @see com.cococopi.andromcp.mcp.McpServer
 */
object McpIntegrationTestHelper {
    const val TEST_BEARER_TOKEN = "test-integration-token"
    const val TEST_BASE_URL = "http://localhost:8080"

    /**
     * Configures multi-window mocking on the given [MockDependencies].
     *
     * Sets up [AccessibilityServiceProvider.getAccessibilityWindows] to return
     * a single mock [AccessibilityWindowInfo] whose root node parses to the given tree.
     *
     * @param deps The mock dependencies to configure.
     * @param tree The parsed accessibility tree to return.
     * @param screenInfo Screen dimensions for getScreenInfo().
     * @param packageName Package name for the window and tracked package.
     * @param activityName Activity name for the focused window.
     * @param windowId The window ID for the mock window.
     */
    @Suppress("LongParameterList")
    fun setupMultiWindowMock(
        deps: MockDependencies,
        tree: AccessibilityNodeData,
        screenInfo: ScreenInfo,
        packageName: String = "com.example.app",
        activityName: String = ".MainActivity",
        windowId: Int = 0,
    ): AccessibilityNodeInfo {
        val mockRootNode = mockk<AccessibilityNodeInfo>()
        val mockWindowInfo = mockk<AccessibilityWindowInfo>(relaxed = true)

        every { deps.accessibilityServiceProvider.isReady() } returns true
        every { mockWindowInfo.id } returns windowId
        every { mockWindowInfo.root } returns mockRootNode
        every { mockWindowInfo.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { mockWindowInfo.title } returns "Test"
        every { mockWindowInfo.layer } returns 0
        every { mockWindowInfo.isFocused } returns true
        every { mockRootNode.refresh() } returns true
        every { mockRootNode.packageName } returns packageName
        // Raw-node walk support: rawNodeExists() reads these properties directly
        // from AccessibilityNodeInfo without going through AccessibilityTreeParser.
        // Return null/0/empty so the root node does not match any search criteria
        // (individual tests that need a match will override these stubs).
        every { mockRootNode.text } returns null
        every { mockRootNode.contentDescription } returns null
        every { mockRootNode.viewIdResourceName } returns null
        every { mockRootNode.className } returns null
        every { mockRootNode.childCount } returns 0
        every { mockRootNode.availableExtraData } returns emptyList()
        every {
            deps.accessibilityServiceProvider.getAccessibilityWindows()
        } returns listOf(mockWindowInfo)
        every { deps.accessibilityServiceProvider.getCurrentPackageName() } returns packageName
        every { deps.accessibilityServiceProvider.getCurrentActivityName() } returns activityName
        every { deps.accessibilityServiceProvider.getScreenInfo() } returns screenInfo
        every { deps.treeParser.parseTree(mockRootNode, "root_w$windowId", any()) } returns tree
        return mockRootNode
    }

    /**
     * Creates mocked service dependencies used by all tool handlers.
     */
    fun createMockDependencies(): MockDependencies {
        val statusFlow = MutableStateFlow<PrivacyModeStatus>(PrivacyModeStatus.Disabled)
        // disabled by default; setPrivacy() changes it
        val configFlow = MutableStateFlow(PrivacyModeConfig())
        val pseudonymStore = PseudonymStore()
        val piiModelInference = mockk<PiiModelInference>()
        coEvery { piiModelInference.infer(any()) } returns emptyList()
        val manager = mockk<PrivacyModeManager>()
        // reads the LATEST value each call
        coEvery { manager.currentConfig() } answers { configFlow.value }
        every { manager.status } returns statusFlow
        val pipeline =
            PrivacyPipelineImpl(
                manager = manager,
                engine =
                    RedactionEngine(
                        DeterministicEngine(
                            CredentialDetector(),
                            CardDetector(),
                            IbanDetector(),
                            EmailDetector(),
                            PhoneDetector(),
                            NationalIdDetector(),
                        ),
                        NerEngine(piiModelInference, NerCache()),
                        ContextExtractor(),
                        Redactor(pseudonymStore),
                    ),
            )
        return MockDependencies(
            actionExecutor = mockk(relaxed = true),
            accessibilityServiceProvider = mockk(relaxed = true),
            screenCaptureProvider = mockk(relaxed = true),
            treeParser = mockk(relaxed = true),
            elementFinder = mockk(relaxed = true),
            storageLocationProvider = mockk(relaxed = true),
            fileOperationProvider = mockk(relaxed = true),
            appManager = mockk(relaxed = true),
            typeInputController = mockk(relaxed = true),
            screenshotAnnotator = mockk(relaxed = true),
            screenshotEncoder = mockk(relaxed = true),
            cameraProvider = mockk(relaxed = true),
            nodeCache = mockk(relaxed = true),
            screenStateSnapshotCache = ScreenStateSnapshotCacheImpl(),
            intentDispatcher = mockk(relaxed = true),
            notificationProvider = mockk(relaxed = true),
            locationProvider = mockk(relaxed = true),
            sharedContentInbox = mockk(relaxed = true),
            ephemeralFileLinkService = mockk(relaxed = true),
            privacyStatusFlow = statusFlow,
            privacyConfigFlow = configFlow,
            privacyModeManager = manager,
            piiModelInference = piiModelInference,
            pseudonymStore = pseudonymStore,
            privacyToolGate = PrivacyToolGate(pipeline),
            placeholderSubstitutor = PlaceholderSubstitutor(pseudonymStore),
            serverLog = RecordingServerLogRepository(),
        )
    }

    /**
     * Reconfigures privacy per test case. Both flows are live-read by the pipeline,
     * so mutating them changes what the gate observes on the next call.
     */
    fun setPrivacy(
        deps: MockDependencies,
        config: PrivacyModeConfig,
        status: PrivacyModeStatus,
    ) {
        deps.privacyConfigFlow.value = config
        deps.privacyStatusFlow.value = status
    }

    /**
     * Registers all MCP tools with the given [Server] using mocked dependencies.
     */
    fun registerAllTools(
        server: Server,
        deps: MockDependencies,
        deviceSlug: String = "",
        perms: ToolPermissionsConfig = ToolPermissionsConfig(),
    ) {
        val toolNamePrefix = McpToolUtils.buildToolNamePrefix(deviceSlug)
        val registrar = LoggedToolRegistrar(server, deps.serverLog)
        registerScreenIntrospectionTools(
            registrar,
            deps.treeParser,
            deps.accessibilityServiceProvider,
            deps.screenCaptureProvider,
            CompactTreeFormatter(),
            deps.screenshotAnnotator,
            deps.screenshotEncoder,
            deps.nodeCache,
            deps.screenStateSnapshotCache,
            WebViewNodeMerger(),
            deps.privacyToolGate,
            ScreenshotRedactor(),
            toolNamePrefix,
            perms,
        )
        registerSystemActionTools(
            registrar,
            deps.actionExecutor,
            deps.accessibilityServiceProvider,
            toolNamePrefix,
            perms,
        )
        registerTouchActionTools(registrar, deps.actionExecutor, toolNamePrefix, perms)
        registerGestureTools(registrar, deps.actionExecutor, toolNamePrefix, perms)
        registerInteractionToolBundle(registrar, deps, toolNamePrefix, perms)
        registerNonAccessibilityTools(registrar, deps, toolNamePrefix, perms)
        registerExtendedToolBundles(registrar, deps, toolNamePrefix, perms)
    }

    /**
     * Registers the device, media, package, web and memory bundles.
     *
     * Mirrors [com.cococopi.andromcp.services.mcp.McpServerService.registerAllTools] so the tool-count
     * integration tests measure the surface the running server actually exposes.
     */
    private fun registerExtendedToolBundles(
        registrar: LoggedToolRegistrar,
        deps: MockDependencies,
        toolNamePrefix: String,
        perms: ToolPermissionsConfig,
    ) {
        registerDeviceServiceTools(registrar, deps.context, deps.intentDispatcher, toolNamePrefix, perms)
        registerCommsTools(registrar, deps.context, toolNamePrefix, perms)
        registerNetworkTools(registrar, deps.context, toolNamePrefix, perms)
        registerHardwareTools(registrar, deps.context, toolNamePrefix, perms)
        registerDeviceSettingsTools(registrar, deps.context, toolNamePrefix, perms)
        registerWorkspaceTools(registrar, deps.context, toolNamePrefix, perms)
        registerDataTools(registrar, deps.context, toolNamePrefix, perms)
        registerShellTools(registrar, deps.context, toolNamePrefix, perms)
        registerTerminalTools(registrar, deps.context, toolNamePrefix, perms)
        registerMediaTools(registrar, deps.context, toolNamePrefix, perms)
        registerAudioCaptureTools(registrar, deps.context, toolNamePrefix, perms)
        registerCryptoVaultTools(registrar, deps.context, toolNamePrefix, perms)
        registerPackageTools(registrar, deps.context, toolNamePrefix, perms)
        registerBluetoothExtrasTools(registrar, deps.context, toolNamePrefix, perms)
        registerNotificationExtrasTools(registrar, deps.context, toolNamePrefix, perms)
        registerWebTools(registrar, deps.context, toolNamePrefix, perms)
        registerAgentMemoryTools(registrar, deps.context, toolNamePrefix, perms)
    }

    private fun registerInteractionToolBundle(
        registrar: LoggedToolRegistrar,
        deps: MockDependencies,
        toolNamePrefix: String,
        perms: ToolPermissionsConfig,
    ) {
        registerNodeActionTools(
            registrar,
            deps.treeParser,
            deps.elementFinder,
            deps.actionExecutor,
            deps.accessibilityServiceProvider,
            deps.nodeCache,
            deps.privacyToolGate,
            deps.placeholderSubstitutor,
            toolNamePrefix,
            perms,
        )
        registerTextInputTools(
            registrar,
            deps.treeParser,
            deps.actionExecutor,
            deps.accessibilityServiceProvider,
            deps.typeInputController,
            deps.nodeCache,
            deps.privacyToolGate,
            deps.placeholderSubstitutor,
            toolNamePrefix,
            perms,
        )
        registerUtilityTools(
            registrar,
            deps.treeParser,
            deps.elementFinder,
            deps.accessibilityServiceProvider,
            deps.nodeCache,
            deps.privacyToolGate,
            deps.placeholderSubstitutor,
            toolNamePrefix,
            perms,
        )
    }

    private fun registerNonAccessibilityTools(
        registrar: LoggedToolRegistrar,
        deps: MockDependencies,
        toolNamePrefix: String,
        perms: ToolPermissionsConfig,
    ) {
        registerFileTools(registrar, deps.storageLocationProvider, deps.fileOperationProvider, toolNamePrefix, perms)
        registerAppManagementTools(registrar, deps.appManager, deps.privacyToolGate, toolNamePrefix, perms)
        registerCameraTools(registrar, deps.cameraProvider, deps.fileOperationProvider, toolNamePrefix, perms)
        registerIntentTools(registrar, deps.intentDispatcher, toolNamePrefix, perms)
        registerNotificationTools(
            registrar,
            deps.notificationProvider,
            deps.privacyToolGate,
            deps.placeholderSubstitutor,
            toolNamePrefix,
            perms,
        )
        registerLocationTools(registrar, deps.locationProvider, deps.privacyToolGate, toolNamePrefix, perms)
        registerSharingTools(
            registrar,
            deps.sharedContentInbox,
            deps.ephemeralFileLinkService,
            deps.fileOperationProvider,
            ServerConfig.DEFAULT_FILE_SIZE_LIMIT_MB,
            { TEST_BASE_URL },
            deps.privacyToolGate,
            toolNamePrefix,
            perms,
        )
    }

    /**
     * Mocks [android.util.Log] static methods to prevent crashes in JVM unit tests.
     * Must be called in @BeforeEach.
     */
    fun mockAndroidLog() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    /**
     * Unmocks [android.util.Log] static methods.
     * Must be called in @AfterEach.
     */
    fun unmockAndroidLog() {
        unmockkStatic(android.util.Log::class)
    }

    /**
     * Creates an SDK [Server] with all tools registered using the given dependencies.
     */
    fun createSdkServer(
        deps: MockDependencies,
        deviceSlug: String = "",
        perms: ToolPermissionsConfig = ToolPermissionsConfig(),
    ): Server {
        val server =
            Server(
                serverInfo =
                    Implementation(
                        name = McpToolUtils.buildServerName(deviceSlug),
                        version = "test",
                    ),
                options =
                    ServerOptions(
                        capabilities =
                            ServerCapabilities(
                                tools = ServerCapabilities.Tools(listChanged = false),
                            ),
                    ),
            )
        registerAllTools(server, deps, deviceSlug, perms)
        return server
    }

    /**
     * Runs a test within a fully configured Ktor [testApplication] using the MCP SDK
     * [Client] with [StreamableHttpClientTransport].
     *
     * The application is configured with ContentNegotiation (McpJson),
     * BearerTokenAuthPlugin, and the stateless Streamable HTTP transport, mirroring the
     * production McpServer setup.
     *
     * @param deps Mocked service dependencies (created via [createMockDependencies]).
     * @param testBlock The test code to execute with the SDK [Client] and [MockDependencies].
     */
    suspend fun withTestApplication(
        deps: MockDependencies = createMockDependencies(),
        deviceSlug: String = "",
        perms: ToolPermissionsConfig = ToolPermissionsConfig(),
        testBlock: suspend (client: Client, deps: MockDependencies) -> Unit,
    ) {
        val sdkServer = createSdkServer(deps, deviceSlug, perms)

        testApplication {
            application {
                // Uses the production base-plugin wiring (ContentNegotiation → CORS → auth).
                installMcpBasePlugins {
                    expectedToken = TEST_BEARER_TOKEN
                    onAuthFailure = { deps.serverLog.log(ServerLogEntry.Type.AUTH, "Authentication failed from $it") }
                }
                installMcpStatelessTransport { sdkServer }
            }

            val httpClient =
                createClient {
                    install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                        json(McpJson)
                    }
                    install(io.ktor.client.plugins.sse.SSE)
                }

            val transport =
                StreamableHttpClientTransport(
                    client = httpClient,
                    url = "/mcp",
                    requestBuilder = {
                        headers.append("Authorization", "Bearer $TEST_BEARER_TOKEN")
                    },
                )

            val mcpClient =
                Client(
                    clientInfo = Implementation(name = "test-client", version = "1.0.0"),
                )
            mcpClient.connect(transport)

            try {
                testBlock(mcpClient, deps)
            } finally {
                mcpClient.close()
            }
        }
    }

    /**
     * Runs a test within a fully configured Ktor [testApplication] using a raw
     * HTTP client (not the MCP SDK client). Useful for testing authentication
     * rejection where the SDK client would fail to connect.
     *
     * @param deps Mocked service dependencies (created via [createMockDependencies]).
     * @param testBlock The test code to execute within the testApplication.
     */
    suspend fun withRawTestApplication(
        deps: MockDependencies = createMockDependencies(),
        bearerTokenEnabled: Boolean = true,
        oauthEnabled: Boolean = false,
        testBlock: suspend io.ktor.server.testing.ApplicationTestBuilder.(MockDependencies) -> Unit,
    ) {
        val sdkServer = createSdkServer(deps)

        testApplication {
            application {
                installMcpBasePlugins {
                    this.bearerTokenEnabled = bearerTokenEnabled
                    expectedToken = if (bearerTokenEnabled) TEST_BEARER_TOKEN else ""
                    this.oauthEnabled = oauthEnabled
                    onAuthFailure = { deps.serverLog.log(ServerLogEntry.Type.AUTH, "Authentication failed from $it") }
                }
                installMcpStatelessTransport { sdkServer }
            }

            testBlock(deps)
        }
    }

    /** Test-side collaborators exposed to OAuth integration tests. */
    class OAuthTestContext(
        val httpClient: io.ktor.client.HttpClient,
        val clientRepository: OAuthClientRepository,
        val approvalCoordinator: OAuthApprovalCoordinator,
        val tokenService: JwtTokenService,
    )

    /**
     * Runs a test within a Ktor [testApplication] configured exactly like production with OAuth enabled:
     * real [JwtTokenServiceImpl] (mocked signing secret), real in-memory code store + approval
     * coordinator, real [OAuthClientRepositoryImpl] over a temp dedicated DataStore, the shared
     * [OAuthAccessValidator], the production [McpAuthPlugin] exclusions, the mounted OAuth routes, and
     * the stateless Streamable HTTP transport. The test drives the DCR→authorize→approve→token→/mcp dance itself.
     *
     * @param bearerTokenEnabled When true (with [bearerToken]), exercises dual-accept.
     * @param publicUrlOverride Pins the metadata/`aud` host (empty = request-derived).
     */
    suspend fun withOAuthTestApplication(
        deps: MockDependencies = createMockDependencies(),
        bearerTokenEnabled: Boolean = false,
        bearerToken: String = "",
        publicUrlOverride: String = "",
        testBlock: suspend io.ktor.server.testing.ApplicationTestBuilder.(OAuthTestContext) -> Unit,
    ) {
        val sdkServer = createSdkServer(deps)
        val settingsRepository = mockk<SettingsRepository>()
        io.mockk.coEvery { settingsRepository.getOrCreateJwtSigningSecret() } returns OAUTH_TEST_SIGNING_SECRET
        val tokenService = JwtTokenServiceImpl(settingsRepository)
        val tempDir =
            java.nio.file.Files
                .createTempDirectory("oauth_helper")
                .toFile()
        val clientsDataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { java.io.File(tempDir, "oauth_clients.preferences_pb") },
            )
        // spyk wraps the real impl transparently so tests can coVerify last-used touches.
        val clientRepository = io.mockk.spyk(OAuthClientRepositoryImpl(clientsDataStore, deps.serverLog))
        val codeStore = AuthorizationCodeStoreImpl()
        val approvalCoordinator = OAuthApprovalCoordinatorImpl(deps.serverLog)
        val accessValidator = OAuthAccessValidator(tokenService, clientRepository, deps.serverLog)

        testApplication {
            application {
                // Full production wiring WITH CORS in front of the real OAuth routes, so the
                // OAuth-flow tests verify OAuth and CORS coexist end-to-end.
                installMcpBasePlugins {
                    this.bearerTokenEnabled = bearerTokenEnabled
                    expectedToken = bearerToken
                    oauthEnabled = true
                    baseUrlOf = { effectiveBaseUrl(it, publicUrlOverride) }
                    validateOAuthToken = { token, resource -> accessValidator.validate(token, resource) }
                    excludedPaths = setOf("/health", "/register", "/token", "/authorize", "/authorize/status")
                    excludedPathPrefixes = setOf(EphemeralFileLinkService.PATH_PREFIX, "/.well-known/")
                    onAuthFailure = { deps.serverLog.log(ServerLogEntry.Type.AUTH, "Authentication failed from $it") }
                }
                routing {
                    installOAuthRoutes(
                        OAuthRouteDeps(
                            oauth =
                                OAuthServerDeps(
                                    jwtTokenService = tokenService,
                                    oauthClientRepository = clientRepository,
                                    authorizationCodeStore = codeStore,
                                    approvalCoordinator = approvalCoordinator,
                                    geoIpResolver = { null },
                                ),
                            publicUrlOverride = publicUrlOverride,
                            serverLog = deps.serverLog,
                        ),
                    )
                }
                installMcpStatelessTransport(publicUrlOverride = publicUrlOverride) { sdkServer }
            }

            val httpClient =
                createClient {
                    install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json(McpJson) }
                    install(io.ktor.client.plugins.sse.SSE)
                }

            testBlock(OAuthTestContext(httpClient, clientRepository, approvalCoordinator, tokenService))
        }
    }

    private const val OAUTH_TEST_SIGNING_SECRET = "oauth-test-signing-secret-0123456789-abc"
}

/**
 * Holds mocked service dependencies for integration tests.
 */
data class MockDependencies(
    val actionExecutor: ActionExecutor,
    val accessibilityServiceProvider: AccessibilityServiceProvider,
    val screenCaptureProvider: ScreenCaptureProvider,
    val treeParser: AccessibilityTreeParser,
    val elementFinder: ElementFinder,
    val storageLocationProvider: StorageLocationProvider,
    val fileOperationProvider: FileOperationProvider,
    val appManager: AppManager,
    val typeInputController: TypeInputController,
    val screenshotAnnotator: ScreenshotAnnotator,
    val screenshotEncoder: ScreenshotEncoder,
    val cameraProvider: CameraProvider,
    val nodeCache: AccessibilityNodeCache,
    val screenStateSnapshotCache: ScreenStateSnapshotCache,
    val intentDispatcher: IntentDispatcher,
    val notificationProvider: NotificationProvider,
    val locationProvider: LocationProvider,
    val sharedContentInbox: SharedContentInbox,
    val ephemeralFileLinkService: EphemeralFileLinkService,
    val privacyStatusFlow: MutableStateFlow<PrivacyModeStatus>,
    val privacyConfigFlow: MutableStateFlow<PrivacyModeConfig>,
    val privacyModeManager: PrivacyModeManager,
    val piiModelInference: PiiModelInference,
    val pseudonymStore: PseudonymStore,
    val privacyToolGate: PrivacyToolGate,
    val placeholderSubstitutor: PlaceholderSubstitutor,
    /** Relaxed mock: the extended bundles only touch the context inside their handlers. */
    val context: Context = mockk(relaxed = true),
    val serverLog: RecordingServerLogRepository = RecordingServerLogRepository(),
)
