package com.cococopi.andromcp.services.mcp

import android.content.Context
import com.cococopi.andromcp.data.model.OptionalToolPermission
import com.cococopi.andromcp.data.model.OptionalToolPermissions
import com.cococopi.andromcp.data.model.ServerConfig
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.services.notifications.McpNotificationListenerService
import com.cococopi.andromcp.utils.PermissionUtils

/**
 * The one place that turns the four optional OS permissions into the effective tool permissions.
 *
 * Both the MCP server and the in-app agent register tools through this, so a tool the user cannot
 * use (camera not granted, notification listener off) is hidden from an external client and from the
 * agent alike — the two surfaces can never drift apart.
 *
 * ALL four signals come from [PermissionUtils], the same source the settings screen renders, so the
 * server-start gate, the greyed-out settings rows and the agent's tool list always agree.
 */
object RuntimeToolAccess {
    /** [config] plus every tool/param whose optional permission is not currently granted. */
    fun effectivePermissions(
        context: Context,
        config: ServerConfig,
    ): ToolPermissionsConfig {
        val stored = config.toolPermissionsConfig
        return OptionalToolPermissions.effectivePermissions(stored, granted(context))
    }

    /** The optional permissions currently granted to this app. */
    fun granted(context: Context): Set<OptionalToolPermission> =
        OptionalToolPermissions.grantedPermissions(
            camera = PermissionUtils.isCameraPermissionGranted(context),
            microphone = PermissionUtils.isMicrophonePermissionGranted(context),
            location = PermissionUtils.isLocationPermissionGranted(context),
            notificationListener =
                PermissionUtils.isNotificationListenerEnabled(
                    context,
                    McpNotificationListenerService::class.java,
                ),
        )
}
