package com.cococopi.andromcp.mcp.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.cococopi.andromcp.mcp.McpToolException

/**
 * Runtime-permission gate shared by every MCP tool that reads or writes permission-protected
 * device data (messages, call log, contacts, calendar, audio capture, Wi-Fi scan results).
 *
 * These tools gate themselves at call time instead of being hidden at registration time, so the
 * agent always sees the full capability surface and gets an actionable
 * [McpToolException.PermissionDenied] naming the exact permission when it is missing. Settings
 * screens can be opened with [openAppDetails] / [openSettings] so the agent can take the user
 * straight to the place where the grant happens.
 */
internal object RuntimePermissionGate {
    /** True when [permission] is currently granted to this app. */
    fun isGranted(
        context: Context,
        permission: String,
    ): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Throws [McpToolException.PermissionDenied] unless [permission] is granted.
     *
     * @param toolName Un-prefixed tool name, used to make the error message actionable.
     */
    fun require(
        context: Context,
        permission: String,
        toolName: String,
    ) {
        if (!isGranted(context, permission)) {
            throw McpToolException.PermissionDenied(
                "Tool '$toolName' needs the Android permission '$permission', which is not granted " +
                    "to this app. In Android Settings open Apps > this app > Permissions, grant it, " +
                    "then call the tool again.",
            )
        }
    }

    /** Opens the OS app-details screen for this app, where every permission can be granted. */
    fun openAppDetails(context: Context): Boolean = launch(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri(context)))

    /** Opens an OS settings screen identified by [action]. Returns false when none handles it. */
    fun openSettings(
        context: Context,
        action: String,
    ): Boolean = launch(context, Intent(action))

    private fun appUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

    private fun launch(
        context: Context,
        intent: Intent,
    ): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        }
    }
}
