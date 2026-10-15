package com.cococopi.andromcp.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.cococopi.andromcp.data.repository.EventChannelSettings
import com.cococopi.andromcp.data.repository.EventChannelSettingsImpl
import com.cococopi.andromcp.data.repository.OAuthClientRepository
import com.cococopi.andromcp.data.repository.OAuthClientRepositoryImpl
import com.cococopi.andromcp.data.repository.ServerLogRepository
import com.cococopi.andromcp.data.repository.ServerLogRepositoryImpl
import com.cococopi.andromcp.data.repository.SettingsRepository
import com.cococopi.andromcp.data.repository.SettingsRepositoryImpl
import com.cococopi.andromcp.geo.DbIpGeoResolver
import com.cococopi.andromcp.geo.GeoIpResolver
import com.cococopi.andromcp.mcp.oauth.AuthorizationCodeStore
import com.cococopi.andromcp.mcp.oauth.AuthorizationCodeStoreImpl
import com.cococopi.andromcp.mcp.oauth.JwtTokenService
import com.cococopi.andromcp.mcp.oauth.JwtTokenServiceImpl
import com.cococopi.andromcp.mcp.oauth.OAuthApprovalCoordinator
import com.cococopi.andromcp.mcp.oauth.OAuthApprovalCoordinatorImpl
import com.cococopi.andromcp.privacy.PrivacyPipeline
import com.cococopi.andromcp.privacy.PrivacyPipelineImpl
import com.cococopi.andromcp.privacy.model.PrivacyModelStore
import com.cococopi.andromcp.privacy.ner.OrtPiiModelRunner
import com.cococopi.andromcp.privacy.ner.PiiModelInference
import com.cococopi.andromcp.services.accessibility.AccessibilityNodeCache
import com.cococopi.andromcp.services.accessibility.AccessibilityNodeCacheImpl
import com.cococopi.andromcp.services.accessibility.AccessibilityServiceProvider
import com.cococopi.andromcp.services.accessibility.AccessibilityServiceProviderImpl
import com.cococopi.andromcp.services.accessibility.ActionExecutor
import com.cococopi.andromcp.services.accessibility.ActionExecutorImpl
import com.cococopi.andromcp.services.accessibility.ScreenStateSnapshotCache
import com.cococopi.andromcp.services.accessibility.ScreenStateSnapshotCacheImpl
import com.cococopi.andromcp.services.accessibility.TypeInputController
import com.cococopi.andromcp.services.accessibility.TypeInputControllerImpl
import com.cococopi.andromcp.services.apps.AppManager
import com.cococopi.andromcp.services.apps.AppManagerImpl
import com.cococopi.andromcp.services.camera.CameraProvider
import com.cococopi.andromcp.services.camera.CameraProviderImpl
import com.cococopi.andromcp.services.channel.EventDispatcher
import com.cococopi.andromcp.services.channel.EventDispatcherImpl
import com.cococopi.andromcp.services.intents.IntentDispatcher
import com.cococopi.andromcp.services.intents.IntentDispatcherImpl
import com.cococopi.andromcp.services.notifications.NotificationProvider
import com.cococopi.andromcp.services.notifications.NotificationProviderImpl
import com.cococopi.andromcp.services.screencapture.ApiLevelProvider
import com.cococopi.andromcp.services.screencapture.DefaultApiLevelProvider
import com.cococopi.andromcp.services.screencapture.ScreenCaptureProvider
import com.cococopi.andromcp.services.screencapture.ScreenCaptureProviderImpl
import com.cococopi.andromcp.services.sharing.EphemeralFileLinkService
import com.cococopi.andromcp.services.sharing.EphemeralFileLinkServiceImpl
import com.cococopi.andromcp.services.sharing.SharedContentInbox
import com.cococopi.andromcp.services.sharing.SharedContentInboxImpl
import com.cococopi.andromcp.services.storage.FileOperationProvider
import com.cococopi.andromcp.services.storage.FileOperationProviderImpl
import com.cococopi.andromcp.services.storage.MediaStoreFileOperations
import com.cococopi.andromcp.services.storage.MediaStoreFileOperationsImpl
import com.cococopi.andromcp.services.storage.PermissionChecker
import com.cococopi.andromcp.services.storage.PermissionCheckerImpl
import com.cococopi.andromcp.services.storage.StorageLocationProvider
import com.cococopi.andromcp.services.storage.StorageLocationProviderImpl
import com.cococopi.andromcp.services.tunnel.AndroidCloudflareBinaryResolver
import com.cococopi.andromcp.services.tunnel.CloudflaredBinaryResolver
import com.cococopi.andromcp.services.update.AppVersionProvider
import com.cococopi.andromcp.services.update.BuildConfigAppVersionProvider
import com.cococopi.andromcp.services.update.GithubReleaseChecker
import com.cococopi.andromcp.services.update.GithubReleaseCheckerImpl
import com.cococopi.andromcp.services.update.UpdateNotifier
import com.cococopi.andromcp.services.update.UpdateNotifierImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifier for the dedicated OAuth-clients Preferences DataStore. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OAuthClientsDataStore

