package com.cococopi.andromcp.data.model

import com.cococopi.andromcp.services.notifications.NotificationData

data class NotificationChangeEvent(
    val eventType: NotificationChangeType,
    val notification: NotificationData,
)

enum class NotificationChangeType {
    POSTED,
    REMOVED,
}
