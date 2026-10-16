@file:Suppress("MatchingDeclarationName")

package com.cococopi.andromcp.mcp.tools

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.nfc.NfcManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.Display
import android.view.Surface
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.mcp.McpToolException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Hardware and sensor tool bundle.
 *
 * Exposes the physical side of the phone to an agent: the sensor suite, battery and thermal
 * state, memory and storage pressure, CPU identity, vibration, the NFC and Bluetooth radios, the
 * display geometry and the current audio routing. These are the readings an agent needs to reason
 * about what the device is physically doing before it decides to act.
 *
 * Everything here uses public framework APIs. Only the Bluetooth tools and body-sensor reads are
 * permission-gated, and they gate themselves at call time with an actionable error.
 */

// ─────────────────────────────────────────────────────────────────────────────
// list_sensors
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_sensors`.
 *
 * **Input**: none.
 * **Output**: JSON array of every sensor the device reports.
 */
class ListSensorsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing list_sensors")
        val sensors = withContext(Dispatchers.IO) { describeSensors() }
        val json =
            buildJsonObject {
                put("sensor_count", sensors.size)
                putJsonArray("sensors") { sensors.forEach { add(it) } }
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private fun describeSensors(): List<JsonObject> {
        val manager =
            context.getSystemService(SensorManager::class.java)
                ?: throw McpToolException.ActionFailed("Sensor services are unavailable on this device")
        return manager.getSensorList(Sensor.TYPE_ALL).map { sensor ->
            buildJsonObject {
                put("name", sensor.name)
                put("type", sensor.type)
                put("string_type", sensor.stringType)
                put("vendor", sensor.vendor)
                put("version", sensor.version)
                put("maximum_range", sensor.maximumRange)
                put("resolution", sensor.resolution)
                put("power_ma", sensor.power)
                put("min_delay_us", sensor.minDelay)
                put("wake_up_sensor", sensor.isWakeUpSensor)
            }
        }
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Lists every sensor this device exposes — accelerometer, gyroscope, magnetometer, " +
                    "light, proximity, pressure, step counter and the rest — with each sensor's " +
                    "name, numeric type, string type, vendor, version, maximum range, resolution, " +
                    "power draw in milliamps and minimum delay in microseconds. Use it to discover " +
                    "which physical measurements the device can actually take before calling " +
                    "read_sensor. No permission is required to enumerate sensors.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_sensors"
        private const val TAG = "MCP:ListSensorsHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// read_sensor
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `read_sensor`.
 *
 * **Input**: `{ "sensor_type": <string>, "timeout_ms": <int>? }`
 * **Output**: JSON object with the first reading from the sensor.
 */
class ReadSensorHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val typeName = McpToolUtils.requireString(arguments, "sensor_type").trim().lowercase()
        val timeoutMs = McpToolUtils.optionalLong(arguments, "timeout_ms", DEFAULT_SENSOR_TIMEOUT_MS)
        McpToolUtils.validatePositiveRange(timeoutMs, "timeout_ms", MAX_SENSOR_TIMEOUT_MS)

        val manager =
            context.getSystemService(SensorManager::class.java)
                ?: throw McpToolException.ActionFailed("Sensor services are unavailable on this device")
        val sensor =
            manager.getDefaultSensor(sensorTypeFor(typeName))
                ?: throw McpToolException.NodeNotFound("This device has no '$typeName' sensor")

        Log.d(TAG, "Executing read_sensor ($typeName)")

        val reading =
            withContext(Dispatchers.Default) {
                try {
                    awaitReading(manager, sensor, timeoutMs)
                } catch (e: SecurityException) {
                    throw McpToolException.PermissionDenied(
                        "Reading the '$typeName' sensor needs an Android permission this app does " +
                            "not hold (body sensors such as heart rate require BODY_SENSORS): ${e.message}",
                    )
                }
            }

        val json =
            buildJsonObject {
                put("sensor_type", typeName)
                put("sensor_name", sensor.name)
                put("vendor", sensor.vendor)
                put("maximum_range", sensor.maximumRange)
                put("resolution", sensor.resolution)
                put("value_count", reading.size)
                putJsonArray("values") { reading.forEach { value -> add(value.toDouble()) } }
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private suspend fun awaitReading(
        manager: SensorManager,
        sensor: Sensor,
        timeoutMs: Long,
    ): FloatArray {
        val first = CompletableDeferred<FloatArray>()
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    first.complete(event.values.copyOf())
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) = Unit
            }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        return try {
            withTimeoutOrNull(timeoutMs) { first.await() }
                ?: throw McpToolException.Timeout("The '$sensor' sensor produced no reading within ${timeoutMs}ms")
        } finally {
            manager.unregisterListener(listener)
        }
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Takes one reading from a named sensor and returns its raw values, together with " +
                    "the sensor's range and resolution so the numbers can be interpreted. Use it to " +
                    "read ambient light, air pressure, humidity, temperature, magnetometer heading, " +
                    "gravity, linear acceleration, a rotation vector, a step count or proximity " +
                    "state. The call waits up to 'timeout_ms' (default 3000, max 30000) for the " +
                    "first event and fails with a timeout error if none arrives. Body sensors such " +
                    "as heart rate additionally need the BODY_SENSORS permission, which the app " +
                    "does not request; those reads fail with a permission error.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("sensor_type") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Sensor to read: accelerometer, magnetometer, gyroscope, light, " +
                                        "pressure, proximity, gravity, linear_acceleration, " +
                                        "rotation_vector, game_rotation_vector, step_counter, " +
                                        "step_detector, ambient_temperature, relative_humidity, " +
                                        "heart_rate, significant_motion, hinge_angle",
                                )
                            }
                            putJsonObject("timeout_ms") {
                                put("type", "integer")
                                put("description", "How long to wait for the first reading (default 3000, max 30000)")
                            }
                        },
                    required = listOf("sensor_type"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "read_sensor"
        private const val TAG = "MCP:ReadSensorHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_battery_details
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_battery_details`.
 *
 * **Input**: none.
 * **Output**: JSON object with charge, power, thermal and health detail.
 */
class GetBatteryDetailsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_battery_details")
        val json = withContext(Dispatchers.IO) { describeBattery() }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private fun describeBattery(): JsonObject {
        val manager =
            context.getSystemService(BatteryManager::class.java)
                ?: throw McpToolException.ActionFailed("Battery services are unavailable on this device")
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, MISSING_INT)

        return buildJsonObject {
            put("capacity_percent", manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            put("charging", manager.isCharging)
            put("level", status?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0))
            put("scale", status?.getIntExtra(BatteryManager.EXTRA_SCALE, 0))
            put(
                "status",
                HardwareLabels.batteryStatus(
                    status?.getIntExtra(BatteryManager.EXTRA_STATUS, MISSING_INT) ?: MISSING_INT,
                ),
            )
            put(
                "plugged",
                HardwareLabels.batteryPlugged(
                    status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, MISSING_INT) ?: MISSING_INT,
                ),
            )
            put(
                "health",
                HardwareLabels.batteryHealth(
                    status?.getIntExtra(BatteryManager.EXTRA_HEALTH, MISSING_INT) ?: MISSING_INT,
                ),
            )
            put("technology", status?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY))
            put("voltage_mv", status?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, MISSING_INT))
            put("temperature_celsius", temperature?.takeIf { it != MISSING_INT }?.let { it / TENTHS_PER_UNIT })
            put("charge_counter_uah", manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            put("current_now_ua", manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            put("current_average_ua", manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE))
            put("energy_counter_nwh", manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER))
        }
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports detailed battery state: charge percentage, whether it is charging and " +
                    "through what, health, technology, voltage, temperature, instantaneous and " +
                    "averaged current draw, and the charge and energy counters. Use it to answer " +
                    "how much power is left, to detect an overheating or aging battery, or to " +
                    "measure what the device is drawing right now. Complements " +
                    "get_battery_status, which reports the short summary a client usually wants.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_battery_details"
        private const val TAG = "MCP:GetBatteryDetailsHandler"
        private const val MISSING_INT = Int.MIN_VALUE
        private const val TENTHS_PER_UNIT = 10f
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_thermal_status
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_thermal_status`.
 *
 * **Input**: none.
 * **Output**: JSON object with the thermal and power state.
 */
class GetThermalStatusHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_thermal_status")
        val powerManager =
            context.getSystemService(PowerManager::class.java)
                ?: throw McpToolException.ActionFailed("Power services are unavailable on this device")
        val json =
            buildJsonObject {
                put("thermal_status", HardwareLabels.thermalStatus(powerManager.currentThermalStatus))
                put("power_save_mode", powerManager.isPowerSaveMode)
                put("device_idle_mode", powerManager.isDeviceIdleMode)
                put("interactive", powerManager.isInteractive)
                put("sustained_performance_mode_supported", powerManager.isSustainedPerformanceModeSupported)
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports how thermally and electrically stressed the device is: the platform's " +
                    "thermal status (NONE through SHUTDOWN), whether battery saver is on, whether " +
                    "the device is in doze, whether the screen is interactive, and whether " +
                    "sustained performance mode is available. Use it before starting heavy work to " +
                    "find out whether the device is throttling, and afterwards to check whether " +
                    "the work pushed it into thermal throttling.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_thermal_status"
        private const val TAG = "MCP:GetThermalStatusHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_memory_info
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_memory_info`.
 *
 * **Input**: none.
 * **Output**: JSON object with system-wide and app-specific memory figures.
 */
class GetMemoryInfoHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_memory_info")
        val json = withContext(Dispatchers.IO) { describeMemory() }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private fun describeMemory(): JsonObject {
        val activityManager =
            context.getSystemService(ActivityManager::class.java)
                ?: throw McpToolException.ActionFailed("Activity services are unavailable on this device")
        val systemMemory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(systemMemory)
        val runtime = Runtime.getRuntime()
        val ownProcess: Debug.MemoryInfo? =
            activityManager.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()

        return buildJsonObject {
            put("system_total_bytes", systemMemory.totalMem)
            put("system_available_bytes", systemMemory.availMem)
            put("system_low_memory_threshold_bytes", systemMemory.threshold)
            put("system_low_memory", systemMemory.lowMemory)
            put("app_heap_max_bytes", runtime.maxMemory())
            put("app_heap_total_bytes", runtime.totalMemory())
            put("app_heap_free_bytes", runtime.freeMemory())
            put("app_pss_kb", ownProcess?.totalPss)
            put("app_private_dirty_kb", ownProcess?.totalPrivateDirty)
        }
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports memory pressure: total and available system RAM, the low-memory threshold " +
                    "and whether the system currently considers itself low on memory, plus this " +
                    "app's own heap ceiling, allocated heap and free heap and its proportional and " +
                    "private-dirty set sizes. Use it to decide whether a large operation is safe, or " +
                    "to explain sluggishness. Reading another process's memory would need elevated " +
                    "access, so only this app's own figures are reported.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_memory_info"
        private const val TAG = "MCP:GetMemoryInfoHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_storage_usage
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_storage_usage`.
 *
 * **Input**: none.
 * **Output**: JSON object with usage for internal and external storage.
 */
class GetStorageUsageHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_storage_usage")
        val json = withContext(Dispatchers.IO) { describeVolumes() }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private fun describeVolumes(): JsonObject =
        buildJsonObject {
            put("internal", volumeJson(Environment.getDataDirectory().absolutePath))
            put("external", volumeJson(Environment.getExternalStorageDirectory().absolutePath))
            put("external_state", Environment.getExternalStorageState())
        }

    private fun volumeJson(path: String): JsonObject {
        val stats = StatFs(path)
        val blockSize = stats.blockSizeLong
        val totalBytes = stats.blockCountLong * blockSize
        val availableBytes = stats.availableBlocksLong * blockSize
        val usedBytes = totalBytes - availableBytes
        return buildJsonObject {
            put("path", path)
            put("total_bytes", totalBytes)
            put("available_bytes", availableBytes)
            put("used_bytes", usedBytes)
            put("used_percent", if (totalBytes > 0) (usedBytes * PERCENT_SCALE) / totalBytes else 0)
            put("block_size_bytes", blockSize)
        }
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports how full the device's storage is: total, used and available bytes plus " +
                    "the used percentage for internal storage and for external storage, along with " +
                    "the current external storage state. Use it before writing a large file to " +
                    "check that there is room, or to answer how much space is left. This reports " +
                    "the whole volume, not a per-app breakdown.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_storage_usage"
        private const val TAG = "MCP:GetStorageUsageHandler"
        private const val PERCENT_SCALE = 100L
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_cpu_info
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_cpu_info`.
 *
 * **Input**: none.
 * **Output**: JSON object with CPU identity and build information.
 */
class GetCpuInfoHandler {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_cpu_info")
        val json =
            buildJsonObject {
                put("soc_manufacturer", Build.SOC_MANUFACTURER)
                put("soc_model", Build.SOC_MODEL)
                put("hardware", Build.HARDWARE)
                put("board", Build.BOARD)
                put("device", Build.DEVICE)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("supported_abis", Build.SUPPORTED_ABIS.joinToString(", "))
                put("available_processors", Runtime.getRuntime().availableProcessors())
                put("android_release", Build.VERSION.RELEASE)
                put("sdk_int", Build.VERSION.SDK_INT)
                put("build_fingerprint", Build.FINGERPRINT)
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports the device's silicon and build identity: system-on-chip manufacturer and " +
                    "model, hardware and board names, supported CPU ABIs, the number of processors " +
                    "available to the app, the Android release, the API level and the build " +
                    "fingerprint. Use it to decide whether a native binary will run on this device, " +
                    "or to identify the hardware in a bug report.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_cpu_info"
        private const val TAG = "MCP:GetCpuInfoHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_uptime
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_uptime`.
 *
 * **Input**: none.
 * **Output**: JSON object with the device's uptime figures.
 */
class GetUptimeHandler {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_uptime")
        val elapsedRealtimeMs = SystemClock.elapsedRealtime()
        val json =
            buildJsonObject {
                put("uptime_since_boot_ms", elapsedRealtimeMs)
                put("uptime_awake_ms", SystemClock.uptimeMillis())
                put("deep_sleep_ms", elapsedRealtimeMs - SystemClock.uptimeMillis())
                put("boot_time_epoch_ms", System.currentTimeMillis() - elapsedRealtimeMs)
                put("current_time_epoch_ms", System.currentTimeMillis())
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports how long the device has been running: total time since boot including " +
                    "sleep, time the device has been awake, time spent asleep, the wall-clock " +
                    "instant of boot, and the current wall-clock time. Use it to detect that the " +
                    "phone rebooted, or to express how long ago something happened relative to " +
                    "uptime.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_uptime"
        private const val TAG = "MCP:GetUptimeHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// vibrate
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `vibrate`.
 *
 * **Input**: `{ "duration_ms": <int>?, "amplitude": <int>? }`
 * **Output**: text confirmation.
 */
class VibrateHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val durationMs = McpToolUtils.optionalLong(arguments, "duration_ms", DEFAULT_VIBRATION_MS)
        McpToolUtils.validatePositiveRange(durationMs, "duration_ms", MAX_VIBRATION_MS)
        val amplitude = McpToolUtils.optionalInt(arguments, "amplitude", VibrationEffect.DEFAULT_AMPLITUDE)
        if (amplitude != VibrationEffect.DEFAULT_AMPLITUDE &&
            amplitude !in MIN_VIBRATION_AMPLITUDE..MAX_VIBRATION_AMPLITUDE
        ) {
            throw McpToolException.InvalidParams(
                "Parameter 'amplitude' must be between $MIN_VIBRATION_AMPLITUDE and " +
                    "$MAX_VIBRATION_AMPLITUDE, or -1 for the device default",
            )
        }

        Log.d(TAG, "Executing vibrate")

        val vibrator =
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?: throw McpToolException.ActionFailed("Vibration is unavailable on this device")
        if (!vibrator.hasVibrator()) {
            throw McpToolException.ActionFailed("This device has no vibrator")
        }
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))

        return McpToolUtils.textResult("Vibrated for ${durationMs}ms at amplitude $amplitude")
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Makes the phone vibrate for a given duration, optionally at a chosen strength. Use " +
                    "it as a silent physical notification, a discreet nudge, or a confirmation that " +
                    "reaches somebody who is holding the phone but not looking at it. 'duration_ms' " +
                    "defaults to 300 and caps at 10000; 'amplitude' is 1 to $MAX_VIBRATION_AMPLITUDE, " +
                    "where -1 (the default) lets the device choose, and lower values are gentler and " +
                    "quieter. Uses the VIBRATE permission, which the app already holds.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("duration_ms") {
                                put("type", "integer")
                                put("description", "Vibration length in milliseconds (default 300, max 10000)")
                            }
                            putJsonObject("amplitude") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Vibration strength from $MIN_VIBRATION_AMPLITUDE to " +
                                        "$MAX_VIBRATION_AMPLITUDE, or -1 for the device default",
                                )
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "vibrate"
        private const val TAG = "MCP:VibrateHandler"
        private const val DEFAULT_VIBRATION_MS = 300L
        private const val MAX_VIBRATION_MS = 10_000L
        private const val MIN_VIBRATION_AMPLITUDE = 1
        private const val MAX_VIBRATION_AMPLITUDE = 255
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_nfc_state
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_nfc_state`.
 *
 * **Input**: none.
 * **Output**: JSON object describing NFC availability.
 */
class GetNfcStateHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_nfc_state")
        val adapter = context.getSystemService(NfcManager::class.java)?.defaultAdapter
        val json =
            buildJsonObject {
                put("present", adapter != null)
                put("enabled", adapter?.isEnabled ?: false)
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports whether the device has NFC hardware and whether it is currently switched " +
                    "on. Use it to explain why a tap-to-pay or tag-read step will not work, and to " +
                    "tell the user to enable NFC in Android Settings when it is off. Reading the " +
                    "state requires no permission; changing it does, and this app does not change it.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_nfc_state"
        private const val TAG = "MCP:GetNfcStateHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_bluetooth_state
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_bluetooth_state`.
 *
 * **Input**: none.
 * **Output**: JSON object describing the Bluetooth adapter.
 */
class GetBluetoothStateHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.BLUETOOTH_CONNECT, TOOL_NAME)

        Log.d(TAG, "Executing get_bluetooth_state")

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val json =
            buildJsonObject {
                put("present", adapter != null)
                put("enabled", adapter?.isEnabled ?: false)
                put("state", HardwareLabels.bluetoothState(adapter?.state ?: BluetoothAdapter.ERROR))
                put("device_name", adapter?.name)
                put("bonded_device_count", adapter?.bondedDevices?.size ?: 0)
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports the Bluetooth adapter's state: whether the device has Bluetooth, whether " +
                    "it is switched on, the adapter's power state, the device's own Bluetooth name, " +
                    "and how many devices are paired to it. Use it to explain a failed headset, car " +
                    "or wearable connection, or to check whether pairing is set up at all. Requires " +
                    "the BLUETOOTH_CONNECT permission.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_bluetooth_state"
        private const val TAG = "MCP:GetBluetoothStateHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// list_bluetooth_devices
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_bluetooth_devices`.
 *
 * **Input**: none.
 * **Output**: JSON array of paired Bluetooth devices.
 */
class ListBluetoothDevicesHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.BLUETOOTH_CONNECT, TOOL_NAME)

        Log.d(TAG, "Executing list_bluetooth_devices")

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        val json =
            buildJsonObject {
                put("device_count", devices.size)
                putJsonArray("devices") {
                    devices.forEach { device ->
                        add(
                            buildJsonObject {
                                put("name", device.name)
                                put("address", device.address)
                                put("type", HardwareLabels.bluetoothDeviceType(device.type))
                                put("bond_state", HardwareLabels.bondState(device.bondState))
                            },
                        )
                    }
                }
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Lists every Bluetooth device paired with this phone — headphones, speakers, cars, " +
                    "watches, keyboards — with each device's name, MAC address, whether it is a " +
                    "classic, low-energy or dual-mode device, and whether pairing is complete. Use " +
                    "it to check whether a headset or car is paired before asking the user to pair " +
                    "it again. Requires the BLUETOOTH_CONNECT permission.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_bluetooth_devices"
        private const val TAG = "MCP:ListBluetoothDevicesHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_display_info
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_display_info`.
 *
 * **Input**: none.
 * **Output**: JSON object describing the default display.
 */
class GetDisplayInfoHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_display_info")

        val metrics = context.resources.displayMetrics
        val display =
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val json =
            buildJsonObject {
                put("width_px", metrics.widthPixels)
                put("height_px", metrics.heightPixels)
                put("density_dpi", metrics.densityDpi)
                put("density_scale", metrics.density)
                put("rotation_degrees", HardwareLabels.rotationDegrees(display?.rotation ?: Surface.ROTATION_0))
                put("physical_width_px", mode?.physicalWidth)
                put("physical_height_px", mode?.physicalHeight)
                put("refresh_rate_hz", mode?.refreshRate)
                put("hdr_supported", display?.isHdr == true)
                put("hdr_types", display?.hdrCapabilities?.supportedHdrTypes?.joinToString(", "))
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reports the screen's geometry and capabilities: logical width and height in " +
                    "pixels, density in dots per inch and the density scale factor, the current " +
                    "rotation in degrees, the panel's physical resolution and refresh rate, and " +
                    "whether HDR is supported and in which formats. Use it to convert UI " +
                    "coordinates, to decide whether a captured image needs scaling, or to check " +
                    "whether the device can display HDR content. Companion tools that act on " +
                    "coordinates expect logical pixels, which are the width and height reported here.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_display_info"
        private const val TAG = "MCP:GetDisplayInfoHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_audio_devices
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_audio_devices`.
 *
 * **Input**: none.
 * **Output**: JSON object with the current audio mode and the attached devices.
 */
class GetAudioDevicesHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_audio_devices")

        val audioManager =
            context.getSystemService(AudioManager::class.java)
                ?: throw McpToolException.ActionFailed("Audio services are unavailable on this device")
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val json =
            buildJsonObject {
                put("mode", HardwareLabels.audioMode(audioManager.mode))
                put("music_active", audioManager.isMusicActive)
                put("output_count", outputs.size)
                putJsonArray("output_devices") { outputs.forEach { device -> add(deviceJson(device)) } }
                put("input_count", inputs.size)
                putJsonArray("input_devices") { inputs.forEach { device -> add(deviceJson(device)) } }
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private fun deviceJson(device: AudioDeviceInfo): JsonObject =
        buildJsonObject {
            put("type", HardwareLabels.audioDeviceType(device.type))
            put("product_name", device.productName.toString())
            put("source", device.isSource)
            put("channel_counts", device.channelCounts.joinToString(", "))
            put("sample_rates_hz", device.sampleRates.joinToString(", "))
        }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Lists the audio devices currently attached to the phone — speaker, earpiece, " +
                    "wired headset, Bluetooth headset or speaker, USB audio, HDMI, microphones — " +
                    "with each device's type, product name, direction, channel counts and supported " +
                    "sample rates, plus the current audio mode and whether media is playing. Use it " +
                    "to find out whether audio will come out of a headset or the loudspeaker before " +
                    "playing something, which matters a lot before speaking text aloud in public.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_audio_devices"
        private const val TAG = "MCP:GetAudioDevicesHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Registration and shared helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Registers every hardware and sensor tool enabled by [perms]. */
fun registerHardwareTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    hardwareRegistrations(registrar, context, toolNamePrefix).forEach { (toolName, register) ->
        if (perms.isToolEnabled(toolName)) register()
    }
}

/** Pairs each tool name with the closure that registers it, keeping registration branch-free. */
private fun hardwareRegistrations(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
): List<Pair<String, () -> Unit>> =
    listOf(
        ListSensorsHandler.TOOL_NAME to
            { ListSensorsHandler(context).register(registrar, toolNamePrefix) },
        ReadSensorHandler.TOOL_NAME to
            { ReadSensorHandler(context).register(registrar, toolNamePrefix) },
        GetBatteryDetailsHandler.TOOL_NAME to
            { GetBatteryDetailsHandler(context).register(registrar, toolNamePrefix) },
        GetThermalStatusHandler.TOOL_NAME to
            { GetThermalStatusHandler(context).register(registrar, toolNamePrefix) },
        GetMemoryInfoHandler.TOOL_NAME to
            { GetMemoryInfoHandler(context).register(registrar, toolNamePrefix) },
        GetStorageUsageHandler.TOOL_NAME to
            { GetStorageUsageHandler(context).register(registrar, toolNamePrefix) },
        GetCpuInfoHandler.TOOL_NAME to
            { GetCpuInfoHandler().register(registrar, toolNamePrefix) },
        GetUptimeHandler.TOOL_NAME to
            { GetUptimeHandler().register(registrar, toolNamePrefix) },
        VibrateHandler.TOOL_NAME to
            { VibrateHandler(context).register(registrar, toolNamePrefix) },
        GetNfcStateHandler.TOOL_NAME to
            { GetNfcStateHandler(context).register(registrar, toolNamePrefix) },
        GetBluetoothStateHandler.TOOL_NAME to
            { GetBluetoothStateHandler(context).register(registrar, toolNamePrefix) },
        ListBluetoothDevicesHandler.TOOL_NAME to
            { ListBluetoothDevicesHandler(context).register(registrar, toolNamePrefix) },
        GetDisplayInfoHandler.TOOL_NAME to
            { GetDisplayInfoHandler(context).register(registrar, toolNamePrefix) },
        GetAudioDevicesHandler.TOOL_NAME to
            { GetAudioDevicesHandler(context).register(registrar, toolNamePrefix) },
    )

/** Maps a friendly sensor name to its platform type id, or fails with the valid names. */
private fun sensorTypeFor(name: String): Int =
    SENSOR_TYPES[name]
        ?: throw McpToolException.InvalidParams(
            "Unknown sensor_type '$name'. Valid values: ${SENSOR_TYPES.keys.sorted().joinToString(", ")}",
        )

/** Human-readable labels for the platform enums reported by the hardware tools. */
private object HardwareLabels {
    fun batteryStatus(status: Int): String =
        when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            else -> "UNKNOWN"
        }

    fun batteryPlugged(plugged: Int): String =
        when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "DOCK"
            else -> "UNPLUGGED"
        }

    fun batteryHealth(health: Int): String =
        when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "FAILURE"
            BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
            else -> "UNKNOWN"
        }

    fun thermalStatus(status: Int): String =
        when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
        }

    fun bluetoothState(state: Int): String =
        when (state) {
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "UNKNOWN"
        }

    fun bluetoothDeviceType(type: Int): String =
        when (type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
            BluetoothDevice.DEVICE_TYPE_LE -> "LE"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
            else -> "UNKNOWN"
        }

    fun bondState(state: Int): String =
        when (state) {
            BluetoothDevice.BOND_BONDED -> "BONDED"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            else -> "NONE"
        }

    fun rotationDegrees(rotation: Int): Int =
        when (rotation) {
            Surface.ROTATION_90 -> ROTATION_90_DEGREES
            Surface.ROTATION_180 -> ROTATION_180_DEGREES
            Surface.ROTATION_270 -> ROTATION_270_DEGREES
            else -> 0
        }

    fun audioMode(mode: Int): String =
        when (mode) {
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            else -> "NORMAL"
        }

    fun audioDeviceType(type: Int): String =
        when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "MIC"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
            else -> "OTHER_TYPE_$type"
        }

    private const val ROTATION_90_DEGREES = 90
    private const val ROTATION_180_DEGREES = 180
    private const val ROTATION_270_DEGREES = 270
}

