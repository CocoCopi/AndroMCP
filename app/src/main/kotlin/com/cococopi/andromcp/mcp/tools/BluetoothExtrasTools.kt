@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.cococopi.andromcp.mcp.tools

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.provider.Settings
import android.util.Log
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.mcp.McpToolException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Bluetooth and USB connectivity tool bundle.
 *
 * `get_bluetooth_state` answers whether Bluetooth is on; these tools do the work around it: run a
 * Bluetooth LE scan and read the results, pair and unpair devices, show what is currently connected
 * over which profile, and inspect wired USB devices attached to the port. That is the difference
 * between knowing a headset exists and being able to route to it, and between seeing a plugged-in
 * accessory and being able to name it.
 *
 * Bluetooth reads need the `BLUETOOTH_CONNECT` grant and a scan additionally needs `BLUETOOTH_SCAN`;
 * the tools name the exact missing permission. A scan also needs a grantable location permission on
 * some builds, because without it the system cannot prove the results are not being used to infer
 * position. USB devices are visible without a grant; claiming one from another app is not
 * something a tool can do on the user's behalf.
 */

/** A device discovered by the current Bluetooth LE scan. */
private data class DiscoveredDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val lastSeenMs: Long,
    val bonded: Boolean,
)

/** Process-wide state for the single Bluetooth LE scan this server can have running. */
internal object BluetoothScanRegistry {
    private val discovered = ConcurrentHashMap<String, DiscoveredDevice>()

    @Volatile
    private var scanner: BluetoothLeScanner? = null

    @Volatile
    private var callback: ScanCallback? = null

    @Volatile
    private var startedAtMs: Long = 0L

    @Volatile
    private var nameFilter: String = ""

    /** True while a scan is running. */
    val isScanning: Boolean get() = scanner != null

    /** When the running scan started, or 0. */
    val startedAt: Long get() = startedAtMs

