@file:Suppress("MatchingDeclarationName")

package com.cococopi.andromcp.mcp.tools

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.mcp.McpToolException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.TimeZone

/**
 * Communication and personal-data tool bundle.
 *
 * Gives an agent access to the account data a phone actually holds: text messages, the call log,
 * contacts and calendar events, plus sending a text message or handing a pre-filled e-mail to a
 * mail client. Every tool here touches a runtime-permission-protected provider, so each one gates
 * itself through [RuntimePermissionGate] and fails with an actionable permission error instead of
 * silently returning nothing.
 *
 * All content returned by these tools is device-derived and therefore wrapped with the
 * anti-prompt-injection warning.
 */

// ─────────────────────────────────────────────────────────────────────────────
// send_sms
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `send_sms`.
 *
 * **Input**: `{ "phone_number": <string>, "message": <string> }`
 * **Output**: text confirmation.
 */
class SendSmsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.SEND_SMS, TOOL_NAME)

        val number = McpToolUtils.requireString(arguments, "phone_number").trim()
        if (number.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'phone_number' must not be blank")
        }
        val message = McpToolUtils.requireString(arguments, "message")
        if (message.isBlank()) {
            throw McpToolException.InvalidParams("Parameter 'message' must not be blank")
        }

        Log.d(TAG, "Executing send_sms (${message.length} chars)")

        val smsManager =
            context.getSystemService(SmsManager::class.java)
                ?: throw McpToolException.ActionFailed("This device exposes no SMS service")
        val parts = smsManager.divideMessage(message)

        withContext(Dispatchers.IO) {
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
        }

        return McpToolUtils.textResult(
            "Submitted a ${message.length}-character message (${parts.size} SMS part(s)) to the " +
                "system for delivery to $number. Delivery is handled by the carrier and cannot be " +
                "confirmed by this call.",
        )
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Sends a text message (SMS) from this device's SIM to a phone number. Use it to " +
                    "message somebody on the user's behalf, reply to a contact, or send a code to " +
                    "another phone. Long bodies are split into multiple SMS parts automatically. " +
                    "The call returns once the message has been handed to the system; carrier " +
                    "delivery is not confirmed. Requires the SEND_SMS permission to be granted to " +
                    "the app (which may require the SIM to be present).",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("phone_number") {
                                put("type", "string")
                                put("description", "Destination number in any format the dialer accepts")
                            }
                            putJsonObject("message") {
                                put("type", "string")
                                put("description", "Body of the text message")
                            }
                        },
                    required = listOf("phone_number", "message"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "send_sms"
        private const val TAG = "MCP:SendSmsHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// list_sms_threads
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_sms_threads`.
 *
 * **Input**: `{ "limit": <int>? }`
 * **Output**: JSON array of conversation summaries, newest first.
 */
class ListSmsThreadsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.READ_SMS, TOOL_NAME)

        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_THREAD_LIMIT)
        McpToolUtils.validatePositiveRange(limit.toLong(), "limit", MAX_THREAD_LIMIT.toLong())

        Log.d(TAG, "Executing list_sms_threads (limit=$limit)")

        val projection =
            arrayOf(
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
            )
        val threads = withContext(Dispatchers.IO) { collectThreads(projection, limit) }

        val json =
            buildJsonObject {
                put("thread_count", threads.size)
                put(
                    "threads",
                    buildJsonArray {
                        threads.forEach { add(it) }
                    },
                )
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private fun collectThreads(
        projection: Array<String>,
        limit: Int,
    ): List<JsonObject> {
        val summaries = linkedMapOf<Long, MutableThreadSummary>()
        val cursor =
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )
        if (cursor == null) {
            throw McpToolException.ActionFailed("The SMS provider is unavailable on this device")
        }

        cursor.use { c ->
            var scanned = 0
            while (c.moveToNext() && summaries.size < limit && scanned < MAX_SCAN_ROWS) {
                scanned++
                val threadId = c.longOrZero(Telephony.Sms.THREAD_ID)
                val body = c.stringOrNull(Telephony.Sms.BODY).orEmpty()
                val summary =
                    summaries.getOrPut(threadId) {
                        MutableThreadSummary(
                            threadId = threadId,
                            address = c.stringOrNull(Telephony.Sms.ADDRESS),
                            lastMessage = body,
                            lastTimestamp = c.longOrZero(Telephony.Sms.DATE),
                            lastType = smsTypeLabel(c.intOrZero(Telephony.Sms.TYPE)),
                        )
                    }
                summary.messageCount++
            }
        }
        return summaries.values.map { it.toJson() }
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Lists the device's text-message conversations, newest first. Each entry reports " +
                    "the thread id (usable with read_sms_thread), the other party's number, how " +
                    "many messages the thread has, and a preview of the most recent message. Use " +
                    "it to see who has been texting the user before reading a specific thread. " +
                    "Parameter 'limit' (default 20, max 200) caps how many conversations are " +
                    "returned. Requires the READ_SMS permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum number of conversations to return (default 20, max 200)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_sms_threads"
        private const val TAG = "MCP:ListSmsThreadsHandler"
    }
}

/** Mutable accumulator for a single SMS conversation while scanning the message table. */
private class MutableThreadSummary(
    private val threadId: Long,
    private val address: String?,
    private val lastMessage: String,
    private val lastTimestamp: Long,
    private val lastType: String,
) {
    var messageCount: Int = 0

    fun toJson(): JsonObject =
        buildJsonObject {
            put("thread_id", threadId)
            put("address", address)
            put("message_count", messageCount)
            put("last_message_preview", lastMessage.take(PREVIEW_CHARS))
            put("last_message_at", isoFromMillis(lastTimestamp))
            put("last_message_type", lastType)
        }

    private companion object {
        const val PREVIEW_CHARS = 160
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// read_sms_thread
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `read_sms_thread`.
 *
 * **Input**: `{ "thread_id": <int>?, "address": <string>?, "limit": <int>? }`
 * **Output**: JSON array of messages in the thread, newest first.
 */
class ReadSmsThreadHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.READ_SMS, TOOL_NAME)

        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_MESSAGE_LIMIT)
        McpToolUtils.validatePositiveRange(limit.toLong(), "limit", MAX_MESSAGE_LIMIT.toLong())

        val hasThreadId = arguments?.containsKey("thread_id") == true
        val address = McpToolUtils.optionalString(arguments, "address", "")
        if (!hasThreadId && address.isEmpty()) {
            throw McpToolException.InvalidParams(
                "Provide 'thread_id' (from list_sms_threads) or 'address' (a phone number)",
            )
        }

        Log.d(TAG, "Executing read_sms_thread")

        val messages =
            withContext(Dispatchers.IO) {
                if (hasThreadId) {
                    val threadId = McpToolUtils.requireLong(arguments, "thread_id")
                    queryMessages(
                        selection = "${Telephony.Sms.THREAD_ID} = ?",
                        selectionArgs = arrayOf(threadId.toString()),
                        limit = limit,
                    )
                } else {
                    queryMessages(
                        selection = "${Telephony.Sms.ADDRESS} LIKE ?",
                        selectionArgs = arrayOf("%$address%"),
                        limit = limit,
                    )
                }
            }

        val json =
            buildJsonObject {
                put("message_count", messages.size)
                put(
                    "messages",
                    buildJsonArray {
                        messages.forEach { add(it) }
                    },
                )
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private fun queryMessages(
        selection: String,
        selectionArgs: Array<String>,
        limit: Int,
    ): List<JsonObject> {
        val projection =
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.THREAD_ID,
            )
        val cursor =
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${Telephony.Sms.DATE} DESC",
            )
        if (cursor == null) {
            throw McpToolException.ActionFailed("The SMS provider is unavailable on this device")
        }

        val messages = mutableListOf<JsonObject>()
        cursor.use { c ->
            while (c.moveToNext() && messages.size < limit) {
                messages.add(
                    buildJsonObject {
                        put("thread_id", c.longOrZero(Telephony.Sms.THREAD_ID))
                        put("address", c.stringOrNull(Telephony.Sms.ADDRESS))
                        put("body", c.stringOrNull(Telephony.Sms.BODY))
                        put("sent_at", isoFromMillis(c.longOrZero(Telephony.Sms.DATE)))
                        put("type", smsTypeLabel(c.intOrZero(Telephony.Sms.TYPE)))
                    },
                )
            }
        }
        return messages
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Reads the messages of one text-message conversation, newest first. Identify the " +
                    "conversation either by 'thread_id' (from list_sms_threads) or by 'address' (a " +
                    "phone number, matched loosely so the last digits are enough). Each message " +
                    "reports its body, direction (INBOX/SENT/DRAFT and so on), and timestamp in " +
                    "ISO-8601. Parameter 'limit' (default 50, max 500) caps how many messages come " +
                    "back. Requires the READ_SMS permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("thread_id") {
                                put("type", "integer")
                                put("description", "Conversation id returned by list_sms_threads")
                            }
                            putJsonObject("address") {
                                put("type", "string")
                                put("description", "Phone number of the other party (used when thread_id is omitted)")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum number of messages to return (default 50, max 500)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "read_sms_thread"
        private const val TAG = "MCP:ReadSmsThreadHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// search_sms
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `search_sms`.
 *
 * **Input**: `{ "query": <string>, "limit": <int>? }`
 * **Output**: JSON array of matching messages with their conversation id.
 */
class SearchSmsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.READ_SMS, TOOL_NAME)

        val query = McpToolUtils.requireString(arguments, "query")
        if (query.isBlank()) {
            throw McpToolException.InvalidParams("Parameter 'query' must not be blank")
        }
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_MESSAGE_LIMIT)
        McpToolUtils.validatePositiveRange(limit.toLong(), "limit", MAX_MESSAGE_LIMIT.toLong())

        Log.d(TAG, "Executing search_sms")

        val matches =
            withContext(Dispatchers.IO) {
                val projection =
                    arrayOf(
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.TYPE,
                        Telephony.Sms.THREAD_ID,
                    )
                val cursor =
                    context.contentResolver.query(
                        Telephony.Sms.CONTENT_URI,
                        projection,
                        "${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?",
                        arrayOf("%$query%", "%$query%"),
                        "${Telephony.Sms.DATE} DESC",
                    )
                if (cursor == null) {
                    throw McpToolException.ActionFailed("The SMS provider is unavailable on this device")
                }
                val found = mutableListOf<JsonObject>()
                cursor.use { c ->
                    while (c.moveToNext() && found.size < limit) {
                        found.add(
                            buildJsonObject {
                                put("thread_id", c.longOrZero(Telephony.Sms.THREAD_ID))
                                put("address", c.stringOrNull(Telephony.Sms.ADDRESS))
                                put("body", c.stringOrNull(Telephony.Sms.BODY))
                                put("sent_at", isoFromMillis(c.longOrZero(Telephony.Sms.DATE)))
                                put("type", smsTypeLabel(c.intOrZero(Telephony.Sms.TYPE)))
                            },
                        )
                    }
                }
                found
            }

        val json =
            buildJsonObject {
                put("match_count", matches.size)
                put(
                    "matches",
                    buildJsonArray {
                        matches.forEach { add(it) }
                    },
                )
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
                "Searches all text messages on the device for a substring, matching either the " +
                    "message body or the other party's number, newest first. Use it to find a " +
                    "verification code, an address somebody texted, or a thread about a topic. " +
                    "Parameter 'limit' (default 50, max 500) caps the results. Requires the " +
                    "READ_SMS permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "Substring to search for in message bodies and addresses")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum number of matches to return (default 50, max 500)")
                            }
                        },
                    required = listOf("query"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "search_sms"
        private const val TAG = "MCP:SearchSmsHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// list_call_log
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_call_log`.
 *
 * **Input**: `{ "limit": <int>?, "type": <string>? }`
 * **Output**: JSON array of call-log entries, newest first.
 */
class ListCallLogHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.READ_CALL_LOG, TOOL_NAME)

        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_CALL_LOG_LIMIT)
        McpToolUtils.validatePositiveRange(limit.toLong(), "limit", MAX_CALL_LOG_LIMIT.toLong())
        val typeFilter = McpToolUtils.optionalString(arguments, "type", "").uppercase()

        Log.d(TAG, "Executing list_call_log (limit=$limit)")

        val entries =
            withContext(Dispatchers.IO) {
                val projection =
                    arrayOf(
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DATE,
                        CallLog.Calls.DURATION,
                        CallLog.Calls.CACHED_NAME,
                    )
                val cursor =
                    context.contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        projection,
                        null,
                        null,
                        "${CallLog.Calls.DATE} DESC",
                    )
                if (cursor == null) {
                    throw McpToolException.ActionFailed("The call-log provider is unavailable on this device")
                }
                val found = mutableListOf<JsonObject>()
                cursor.use { c ->
                    var scanned = 0
                    while (c.moveToNext() && found.size < limit && scanned < MAX_SCAN_ROWS) {
                        scanned++
                        val label = callTypeLabel(c.intOrZero(CallLog.Calls.TYPE))
                        if (typeFilter.isNotEmpty() && label != typeFilter) continue
                        found.add(
                            buildJsonObject {
                                put("number", c.stringOrNull(CallLog.Calls.NUMBER))
                                put("contact_name", c.stringOrNull(CallLog.Calls.CACHED_NAME))
                                put("type", label)
                                put("at", isoFromMillis(c.longOrZero(CallLog.Calls.DATE)))
                                put("duration_seconds", c.longOrZero(CallLog.Calls.DURATION))
                            },
                        )
                    }
                }
                found
            }

        val json =
            buildJsonObject {
                put("entry_count", entries.size)
                put(
                    "entries",
                    buildJsonArray {
                        entries.forEach { add(it) }
                    },
                )
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
                "Lists recent calls from the device call log, newest first, with number, cached " +
                    "contact name, direction (INCOMING/OUTGOING/MISSED/REJECTED/BLOCKED/VOICEMAIL) " +
                    "and duration in seconds. Use it to answer questions about who called, when, " +
                    "and whether a call was answered. Parameter 'limit' (default 50, max 500) caps " +
                    "the results and 'type' filters to one direction. Requires the READ_CALL_LOG " +
                    "permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum number of entries to return (default 50, max 500)")
                            }
                            putJsonObject("type") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional call direction filter: INCOMING, " +
                                        "OUTGOING, MISSED, REJECTED, BLOCKED or VOICEMAIL",
                                )
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_call_log"
        private const val TAG = "MCP:ListCallLogHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// search_contacts
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `search_contacts`.
 *
 * **Input**: `{ "query": <string>, "limit": <int>? }`
 * **Output**: JSON array of matching phone entries.
 */
class SearchContactsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.READ_CONTACTS, TOOL_NAME)

        val query = McpToolUtils.requireString(arguments, "query").trim()
        if (query.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'query' must not be blank")
        }
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_CONTACT_LIMIT)
        McpToolUtils.validatePositiveRange(limit.toLong(), "limit", MAX_CONTACT_LIMIT.toLong())

        Log.d(TAG, "Executing search_contacts")

        val matches =
            withContext(Dispatchers.IO) {
                val projection =
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                    )
                val cursor =
                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        projection,
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                            "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                        arrayOf("%$query%", "%$query%"),
                        null,
                    )
                if (cursor == null) {
                    throw McpToolException.ActionFailed("The contacts provider is unavailable on this device")
                }
                val found = mutableListOf<JsonObject>()
                cursor.use { c ->
                    while (c.moveToNext() && found.size < limit) {
                        found.add(
                            buildJsonObject {
                                put(
                                    "contact_id",
                                    c.longOrZero(ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
                                )
                                put(
                                    "display_name",
                                    c.stringOrNull(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                                )
                                put("phone_number", c.stringOrNull(ContactsContract.CommonDataKinds.Phone.NUMBER))
                            },
                        )
                    }
                }
                found
            }

        val json =
            buildJsonObject {
                put("match_count", matches.size)
                put(
                    "contacts",
                    buildJsonArray {
                        matches.forEach { add(it) }
                    },
                )
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
                "Looks up contacts in the device address book by a substring of the name or of " +
                    "the phone number, returning matching contact ids, display names and numbers. " +
                    "Use it to resolve a person into a number before calling or texting them. " +
                    "Parameter 'limit' (default 25, max 200) caps the results. Requires the " +
                    "READ_CONTACTS permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "Substring of a contact name or phone number")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum number of contacts to return (default 25, max 200)")
                            }
                        },
                    required = listOf("query"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "search_contacts"
        private const val TAG = "MCP:SearchContactsHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_contact_details
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_contact_details`.
 *
 * **Input**: `{ "contact_id": <int> }`
 * **Output**: JSON object with every phone number and e-mail address of the contact.
 */
class GetContactDetailsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.READ_CONTACTS, TOOL_NAME)

        val contactId = McpToolUtils.requireLong(arguments, "contact_id")
        Log.d(TAG, "Executing get_contact_details")

        val details =
            withContext(Dispatchers.IO) {
                buildJsonObject {
                    put("contact_id", contactId)
                    put("display_name", queryDisplayName(contactId))
                    put(
                        "phone_numbers",
                        buildJsonArray {
                            queryDataValues(
                                contactId,
                                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                                ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ).forEach { add(it) }
                        },
                    )
                    put(
                        "email_addresses",
                        buildJsonArray {
                            queryDataValues(
                                contactId,
                                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                                ContactsContract.CommonDataKinds.Email.ADDRESS,
                            ).forEach { add(it) }
                        },
                    )
                }
            }
        return McpToolUtils.untrustedTextResult(details.toString())
    }

    private fun queryDisplayName(contactId: Long): String? {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val cursor =
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                null,
                null,
                null,
            )
        cursor?.use { c ->
            if (c.moveToFirst()) return c.stringOrNull(ContactsContract.Contacts.DISPLAY_NAME)
        }
        return null
    }

    /** Reads every value of one data-table column for [contactId] matching [mimeType]. */
    private fun queryDataValues(
        contactId: Long,
        mimeType: String,
        column: String,
    ): List<String> {
        val cursor =
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(column),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId.toString(), mimeType),
                null,
            )
        val values = mutableListOf<String>()
        cursor?.use { c ->
            while (c.moveToNext()) {
                c.stringOrNull(column)?.takeIf { it.isNotBlank() }?.let { values.add(it) }
            }
        }
        return values
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Returns every detail stored for one contact: display name, all phone numbers and " +
                    "all e-mail addresses. Use it after search_contacts to decide which number to " +
                    "dial or which address to mail when a contact has several. Requires the " +
                    "READ_CONTACTS permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("contact_id") {
                                put("type", "integer")
                                put("description", "Contact id returned by search_contacts")
                            }
                        },
                    required = listOf("contact_id"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_contact_details"
        private const val TAG = "MCP:GetContactDetailsHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// create_contact
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `create_contact`.
 *
 * **Input**: `{ "display_name": <string>, "phone_number": <string>?, "email": <string>? }`
 * **Output**: text confirmation.
 */
class CreateContactHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.WRITE_CONTACTS, TOOL_NAME)

        val name = McpToolUtils.requireString(arguments, "display_name").trim()
        if (name.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'display_name' must not be blank")
        }
        val phone = McpToolUtils.optionalString(arguments, "phone_number", "").trim()
        val email = McpToolUtils.optionalString(arguments, "email", "").trim()

        Log.d(TAG, "Executing create_contact")

        val rawContactUri =
            withContext(Dispatchers.IO) {
                val operations = arrayListOf<ContentProviderOperation>()
                operations.add(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build(),
                )
                operations.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                        )
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                        .build(),
                )
                if (phone.isNotEmpty()) {
                    operations.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(
                                ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                            )
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                            .build(),
                    )
                }
                if (email.isNotEmpty()) {
                    operations.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(
                                ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                            )
                            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                            .build(),
                    )
                }
                val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
                results.firstOrNull()?.uri
            } ?: throw McpToolException.ActionFailed("The contact could not be created")

        return McpToolUtils.textResult("Created contact '$name' ($rawContactUri)")
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Creates a new contact in the device address book with a display name and, " +
                    "optionally, one phone number and one e-mail address. Use it when the user " +
                    "asks to save somebody's number. If no account is configured on the device " +
                    "the contact is stored as a local contact. Requires the WRITE_CONTACTS " +
                    "permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("display_name") {
                                put("type", "string")
                                put("description", "Full name of the new contact")
                            }
                            putJsonObject("phone_number") {
                                put("type", "string")
                                put("description", "Optional phone number to attach")
                            }
                            putJsonObject("email") {
                                put("type", "string")
                                put("description", "Optional e-mail address to attach")
                            }
                        },
                    required = listOf("display_name"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "create_contact"
        private const val TAG = "MCP:CreateContactHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// list_calendar_events
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_calendar_events`.
 *
 * **Input**: `{ "days_back": <int>?, "days_ahead": <int>?, "limit": <int>? }`
 * **Output**: JSON array of event instances ordered by start time.
 */
class ListCalendarEventsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.READ_CALENDAR, TOOL_NAME)

        val daysBack = McpToolUtils.optionalInt(arguments, "days_back", 0)
        val daysAhead = McpToolUtils.optionalInt(arguments, "days_ahead", DEFAULT_EVENT_WINDOW_DAYS)
        McpToolUtils.validateNonNegative(daysBack.toFloat(), "days_back")
        McpToolUtils.validateNonNegative(daysAhead.toFloat(), "days_ahead")
        if (daysBack > MAX_EVENT_WINDOW_DAYS || daysAhead > MAX_EVENT_WINDOW_DAYS) {
            throw McpToolException.InvalidParams(
                "'days_back' and 'days_ahead' must each be at most $MAX_EVENT_WINDOW_DAYS",
            )
        }
        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_EVENT_LIMIT)
        McpToolUtils.validatePositiveRange(limit.toLong(), "limit", MAX_EVENT_LIMIT.toLong())

        val now = System.currentTimeMillis()
        val startMillis = now - daysBack * MILLIS_PER_DAY
        val endMillis = now + daysAhead * MILLIS_PER_DAY

        Log.d(TAG, "Executing list_calendar_events")

        val events = withContext(Dispatchers.IO) { queryInstances(startMillis, endMillis, limit) }

        val json =
            buildJsonObject {
                put("window_start", isoFromMillis(startMillis))
                put("window_end", isoFromMillis(endMillis))
                put("event_count", events.size)
                put(
                    "events",
                    buildJsonArray {
                        events.forEach { add(it) }
                    },
                )
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private fun queryInstances(
        startMillis: Long,
        endMillis: Long,
        limit: Int,
    ): List<JsonObject> {
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMillis)
        ContentUris.appendId(builder, endMillis)

        val projection =
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.DESCRIPTION,
            )
        val cursor =
            context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )
        if (cursor == null) {
            throw McpToolException.ActionFailed("The calendar provider is unavailable on this device")
        }

        val events = mutableListOf<JsonObject>()
        cursor.use { c ->
            while (c.moveToNext() && events.size < limit) {
                events.add(
                    buildJsonObject {
                        put("event_id", c.longOrZero(CalendarContract.Instances.EVENT_ID))
                        put("title", c.stringOrNull(CalendarContract.Instances.TITLE))
                        put("starts_at", isoFromMillis(c.longOrZero(CalendarContract.Instances.BEGIN)))
                        put("ends_at", isoFromMillis(c.longOrZero(CalendarContract.Instances.END)))
                        put("all_day", c.intOrZero(CalendarContract.Instances.ALL_DAY) != 0)
                        put("location", c.stringOrNull(CalendarContract.Instances.EVENT_LOCATION))
                        put("description", c.stringOrNull(CalendarContract.Instances.DESCRIPTION))
                    },
                )
            }
        }
        return events
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Lists calendar events in a time window, ordered by start time. Recurring events " +
                    "are expanded into their individual occurrences, so each entry is a real " +
                    "instance with its own start and end. 'days_back' (default 0) and 'days_ahead' " +
                    "(default 7) set the window in days, up to 365 each, and 'limit' (default 50, " +
                    "max 500) caps the results. Each entry reports event_id (usable with " +
                    "delete_calendar_event), title, ISO-8601 start/end, all-day flag, location and " +
                    "description. Requires the READ_CALENDAR permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("days_back") {
                                put("type", "integer")
                                put("description", "How many days into the past to include (default 0, max 365)")
                            }
                            putJsonObject("days_ahead") {
                                put("type", "integer")
                                put("description", "How many days into the future to include (default 7, max 365)")
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum number of events to return (default 50, max 500)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_calendar_events"
        private const val TAG = "MCP:ListCalendarEventsHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// create_calendar_event
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `create_calendar_event`.
 *
 * **Input**: `{ "title": <string>, "start_time": <string>, "end_time": <string>?,
 * "duration_minutes": <int>?, "calendar_id": <int>?, "location": <string>?,
 * "description": <string>?, "all_day": <bool>? }`
 * **Output**: text confirmation with the new event id.
 */
class CreateCalendarEventHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.WRITE_CALENDAR, TOOL_NAME)

        val title = McpToolUtils.requireString(arguments, "title").trim()
        if (title.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'title' must not be blank")
        }
        val startMillis = parseInstantMillis(McpToolUtils.requireString(arguments, "start_time"), "start_time")
        val endValue = McpToolUtils.optionalString(arguments, "end_time", "")
        val allDay = McpToolUtils.optionalBoolean(arguments, "all_day", false)
        val durationMinutes =
            McpToolUtils.optionalInt(arguments, "duration_minutes", DEFAULT_EVENT_DURATION_MINUTES)
        McpToolUtils.validatePositiveRange(
            durationMinutes.toLong(),
            "duration_minutes",
            MAX_EVENT_DURATION_MINUTES.toLong(),
        )
        val durationMillis = durationMinutes.toLong() * MILLIS_PER_MINUTE
        val endMillis =
            if (endValue.isEmpty()) {
                startMillis + durationMillis
            } else {
                parseInstantMillis(endValue, "end_time")
            }
        if (endMillis <= startMillis) {
            throw McpToolException.InvalidParams("The event end must be after its start")
        }

        val location = McpToolUtils.optionalString(arguments, "location", "")
        val description = McpToolUtils.optionalString(arguments, "description", "")

        Log.d(TAG, "Executing create_calendar_event")

        val eventId =
            withContext(Dispatchers.IO) {
                val calendarId =
                    if (arguments?.containsKey("calendar_id") == true) {
                        McpToolUtils.requireLong(arguments, "calendar_id")
                    } else {
                        defaultWritableCalendarId()
                    }
                val values =
                    ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calendarId)
                        put(CalendarContract.Events.TITLE, title)
                        put(CalendarContract.Events.DTSTART, startMillis)
                        put(CalendarContract.Events.DTEND, endMillis)
                        put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                        if (location.isNotEmpty()) {
                            put(CalendarContract.Events.EVENT_LOCATION, location)
                        }
                        if (description.isNotEmpty()) {
                            put(CalendarContract.Events.DESCRIPTION, description)
                        }
                    }
                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                uri?.lastPathSegment?.toLongOrNull()
            } ?: throw McpToolException.ActionFailed(
                "The event could not be created; no calendar accepting writes may be configured",
            )

        return McpToolUtils.textResult(
            "Created event '$title' (event_id=$eventId) from ${isoFromMillis(startMillis)} " +
                "to ${isoFromMillis(endMillis)}",
        )
    }

    private fun defaultWritableCalendarId(): Long {
        val cursor =
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                null,
            )
        cursor?.use { c ->
            if (c.moveToFirst()) return c.longOrZero(CalendarContract.Calendars._ID)
        }
        throw McpToolException.ActionFailed(
            "No writable calendar is configured on this device. Pass 'calendar_id' explicitly " +
                "(see list_calendars) or add an account in the Calendar app first.",
        )
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Creates a calendar event. 'start_time' is required and accepts either an " +
                    "ISO-8601 instant such as 2026-10-15T09:00:00Z or epoch milliseconds. The end " +
                    "is taken from 'end_time' when supplied, otherwise computed from " +
                    "'duration_minutes' (default 60, max 43200). 'calendar_id' selects the target " +
                    "calendar; when omitted the first writable calendar is used, and the call " +
                    "fails with guidance if none exists. Use 'all_day', 'location' and " +
                    "'description' to fill the remaining fields. Requires the WRITE_CALENDAR " +
                    "permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("title") {
                                put("type", "string")
                                put("description", "Event title")
                            }
                            putJsonObject("start_time") {
                                put("type", "string")
                                put("description", "ISO-8601 instant (2026-10-15T09:00:00Z) or epoch milliseconds")
                            }
                            putJsonObject("end_time") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional ISO-8601 instant or epoch milliseconds; " +
                                        "overrides duration_minutes",
                                )
                            }
                            putJsonObject("duration_minutes") {
                                put("type", "integer")
                                put("description", "Duration when end_time is omitted (default 60, max 43200)")
                            }
                            putJsonObject("calendar_id") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Target calendar id from list_calendars; defaults to the " +
                                        "first writable calendar",
                                )
                            }
                            putJsonObject("location") {
                                put("type", "string")
                                put("description", "Optional event location")
                            }
                            putJsonObject("description") {
                                put("type", "string")
                                put("description", "Optional event notes")
                            }
                            putJsonObject("all_day") {
                                put("type", "boolean")
                                put("description", "Whether the event lasts the whole day (default false)")
                            }
                        },
                    required = listOf("title", "start_time"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "create_calendar_event"
        private const val TAG = "MCP:CreateCalendarEventHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// delete_calendar_event
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `delete_calendar_event`.
 *
 * **Input**: `{ "event_id": <int> }`
 * **Output**: text confirmation.
 */
class DeleteCalendarEventHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.WRITE_CALENDAR, TOOL_NAME)

        val eventId = McpToolUtils.requireLong(arguments, "event_id")
        Log.d(TAG, "Executing delete_calendar_event")

        val deleted =
            withContext(Dispatchers.IO) {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                context.contentResolver.delete(uri, null, null)
            }
        if (deleted == 0) {
            throw McpToolException.NodeNotFound("No calendar event with id $eventId exists")
        }
        return McpToolUtils.textResult(
            "Deleted calendar event $eventId from this device. On an account-synced calendar the " +
                "deletion propagates to the server on the next sync.",
        )
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Deletes one calendar event by its event_id (from list_calendar_events). Use it to " +
                    "cancel an appointment or remove an event the user no longer wants. On a " +
                    "synced calendar the removal is forwarded to the account on the next sync. " +
                    "Requires the WRITE_CALENDAR permission.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("event_id") {
                                put("type", "integer")
                                put("description", "Event id returned by list_calendar_events")
                            }
                        },
                    required = listOf("event_id"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "delete_calendar_event"
        private const val TAG = "MCP:DeleteCalendarEventHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// list_calendars
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_calendars`.
 *
 * **Input**: none.
 * **Output**: JSON array of calendars with their access level.
 */
class ListCalendarsHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.READ_CALENDAR, TOOL_NAME)

        Log.d(TAG, "Executing list_calendars")

        val calendars =
            withContext(Dispatchers.IO) {
                val projection =
                    arrayOf(
                        CalendarContract.Calendars._ID,
                        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                        CalendarContract.Calendars.ACCOUNT_NAME,
                        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                        CalendarContract.Calendars.VISIBLE,
                    )
                val cursor =
                    context.contentResolver.query(
                        CalendarContract.Calendars.CONTENT_URI,
                        projection,
                        null,
                        null,
                        null,
                    )
                if (cursor == null) {
                    throw McpToolException.ActionFailed("The calendar provider is unavailable on this device")
                }
                val found = mutableListOf<JsonObject>()
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val accessLevel = c.intOrZero(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                        found.add(
                            buildJsonObject {
                                put("calendar_id", c.longOrZero(CalendarContract.Calendars._ID))
                                put(
                                    "display_name",
                                    c.stringOrNull(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                                )
                                put("account_name", c.stringOrNull(CalendarContract.Calendars.ACCOUNT_NAME))
                                put("writable", accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
                                put("visible", c.intOrZero(CalendarContract.Calendars.VISIBLE) != 0)
                            },
                        )
                    }
                }
                found
            }

        val json =
            buildJsonObject {
                put("calendar_count", calendars.size)
                put(
                    "calendars",
                    buildJsonArray {
                        calendars.forEach { add(it) }
                    },
                )
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
                "Lists every calendar configured on the device with its id, display name, owning " +
                    "account, whether this app may write to it, and whether it is visible. Use it " +
                    "to pick a 'calendar_id' for create_calendar_event, for example to place an " +
                    "event on a shared family calendar rather than a personal one. Requires the " +
                    "READ_CALENDAR permission.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_calendars"
        private const val TAG = "MCP:ListCalendarsHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// compose_email
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `compose_email`.
 *
 * **Input**: `{ "to": <string>, "subject": <string>?, "body": <string>? }`
 * **Output**: text confirmation.
 */
class ComposeEmailHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val to = McpToolUtils.requireString(arguments, "to").trim()
        if (to.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'to' must not be blank")
        }
        val subject = McpToolUtils.optionalString(arguments, "subject", "")
        val body = McpToolUtils.optionalString(arguments, "body", "")

        Log.d(TAG, "Executing compose_email")

        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = MIME_TYPE_TEXT
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        try {
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
            throw McpToolException.ActionFailed("No e-mail app is installed on this device")
        }

        return McpToolUtils.textResult(
            "Opened the mail composer with recipient '$to'. The message is not sent until the " +
                "user confirms it in the mail app.",
        )
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Opens the device's e-mail app with the recipient, subject and body pre-filled, " +
                    "leaving the final send to the user. Use it to prepare an e-mail for approval " +
                    "rather than to send silently; no permission is required because the mail app " +
                    "performs the send. Reports an error when no mail client is installed.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("to") {
                                put("type", "string")
                                put("description", "Recipient e-mail address")
                            }
                            putJsonObject("subject") {
                                put("type", "string")
                                put("description", "Optional subject line")
                            }
                            putJsonObject("body") {
                                put("type", "string")
                                put("description", "Optional message body")
                            }
                        },
                    required = listOf("to"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "compose_email"
        private const val TAG = "MCP:ComposeEmailHandler"
        private const val MIME_TYPE_TEXT = "text/plain"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Registration and shared helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Registers every communication and personal-data tool enabled by [perms]. */
fun registerCommsTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(SendSmsHandler.TOOL_NAME)) {
        SendSmsHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ListSmsThreadsHandler.TOOL_NAME)) {
        ListSmsThreadsHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ReadSmsThreadHandler.TOOL_NAME)) {
        ReadSmsThreadHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(SearchSmsHandler.TOOL_NAME)) {
        SearchSmsHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ListCallLogHandler.TOOL_NAME)) {
        ListCallLogHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(SearchContactsHandler.TOOL_NAME)) {
        SearchContactsHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(GetContactDetailsHandler.TOOL_NAME)) {
        GetContactDetailsHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(CreateContactHandler.TOOL_NAME)) {
        CreateContactHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ListCalendarEventsHandler.TOOL_NAME)) {
        ListCalendarEventsHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(CreateCalendarEventHandler.TOOL_NAME)) {
        CreateCalendarEventHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(DeleteCalendarEventHandler.TOOL_NAME)) {
        DeleteCalendarEventHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ListCalendarsHandler.TOOL_NAME)) {
        ListCalendarsHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ComposeEmailHandler.TOOL_NAME)) {
        ComposeEmailHandler(context).register(registrar, toolNamePrefix)
    }
}

/** Reads a string column, returning null when the column is missing or SQL NULL. */
private fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getString(index)
}

/** Reads a long column, returning 0 when the column is missing or SQL NULL. */
private fun Cursor.longOrZero(column: String): Long {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) 0L else getLong(index)
}

/** Reads an int column, returning 0 when the column is missing or SQL NULL. */
private fun Cursor.intOrZero(column: String): Int {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) 0 else getInt(index)
}

/** Formats epoch milliseconds as an ISO-8601 UTC instant. */
private fun isoFromMillis(millis: Long): String = Instant.ofEpochMilli(millis).toString()

/** Accepts either an ISO-8601 instant or raw epoch milliseconds. */
private fun parseInstantMillis(
    value: String,
    paramName: String,
): Long =
    value.trim().toLongOrNull() ?: try {
        Instant.parse(value.trim()).toEpochMilli()
    } catch (_: DateTimeParseException) {
        throw McpToolException.InvalidParams(
            "Parameter '$paramName' must be an ISO-8601 instant (for example " +
                "2026-10-15T09:00:00Z) or epoch milliseconds, got: '$value'",
        )
    }

/** Human-readable label for a `CallLog.Calls.TYPE` value. */
private fun callTypeLabel(type: Int): String =
    when (type) {
        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
        CallLog.Calls.MISSED_TYPE -> "MISSED"
        CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
        CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
        else -> "UNKNOWN"
    }

/** Human-readable label for a `Telephony.Sms.TYPE` value. */
private fun smsTypeLabel(type: Int): String =
    when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "INBOX"
        Telephony.Sms.MESSAGE_TYPE_SENT -> "SENT"
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "DRAFT"
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "OUTBOX"
        Telephony.Sms.MESSAGE_TYPE_FAILED -> "FAILED"
        Telephony.Sms.MESSAGE_TYPE_QUEUED -> "QUEUED"
        else -> "UNKNOWN"
    }

private const val DEFAULT_THREAD_LIMIT = 20
private const val MAX_THREAD_LIMIT = 200
private const val DEFAULT_MESSAGE_LIMIT = 50
private const val MAX_MESSAGE_LIMIT = 500
private const val DEFAULT_CALL_LOG_LIMIT = 50
private const val MAX_CALL_LOG_LIMIT = 500
private const val DEFAULT_CONTACT_LIMIT = 25
private const val MAX_CONTACT_LIMIT = 200
private const val DEFAULT_EVENT_LIMIT = 50
private const val MAX_EVENT_LIMIT = 500
private const val DEFAULT_EVENT_WINDOW_DAYS = 7
private const val MAX_EVENT_WINDOW_DAYS = 365
private const val DEFAULT_EVENT_DURATION_MINUTES = 60
private const val MAX_EVENT_DURATION_MINUTES = 43200
private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_DAY = 86_400_000L
private const val MAX_SCAN_ROWS = 2000