/** Extension property for creating the Preferences DataStore on [Context]. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

/** Extension property for the dedicated OAuth-clients Preferences DataStore on [Context]. */
private val Context.oauthClientsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "oauth_clients",
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /**
     * Provides the application-scoped [DataStore] for settings persistence.
     */
    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.settingsDataStore

    /**
     * Provides the dedicated application-scoped [DataStore] for the OAuth client registry.
     */
    @Provides
    @Singleton
    @OAuthClientsDataStore
    fun provideOAuthClientsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.oauthClientsDataStore

    /**
     * Provides [Dispatchers.IO] for background work.
     */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Provides [Dispatchers.Default] for CPU-bound work (on-device NER inference).
     */
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * Provides the Privacy Mode model store rooted at the app's files directory.
     */
    @Provides
    @Singleton
    fun providePrivacyModelStore(
        @ApplicationContext context: Context,
    ): PrivacyModelStore = PrivacyModelStore(context.filesDir)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    /**
     * Binds [SettingsRepositoryImpl] as the implementation of [SettingsRepository].
     */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    /** Binds the event-channel settings slice that [SettingsRepositoryImpl] delegates to. */
    @Binds
    @Singleton
    abstract fun bindEventChannelSettings(impl: EventChannelSettingsImpl): EventChannelSettings

    /** Binds the persisted OAuth client registry. */
    @Binds
    @Singleton
    abstract fun bindOAuthClientRepository(impl: OAuthClientRepositoryImpl): OAuthClientRepository

    /** Binds the offline IP-geolocation resolver used to annotate connection requests. */
    @Binds
    @Singleton
    abstract fun bindGeoIpResolver(impl: DbIpGeoResolver): GeoIpResolver

    /** Binds the disk-backed server log used by the in-app logs viewer. */
    @Binds
    @Singleton
    abstract fun bindServerLogRepository(impl: ServerLogRepositoryImpl): ServerLogRepository
}

@Suppress("TooManyFunctions")
@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {
    @Binds
    @Singleton
    abstract fun bindAccessibilityNodeCache(impl: AccessibilityNodeCacheImpl): AccessibilityNodeCache

    @Binds
    @Singleton
    abstract fun bindScreenStateSnapshotCache(impl: ScreenStateSnapshotCacheImpl): ScreenStateSnapshotCache

    @Binds
    @Singleton
    abstract fun bindApiLevelProvider(impl: DefaultApiLevelProvider): ApiLevelProvider

    @Binds
    @Singleton
    abstract fun bindTypeInputController(impl: TypeInputControllerImpl): TypeInputController

    @Binds
    @Singleton
    abstract fun bindActionExecutor(impl: ActionExecutorImpl): ActionExecutor

    @Binds
    @Singleton
    abstract fun bindAccessibilityServiceProvider(impl: AccessibilityServiceProviderImpl): AccessibilityServiceProvider

    @Binds
    @Singleton
    abstract fun bindScreenCaptureProvider(impl: ScreenCaptureProviderImpl): ScreenCaptureProvider

    @Binds
    abstract fun bindCloudflareBinaryResolver(impl: AndroidCloudflareBinaryResolver): CloudflaredBinaryResolver

    @Binds
    @Singleton
    abstract fun bindStorageLocationProvider(impl: StorageLocationProviderImpl): StorageLocationProvider

    @Binds
    @Singleton
    abstract fun bindFileOperationProvider(impl: FileOperationProviderImpl): FileOperationProvider

    @Binds
    @Singleton
    abstract fun bindMediaStoreFileOperations(impl: MediaStoreFileOperationsImpl): MediaStoreFileOperations

    @Binds
    @Singleton
    abstract fun bindAppManager(impl: AppManagerImpl): AppManager

    @Binds
    @Singleton
    abstract fun bindCameraProvider(impl: CameraProviderImpl): CameraProvider

    @Binds
    @Singleton
    abstract fun bindIntentDispatcher(impl: IntentDispatcherImpl): IntentDispatcher

    @Binds
    @Singleton
    abstract fun bindNotificationProvider(impl: NotificationProviderImpl): NotificationProvider

    @Binds
    @Singleton
    abstract fun bindPermissionChecker(impl: PermissionCheckerImpl): PermissionChecker

    // LocationProvider is bound per-flavor (Fused in gms / LocationManager in foss) — see
    // GmsLocationModule / FossLocationModule.

    @Binds
    @Singleton
    abstract fun bindEventDispatcher(impl: EventDispatcherImpl): EventDispatcher

    // GeofenceManager and GeofenceChannelController are bound per-flavor — see GmsGeofenceModule
    // (gms) / FossGeofenceModule (foss). foss has no GeofenceManager (geofencing excluded).

    @Binds
    @Singleton
    abstract fun bindEphemeralFileLinkService(impl: EphemeralFileLinkServiceImpl): EphemeralFileLinkService

    @Binds
    @Singleton
    abstract fun bindSharedContentInbox(impl: SharedContentInboxImpl): SharedContentInbox

    @Binds
    @Singleton
    abstract fun bindJwtTokenService(impl: JwtTokenServiceImpl): JwtTokenService

    @Binds
    @Singleton
    abstract fun bindAuthorizationCodeStore(impl: AuthorizationCodeStoreImpl): AuthorizationCodeStore

    @Binds
    @Singleton
    abstract fun bindOAuthApprovalCoordinator(impl: OAuthApprovalCoordinatorImpl): OAuthApprovalCoordinator

    @Binds
    @Singleton
    abstract fun bindPrivacyPipeline(impl: PrivacyPipelineImpl): PrivacyPipeline

    @Binds
    @Singleton
    abstract fun bindPiiModelInference(impl: OrtPiiModelRunner): PiiModelInference

    @Binds
    @Singleton
    abstract fun bindGithubReleaseChecker(impl: GithubReleaseCheckerImpl): GithubReleaseChecker

    @Binds
    @Singleton
    abstract fun bindAppVersionProvider(impl: BuildConfigAppVersionProvider): AppVersionProvider

    @Binds
    @Singleton
    abstract fun bindUpdateNotifier(impl: UpdateNotifierImpl): UpdateNotifier
}
