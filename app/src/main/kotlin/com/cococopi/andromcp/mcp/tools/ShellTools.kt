@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.cococopi.andromcp.mcp.tools

import android.content.Context
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Shell-level tool bundle: the escape hatch that turns "the tools I shipped" into "the whole
 * device".
 *
 * Everything else in this app is a narrow, purpose-built capability, which means any operation
 * nobody thought to wrap is impossible for the agent. These tools remove that ceiling: run any
 * command the app's own uid is allowed to run, run commands as root when the device is rooted,
 * inspect processes, dump system properties, read logcat and read arbitrary readable files. They
 * are the last resort rather than the first choice — a dedicated tool is still safer and gives
 * structured output — but they make the difference between a device an agent can partly drive and
 * a device it can fully operate.
 *
 * Output from these tools is device-controlled text, so every result is returned as untrusted
 * content and capped at [DeviceShell.MAX_OUTPUT_CHARS] characters.
 */

/** Shared process-spawning helper for the shell and terminal bundles. */
internal object DeviceShell {
    /** Timeout applied when a caller does not ask for one. */
    const val DEFAULT_TIMEOUT_SECONDS = 20

    /** Upper bound any caller can request, so one call can never hang the server forever. */
    const val MAX_TIMEOUT_SECONDS = 180

    /** Hard cap on captured output, keeping a runaway command from blowing up the response. */
    const val MAX_OUTPUT_CHARS = 50_000

    private const val JOIN_TIMEOUT_MS = 500L
    private const val INTERNAL_ERROR_HINT = "Run shell_exec with 'timeout_seconds' raised if the command needs longer."

    /** Result of a finished (or killed) shell command. */
    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )

    /** Clamps a caller-supplied timeout into the supported range. */
    fun clampTimeout(requested: Int): Int = requested.coerceIn(1, MAX_TIMEOUT_SECONDS)

    /** True when a `su` binary exists on this device (presence, not a successful grant). */
    fun suBinaryPresent(): Boolean = SU_PATHS.any { File(it).canExecute() || File(it).exists() }

    /**
     * Runs [command] through `sh -c` (or `su -c` when [useSu]) and captures stdout, stderr and the
     * exit code. Never throws for a non-zero exit code — that is returned to the caller as data.
     * A command that outlives [timeoutSeconds] is killed and reported with `timedOut = true`.
     */
    @Suppress("TooGenericExceptionCaught")
    fun run(
        command: String,
        timeoutSeconds: Int,
        useSu: Boolean = false,
        workingDirectory: String? = null,
    ): Result {
        val argv = if (useSu) arrayOf("su", "-c", command) else arrayOf("sh", "-c", command)
        val builder = ProcessBuilder(*argv).redirectErrorStream(false)
        if (!workingDirectory.isNullOrEmpty()) {
            val directory = File(workingDirectory)
            if (!directory.isDirectory) {
                throw McpToolException.InvalidParams(
                    "Parameter 'working_directory' must be an existing directory, got '$workingDirectory'.",
                )
            }
            builder.directory(directory)
        }
        val process =
            try {
                builder.start()
            } catch (exception: Exception) {
                throw McpToolException.ActionFailed(
                    "Could not start '${argv[0]}': ${exception.message ?: exception.javaClass.simpleName}. " +
                        INTERNAL_ERROR_HINT,
                )
            }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutPump = pump(process.inputStream, stdout, "mcp-shell-stdout")
        val stderrPump = pump(process.errorStream, stderr, "mcp-shell-stderr")
        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        stdoutPump.join(JOIN_TIMEOUT_MS)
        stderrPump.join(JOIN_TIMEOUT_MS)
        return Result(
            exitCode = if (finished) process.exitValue() else -1,
            stdout = truncate(stdout.toString()),
            stderr = truncate(stderr.toString()),
            timedOut = !finished,
        )
    }

    /** Keeps at most [MAX_OUTPUT_CHARS] characters, marking the cut so the agent is not misled. */
    fun truncate(text: String): String =
        if (text.length <= MAX_OUTPUT_CHARS) {
            text
        } else {
            text.take(MAX_OUTPUT_CHARS) + "\n…[output truncated at $MAX_OUTPUT_CHARS characters]"
        }

    private fun pump(
        stream: InputStream,
        sink: StringBuilder,
        threadName: String,
    ) = thread(isDaemon = true, name = threadName) {
        stream.bufferedReader().use { reader ->
            val buffer = CharArray(CHAR_BUFFER_SIZE)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                sink.append(buffer, 0, read)
            }
        }
    }

    private const val CHAR_BUFFER_SIZE = 4096

    private val SU_PATHS =
        listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/debug_ramdisk/su",
        )
}

