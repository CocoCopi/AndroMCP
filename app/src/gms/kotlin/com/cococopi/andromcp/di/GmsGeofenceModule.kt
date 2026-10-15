package com.cococopi.andromcp.di

import com.cococopi.andromcp.services.channel.GeofenceChannelController
import com.cococopi.andromcp.services.channel.GeofenceChannelControllerImpl
import com.cococopi.andromcp.services.channel.geofence.GeofenceManager
import com.cococopi.andromcp.services.channel.geofence.GeofenceManagerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** gms flavor: binds the real geofence manager + channel controller. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GmsGeofenceModule {
    @Binds
    @Singleton
    abstract fun bindGeofenceManager(impl: GeofenceManagerImpl): GeofenceManager

    @Binds
    @Singleton
    abstract fun bindGeofenceChannelController(impl: GeofenceChannelControllerImpl): GeofenceChannelController
}
