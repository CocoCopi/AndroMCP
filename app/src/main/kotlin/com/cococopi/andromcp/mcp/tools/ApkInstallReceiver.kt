package com.cococopi.andromcp.mcp.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Result of the most recent APK install session started by `install_apk`.
 *
 * A PackageInstaller session reports its outcome asynchronously — the user confirms the install in
 * a system dialog, and only then does the [PendingIntent] fire. The receiver that owns that intent
 * records the outcome here so `get_install_status` can answer the agent's next question without
 * needing any state of its own.
 */
internal object ApkInstallStatus {
    @Volatile
    private var lastResult: JsonObject? = null

    @Volatile
    private var lastPackageName: String = ""

    /** Records the outcome delivered by the system for a commit. */
    fun record(
        statusCode: Int,
        statusMessage: String,
        packageName: String?,
    ) {
        val name = packageName ?: lastPackageName
        lastPackageName = name
        lastResult =
            buildJsonObject {
                put("reported", true)
                put("status_code", statusCode)
                put("status", statusName(statusCode))
                put("status_message", statusMessage)
                put("package_name", name)
                put("completed_epoch_ms", System.currentTimeMillis())
            }
    }

    /** Remembers which package a commit is for, until the system reports back. */
    fun notePackage(packageName: String) {
        lastPackageName = packageName
        lastResult = null
    }

    /** The most recent outcome, or null when nothing has completed yet. */
    fun last(): JsonObject? = lastResult

    /** Maps a [PackageInstaller] status code to a readable name. */
    private fun statusName(statusCode: Int): String =
        when (statusCode) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "pending_user_action"
            PackageInstaller.STATUS_SUCCESS -> "success"
            PackageInstaller.STATUS_FAILURE -> "failure"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "failure_blocked"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "failure_aborted"
            PackageInstaller.STATUS_FAILURE_INVALID -> "failure_invalid"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "failure_conflict"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "failure_storage"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "failure_incompatible"
            else -> "unknown($statusCode)"
        }
}

/**
 * Receives the outcome of an APK install session.
 *
 * Declared in the manifest and referenced exclusively by an explicit [PendingIntent] from
 * `install_apk`, so it is not exported and takes no input from other apps. The extra keys it reads
 * are the ones the system documents for install sessions.
 */
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        Log.d(TAG, "APK install session reported status=$status")
        ApkInstallStatus.record(status, message, packageName)
    }

    private companion object {
        const val TAG = "MCP:ApkInstallReceiver"
    }
}