// ─────────────────────────────────────────────────────────────────────────────
// shell_exec
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `shell_exec`.
 * Input: `{ "command": <string>, "timeout_seconds": <int>?, "working_directory": <string>? }`.
 */
class ShellExecHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val command = McpToolUtils.requireString(arguments, "command")
        val timeout =
            DeviceShell.clampTimeout(
                McpToolUtils.optionalInt(arguments, "timeout_seconds", DeviceShell.DEFAULT_TIMEOUT_SECONDS),
            )
        val workingDirectory = McpToolUtils.optionalString(arguments, "working_directory", "")
        Log.d(TAG, "Executing shell_exec")

        val result =
            withContext(Dispatchers.IO) {
                DeviceShell.run(
                    command = command,
                    timeoutSeconds = timeout,
                    useSu = false,
                    workingDirectory = workingDirectory.ifEmpty { null },
                )
            }
        val json =
            buildJsonObject {
                put("command", command)
                put("as_root", false)
                put("exit_code", result.exitCode)
                put("timed_out", result.timedOut)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
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
                "Runs a shell command on the device through `sh -c` as this app's own user and " +
                    "returns stdout, stderr and the exit code. This is the general escape hatch: use " +
                    "it for anything no dedicated tool covers, from listing a directory outside the " +
                    "authorized storage locations to driving an on-device binary. A non-zero exit " +
                    "code is normal data, not an error. Commands are killed and reported with " +
                    "'timed_out': true after 'timeout_seconds' (default ${DeviceShell.DEFAULT_TIMEOUT_SECONDS}, " +
                    "max ${DeviceShell.MAX_TIMEOUT_SECONDS}). Output is truncated past " +
                    "${DeviceShell.MAX_OUTPUT_CHARS} characters. It runs unprivileged: for root-level " +
                    "commands use su_exec, and call check_root first when unsure.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("command") {
                                put("type", "string")
                                put("description", "Shell command to run, e.g. \"ls -la /sdcard/Download\"")
                            }
                            putJsonObject("timeout_seconds") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Seconds before the command is killed " +
                                        "(default ${DeviceShell.DEFAULT_TIMEOUT_SECONDS})",
                                )
                            }
                            putJsonObject("working_directory") {
                                put("type", "string")
                                put("description", "Absolute working directory (default: the app's files dir)")
                            }
                        },
                    required = listOf("command"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "shell_exec"
        private const val TAG = "MCP:ShellExecHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// su_exec
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `su_exec`. Input: `{ "command": <string>, "timeout_seconds": <int>? }`. */
class SuExecHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val command = McpToolUtils.requireString(arguments, "command")
        val timeout =
            DeviceShell.clampTimeout(
                McpToolUtils.optionalInt(arguments, "timeout_seconds", DeviceShell.DEFAULT_TIMEOUT_SECONDS),
            )
        Log.d(TAG, "Executing su_exec")

        val result =
            withContext(Dispatchers.IO) {
                DeviceShell.run(command = command, timeoutSeconds = timeout, useSu = true)
            }
        if (result.timedOut && result.stdout.isEmpty() && result.stderr.isEmpty()) {
            throw McpToolException.ActionFailed(
                "`su` produced no output within $timeout seconds. The device may be unrooted, or the " +
                    "root grant dialog may be waiting on screen. Run check_root to test root access.",
            )
        }
        val json =
            buildJsonObject {
                put("command", command)
                put("as_root", true)
                put("exit_code", result.exitCode)
                put("timed_out", result.timedOut)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
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
                "Runs a shell command as root through `su -c` and returns stdout, stderr and the " +
                    "exit code. Use it when a task needs privileges the app does not hold: writing " +
                    "under /system, changing protected settings, rebooting, or reaching another app's " +
                    "private data. Root is not guaranteed — on an unrooted device, or before the user " +
                    "approves the root prompt, the call fails or times out, so call check_root when " +
                    "the outcome matters. The command is killed after 'timeout_seconds' " +
                    "(default ${DeviceShell.DEFAULT_TIMEOUT_SECONDS}).",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("command") {
                                put("type", "string")
                                put("description", "Command to run as root, e.g. \"id\" or \"wm size\"")
                            }
                            putJsonObject("timeout_seconds") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Seconds before the command is killed " +
                                        "(default ${DeviceShell.DEFAULT_TIMEOUT_SECONDS})",
                                )
                            }
                        },
                    required = listOf("command"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "su_exec"
        private const val TAG = "MCP:SuExecHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// check_root
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `check_root`. Input: `{}`. */
class CheckRootHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing check_root")
        val binaryPresent = DeviceShell.suBinaryPresent()
        val probe =
            withContext(Dispatchers.IO) {
                runCatching { DeviceShell.run("id", timeoutSeconds = ROOT_PROBE_SECONDS, useSu = true) }.getOrNull()
            }
        val identity = probe?.stdout?.trim().orEmpty()
        val granted = (probe?.exitCode == 0) && identity.contains("uid=0")
        val json =
            buildJsonObject {
                put("su_binary_present", binaryPresent)
                put("root_granted", granted)
                put("identity", identity)
                put("root_uid", granted)
                put("probe_exit_code", probe?.exitCode ?: -1)
                put("probe_timed_out", probe?.timedOut ?: false)
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
                "Reports whether this device grants root: whether a `su` binary exists and whether " +
                    "`su -c id` actually returns uid 0. Call it before su_exec whenever the task " +
                    "depends on privileges, and again after the user approves a pending root prompt. " +
                    "A present `su` with 'root_granted': false means the prompt was denied, dismissed, " +
                    "or the manager is still waiting for the user.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "check_root"
        private const val TAG = "MCP:CheckRootHandler"
        private const val ROOT_PROBE_SECONDS = 8
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// list_processes
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `list_processes`. Input: `{ "filter": <string>? , "limit": <int>? }`. */
class ListProcessesHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val filter = McpToolUtils.optionalString(arguments, "filter", "").lowercase()
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        Log.d(TAG, "Executing list_processes")

        val raw =
            withContext(Dispatchers.IO) {
                val detailed = runCatching { DeviceShell.run(PS_DETAILED, PS_TIMEOUT_SECONDS) }.getOrNull()
                if (detailed != null && detailed.exitCode == 0 && !detailed.timedOut) {
                    detailed.stdout
                } else {
                    runCatching { DeviceShell.run(PS_PLAIN, PS_TIMEOUT_SECONDS) }.getOrNull()?.stdout.orEmpty()
                }
            }
        val lines =
            raw.lines()
                .map { it.trimEnd() }
                .filter { it.isNotBlank() }
                .let { rows -> if (filter.isEmpty()) rows else rows.filter { it.lowercase().contains(filter) } }
        val shown = lines.take(limit)
        val json =
            buildJsonObject {
                put("filter", filter)
                put("matched", lines.size)
                put("returned", shown.size)
                put("truncated", lines.size > shown.size)
                put("columns", shown.firstOrNull().orEmpty())
                putJsonArray("processes") { shown.forEach { add(it) } }
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
                "Lists running processes and lets you narrow them with 'filter' (case-insensitive " +
                    "match on any column). Use it to find which app is draining the battery or the " +
                    "network, to look up a PID before signalling it, or to confirm that a process you " +
                    "started is still alive. Unprivileged callers only see processes they may inspect, " +
                    "so the list is complete for this app and partial for the rest of the system; use " +
                    "su_exec when a full listing matters.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("filter") {
                                put("type", "string")
                                put("description", "Substring matched against each process line")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum processes to return (default $DEFAULT_LIMIT)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_processes"
        private const val TAG = "MCP:ListProcessesHandler"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 500
        private const val PS_TIMEOUT_SECONDS = 15
        private const val PS_DETAILED = "ps -A -o USER,PID,PPID,VSZ,RSS,NAME"
        private const val PS_PLAIN = "ps -A"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_system_properties
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_system_properties`. Input: `{ "filter": <string>? }`. */
class GetSystemPropertiesHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val filter = McpToolUtils.optionalString(arguments, "filter", "").lowercase()
        Log.d(TAG, "Executing get_system_properties")

        val output =
            withContext(Dispatchers.IO) {
                runCatching { DeviceShell.run("getprop", PROP_TIMEOUT_SECONDS) }.getOrNull()?.stdout.orEmpty()
            }
        val properties = PROPERTY_PATTERN.findAll(output).associate { it.groupValues[1] to it.groupValues[2] }
        val selected =
            if (filter.isEmpty()) {
                properties
            } else {
                properties.filterKeys { it.lowercase().contains(filter) }
            }
        val json =
            buildJsonObject {
                put("filter", filter)
                put("total_properties", properties.size)
                put("returned", selected.size)
                putJsonObject("properties") {
                    selected.toSortedMap().forEach { (key, value) -> put(key, value) }
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
                "Reads Android system properties (`getprop`) as a key/value object, optionally " +
                    "narrowed by a case-insensitive 'filter' on the property name. Reach for it for " +
                    "facts no other tool exposes: the build fingerprint, hardware and SoC names, the " +
                    "bootloader state, whether the device is rooted, emulator flags (e.g. " +
                    "'ro.build.type', 'ro.product.model', 'ro.debuggable'). Reading any property " +
                    "needs no permission; changing one does, which is su_exec territory.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("filter") {
                                put("type", "string")
                                put("description", "Substring matched against property names, e.g. \"ro.product\"")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_system_properties"
        private const val TAG = "MCP:GetSystemPropertiesHandler"
        private const val PROP_TIMEOUT_SECONDS = 15
        private val PROPERTY_PATTERN = Regex("""\[([^\]]+)]:\s*\[([^\]]*)]""")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_environment
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `get_environment`. Input: `{}`. */
class GetEnvironmentHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_environment")
        val environment = System.getenv()
        val runtime =
            Runtime.getRuntime()
        val json =
            buildJsonObject {
                put("variable_count", environment.size)
                putJsonObject("environment") {
                    environment.toSortedMap().forEach { (key, value) -> put(key, value) }
                }
                putJsonObject("runtime") {
                    put("available_processors", runtime.availableProcessors())
                    put("max_memory_bytes", runtime.maxMemory())
                    put("total_memory_bytes", runtime.totalMemory())
                    put("free_memory_bytes", runtime.freeMemory())
                    put("java_version", System.getProperty("java.version").orEmpty())
                    put("vm_name", System.getProperty("java.vm.name").orEmpty())
                    put("os_arch", System.getProperty("os.arch").orEmpty())
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
                "Returns the process environment plus a snapshot of the runtime: environment " +
                    "variables, processor count, memory limits and usage, and Java/VM/architecture " +
                    "names. Use it to learn how the server itself was launched, where its temporary " +
                    "directories point, or how much heap remains before a heavy operation. Paths the " +
                    "app can read are also reported by list_storage_locations; prefer that tool for " +
                    "user-facing file work.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_environment"
        private const val TAG = "MCP:GetEnvironmentHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// read_logcat
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `read_logcat`. Input: `{ "lines": <int>?, "filter": <string>? }`. */
class ReadLogcatHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val lines = McpToolUtils.optionalInt(arguments, "lines", DEFAULT_LINES).coerceIn(1, MAX_LINES)
        val filter = McpToolUtils.optionalString(arguments, "filter", "")
        Log.d(TAG, "Executing read_logcat")

        val output =
            withContext(Dispatchers.IO) {
                runCatching {
                    DeviceShell.run("logcat -d -v threadtime -t $lines", LOGCAT_TIMEOUT_SECONDS)
                }.getOrNull()
            }
        if (output == null) {
            throw McpToolException.ActionFailed(
                "Could not run `logcat`. The device may not expose the logcat binary to this app; use " +
                    "shell_exec to inspect the failure.",
            )
        }
        val allLines = output.stdout.lines().filter { it.isNotBlank() }
        val matching =
            if (filter.isEmpty()) allLines else allLines.filter { it.contains(filter, ignoreCase = true) }
        val json =
            buildJsonObject {
                put("requested_lines", lines)
                put("filter", filter)
                put("captured_lines", allLines.size)
                put("returned_lines", matching.size)
                put("exit_code", output.exitCode)
                put("log", matching.joinToString("\n"))
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
                "Dumps the most recent device log lines (`logcat -d -t N`) and can keep only the " +
                    "lines containing 'filter'. Use it to find out why something failed on the device " +
                    "side — a crashed helper process, a permission denial the framework logged, a " +
                    "stack trace from an app you just drove. Unprivileged apps only receive their own " +
                    "uid's entries, so the output covers this app and its events; su_exec gets the " +
                    "full buffer on a rooted device.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("lines") {
                                put("type", "integer")
                                put("description", "Number of recent lines to read (default $DEFAULT_LINES)")
                            }
                            putJsonObject("filter") {
                                put("type", "string")
                                put("description", "Case-insensitive substring every returned line must contain")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "read_logcat"
        private const val TAG = "MCP:ReadLogcatHandler"
        private const val DEFAULT_LINES = 200
        private const val MAX_LINES = 4000
        private const val LOGCAT_TIMEOUT_SECONDS = 20
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// read_system_file
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `read_system_file`. Input: `{ "path": <string>, "max_bytes": <int>? }`. */
class ReadSystemFileHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val path = McpToolUtils.requireString(arguments, "path").trim()
        val maxBytes = McpToolUtils.optionalInt(arguments, "max_bytes", DEFAULT_MAX_BYTES).coerceIn(1, MAX_MAX_BYTES)
        Log.d(TAG, "Executing read_system_file")

        if (!path.startsWith("/")) {
            throw McpToolException.InvalidParams(
                "Parameter 'path' must be an absolute device path such as '/proc/version'.",
            )
        }
        val file = File(path)
        if (!file.exists()) {
            throw McpToolException.InvalidParams("No such file: '$path'.")
        }
        if (file.isDirectory) {
            throw McpToolException.InvalidParams("'$path' is a directory; use shell_exec with `ls` instead.")
        }
        @Suppress("TooGenericExceptionCaught")
        val bytes =
            withContext(Dispatchers.IO) {
                try {
                    readCapped(file, maxBytes)
                } catch (exception: Exception) {
                    throw McpToolException.ActionFailed(
                        "Could not read '$path': ${exception.message ?: exception.javaClass.simpleName}. " +
                            "This app's uid has no read access there; su_exec runs as root instead.",
                    )
                }
            }
        val text = String(bytes, Charsets.UTF_8)
        val json =
            buildJsonObject {
                put("path", path)
                put("size_bytes", if (file.length() > 0) file.length() else bytes.size.toLong())
                put("bytes_returned", bytes.size)
                put("readable", file.canRead())
                put("content", DeviceShell.truncate(text))
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
                "Reads a file by absolute device path and returns its text, capped at 'max_bytes'. " +
                    "It exists for the places the file providers cannot reach: /proc and /sys for " +
                    "kernel, thermal, battery and process details, /data/local/tmp for scratch space, " +
                    "or any world-readable config. Reading still requires the path to be readable by " +
                    "this app's uid; use su_exec for protected paths. Prefer read_file for the " +
                    "user's storage and read_workspace_file for files this server wrote itself.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Absolute path, e.g. \"/proc/version\" or \"/proc/net/tcp\"")
                            }
                            putJsonObject("max_bytes") {
                                put("type", "integer")
                                put("description", "Maximum bytes to return (default $DEFAULT_MAX_BYTES)")
                            }
                        },
                    required = listOf("path"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "read_system_file"
        private const val TAG = "MCP:ReadSystemFileHandler"
        private const val DEFAULT_MAX_BYTES = 65_536
        private const val MAX_MAX_BYTES = 1_048_576

        /** Reads at most [maxBytes] bytes without ever loading the whole file into memory. */
        private fun readCapped(
            file: File,
            maxBytes: Int,
        ): ByteArray {
            val sink = ByteArrayOutputStream()
            file.inputStream().use { stream ->
                val buffer = ByteArray(READ_CHUNK_BYTES)
                while (sink.size() < maxBytes) {
                    val remaining = maxBytes - sink.size()
                    val read = stream.read(buffer, 0, minOf(READ_CHUNK_BYTES, remaining))
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
            }
            return sink.toByteArray()
        }

        private const val READ_CHUNK_BYTES = 8192
    }
}

/** Registers the shell-level bundle: command execution, root access and raw system inspection. */
fun registerShellTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    ShellExecHandler(context).register(registrar, toolNamePrefix)
    SuExecHandler(context).register(registrar, toolNamePrefix)
    CheckRootHandler(context).register(registrar, toolNamePrefix)
    ListProcessesHandler(context).register(registrar, toolNamePrefix)
    GetSystemPropertiesHandler(context).register(registrar, toolNamePrefix)
    GetEnvironmentHandler(context).register(registrar, toolNamePrefix)
    ReadLogcatHandler(context).register(registrar, toolNamePrefix)
    ReadSystemFileHandler(context).register(registrar, toolNamePrefix)
}
