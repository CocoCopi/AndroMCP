package com.cococopi.andromcp.di

import com.cococopi.andromcp.data.repository.GeofenceConfigRepository
import com.cococopi.andromcp.data.repository.GeofenceConfigRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * gms flavor: binds the geofence config repository. Kept separate from GmsGeofenceModule so the
 * eager startup migration seam has its binding without depending on later user stories.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GmsGeofenceConfigModule {
    @Binds
    @Singleton
    abstract fun bindGeofenceConfigRepository(impl: GeofenceConfigRepositoryImpl): GeofenceConfigRepository
}
