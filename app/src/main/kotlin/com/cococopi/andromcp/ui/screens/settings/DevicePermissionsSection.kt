@file:Suppress("FunctionNaming")

package com.cococopi.andromcp.ui.screens.settings

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cococopi.andromcp.R

/**
 * Runtime permission groups and special accesses that gate the device-data tool bundles
 * (messages, contacts, calendar, call log, Bluetooth, usage stats, system settings, app installs).
 *
 * Every entry is optional: the tools stay registered and answer with an actionable
 * PermissionDenied error until the matching grant happens here. Rows re-read their status on
 * ON_RESUME, so granting in the OS screen, or returning from a permission dialog, updates the
 * checkmarks without leaving the settings screen.
 */
@Composable
internal fun DevicePermissionsSection() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshToken by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshToken++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshToken++
        }

    PermissionSectionHeader(
        title = stringResource(R.string.permission_section_device_title),
        subtitle = stringResource(R.string.permission_section_device_subtitle),
    )

    RuntimePermissionGroupRows(refreshToken = refreshToken, onRequest = permissionLauncher::launch)
    SpecialAccessRows(refreshToken = refreshToken)
}

/** One row per runtime permission group (messages, contacts, calendar, call log, Bluetooth). */
@Composable
private fun RuntimePermissionGroupRows(
    refreshToken: Int,
    onRequest: (Array<String>) -> Unit,
) {
    val context = LocalContext.current

    remember(refreshToken) { runtimePermissionGroups() }.forEach { group ->
        val granted = group.permissions.isEmpty() || group.permissions.all { context.isGranted(it) }
        PermissionRow(
            label = stringResource(group.labelRes),
            rationale = stringResource(group.rationaleRes),
            isEnabled = granted,
            buttonText =
                if (granted) {
                    stringResource(R.string.permission_granted)
                } else {
                    stringResource(R.string.permission_grant)
                },
            onAction = {
                // An empty list means the permission does not exist on this OS version; fall back
                // to the app details screen so the user can still inspect what is granted.
                if (group.permissions.isEmpty()) {
                    context.openAppDetails()
                } else {
                    onRequest(group.permissions.toTypedArray())
                }
            },
            actionEnabled = !granted,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** One row per special access, each granted on its own OS screen instead of a dialog. */
@Composable
private fun SpecialAccessRows(refreshToken: Int) {
    val context = LocalContext.current

    specialAccesses().forEach { access ->
        val granted = remember(refreshToken) { access.isGranted(context) }
        PermissionRow(
            label = stringResource(access.labelRes),
            rationale = stringResource(access.rationaleRes),
            isEnabled = granted,
            buttonText =
                if (granted) {
                    stringResource(R.string.permission_granted)
                } else {
                    stringResource(R.string.permission_enable)
                },
            onAction = { context.openSettingsIntent(access.settingsAction) },
            actionEnabled = !granted,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** One runtime permission group: granted only when every permission in [permissions] is granted. */
private class RuntimePermissionGroup(
    val labelRes: Int,
    val rationaleRes: Int,
    val permissions: List<String>,
)

/** Special accesses that are granted from a dedicated OS screen instead of a permission dialog. */
private class SpecialAccess(
    val labelRes: Int,
    val rationaleRes: Int,
    val settingsAction: String,
    val isGranted: (Context) -> Boolean,
)

private fun runtimePermissionGroups(): List<RuntimePermissionGroup> =
    listOf(
        RuntimePermissionGroup(
            labelRes = R.string.permission_sms,
            rationaleRes = R.string.permission_sms_rationale,
            permissions = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS),
        ),
        RuntimePermissionGroup(
            labelRes = R.string.permission_contacts,
            rationaleRes = R.string.permission_contacts_rationale,
            permissions = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
        ),
        RuntimePermissionGroup(
            labelRes = R.string.permission_calendar,
            rationaleRes = R.string.permission_calendar_rationale,
            permissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        ),
        RuntimePermissionGroup(
            labelRes = R.string.permission_call_log,
            rationaleRes = R.string.permission_call_log_rationale,
            permissions = listOf(Manifest.permission.READ_CALL_LOG),
        ),
        RuntimePermissionGroup(
            labelRes = R.string.permission_bluetooth,
            rationaleRes = R.string.permission_bluetooth_rationale,
            permissions = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
        ),
    )

private fun specialAccesses(): List<SpecialAccess> =
    listOf(
        SpecialAccess(
            labelRes = R.string.permission_usage_access,
            rationaleRes = R.string.permission_usage_access_rationale,
            settingsAction = Settings.ACTION_USAGE_ACCESS_SETTINGS,
            isGranted = ::hasUsageAccess,
        ),
        SpecialAccess(
            labelRes = R.string.permission_write_settings,
            rationaleRes = R.string.permission_write_settings_rationale,
            settingsAction = Settings.ACTION_MANAGE_WRITE_SETTINGS,
            isGranted = { context -> Settings.System.canWrite(context) },
        ),
        SpecialAccess(
            labelRes = R.string.permission_install_apps,
            rationaleRes = R.string.permission_install_apps_rationale,
            settingsAction = Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            isGranted = { context -> context.packageManager.canRequestPackageInstalls() },
        ),
    )

private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppDetails() {
    openSettingsIntent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
}

private fun Context.openSettingsIntent(action: String) {
    val intent =
        Intent(action).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    runCatching { startActivity(intent) }
}
