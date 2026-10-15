package com.cococopi.andromcp.di

import com.cococopi.andromcp.services.power.BatteryOptimizationManager
import com.cococopi.andromcp.services.power.GmsBatteryOptimizationManagerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GmsBatteryModule {
    @Binds
    @Singleton
    abstract fun bindBatteryOptimizationManager(impl: GmsBatteryOptimizationManagerImpl): BatteryOptimizationManager
}