    /** The scan callback currently registered, if any. */
    private fun newCallback(): ScanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) {
                record(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { record(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Bluetooth scan failed with code $errorCode")
                stop()
            }
        }

    private fun record(result: ScanResult) {
        val device = result.device
        val address = device.address
        if (address.isEmpty()) return
        @Suppress("TooGenericExceptionCaught")
        val name =
            try {
                device.name.orEmpty()
            } catch (_: SecurityException) {
                ""
            }
        val wanted = nameFilter
        val matches =
            wanted.isEmpty() ||
                name.contains(wanted, ignoreCase = true) ||
                address.equals(wanted, ignoreCase = true)
        if (!matches) return
        discovered[address] =
            DiscoveredDevice(
                address = address,
                name = name,
                rssi = result.rssi,
                lastSeenMs = System.currentTimeMillis(),
                bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            )
    }

    /** Starts a scan, replacing any scan already running. */
    @Suppress("TooGenericExceptionCaught")
    fun start(
        adapter: BluetoothAdapter,
        filter: String,
    ) {
        val leScanner =
            adapter.bluetoothLeScanner
                ?: throw McpToolException.ActionFailed(
                    "Bluetooth LE scanning is unavailable. Turn Bluetooth on first — " +
                        "get_bluetooth_state reports the current state.",
                )
        stop()
        discovered.clear()
        nameFilter = filter
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val scanCallback = newCallback()
        try {
            leScanner.startScan(emptyList(), settings, scanCallback)
        } catch (exception: Exception) {
            throw McpToolException.ActionFailed(
                "Could not start a Bluetooth scan: " +
                    (exception.message ?: exception.javaClass.simpleName) +
                    ". Scanning needs the BLUETOOTH_SCAN permission, and on some builds a granted " +
                    "location permission as well.",
            )
        }
        scanner = leScanner
        callback = scanCallback
        startedAtMs = System.currentTimeMillis()
    }

    /** Stops the running scan; returns true when one was actually stopped. */
    fun stop(): Boolean {
        val active = scanner ?: return false
        val activeCallback = callback
        runCatching { if (activeCallback != null) active.stopScan(activeCallback) }
        scanner = null
        callback = null
        startedAtMs = 0L
        return true
    }

    /** Everything seen so far, strongest signal first. */
    fun results(): List<JsonObject> =
        discovered.values
            .sortedByDescending { it.rssi }
            .map { device ->
                buildJsonObject {
                    put("address", device.address)
                    put("name", device.name)
                    put("rssi", device.rssi)
                    put("bonded", device.bonded)
                    put("last_seen_epoch_ms", device.lastSeenMs)
                }
            }

    private const val TAG = "MCP:BluetoothScan"
}

/** Persisted filter so repeated listings stay consistent with the scan in flight. */
private fun bluetoothAdapter(context: Context): BluetoothAdapter {
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return manager?.adapter
        ?: throw McpToolException.ActionFailed(
            "This device has no Bluetooth adapter. get_bluetooth_state confirms what the hardware reports.",
        )
}

// ─────────────────────────────────────────────────────────────────────────────
// get_bluetooth_summary
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_bluetooth_summary`. Input: `{}`. */
class GetBluetoothSummaryHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.BLUETOOTH_CONNECT, TOOL_NAME)
        Log.d(TAG, "Executing get_bluetooth_summary")

        val adapter = bluetoothAdapter(context)
        val bonded =
            withContext(Dispatchers.IO) {
                try {
                    adapter.bondedDevices.orEmpty().map { device ->
                        buildJsonObject {
                            put("address", device.address)
                            put("name", device.name.orEmpty())
                            put("bond_state", bondStateName(device.bondState))
                            put("type", device.type)
                        }
                    }
                } catch (exception: Exception) {
                    throw McpToolException.PermissionDenied(
                        "Could not read the paired devices: " +
                            (exception.message ?: exception.javaClass.simpleName) +
                            ". Grant the nearby-devices permission to this app and try again.",
                    )
                }
            }
        val packageManager = context.packageManager
        val json =
            buildJsonObject {
                put("enabled", adapter.isEnabled)
                put("state", adapter.state)
                put("name", adapter.name.orEmpty())
                put("address", adapter.address.orEmpty())
                put("scan_mode", adapter.scanMode)
                put("le_supported", packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
                put("multiple_advertisements_supported", adapter.isMultipleAdvertisementSupported)
                put("offloaded_filtering_supported", adapter.isOffloadedFilteringSupported)
                put("scanning", BluetoothScanRegistry.isScanning)
                put("bonded_count", bonded.size)
                putJsonArray("bonded_devices") { bonded.forEach { add(it) } }
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
                "Reports the Bluetooth adapter's state in full: whether it is on, its name and " +
                    "address, whether the hardware supports Bluetooth LE, filtering and multiple " +
                    "advertisements, whether a scan is currently running, and every paired device with " +
                    "its bond state. Use it as the first call in any Bluetooth task — it tells you " +
                    "whether to expect an LE scan to work at all, and it lists the headset, watch or " +
                    "car that later tools can address by MAC address. Needs BLUETOOTH_CONNECT.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_bluetooth_summary"
        private const val TAG = "MCP:GetBluetoothSummaryHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// start_bluetooth_scan
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `start_bluetooth_scan`.
 * Input: `{ "filter": <string>?, "wait_seconds": <int>?, "stop_after": <bool>? }`.
 */
class StartBluetoothScanHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val filter = McpToolUtils.optionalString(arguments, "filter", "")
        val waitSeconds = McpToolUtils.optionalInt(arguments, "wait_seconds", DEFAULT_WAIT_SECONDS)
            .coerceIn(0, MAX_WAIT_SECONDS)
        val stopAfter = McpToolUtils.optionalBoolean(arguments, "stop_after", true)
        RuntimePermissionGate.require(context, Manifest.permission.BLUETOOTH_SCAN, TOOL_NAME)
        Log.d(TAG, "Executing start_bluetooth_scan")

        val adapter = bluetoothAdapter(context)
        withContext(Dispatchers.IO) { BluetoothScanRegistry.start(adapter, filter) }
        if (waitSeconds > 0) delay(waitSeconds.toLong() * 1000L)
        val results = BluetoothScanRegistry.results()
        val stopped = if (stopAfter) withContext(Dispatchers.IO) { BluetoothScanRegistry.stop() } else false
        val json =
            buildJsonObject {
                put("scanning", BluetoothScanRegistry.isScanning)
                put("waited_seconds", waitSeconds)
                put("stopped_after_wait", stopped)
                put("found", results.size)
                putJsonArray("results") { results.forEach { add(it) } }
                put(
                    "note",
                    if (BluetoothScanRegistry.isScanning) {
                        "The scan is still running. Call get_bluetooth_scan_results for more devices, " +
                            "then stop_bluetooth_scan when you are done."
                    } else {
                        "The scan finished; the results above are everything that was seen."
                    },
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
                "Starts a Bluetooth LE scan, waits briefly, and returns what was discovered ordered by " +
                    "signal strength. This is one call instead of three: by default it waits " +
                    "'wait_seconds' (default $DEFAULT_WAIT_SECONDS) and stops the scan before " +
                    "returning, which is enough to see nearby headphones, watches and beacons. Pass " +
                    "'stop_after': false and a short wait for a longer sweep you poll with " +
                    "get_bluetooth_scan_results, and use 'filter' to keep only devices whose name or " +
                    "address contains a substring. Needs BLUETOOTH_SCAN, and on some builds a granted " +
                    "location permission as well.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("filter") {
                                put("type", "string")
                                put("description", "Keep only devices whose name or address contains this text")
                            }
                            putJsonObject("wait_seconds") {
                                put("type", "integer")
                                put("description", "Seconds to scan before returning (default $DEFAULT_WAIT_SECONDS)")
                            }
                            putJsonObject("stop_after") {
                                put("type", "boolean")
                                put("description", "Stop the scan before returning (default true)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "start_bluetooth_scan"
        private const val TAG = "MCP:StartBluetoothScanHandler"
        private const val DEFAULT_WAIT_SECONDS = 5
        private const val MAX_WAIT_SECONDS = 25
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_bluetooth_scan_results
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_bluetooth_scan_results`.
 * Input: `{ "include_bonded": <bool>?, "limit": <int>? }`.
 */
class GetBluetoothScanResultsHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val includeBonded = McpToolUtils.optionalBoolean(arguments, "include_bonded", true)
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        Log.d(TAG, "Executing get_bluetooth_scan_results")

        val results = BluetoothScanRegistry.results()
        val filtered =
            if (includeBonded) {
                results
            } else {
                results.filter { !it["bonded"].toString().toBoolean() }
            }.take(limit)
        val json =
            buildJsonObject {
                put("scanning", BluetoothScanRegistry.isScanning)
                put("scan_started_epoch_ms", BluetoothScanRegistry.startedAt)
                put("total_discovered", results.size)
                put("returned", filtered.size)
                putJsonArray("results") { filtered.forEach { add(it) } }
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
                "Returns every device the current or most recent Bluetooth LE scan has seen, strongest " +
                    "signal first, with address, name, RSSI, bond state and when it was last heard. Use " +
                    "it to poll a scan started with 'stop_after': false — repeated calls show the list " +
                    "growing, so you can wait for the device you want to appear instead of guessing " +
                    "when the scan is finished. Set 'include_bonded': false to hide devices that are " +
                    "already paired and focus on new ones.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("include_bonded") {
                                put("type", "boolean")
                                put("description", "Include already-paired devices (default true)")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum devices to return (default $DEFAULT_LIMIT)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_bluetooth_scan_results"
        private const val TAG = "MCP:GetBluetoothScanResultsHandler"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 500
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// stop_bluetooth_scan
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `stop_bluetooth_scan`. Input: `{}`. */
class StopBluetoothScanHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing stop_bluetooth_scan")
        val found = BluetoothScanRegistry.results().size
        val stopped = withContext(Dispatchers.IO) { BluetoothScanRegistry.stop() }
        val json =
            buildJsonObject {
                put("stopped", stopped)
                put("discovered_before_stop", found)
                put("note", "Results stay available to get_bluetooth_scan_results until the next scan starts.")
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
                "Stops the Bluetooth LE scan. Always stop a scan you no longer need: an active scan " +
                    "keeps the Bluetooth radio at full power and is one of the fastest ways to drain a " +
                    "battery. It is safe to call when nothing is scanning (it reports 'stopped': false) " +
                    "and the devices found so far remain readable through get_bluetooth_scan_results " +
                    "until the next scan begins.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "stop_bluetooth_scan"
        private const val TAG = "MCP:StopBluetoothScanHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// pair_bluetooth_device
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `pair_bluetooth_device`. Input: `{ "address": <string> }`. */
class PairBluetoothDeviceHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val address = McpToolUtils.requireString(arguments, "address").trim().uppercase()
        RuntimePermissionGate.require(context, Manifest.permission.BLUETOOTH_CONNECT, TOOL_NAME)
        Log.d(TAG, "Executing pair_bluetooth_device")

        val adapter = bluetoothAdapter(context)
        val device =
            withContext(Dispatchers.IO) {
                try {
                    adapter.getRemoteDevice(address)
                } catch (_: IllegalArgumentException) {
                    throw McpToolException.InvalidParams(
                        "'$address' is not a valid Bluetooth MAC address. Use the address returned by " +
                            "get_bluetooth_scan_results.",
                    )
                }
            }
        val state = device.bondState
        if (state == BluetoothDevice.BOND_BONDED) {
            return McpToolUtils.textResult(
                buildJsonObject {
                    put("address", address)
                    put("bond_state", bondStateName(state))
                    put("already_paired", true)
                }.toString(),
            )
        }
        val started =
            withContext(Dispatchers.IO) {
                try {
                    device.createBond()
                } catch (exception: Exception) {
                    throw McpToolException.ActionFailed(
                        "Could not start pairing with '$address': " +
                            (exception.message ?: exception.javaClass.simpleName) +
                            ". Pairing needs BLUETOOTH_CONNECT, and the device must be in range and " +
                            "discoverable at the moment of the call.",
                    )
                }
            }
        val json =
            buildJsonObject {
                put("address", address)
                put("name", device.name.orEmpty())
                put("pairing_started", started)
                put("bond_state", bondStateName(device.bondState))
                put(
                    "note",
                    "Pairing completes asynchronously. Call get_bluetooth_bond_state to see when it " +
                        "reaches 'bonded'; a PIN or passkey prompt may appear on screen.",
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
                "Starts pairing with a Bluetooth device by MAC address — discover the address with " +
                    "start_bluetooth_scan first. Pairing is asynchronous and may require a passkey or " +
                    "confirmation on either side, so follow it with get_bluetooth_bond_state until the " +
                    "state settles. It is idempotent: asking to pair with an already-paired device " +
                    "returns 'already_paired': true instead of failing. Needs BLUETOOTH_CONNECT and the " +
                    "target device in range.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("address") {
                                put("type", "string")
                                put("description", "Device MAC address, e.g. \"AA:BB:CC:DD:EE:FF\"")
                            }
                        },
                    required = listOf("address"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "pair_bluetooth_device"
        private const val TAG = "MCP:PairBluetoothDeviceHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// unpair_bluetooth_device
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `unpair_bluetooth_device`. Input: `{ "address": <string> }`. */
class UnpairBluetoothDeviceHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val address = McpToolUtils.requireString(arguments, "address").trim().uppercase()
        RuntimePermissionGate.require(context, Manifest.permission.BLUETOOTH_CONNECT, TOOL_NAME)
        Log.d(TAG, "Executing unpair_bluetooth_device")

        val adapter = bluetoothAdapter(context)
        val device = withContext(Dispatchers.IO) { adapter.getRemoteDevice(address) }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            throw McpToolException.InvalidParams(
                "'$address' is not paired, so there is nothing to remove. get_bluetooth_summary lists " +
                    "the paired devices.",
            )
        }
        val removed =
            withContext(Dispatchers.IO) {
                try {
                    removeBond(device)
                } catch (exception: Exception) {
                    throw McpToolException.ActionFailed(
                        "Could not unpair '$address': " +
                            (exception.message ?: exception.javaClass.simpleName),
                    )
                }
            }
        val json =
            buildJsonObject {
                put("address", address)
                put("name", device.name.orEmpty())
                put("unpair_started", removed)
                put("bond_state", bondStateName(device.bondState))
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
                "Removes a Bluetooth pairing, also dropping any active connection over it. Use it to " +
                    "clean up a device the user no longer owns, to force a fresh pairing when a " +
                    "connection is stale, or as the first half of switching between two headsets. The " +
                    "target must currently be paired — the error names the summary tool that lists " +
                    "them — and unpairing is immediate and cannot be undone by this server.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("address") {
                                put("type", "string")
                                put("description", "MAC address of the paired device to remove")
                            }
                        },
                    required = listOf("address"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "unpair_bluetooth_device"
        private const val TAG = "MCP:UnpairBluetoothDeviceHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_bluetooth_bond_state
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_bluetooth_bond_state`. Input: `{ "address": <string> }`. */
class GetBluetoothBondStateHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val address = McpToolUtils.requireString(arguments, "address").trim().uppercase()
        RuntimePermissionGate.require(context, Manifest.permission.BLUETOOTH_CONNECT, TOOL_NAME)
        Log.d(TAG, "Executing get_bluetooth_bond_state")

        val adapter = bluetoothAdapter(context)
        val device = withContext(Dispatchers.IO) { adapter.getRemoteDevice(address) }
        val json =
            buildJsonObject {
                put("address", address)
                put("name", device.name.orEmpty())
                put("bond_state", bondStateName(device.bondState))
                put("bonded", device.bondState == BluetoothDevice.BOND_BONDED)
                put("bonding_in_progress", device.bondState == BluetoothDevice.BOND_BONDING)
                put("type", device.type)
                put("uuids_available", device.uuids?.size ?: 0)
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
                "Reports one device's pairing state: 'none', 'bonding' while a pairing is still in " +
                    "progress, or 'bonded' once it is complete, plus its name, transport type and how " +
                    "many service UUIDs are known. Use it to wait for pair_bluetooth_device to finish — " +
                    "poll it until the state leaves 'bonding' — or to confirm a device is still paired " +
                    "before trying to connect or route audio to it.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("address") {
                                put("type", "string")
                                put("description", "Device MAC address")
                            }
                        },
                    required = listOf("address"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_bluetooth_bond_state"
        private const val TAG = "MCP:GetBluetoothBondStateHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_connected_bluetooth_devices
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_connected_bluetooth_devices`.
 * Input: `{ "profile": <string>? }`.
 */
class GetConnectedBluetoothDevicesHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val requested = McpToolUtils.optionalString(arguments, "profile", "all").lowercase()
        RuntimePermissionGate.require(context, Manifest.permission.BLUETOOTH_CONNECT, TOOL_NAME)
        Log.d(TAG, "Executing get_connected_bluetooth_devices")

        val profiles =
            when (requested) {
                "all" ->
                    listOf(
                        "a2dp" to BluetoothProfile.A2DP,
                        "headset" to BluetoothProfile.HEADSET,
                        "gatt" to BluetoothProfile.GATT,
                    )

                "a2dp" -> listOf("a2dp" to BluetoothProfile.A2DP)
                "headset" -> listOf("headset" to BluetoothProfile.HEADSET)
                "gatt" -> listOf("gatt" to BluetoothProfile.GATT)
                else -> throw McpToolException.InvalidParams(
                    "Parameter 'profile' must be one of: all, a2dp, headset, gatt. Got '$requested'.",
                )
            }
        val manager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: throw McpToolException.ActionFailed("This device has no Bluetooth service.")
        val entries =
            withContext(Dispatchers.IO) {
                profiles.map { (name, profile) ->
                    val devices =
                        try {
                            manager.getConnectedDevices(profile).orEmpty().map { device ->
                                buildJsonObject {
                                    put("address", device.address)
                                    put("name", device.name.orEmpty())
                                    put("bond_state", bondStateName(device.bondState))
                                }
                            }
                        } catch (exception: Exception) {
                            throw McpToolException.PermissionDenied(
                                "Could not read connected '$name' devices: " +
                                    (exception.message ?: exception.javaClass.simpleName),
                            )
                        }
                    buildJsonObject {
                        put("profile", name)
                        put("connected_count", devices.size)
                        putJsonArray("devices") { devices.forEach { add(it) } }
                    }
                }
            }
        val json =
            buildJsonObject {
                put("requested_profile", requested)
                putJsonArray("profiles") { entries.forEach { add(it) } }
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
                "Lists which Bluetooth devices are currently connected, grouped by profile: 'a2dp' for " +
                    "audio playback, 'headset' for calls, 'gatt' for low-energy peripherals. Use it to " +
                    "find out where sound is going before playing or speaking — audio may be routed to " +
                    "a speaker or a headset without any indication on screen — and to confirm a " +
                    "connection after pairing. Pass 'profile' to check just one of them.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("profile") {
                                put("type", "string")
                                put("description", "all, a2dp, headset or gatt (default all)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_connected_bluetooth_devices"
        private const val TAG = "MCP:GetConnectedBluetoothDevicesHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// open_bluetooth_settings
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `open_bluetooth_settings`. Input: `{}`. */
class OpenBluetoothSettingsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing open_bluetooth_settings")
        val opened =
            withContext(Dispatchers.IO) {
                RuntimePermissionGate.openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)
            }
        if (!opened) {
            throw McpToolException.ActionFailed("No settings screen accepted a Bluetooth settings request.")
        }
        val json =
            buildJsonObject {
                put("opened", true)
                put("screen", "Bluetooth")
                put(
                    "note",
                    "Turning Bluetooth on or off, and completing a pairing that needs a passkey, are " +
                        "user actions on this screen. Any screen tool can then read and drive the page.",
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
                "Opens the system Bluetooth settings screen. Use it when the task needs something only " +
                    "a user can do there: turning the radio on when the app cannot, or completing a " +
                    "pairing that shows a passkey on the other device. After it opens, the screen tools " +
                    "can read the page; tell the user what to change rather than assuming it happened.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "open_bluetooth_settings"
        private const val TAG = "MCP:OpenBluetoothSettingsHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// list_usb_devices
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `list_usb_devices`. Input: `{}`. */
class ListUsbDevicesHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing list_usb_devices")
        val manager = usbManager(context)
        val devices = withContext(Dispatchers.IO) { manager.deviceList.values.toList() }
        val rows =
            devices.map { device ->
                buildJsonObject {
                    put("device_name", device.deviceName)
                    put("vendor_id", device.vendorId)
                    put("product_id", device.productId)
                    put("manufacturer", device.manufacturerName.orEmpty())
                    put("product", device.productName.orEmpty())
                    put("interface_count", device.interfaceCount)
                    put("permission_granted", manager.hasPermission(device))
                }
            }
        val json =
            buildJsonObject {
                put("count", rows.size)
                put("host_supported", context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST))
                putJsonArray("devices") { rows.forEach { add(it) } }
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
                "Lists USB devices attached to this device — a keyboard, an external drive, a " +
                    "microcontroller, a DAC — with vendor and product ids, manufacturer and product " +
                    "names, interface count and whether this app already holds access to each one. Use " +
                    "it to detect that something was plugged in, to identify it before driving it, and " +
                    "to see whether a permission prompt still has to be answered for that device.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_usb_devices"
        private const val TAG = "MCP:ListUsbDevicesHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_usb_device_detail
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_usb_device_detail`. Input: `{ "device_name": <string> }`. */
class GetUsbDeviceDetailHandler(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val deviceName = McpToolUtils.requireString(arguments, "device_name").trim()
        Log.d(TAG, "Executing get_usb_device_detail")

        val manager = usbManager(context)
        val device =
            withContext(Dispatchers.IO) { manager.deviceList[deviceName] }
                ?: throw McpToolException.InvalidParams(
                    "No USB device named '$deviceName'. Call list_usb_devices for the current names.",
                )
        val serial =
            withContext(Dispatchers.IO) {
                runCatching { device.serialNumber }.getOrNull()
            }
        val interfaces =
            (0 until device.interfaceCount).map { index ->
                val usbInterface = device.getInterface(index)
                buildJsonObject {
                    put("id", usbInterface.id)
                    put("interface_class", usbInterface.interfaceClass)
                    put("interface_subclass", usbInterface.interfaceSubclass)
                    put("protocol", usbInterface.interfaceProtocol)
                    put("endpoint_count", usbInterface.endpointCount)
                }
            }
        val json =
            buildJsonObject {
                put("device_name", device.deviceName)
                put("vendor_id", device.vendorId)
                put("product_id", device.productId)
                put("device_class", device.deviceClass)
                put("device_subclass", device.deviceSubclass)
                put("device_protocol", device.deviceProtocol)
                put("manufacturer", device.manufacturerName.orEmpty())
                put("product", device.productName.orEmpty())
                put("serial_number", serial.orEmpty())
                put("has_permission", manager.hasPermission(device))
                putJsonArray("interfaces") { interfaces.forEach { add(it) } }
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
                "Returns everything the system exposes about one attached USB device: ids, device " +
                    "class, manufacturer, product, serial number, whether access is granted, and each " +
                    "interface with its class and endpoint count. Use it to tell two similar adapters " +
                    "apart, to identify an unknown accessory by its class, or to check that a device " +
                    "presenting several interfaces is the one you expect. Claiming a device from another " +
                    "app is not possible from a tool; that requires the user's consent dialog.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("device_name") {
                                put("type", "string")
                                put("description", "Device name as reported by list_usb_devices")
                            }
                        },
                    required = listOf("device_name"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_usb_device_detail"
        private const val TAG = "MCP:GetUsbDeviceDetailHandler"
    }
}

/** Resolves the USB manager, failing clearly on hardware without USB host support. */
/**
 * Unpairs [device] by calling `BluetoothDevice.removeBond()` reflectively.
 *
 * Every Android release has the method, but it is marked @hide and so is absent from the SDK this
 * module compiles against. Failures (a release that blocks the hidden API, or a device that has
 * already dropped the bond) surface as the caller's "Could not unpair" error.
 */
private fun removeBond(device: BluetoothDevice): Boolean =
    try {
        BluetoothDevice::class.java.getMethod("removeBond").invoke(device) as? Boolean ?: false
    } catch (exception: ReflectiveOperationException) {
        throw McpToolException.ActionFailed(
            "This Android version does not expose Bluetooth unpairing to apps " +
                "(${exception.javaClass.simpleName}). Unpair the device from Settings > Bluetooth.",
        )
    }

private fun usbManager(context: Context): UsbManager =
    context.getSystemService(Context.USB_SERVICE) as? UsbManager
        ?: throw McpToolException.ActionFailed("This device does not expose a USB manager.")

/** Human-readable name of a Bluetooth bond state. */
private fun bondStateName(bondState: Int): String =
    when (bondState) {
        BluetoothDevice.BOND_NONE -> "none"
        BluetoothDevice.BOND_BONDING -> "bonding"
        BluetoothDevice.BOND_BONDED -> "bonded"
        else -> "unknown($bondState)"
    }

/** Registers the connectivity bundle: Bluetooth scanning, pairing and USB inspection. */
fun registerBluetoothExtrasTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    GetBluetoothSummaryHandler(context).register(registrar, toolNamePrefix)
    StartBluetoothScanHandler(context).register(registrar, toolNamePrefix)
    GetBluetoothScanResultsHandler(context).register(registrar, toolNamePrefix)
    StopBluetoothScanHandler(context).register(registrar, toolNamePrefix)
    PairBluetoothDeviceHandler(context).register(registrar, toolNamePrefix)
    UnpairBluetoothDeviceHandler(context).register(registrar, toolNamePrefix)
    GetBluetoothBondStateHandler(context).register(registrar, toolNamePrefix)
    GetConnectedBluetoothDevicesHandler(context).register(registrar, toolNamePrefix)
    OpenBluetoothSettingsHandler(context).register(registrar, toolNamePrefix)
    ListUsbDevicesHandler(context).register(registrar, toolNamePrefix)
    GetUsbDeviceDetailHandler(context).register(registrar, toolNamePrefix)
}
