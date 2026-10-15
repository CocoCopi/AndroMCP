package com.cococopi.andromcp.data.model

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val street: String?,
)
