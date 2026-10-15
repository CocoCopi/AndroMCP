package com.cococopi.andromcp.services.channel

import com.cococopi.andromcp.data.model.ChannelConnectionStatus
import com.cococopi.andromcp.data.model.ChannelEvent
import kotlinx.coroutines.flow.StateFlow

interface EventDispatcher {
    val connectionStatus: StateFlow<ChannelConnectionStatus>

    suspend fun dispatch(event: ChannelEvent): Result<Unit>

    suspend fun healthCheck(): Result<Unit>

    fun start(
        endpointUrl: String,
        authToken: String,
    )

    fun stop()
}
