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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Persistent interactive-shell tool bundle.
 *
 * `shell_exec` starts a process, waits for it to finish and returns everything it printed. That is
 * the wrong shape for anything stateful: a REPL, an `adb`-style shell, a session that must stay
 * authenticated, or a program that only accepts input after it has started. These tools keep a
 * long-lived `sh` process per session so an agent can write to it, read the incremental output
 * when it is ready, and close it later.
 *
 * Sessions live in the server process, are capped at [TerminalSessions.MAX_SESSIONS], buffer at
 * most [TerminalSessions.MAX_BUFFER_CHARS] characters each, and are all closed when the server
 * stops. Every read returns the bytes produced since the last read, so nothing is lost between
 * calls and nothing is returned twice.
 */

/** Process-wide registry of interactive shell sessions, keyed by a short session id. */
internal object TerminalSessions {
    /** Upper bound on concurrently open sessions so a looping agent cannot exhaust the process. */
    const val MAX_SESSIONS = 4

    /** Per-session output buffer ceiling; oldest output is dropped first. */
    const val MAX_BUFFER_CHARS = 200_000

    private val sessions = ConcurrentHashMap<String, Session>()

    /** One live `sh` process plus its captured output. */
    class Session(
        val id: String,
        val process: Process,
    ) {
        val openedAtMs = System.currentTimeMillis()
        private val pending = StringBuilder()
        private val consumedChars = AtomicLong(0)

        val stdin: Writer = OutputStreamWriter(process.outputStream)
        val isAlive: Boolean get() = process.isAlive

        /** PID while the process runs, or -1 once it has exited. */
        fun pidOrMinusOne(): Long = if (process.isAlive) process.pid() else -1L

        /** Appends freshly read output, trimming the oldest characters past the buffer ceiling. */
        fun append(text: String) {
            synchronized(pending) {
                pending.append(text)
                val excess = pending.length - MAX_BUFFER_CHARS
                if (excess > 0) pending.delete(0, excess)
            }
        }

        /** Returns everything produced since the previous read. */
        fun drain(): String =
            synchronized(pending) {
                val text = pending.toString()
                pending.setLength(0)
                consumedChars.addAndGet(text.length.toLong())
                text
            }

        /** Characters waiting to be read, without consuming them. */
        fun pendingChars(): Int = synchronized(pending) { pending.length }

        /** Total characters handed to the caller so far. */
        fun consumedTotal(): Long = consumedChars.get()

        /** Closes stdin, then the process; always safe to call more than once. */
        fun close() {
            runCatching { stdin.close() }
            process.destroy()
            if (!process.waitFor(CLOSE_GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }

        private companion object {
            const val CLOSE_GRACE_SECONDS = 2L
        }
    }

    /** Starts a new session running [shellCommand] (default `/system/bin/sh`). */
    fun open(shellCommand: String, workingDirectory: String?): Session {
        if (sessions.size >= MAX_SESSIONS) {
            throw McpToolException.ActionFailed(
                "Already $MAX_SESSIONS interactive sessions are open. Close one with terminal_close, " +
                    "or reuse a session you already have (see terminal_list).",
            )
        }
        val builder = ProcessBuilder(shellCommand).redirectErrorStream(true)
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
                    "Could not start interactive shell '$shellCommand': " +
                        (exception.message ?: exception.javaClass.simpleName),
                )
            }
        val session = Session(id = newId(), process = process)
        sessions[session.id] = session
        thread(isDaemon = true, name = "mcp-terminal-${session.id}") {
            process.inputStream.bufferedReader().use { reader -> pump(reader, session) }
        }
        return session
    }

    /** The session with [id], or throws an actionable error listing the open ones. */
    fun require(id: String): Session {
        val session = sessions[id]
        if (session != null) return session
        val open = if (sessions.isEmpty()) "none" else sessions.keys.joinToString()
        throw McpToolException.InvalidParams("Unknown session '$id'. Open sessions: $open.")
    }

    /** All open sessions. */
    fun all(): List<Session> = sessions.values.toList()

    /** Closes and forgets the session with [id]; returns false when there was nothing to close. */
    fun close(id: String): Boolean {
        val session = sessions.remove(id) ?: return false
        session.close()
        return true
    }