/** Every sensor name accepted by `read_sensor`, mapped to its platform type id. */
private val SENSOR_TYPES: Map<String, Int> =
    mapOf(
        "accelerometer" to Sensor.TYPE_ACCELEROMETER,
        "magnetometer" to Sensor.TYPE_MAGNETIC_FIELD,
        "gyroscope" to Sensor.TYPE_GYROSCOPE,
        "light" to Sensor.TYPE_LIGHT,
        "pressure" to Sensor.TYPE_PRESSURE,
        "proximity" to Sensor.TYPE_PROXIMITY,
        "gravity" to Sensor.TYPE_GRAVITY,
        "linear_acceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
        "rotation_vector" to Sensor.TYPE_ROTATION_VECTOR,
        "game_rotation_vector" to Sensor.TYPE_GAME_ROTATION_VECTOR,
        "geomagnetic_rotation_vector" to Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
        "step_counter" to Sensor.TYPE_STEP_COUNTER,
        "step_detector" to Sensor.TYPE_STEP_DETECTOR,
        "ambient_temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
        "relative_humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
        "heart_rate" to Sensor.TYPE_HEART_RATE,
        "significant_motion" to Sensor.TYPE_SIGNIFICANT_MOTION,
        "pose_6dof" to Sensor.TYPE_POSE_6DOF,
        "stationary_detect" to Sensor.TYPE_STATIONARY_DETECT,
        "motion_detect" to Sensor.TYPE_MOTION_DETECT,
        "hinge_angle" to Sensor.TYPE_HINGE_ANGLE,
    )

private const val DEFAULT_SENSOR_TIMEOUT_MS = 3_000L
private const val MAX_SENSOR_TIMEOUT_MS = 30_000L
