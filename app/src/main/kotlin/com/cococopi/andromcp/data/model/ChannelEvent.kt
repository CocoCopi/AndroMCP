package com.cococopi.andromcp.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChannelEvent(
    val type: String,
    val timestamp: String,
    val data: JsonElement,
)
