@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.cococopi.andromcp.mcp.tools

import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.mcp.McpToolException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Package and app-management tool bundle.
 *
 * The launcher sees a handful of icons; the package manager sees every app, service, permission and
 * signature on the device. These tools expose that view: enumerate and search installed apps,
 * inspect one in depth, list what it declares and what it may actually do, hash its signing
 * certificate, read its usage statistics, launch it, stop it, open its settings page, uninstall it
 * and install an APK that the agent built or downloaded.
 *
 * `QUERY_ALL_PACKAGES` is already declared, so the inventory is complete on Android 13+. Usage
 * statistics are gated behind the separate "Usage access" grant, which the tool explains when it is
 * missing, and installing an APK needs the user's one-time "install unknown apps" approval plus the
 * system confirmation dialog — both of which the tools report honestly rather than hiding.
 */

/** Row shape shared by the app listings. */
private fun appRow(
    packageManager: PackageManager,
    info: PackageInfo,
): JsonObject {
    val application = info.applicationInfo
    return buildJsonObject {
        put("package_name", info.packageName)
        put("label", application?.let { packageManager.getApplicationLabel(it).toString() }.orEmpty())
        put("version_name", info.versionName.orEmpty())
        put("version_code", info.longVersionCode)
        put("is_system", application != null && (application.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
        put("enabled", application?.enabled ?: false)
        put("target_sdk", application?.targetSdkVersion ?: 0)
        put("size_bytes", application?.sourceDir?.let { File(it).length() } ?: 0L)
        put("first_install_epoch_ms", info.firstInstallTime)
        put("last_update_epoch_ms", info.lastUpdateTime)
    }
}

/** Hexadecimal SHA-256 (or other) digest, matching the hashing used elsewhere in the server. */
private fun digestHex(
    bytes: ByteArray,
    algorithm: String,
): String {
    val digest = MessageDigest.getInstance(algorithm).digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * Decodes one APK signer blob (`PackageInfo`/`SigningInfo` hand out [Signature] objects) into its
 * X.509 certificate. Returns null when the blob cannot be parsed.
 */
private fun x509CertificateOf(signature: Signature): X509Certificate? =
    try {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(signature.toByteArray())) as? X509Certificate
    } catch (_: CertificateException) {
        null
    }

/** Shared package-manager plumbing for the app bundle. */
private object AppInventory {
    /** Package info flags used for listings: identity, versions and the application block. */
    val LIST_FLAGS: PackageManager.PackageInfoFlags = PackageManager.PackageInfoFlags.of(0L)

    /** Resolves a package, throwing an actionable error when it is not installed. */
    fun requirePackage(
        context: Context,
        packageName: String,
    ): PackageInfo =
        try {
            context.packageManager.getPackageInfo(packageName, LIST_FLAGS)
        } catch (_: PackageManager.NameNotFoundException) {
            throw McpToolException.InvalidParams(
                "App '$packageName' is not installed. Use search_apps to find the right package name.",
            )
        }

    /** Every installed package, sorted by package name. */
    fun installedPackages(context: Context): List<PackageInfo> =
        context.packageManager
            .getInstalledPackages(LIST_FLAGS)
            .sortedBy { it.packageName }

    /** Starts an activity, adding the flag a non-activity context requires. */
    fun startActivity(
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

// ─────────────────────────────────────────────────────────────────────────────
// list_installed_apps
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_installed_apps`.
 * Input: `{ "include_system": <bool>?, "limit": <int>? }`.
 */
class ListInstalledAppsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val includeSystem = McpToolUtils.optionalBoolean(arguments, "include_system", false)
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        Log.d(TAG, "Executing list_installed_apps")

        val rows =
            withContext(Dispatchers.IO) {
                AppInventory
                    .installedPackages(context)
                    .filter { includeSystem || (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM == 0 }
                    .map { appRow(context.packageManager, it) }
            }
        val shown = rows.take(limit)
        val json =
            buildJsonObject {
                put("include_system", includeSystem)
                put("total_matches", rows.size)
                put("returned", shown.size)
                put("truncated", rows.size > shown.size)
                putJsonArray("apps") { shown.forEach { add(it) } }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Lists installed apps with package name, label, version, install dates, target SDK and " +
                    "APK size. User-installed apps come first by default; set 'include_system': true " +
                    "when you need the whole inventory, including the system apps that provide " +
                    "settings pages, launchers and dialers. Use it to discover what is available, to " +
                    "find the package name that other tools need, or to check what a fresh device has " +
                    "before driving it. For a specific name, search_apps is faster.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("include_system") {
                                put("type", "boolean")
                                put("description", "Include system apps (default false)")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum apps to return (default $DEFAULT_LIMIT)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_installed_apps"
        private const val TAG = "MCP:ListInstalledAppsHandler"
        private const val DEFAULT_LIMIT = 100
        private const val MAX_LIMIT = 1000
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// search_apps
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `search_apps`.
 * Input: `{ "query": <string>, "include_system": <bool>?, "limit": <int>? }`.
 */
class SearchAppsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val query = McpToolUtils.requireString(arguments, "query").trim().lowercase()
        val includeSystem = McpToolUtils.optionalBoolean(arguments, "include_system", true)
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        Log.d(TAG, "Executing search_apps")

        val rows =
            withContext(Dispatchers.IO) {
                AppInventory
                    .installedPackages(context)
                    .filter { info ->
                        val application = info.applicationInfo
                        val system = application != null && (application.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val allowed = includeSystem || !system
                        val label = application?.let { context.packageManager.getApplicationLabel(it).toString() }
                        allowed &&
                            (
                                info.packageName.lowercase().contains(query) ||
                                    label?.lowercase()?.contains(query) == true
                            )
                    }.map { appRow(context.packageManager, it) }
            }
        val shown = rows.take(limit)
        val json =
            buildJsonObject {
                put("query", query)
                put("total_matches", rows.size)
                put("returned", shown.size)
                putJsonArray("apps") { shown.forEach { add(it) } }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Finds installed apps by a case-insensitive substring of their package name or their " +
                    "visible label, so \"chrome\" finds com.android.chrome and \"com.google\" finds " +
                    "the whole family. Use it as the first step whenever a task names an app in human " +
                    "terms and the tools need a package name — then feed that name to launch_app, " +
                    "get_app_info, uninstall_app or open_app_settings. Unlike list_installed_apps it " +
                    "searches system apps by default.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "Substring of the package name or app label")
                            }
                            putJsonObject("include_system") {
                                put("type", "boolean")
                                put("description", "Search system apps too (default true)")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum apps to return (default $DEFAULT_LIMIT)")
                            }
                        },
                    required = listOf("query"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "search_apps"
        private const val TAG = "MCP:SearchAppsHandler"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 1000
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_app_info
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_app_info`. Input: `{ "package_name": <string> }`. */
class GetAppInfoHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val packageName = McpToolUtils.requireString(arguments, "package_name").trim()
        Log.d(TAG, "Executing get_app_info")

        val info = withContext(Dispatchers.IO) { AppInventory.requirePackage(context, packageName) }
        val application = info.applicationInfo
        val flags = application?.flags ?: 0
        val signature =
            withContext(Dispatchers.IO) {
                runCatching {
                    val signers = info.signingInfo?.apkContentsSigners
                    signers?.firstOrNull()?.let { digestHex(it.toByteArray(), "SHA-256") }
                }.getOrNull()
            }
        val json =
            buildJsonObject {
                put("package_name", info.packageName)
                put("label", application?.let { context.packageManager.getApplicationLabel(it).toString() }.orEmpty())
                put("version_name", info.versionName.orEmpty())
                put("version_code", info.longVersionCode)
                put("uid", application?.uid ?: 0)
                put("is_system", (flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                put("is_updated_system_app", (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                put("debuggable", (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                put("enabled", application?.enabled ?: false)
                put("stopped", (flags and ApplicationInfo.FLAG_STOPPED) != 0)
                put("target_sdk", application?.targetSdkVersion ?: 0)
                put("min_sdk", application?.minSdkVersion ?: 0)
                put("device_sdk", Build.VERSION.SDK_INT)
                put("apk_path", application?.sourceDir.orEmpty())
                put("apk_size_bytes", application?.sourceDir?.let { File(it).length() } ?: 0L)
                put("data_dir", application?.dataDir.orEmpty())
                put("native_library_dir", application?.nativeLibraryDir.orEmpty())
                val installer = context.packageManager.getInstallSourceInfo(packageName).installingPackageName
                put("installer", installer.orEmpty())
                put("first_install_epoch_ms", info.firstInstallTime)
                put("last_update_epoch_ms", info.lastUpdateTime)
                put("signature_sha256", signature.orEmpty())
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Returns a deep profile of one installed app: version and version code, uid, target " +
                    "and minimum SDK, enabled and stopped state, whether it is debuggable or a system " +
                    "app, where its APK, data and native libraries live, its size, who installed it, " +
                    "when, and the SHA-256 of its signing certificate. Use it to identify an app " +
                    "precisely before acting on it, to confirm a rebuild actually replaced the old " +
                    "install (compare the signature and update time), or to gather the facts needed " +
                    "when reporting a problem with a specific app.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("package_name") {
                                put("type", "string")
                                put("description", "Package name, e.g. \"com.android.chrome\"")
                            }
                        },
                    required = listOf("package_name"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_app_info"
        private const val TAG = "MCP:GetAppInfoHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_app_permissions
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_app_permissions`. Input: `{ "package_name": <string> }`. */
class GetAppPermissionsHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val packageName = McpToolUtils.requireString(arguments, "package_name").trim()
        Log.d(TAG, "Executing get_app_permissions")

        val info =
            withContext(Dispatchers.IO) {
                try {
                    context.packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    throw McpToolException.InvalidParams("App '$packageName' is not installed.")
                }
            }
        val requested = info.requestedPermissions?.toList().orEmpty()
        val grantedCount =
            requested.count { permission ->
                context.packageManager.checkPermission(permission, packageName) ==
                    PackageManager.PERMISSION_GRANTED
            }
        val json =
            buildJsonObject {
                put("package_name", packageName)
                put("requested_count", requested.size)
                put("granted_count", grantedCount)
                putJsonArray("permissions") {
                    requested.forEach { permission ->
                        add(
                            buildJsonObject {
                                put("permission", permission)
                                put(
                                    "granted",
                                    context.packageManager.checkPermission(permission, packageName) ==
                                        PackageManager.PERMISSION_GRANTED,
                                )
                            },
                        )
                    }
                }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Lists every permission an app declares and whether it is currently granted. Use it " +
                    "to explain why an app cannot do something, to check whether a sensitive " +
                    "permission is actually held before relying on it, or to audit what a freshly " +
                    "installed app is asking for. Note that a declared-but-denied permission is " +
                    "normal for runtime permissions until the user grants them, so read both counts " +
                    "before drawing conclusions.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("package_name") {
                                put("type", "string")
                                put("description", "Package name to inspect")
                            }
                        },
                    required = listOf("package_name"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_app_permissions"
        private const val TAG = "MCP:GetAppPermissionsHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_app_components
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_app_components`. Input: `{ "package_name": <string>, "limit": <int>? }`. */
class GetAppComponentsHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val packageName = McpToolUtils.requireString(arguments, "package_name").trim()
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        Log.d(TAG, "Executing get_app_components")

        val info =
            withContext(Dispatchers.IO) {
                val flags =
                    PackageManager.GET_ACTIVITIES.toLong() or
                        PackageManager.GET_SERVICES.toLong() or
                        PackageManager.GET_RECEIVERS.toLong() or
                        PackageManager.GET_PROVIDERS.toLong()
                try {
                    context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
                } catch (_: PackageManager.NameNotFoundException) {
                    throw McpToolException.InvalidParams("App '$packageName' is not installed.")
                }
            }
        fun names(components: Array<out android.content.pm.ComponentInfo>?): List<String> =
            components.orEmpty().map { it.name }.sorted().take(limit)
        val json =
            buildJsonObject {
                put("package_name", packageName)
                putJsonObject("activities") { putJsonArray("names") { names(info.activities).forEach { add(it) } } }
                putJsonObject("services") { putJsonArray("names") { names(info.services).forEach { add(it) } } }
                putJsonObject("receivers") { putJsonArray("names") { names(info.receivers).forEach { add(it) } } }
                putJsonObject("providers") {
                    putJsonArray("names") { names(info.providers).forEach { add(it) } }
                }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Lists the activities, services, broadcast receivers and content providers an app " +
                    "declares, by fully qualified class name. Use it to find the exact component to " +
                    "start or query, to learn how an app is structured before automating it, or to " +
                    "confirm a feature exists in the version installed. Pair it with query_intent_handlers " +
                    "when you care which app responds to an intent rather than which components a " +
                    "single app owns.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("package_name") {
                                put("type", "string")
                                put("description", "Package name to inspect")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum names per component type (default $DEFAULT_LIMIT)")
                            }
                        },
                    required = listOf("package_name"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_app_components"
        private const val TAG = "MCP:GetAppComponentsHandler"
        private const val DEFAULT_LIMIT = 200
        private const val MAX_LIMIT = 1000
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_app_signature
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_app_signature`. Input: `{ "package_name": <string> }`. */
class GetAppSignatureHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val packageName = McpToolUtils.requireString(arguments, "package_name").trim()
        Log.d(TAG, "Executing get_app_signature")

        val info =
            withContext(Dispatchers.IO) {
                try {
                    context.packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    throw McpToolException.InvalidParams("App '$packageName' is not installed.")
                }
            }
        val signingInfo =
            info.signingInfo
                ?: throw McpToolException.ActionFailed(
                    "The system did not expose signing information for '$packageName'.",
                )
        val certificates =
            withContext(Dispatchers.IO) {
                val current = signingInfo.apkContentsSigners?.toList().orEmpty()
                val history = signingInfo.signingCertificateHistory?.toList().orEmpty()
                (current + history).distinct()
            }
        val json =
            buildJsonObject {
                put("package_name", packageName)
                put("has_multiple_signers", signingInfo.hasMultipleSigners())
                put("has_past_signing_certificates", signingInfo.hasPastSigningCertificates())
                putJsonArray("signatures") {
                    certificates.forEach { signature ->
                        val certificate = x509CertificateOf(signature)
                        add(
                            buildJsonObject {
                                put("sha256", digestHex(signature.toByteArray(), "SHA-256"))
                                put("sha1", digestHex(signature.toByteArray(), "SHA-1"))
                                put("signature_algorithm", certificate?.sigAlgName.orEmpty())
                                put("issuer", certificate?.issuerDN?.name.orEmpty())
                                put("subject", certificate?.subjectDN?.name.orEmpty())
                                put("not_before", certificate?.notBefore?.time)
                                put("not_after", certificate?.notAfter?.time)
                            },
                        )
                    }
                }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Returns the signing certificates of an installed app with their SHA-256 and SHA-1 " +
                    "fingerprints, validity dates and issuer. Use it to confirm that a build really " +
                    "came from the expected key (a sideloaded rebuild with a different certificate " +
                    "cannot update the original), to check whether an app is signed with multiple " +
                    "signers, or to detect that the installed copy is not the one you think it is — " +
                    "the fingerprint is the only reliable identity an app has.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("package_name") {
                                put("type", "string")
                                put("description", "Package name to inspect")
                            }
                        },
                    required = listOf("package_name"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_app_signature"
        private const val TAG = "MCP:GetAppSignatureHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_app_usage_stats
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_app_usage_stats`. Input: `{ "days": <int>?, "limit": <int>? }`. */
class GetAppUsageStatsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val days = McpToolUtils.optionalInt(arguments, "days", 1).coerceIn(1, MAX_DAYS)
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        Log.d(TAG, "Executing get_app_usage_stats")

        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: throw McpToolException.ActionFailed("The usage-statistics service is unavailable on this device.")
        val end = System.currentTimeMillis()
        val begin = end - days.toLong() * MILLIS_PER_DAY
        val rows =
            withContext(Dispatchers.IO) {
                manager
                    .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
                    .sortedByDescending { it.totalTimeInForeground }
                    .take(limit)
                    .map { stat ->
                        buildJsonObject {
                            put("package_name", stat.packageName)
                            put("total_time_in_foreground_ms", stat.totalTimeInForeground)
                            put("total_time_visible_ms", stat.totalTimeVisible)
                            put("last_time_used_epoch_ms", stat.lastTimeUsed)
                            put("first_time_stamp_epoch_ms", stat.firstTimeStamp)
                            put("last_time_stamp_epoch_ms", stat.lastTimeStamp)
                        }
                    }
            }
        if (rows.isEmpty()) {
            throw McpToolException.PermissionDenied(
                "No usage data is available. Android keeps app usage behind the separate \"Usage " +
                    "access\" grant: open Settings > Apps > Special app access > Usage access and " +
                    "enable it for this app, then call the tool again.",
            )
        }
        val json =
            buildJsonObject {
                put("days", days)
                put("window_start_epoch_ms", begin)
                put("window_end_epoch_ms", end)
                put("count", rows.size)
                putJsonArray("usage") { rows.forEach { add(it) } }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports how long each app has been in the foreground over the last few days, longest " +
                    "first, with last-used timestamps. Use it to answer questions about the user's " +
                    "device behaviour, to find a distraction worth limiting, or to check whether an " +
                    "app the agent launched was actually used. It requires the separate \"Usage " +
                    "access\" grant (Settings > Apps > Special app access > Usage access) and says so " +
                    "explicitly when that grant is missing instead of returning an empty list.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("days") {
                                put("type", "integer")
                                put("description", "Days of history to summarize (default 1)")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum apps to return (default $DEFAULT_LIMIT)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_app_usage_stats"
        private const val TAG = "MCP:GetAppUsageStatsHandler"
        private const val DEFAULT_LIMIT = 30
        private const val MAX_LIMIT = 200
        private const val MAX_DAYS = 30
        private const val MILLIS_PER_DAY = 86_400_000L
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// launch_app
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `launch_app`. Input: `{ "package_name": <string>, "activity": <string>? }`. */
class LaunchAppHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val packageName = McpToolUtils.requireString(arguments, "package_name").trim()
        val activity = McpToolUtils.optionalString(arguments, "activity", "").trim()
        Log.d(TAG, "Executing launch_app")

        val intent =
            withContext(Dispatchers.IO) {
                if (activity.isNotEmpty()) {
                    Intent().setClassName(packageName, activity)
                } else {
                    context.packageManager.getLaunchIntentForPackage(packageName)
                        ?: throw McpToolException.ActionFailed(
                            "App '$packageName' has no launcher activity, so it cannot be started this " +
                                "way. Pass an explicit 'activity' (see get_app_components), or use " +
                                "send_intent with the action the app responds to.",
                        )
                }
            }
        val started = withContext(Dispatchers.IO) { AppInventory.startActivity(context, intent) }
        if (!started) {
            throw McpToolException.ActionFailed(
                "Android refused to start '$packageName'. The component may not exist, or the app may " +
                    "be stopped or disabled.",
            )
        }
        val json =
            buildJsonObject {
                put("package_name", packageName)
                put("launched", true)
                put("activity", activity)
                put("note", "The app is starting. Use wait_for_idle or get_screen_state to see when it is ready.")
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Starts an app's launcher activity, or a specific activity when you name one. Use it " +
                    "to bring an app to the front before interacting with its screen, to relaunch " +
                    "something that was closed, or to jump straight into a particular screen that " +
                    "exposes a deep-link activity (find the name with get_app_components). Unlike a " +
                    "tap on the launcher this is exact: it fails loudly when nothing can be started, " +
                    "so a silent no-op never leaves the agent acting on the wrong screen.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("package_name") {
                                put("type", "string")
                                put("description", "Package name of the app to start")
                            }
                            putJsonObject("activity") {
                                put("type", "string")
                                put("description", "Fully qualified activity class to start instead of the launcher")
                            }
                        },
                    required = listOf("package_name"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "launch_app"
        private const val TAG = "MCP:LaunchAppHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// force_stop_app
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `force_stop_app`. Input: `{ "package_name": <string> }`. */
class ForceStopAppHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val packageName = McpToolUtils.requireString(arguments, "package_name").trim()
        Log.d(TAG, "Executing force_stop_app")

        if (packageName == context.packageName) {
            throw McpToolException.InvalidParams(
                "Refusing to stop this server's own app ('$packageName'), which would take the MCP " +
                    "server down with it.",
            )
        }
        withContext(Dispatchers.IO) { AppInventory.requirePackage(context, packageName) }
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: throw McpToolException.ActionFailed("The activity service is unavailable on this device.")
        withContext(Dispatchers.IO) { activityManager.killBackgroundProcesses(packageName) }
        val json =
            buildJsonObject {
                put("package_name", packageName)
                put("stop_requested", true)
                put("note", "Background processes were killed. On Android 13+ the app may be restarted " +
                    "by the system if it holds a foreground service; su_exec with `am force-stop` is " +
                    "the harder stop.")
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Kills an app's background processes so it stops consuming battery, network or " +
                    "microphone time. Use it to clean up after driving an app, to stop a media player " +
                    "that keeps playing, or to give another app a clean start. It refuses to target " +
                    "this server's own package, and it is a soft stop: a foreground service can bring " +
                    "the process back, in which case su_exec with `am force-stop` is the definitive " +
                    "answer.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("package_name") {
                                put("type", "string")
                                put("description", "Package name whose background processes should be killed")
                            }
                        },
                    required = listOf("package_name"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "force_stop_app"
        private const val TAG = "MCP:ForceStopAppHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// open_app_settings
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `open_app_settings`. Input: `{ "package_name": <string> }`. */
class OpenAppSettingsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val packageName = McpToolUtils.requireString(arguments, "package_name").trim()
        Log.d(TAG, "Executing open_app_settings")

        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", packageName, null))
        val opened = withContext(Dispatchers.IO) { AppInventory.startActivity(context, intent) }
        if (!opened) {
            throw McpToolException.ActionFailed(
                "No settings screen accepted a request for '$packageName'. Confirm the package exists " +
                    "with search_apps.",
            )
        }
        val json =
            buildJsonObject {
                put("package_name", packageName)
                put("opened", true)
                put("screen", "App info")
                put(
                    "next",
                    "Permissions, notifications, storage and \"Force stop\" for this app are reachable " +
                        "from this screen — useful when a grant has to be given by hand.",
                )
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Opens the system \"App info\" screen for one app, which is where permissions are " +
                    "granted, notifications are toggled and storage can be cleared. Use it whenever a " +
                    "task needs something only a human can grant — a runtime permission, special " +
                    "access, or the \"install unknown apps\" toggle — then tell the user what to " +
                    "change. After it opens, screen tools can read the page and drive it like any " +
                    "other screen.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("package_name") {
                                put("type", "string")
                                put("description", "Package name whose settings page should open")
                            }
                        },
                    required = listOf("package_name"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "open_app_settings"
        private const val TAG = "MCP:OpenAppSettingsHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// uninstall_app
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `uninstall_app`. Input: `{ "package_name": <string> }`. */
class UninstallAppHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val packageName = McpToolUtils.requireString(arguments, "package_name").trim()
        Log.d(TAG, "Executing uninstall_app")

        if (packageName == context.packageName) {
            throw McpToolException.InvalidParams(
                "Refusing to uninstall this server's own app ('$packageName').",
            )
        }
        withContext(Dispatchers.IO) { AppInventory.requirePackage(context, packageName) }
        val intent = Intent(Intent.ACTION_DELETE).setData(Uri.fromParts("package", packageName, null))
        val opened = withContext(Dispatchers.IO) { AppInventory.startActivity(context, intent) }
        if (!opened) {
            throw McpToolException.ActionFailed(
                "Android refused to open the uninstall dialog for '$packageName'. The app may be a " +
                    "protected system app; su_exec with `pm uninstall` can remove it on a rooted device.",
            )
        }
        val json =
            buildJsonObject {
                put("package_name", packageName)
                put("confirmation_requested", true)
                put(
                    "note",
                    "Android is showing the uninstall confirmation. The removal only completes if the " +
                        "user accepts, and this call cannot do that on their behalf.",
                )
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Starts uninstalling an app by opening Android's own confirmation dialog, and reports " +
                    "that the dialog is up. Use it when the user asks for an app to be removed, and say " +
                    "clearly that they must confirm the prompt — no tool can click it. It refuses to " +
                    "uninstall this server's own package, and protected system apps cannot be removed " +
                    "this way at all.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("package_name") {
                                put("type", "string")
                                put("description", "Package name to uninstall")
                            }
                        },
                    required = listOf("package_name"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "uninstall_app"
        private const val TAG = "MCP:UninstallAppHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// install_apk
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `install_apk`. Input: `{ "path": <string> }`. */
class InstallApkHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught", "LongMethod")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val path = McpToolUtils.requireString(arguments, "path").trim()
        Log.d(TAG, "Executing install_apk")

        val file = File(path)
        if (!file.isFile) {
            throw McpToolException.InvalidParams(
                "No APK file at '$path'. Use the absolute path of a file this app can read — for " +
                    "example something write_workspace_file or download_from_url produced.",
            )
        }
        if (file.length() <= 0) throw McpToolException.InvalidParams("'$path' is empty.")
        val installer = context.packageManager.packageInstaller
        val sessionId =
            withContext(Dispatchers.IO) {
                try {
                    val params =
                        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    val id = installer.createSession(params)
                    installer.openSession(id).use { session ->
                        FileInputStream(file).use { input ->
                            session.openWrite(APK_STREAM_NAME, 0, file.length()).use { output ->
                                input.copyTo(output)
                                session.fsync(output)
                            }
                        }
                    }
                    id
                } catch (exception: Exception) {
                    throw McpToolException.ActionFailed(
                        "Could not stage '$path' for installation: " +
                            (exception.message ?: exception.javaClass.simpleName) +
                            ". Grant this app the \"install unknown apps\" permission in Settings > Apps > " +
                            "Special app access, and confirm the file is a valid APK.",
                    )
                }
            }
        ApkInstallStatus.notePackage("")
        val commit =
            withContext(Dispatchers.IO) {
                try {
                    val session = installer.openSession(sessionId)
                    val pendingIntent =
                        PendingIntent.getBroadcast(
                            context,
                            sessionId,
                            Intent(context, ApkInstallReceiver::class.java)
                                .setAction(INSTALL_COMMIT_ACTION),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        )
                    session.commit(pendingIntent.intentSender)
                    session.close()
                    true
                } catch (exception: Exception) {
                    throw McpToolException.ActionFailed(
                        "Could not commit the install session for '$path': " +
                            (exception.message ?: exception.javaClass.simpleName),
                    )
                }
            }
        val json =
            buildJsonObject {
                put("path", path)
                put("size_bytes", file.length())
                put("session_id", sessionId)
                put("commit_started", commit)
                put("needs_user_confirmation", true)
                put(
                    "note",
                    "Android is showing the install confirmation dialog. Once it closes, call " +
                        "get_install_status to read the outcome.",
                )
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Installs an APK that already exists on this device. Use it to sideload an app the " +
                    "agent downloaded, or to update one it built — an update must be signed by the same " +
                    "key as the installed copy, which get_app_signature can confirm. The file is " +
                    "staged through the system PackageInstaller, then Android shows its own " +
                    "confirmation dialog: this tool cannot accept it for the user, so expect " +
                    "'needs_user_confirmation': true and follow up with get_install_status. Also " +
                    "requires the one-time \"install unknown apps\" permission for this app.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Absolute path of the APK file to install")
                            }
                        },
                    required = listOf("path"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "install_apk"
        private const val TAG = "MCP:InstallApkHandler"
        private const val APK_STREAM_NAME = "base.apk"
        private const val INSTALL_COMMIT_ACTION = "com.cococopi.andromcp.action.INSTALL_COMMIT"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_install_status
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_install_status`. Input: `{}`. */
class GetInstallStatusHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_install_status")
        val last = ApkInstallStatus.last()
        if (last == null) {
            return McpToolUtils.textResult(
                buildJsonObject {
                    put("reported", false)
                    put(
                        "note",
                        "No install session has finished since the server started. If install_apk was " +
                            "called recently, the confirmation dialog may still be waiting on screen.",
                    )
                }.toString(),
            )
        }
        return McpToolUtils.textResult(last.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports the outcome of the most recent install_apk call: whether the system has " +
                    "reported back yet, the status (success, pending_user_action, or one of the " +
                    "failure codes), the message the system attached, and which package was affected. " +
                    "Call it a few seconds after install_apk — the result only arrives once the user " +
                    "has answered the confirmation dialog, so 'reported': false means the dialog is " +
                    "still open rather than that something went wrong.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_install_status"
        private const val TAG = "MCP:GetInstallStatusHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// query_intent_handlers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `query_intent_handlers`.
 * Input: `{ "action": <string>, "data_uri": <string>?, "mime_type": <string>?, "limit": <int>? }`.
 */
class QueryIntentHandlersHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val action = McpToolUtils.requireString(arguments, "action").trim()
        val dataUri = McpToolUtils.optionalString(arguments, "data_uri", "").trim()
        val mimeType = McpToolUtils.optionalString(arguments, "mime_type", "").trim()
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        Log.d(TAG, "Executing query_intent_handlers")

        val intent = Intent(action)
        if (dataUri.isNotEmpty()) intent.data = Uri.parse(dataUri)
        if (mimeType.isNotEmpty()) intent.type = mimeType
        // The activity the system would actually launch for this intent, so `is_default` reports the
        // user's current choice instead of a flag bit taken off the wrong object.
        val defaultHandler =
            withContext(Dispatchers.IO) {
                context.packageManager
                    .resolveActivity(
                        intent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                    )?.activityInfo
            }
        val handlers =
            withContext(Dispatchers.IO) {
                context.packageManager
                    .queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
                    .mapNotNull { resolved ->
                        val activity = resolved.activityInfo ?: return@mapNotNull null
                        buildJsonObject {
                            put("package_name", activity.packageName)
                            put("activity", activity.name)
                            put(
                                "label",
                                activity.applicationInfo?.let {
                                    context.packageManager.getApplicationLabel(it).toString()
                                }.orEmpty(),
                            )
                            put(
                                "is_default",
                                defaultHandler?.let {
                                    it.packageName == activity.packageName && it.name == activity.name
                                } ?: false,
                            )
                        }
                    }.sortedBy { it["package_name"].toString() }
                    .take(limit)
            }
        val json =
            buildJsonObject {
                put("action", action)
                put("data_uri", dataUri)
                put("mime_type", mimeType)
                put("count", handlers.size)
                putJsonArray("handlers") { handlers.forEach { add(it) } }
            }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Asks the system which apps can handle an intent, optionally narrowed by a data URI or " +
                    "a MIME type, and returns the package and activity of each. Use it before " +
                    "send_intent or open_uri to learn what will actually open — whether anything can " +
                    "handle a link, which gallery will take an image share, or which browser owns an " +
                    "http action — so the agent can predict the screen it is about to cause instead of " +
                    "discovering it afterwards.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("action") {
                                put("type", "string")
                                put("description", "Intent action, e.g. \"android.intent.action.VIEW\"")
                            }
                            putJsonObject("data_uri") {
                                put("type", "string")
                                put("description", "Data URI to resolve, e.g. \"https://example.com\"")
                            }
                            putJsonObject("mime_type") {
                                put("type", "string")
                                put("description", "MIME type the handler must accept, e.g. \"image/png\"")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum handlers to return (default $DEFAULT_LIMIT)")
                            }
                        },
                    required = listOf("action"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "query_intent_handlers"
        private const val TAG = "MCP:QueryIntentHandlersHandler"
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 200
    }
}

/** Registers the app-management bundle: inventory, inspection, launching and installation. */
fun registerPackageTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    ListInstalledAppsHandler(context).register(registrar, toolNamePrefix)
    SearchAppsHandler(context).register(registrar, toolNamePrefix)
    GetAppInfoHandler(context).register(registrar, toolNamePrefix)
    GetAppPermissionsHandler(context).register(registrar, toolNamePrefix)
    GetAppComponentsHandler(context).register(registrar, toolNamePrefix)
    GetAppSignatureHandler(context).register(registrar, toolNamePrefix)
    GetAppUsageStatsHandler(context).register(registrar, toolNamePrefix)
    LaunchAppHandler(context).register(registrar, toolNamePrefix)
    ForceStopAppHandler(context).register(registrar, toolNamePrefix)
    OpenAppSettingsHandler(context).register(registrar, toolNamePrefix)
    UninstallAppHandler(context).register(registrar, toolNamePrefix)
    InstallApkHandler(context).register(registrar, toolNamePrefix)
    GetInstallStatusHandler(context).register(registrar, toolNamePrefix)
    QueryIntentHandlersHandler(context).register(registrar, toolNamePrefix)
}