    private fun pump(
        reader: BufferedReader,
        session: Session,
    ) {
        val buffer = CharArray(PUMP_CHUNK_CHARS)
        while (true) {
            val read =
                try {
                    reader.read(buffer)
                } catch (_: IOException) {
                    break
                }
            if (read < 0) break
            session.append(String(buffer, 0, read))
        }
    }

    private fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    private const val PUMP_CHUNK_CHARS = 4096
}

/** Shell started by `terminal_open` when the caller does not name one. */
private const val TERMINAL_DEFAULT_SHELL = "/system/bin/sh"

// ─────────────────────────────────────────────────────────────────────────────
// terminal_open
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `terminal_open`. Input: `{ "shell": <string>?, "working_directory": <string>? }`. */
class TerminalOpenHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val shell = McpToolUtils.optionalString(arguments, "shell", TERMINAL_DEFAULT_SHELL)
        val workingDirectory = McpToolUtils.optionalString(arguments, "working_directory", "")
        Log.d(TAG, "Executing terminal_open")

        val session =
            withContext(Dispatchers.IO) {
                TerminalSessions.open(shell, workingDirectory.ifEmpty { null })
            }
        val json =
            buildJsonObject {
                put("session_id", session.id)
                put("shell", shell)
                put("pid", session.pidOrMinusOne())
                put("status", "open")
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
                "Opens a long-lived interactive shell and returns its 'session_id'. Use it instead of " +
                    "shell_exec whenever the work is a conversation rather than a single command: a REPL, " +
                    "a sequence where each step depends on the last, or a program that waits for input. " +
                    "Write with terminal_write, collect output with terminal_read, and release the " +
                    "session with terminal_close — at most ${TerminalSessions.MAX_SESSIONS} sessions can " +
                    "be open at once and they all end when the server stops. Pass 'shell' to start " +
                    "something other than the default $TERMINAL_DEFAULT_SHELL, and 'working_directory' to " +
                    "control where it starts.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("shell") {
                                put("type", "string")
                                put("description", "Shell or program to run (default \"$TERMINAL_DEFAULT_SHELL\")")
                            }
                            putJsonObject("working_directory") {
                                put("type", "string")
                                put("description", "Absolute directory to start in")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "terminal_open"
        private const val TAG = "MCP:TerminalOpenHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// terminal_write
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `terminal_write`.
 * Input: `{ "session_id": <string>, "input": <string>, "newline": <bool>?, "flush": <bool>? }`.
 */
class TerminalWriteHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val sessionId = McpToolUtils.requireString(arguments, "session_id")
        val input = McpToolUtils.requireString(arguments, "input")
        val newline = McpToolUtils.optionalBoolean(arguments, "newline", true)
        Log.d(TAG, "Executing terminal_write")

        val session = TerminalSessions.require(sessionId)
        if (!session.isAlive) {
            throw McpToolException.ActionFailed(
                "Session '$sessionId' is no longer running. Open a new one with terminal_open.",
            )
        }
        withContext(Dispatchers.IO) {
            session.stdin.write(input)
            if (newline) session.stdin.write("\n")
            session.stdin.flush()
        }
        val json =
            buildJsonObject {
                put("session_id", sessionId)
                put("written_chars", input.length + if (newline) 1 else 0)
                put("newline", newline)
                put("alive", session.isAlive)
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
                "Sends text to an interactive session's standard input, with a trailing newline " +
                    "unless 'newline' is false (pass false to feed a partial line, or to send control " +
                    "characters such as a lone q to quit a pager). The call returns as soon as the " +
                    "bytes are flushed and does not wait for the program to react, because output " +
                    "arrives asynchronously. Follow it with terminal_read to collect the response.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("session_id") {
                                put("type", "string")
                                put("description", "Session id returned by terminal_open")
                            }
                            putJsonObject("input") {
                                put("type", "string")
                                put("description", "Text to write, e.g. \"ls -la /sdcard\"")
                            }
                            putJsonObject("newline") {
                                put("type", "boolean")
                                put("description", "Append a newline after the text (default true)")
                            }
                        },
                    required = listOf("session_id", "input"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "terminal_write"
        private const val TAG = "MCP:TerminalWriteHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// terminal_read
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `terminal_read`. Input: `{ "session_id": <string>, "wait_ms": <int>? }`. */
class TerminalReadHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val sessionId = McpToolUtils.requireString(arguments, "session_id")
        val waitMs = McpToolUtils.optionalInt(arguments, "wait_ms", 0).coerceIn(0, MAX_WAIT_MS)
        Log.d(TAG, "Executing terminal_read")

        val session = TerminalSessions.require(sessionId)
        if (waitMs > 0 && session.pendingChars() == 0) {
            delay(waitMs.toLong())
        }
        val output = session.drain()
        val json =
            buildJsonObject {
                put("session_id", sessionId)
                put("alive", session.isAlive)
                put("chars", output.length)
                put("pending_chars", session.pendingChars())
                put("total_chars_read", session.consumedTotal())
                put("output", output)
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
                "Returns everything an interactive session has printed since the previous read and " +
                    "then forgets it, so each call reports only new output. Set 'wait_ms' to wait for " +
                    "output that has not appeared yet (up to $MAX_WAIT_MS ms) — useful right after " +
                    "terminal_write, since a shell needs a moment to respond. An empty 'output' with " +
                    "'alive': true simply means the program has not printed anything yet; with " +
                    "'alive': false the process has exited and the buffer you receive is its final word.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("session_id") {
                                put("type", "string")
                                put("description", "Session id returned by terminal_open")
                            }
                            putJsonObject("wait_ms") {
                                put("type", "integer")
                                put("description", "Milliseconds to wait for new output first (default 0)")
                            }
                        },
                    required = listOf("session_id"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "terminal_read"
        private const val TAG = "MCP:TerminalReadHandler"
        private const val MAX_WAIT_MS = 30_000
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// terminal_list
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `terminal_list`. Input: `{}`. */
class TerminalListHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing terminal_list")
        val sessions = TerminalSessions.all()
        val json =
            buildJsonObject {
                put("count", sessions.size)
                put("max_sessions", TerminalSessions.MAX_SESSIONS)
                putJsonArray("sessions") {
                    sessions.forEach { session ->
                        add(
                            buildJsonObject {
                                put("session_id", session.id)
                                put("alive", session.isAlive)
                                put("pid", session.pidOrMinusOne())
                                put("age_ms", System.currentTimeMillis() - session.openedAtMs)
                                put("pending_chars", session.pendingChars())
                                put("total_chars_read", session.consumedTotal())
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
                "Lists the interactive sessions that are currently open, with their session id, " +
                    "whether the process is still alive, its pid, how long it has been up, how many " +
                    "characters are waiting to be read and how much has been read in total. Call it " +
                    "when you have lost track of a session id, or before terminal_open to see whether " +
                    "the ${TerminalSessions.MAX_SESSIONS}-session limit is already reached.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "terminal_list"
        private const val TAG = "MCP:TerminalListHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// terminal_close
// ─────────────────────────────────────────────────────────────────────────────

/** MCP tool handler for `terminal_close`. Input: `{ "session_id": <string> }`. */
class TerminalCloseHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val sessionId = McpToolUtils.requireString(arguments, "session_id")
        Log.d(TAG, "Executing terminal_close")

        val session = TerminalSessions.require(sessionId)
        val remaining = session.pendingChars()
        val closed =
            withContext(Dispatchers.IO) {
                TerminalSessions.close(sessionId)
            }
        val json =
            buildJsonObject {
                put("session_id", sessionId)
                put("closed", closed)
                put("alive_before_close", session.isAlive)
                put("unread_chars", remaining)
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
                "Ends an interactive session: standard input is closed and the process is terminated " +
                    "(forcibly if it does not exit within a couple of seconds). Anything still unread " +
                    "is discarded and reported as 'unread_chars', so call terminal_read first when the " +
                    "final output matters. Always close sessions you no longer need — the " +
                    "${TerminalSessions.MAX_SESSIONS}-session limit blocks new ones, and every process " +
                    "keeps running (and holding memory) until it is closed.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("session_id") {
                                put("type", "string")
                                put("description", "Session id returned by terminal_open")
                            }
                        },
                    required = listOf("session_id"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "terminal_close"
        private const val TAG = "MCP:TerminalCloseHandler"
    }
}

/** Registers the interactive-terminal bundle: open, write, read, list and close. */
fun registerTerminalTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    TerminalOpenHandler(context).register(registrar, toolNamePrefix)
    TerminalWriteHandler(context).register(registrar, toolNamePrefix)
    TerminalReadHandler(context).register(registrar, toolNamePrefix)
    TerminalListHandler(context).register(registrar, toolNamePrefix)
    TerminalCloseHandler(context).register(registrar, toolNamePrefix)
}
