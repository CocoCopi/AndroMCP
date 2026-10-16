@file:Suppress("MatchingDeclarationName")

package com.cococopi.andromcp.mcp.tools

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.cococopi.andromcp.data.model.ToolPermissionsConfig
import com.cococopi.andromcp.mcp.McpToolException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

// Network and connectivity tool bundle.
//
// Turns the phone into a network probe and an HTTP client the agent can drive: arbitrary REST
// calls, connectivity and interface inspection, DNS resolution, reachability tests, TCP port
// checks and Wi-Fi scanning. Nothing here shells out or needs root — every tool uses public
// Android/Java networking APIs, and the Wi-Fi tools gate themselves on the location permission
// Android requires before it will reveal network identifiers.
//
// Responses that describe the surrounding network (scan results, HTTP bodies, host names) are
// external, untrusted input and are returned with the anti-prompt-injection warning.

// ─────────────────────────────────────────────────────────────────────────────
// http_request
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `http_request`.
 *
 * **Input**: `{ "url": <string>, "method": <string>?, "headers": <object>?, "body": <string>?,
 * "follow_redirects": <bool>?, "timeout_ms": <int>?, "max_response_chars": <int>? }`
 * **Output**: JSON object with status code, response headers and (possibly truncated) body.
 */
class HttpRequestHandler {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val uri = parseHttpUri(McpToolUtils.requireString(arguments, "url"))
        val method = McpToolUtils.optionalString(arguments, "method", DEFAULT_HTTP_METHOD).uppercase()
        if (method !in ALLOWED_HTTP_METHODS) {
            throw McpToolException.InvalidParams(
                "Parameter 'method' must be one of ${ALLOWED_HTTP_METHODS.joinToString(", ")}, got: '$method'",
            )
        }
        val body = McpToolUtils.optionalString(arguments, "body", "")
        val followRedirects = McpToolUtils.optionalBoolean(arguments, "follow_redirects", true)
        val maxChars = McpToolUtils.optionalInt(arguments, "max_response_chars", DEFAULT_MAX_RESPONSE_CHARS)
        McpToolUtils.validatePositiveRange(
            maxChars.toLong(),
            "max_response_chars",
            MAX_MAX_RESPONSE_CHARS.toLong(),
        )
        val timeoutMs = McpToolUtils.optionalLong(arguments, "timeout_ms", DEFAULT_HTTP_TIMEOUT_MS)
        McpToolUtils.validatePositiveRange(timeoutMs, "timeout_ms", MAX_HTTP_TIMEOUT_MS)
        val headers = headerMap(arguments)

        Log.d(TAG, "Executing http_request ($method)")

        val response =
            withContext(Dispatchers.IO) {
                executeRequest(
                    uri = uri,
                    method = method,
                    headers = headers,
                    body = body,
                    followRedirects = followRedirects,
                    timeoutMs = timeoutMs.toInt(),
                    maxChars = maxChars,
                )
            }
        return McpToolUtils.untrustedTextResult(response.toString())
    }

    private fun executeRequest(
        uri: URI,
        method: String,
        headers: Map<String, String>,
        body: String,
        followRedirects: Boolean,
        timeoutMs: Int,
        maxChars: Int,
    ): JsonObject {
        val connection =
            try {
                uri.toURL().openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw McpToolException.ActionFailed("Could not open a connection to $uri: ${e.message}")
            }

        return try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = followRedirects
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            if (body.isNotEmpty() && method !in BODYLESS_HTTP_METHODS) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }

            val status = connection.responseCode
            val stream = if (status >= HTTP_ERROR_THRESHOLD) connection.errorStream else connection.inputStream
            val (text, truncated) = readCapped(stream, maxChars)

            buildJsonObject {
                put("method", method)
                put("url", connection.url.toString())
                put("status_code", status)
                put(
                    "headers",
                    buildJsonObject {
                        connection.headerFields.forEach { (name, values) ->
                            if (name != null) put(name, values.joinToString(", "))
                        }
                    },
                )
                put("body_truncated", truncated)
                put("body", text)
            }
        } catch (e: IOException) {
            throw McpToolException.ActionFailed("HTTP request to $uri failed: ${e.message}")
        } finally {
            connection.disconnect()
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
                "Performs an HTTP or HTTPS request from this device and returns the status code, " +
                    "the response headers and the response body. Use it to call a REST API, post a " +
                    "webhook, fetch a page, or download JSON into the conversation. The phone's own " +
                    "network identity and cookies are not reused between calls. 'method' defaults " +
                    "to GET and accepts GET, POST, PUT, DELETE, HEAD or OPTIONS; 'headers' is an " +
                    "object of request headers; 'body' is sent as the raw request body for methods " +
                    "that carry one. 'max_response_chars' (default 20000, max 200000) bounds the " +
                    "body returned and 'timeout_ms' (default 30000, max 120000) bounds connect and " +
                    "read timeouts. Requires the INTERNET permission, which the app already holds.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("url") {
                                put("type", "string")
                                put("description", "Absolute http:// or https:// URL to request")
                            }
                            putJsonObject("method") {
                                put("type", "string")
                                put("description", "HTTP method: GET (default), POST, PUT, DELETE, HEAD or OPTIONS")
                            }
                            putJsonObject("headers") {
                                put("type", "object")
                                put("description", "Optional map of request header names to values")
                            }
                            putJsonObject("body") {
                                put("type", "string")
                                put("description", "Optional raw request body sent with the request")
                            }
                            putJsonObject("follow_redirects") {
                                put("type", "boolean")
                                put("description", "Whether to follow HTTP redirects automatically (default true)")
                            }
                            putJsonObject("timeout_ms") {
                                put("type", "integer")
                                put("description", "Connect/read timeout in ms (default 30000, max 120000)")
                            }
                            putJsonObject("max_response_chars") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Maximum number of response body characters to return " +
                                        "(default 20000, max 200000)",
                                )
                            }
                        },
                    required = listOf("url"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "http_request"
        private const val TAG = "MCP:HttpRequestHandler"
        private const val DEFAULT_HTTP_METHOD = "GET"
        private const val HTTP_ERROR_THRESHOLD = 400
        private val ALLOWED_HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS")
        private val BODYLESS_HTTP_METHODS = setOf("GET", "HEAD")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_network_info
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_network_info`.
 *
 * **Input**: none.
 * **Output**: JSON object describing the active network.
 */
class GetNetworkInfoHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_network_info")
        val info = withContext(Dispatchers.IO) { describeActiveNetwork() }
        return McpToolUtils.untrustedTextResult(info.toString())
    }

    private fun describeActiveNetwork(): JsonObject {
        val manager =
            context.getSystemService(ConnectivityManager::class.java)
                ?: throw McpToolException.ActionFailed("Connectivity services are unavailable on this device")
        val network =
            manager.activeNetwork
                ?: return buildJsonObject { put("connected", false) }
        val capabilities = manager.getNetworkCapabilities(network)
        val linkProperties = manager.getLinkProperties(network)

        return buildJsonObject {
            put("connected", true)
            put("transport", transportLabel(capabilities))
            put(
                "metered",
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
            )
            put(
                "validated",
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            )
            put(
                "has_internet_capability",
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            )
            put(
                "not_vpn",
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true,
            )
            put("interface_name", linkProperties?.interfaceName)
            put("mtu", linkProperties?.mtu)
            put("downstream_kbps", capabilities?.linkDownstreamBandwidthKbps)
            put("upstream_kbps", capabilities?.linkUpstreamBandwidthKbps)
            putJsonArray("ip_addresses") {
                linkProperties?.linkAddresses?.forEach { address ->
                    add(
                        buildJsonObject {
                            put("address", address.address.hostAddress)
                            put("prefix_length", address.prefixLength)
                        },
                    )
                }
            }
            putJsonArray("dns_servers") {
                linkProperties?.dnsServers?.forEach { server -> add(server.hostAddress ?: "") }
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
                "Reports how this device is currently connected: the transport in use (WIFI, " +
                    "CELLULAR, ETHERNET, VPN, BLUETOOTH or USB), whether the connection is metered " +
                    "or validated for internet access, the interface name, MTU, upstream and " +
                    "downstream bandwidth estimates, the device's IP addresses with prefix lengths, " +
                    "and the DNS servers in use. Use it to diagnose why something is unreachable or " +
                    "to report the device's local IP address. No permission beyond the app's " +
                    "existing ACCESS_NETWORK_STATE grant is required.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_network_info"
        private const val TAG = "MCP:GetNetworkInfoHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_public_ip
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_public_ip`.
 *
 * **Input**: `{ "endpoint": <string>?, "timeout_ms": <int>? }`
 * **Output**: JSON object with the address the endpoint reported.
 */
class GetPublicIpHandler {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val uri = parseHttpUri(McpToolUtils.optionalString(arguments, "endpoint", DEFAULT_PUBLIC_IP_ENDPOINT))
        val timeoutMs = McpToolUtils.optionalLong(arguments, "timeout_ms", DEFAULT_HTTP_TIMEOUT_MS)
        McpToolUtils.validatePositiveRange(timeoutMs, "timeout_ms", MAX_HTTP_TIMEOUT_MS)

        Log.d(TAG, "Executing get_public_ip")

        val answer =
            withContext(Dispatchers.IO) {
                val connection =
                    try {
                        uri.toURL().openConnection() as HttpURLConnection
                    } catch (e: IOException) {
                        throw McpToolException.ActionFailed("Could not reach $uri: ${e.message}")
                    }
                try {
                    connection.connectTimeout = timeoutMs.toInt()
                    connection.readTimeout = timeoutMs.toInt()
                    connection.requestMethod = "GET"
                    val status = connection.responseCode
                    val stream = if (status == HTTP_OK) connection.inputStream else connection.errorStream
                    val body = readCapped(stream, PUBLIC_IP_MAX_CHARS).first.trim()
                    if (status != HTTP_OK) {
                        throw McpToolException.ActionFailed(
                            "The public-IP endpoint answered with HTTP $status",
                        )
                    }
                    body
                } catch (e: IOException) {
                    throw McpToolException.ActionFailed("Could not reach $uri: ${e.message}")
                } finally {
                    connection.disconnect()
                }
            }

        return McpToolUtils.untrustedTextResult(
            buildJsonObject {
                put("public_ip", answer)
                put("endpoint", uri.toString())
            }.toString(),
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
                "Reports the public (external) IP address of this device's internet connection by " +
                    "querying an external echo service. The request necessarily leaves the device " +
                    "and the chosen endpoint learns the address it answered with. 'endpoint' " +
                    "overrides the plain-text IP echo service used by default; anything returning " +
                    "the address as the response body works. 'timeout_ms' defaults to 30000 and " +
                    "caps at 120000. Fails when the device has no working internet route.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("endpoint") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional http(s) URL of a plain-text public-IP echo service",
                                )
                            }
                            putJsonObject("timeout_ms") {
                                put("type", "integer")
                                put("description", "Connect/read timeout in ms (default 30000, max 120000)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_public_ip"
        private const val TAG = "MCP:GetPublicIpHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// dns_lookup
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `dns_lookup`.
 *
 * **Input**: `{ "host": <string> }`
 * **Output**: JSON object with the resolved addresses.
 */
class DnsLookupHandler {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val host = McpToolUtils.requireString(arguments, "host").trim()
        if (host.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'host' must not be blank")
        }
        Log.d(TAG, "Executing dns_lookup")

        val addresses =
            withContext(Dispatchers.IO) {
                try {
                    InetAddress.getAllByName(host).toList()
                } catch (e: UnknownHostException) {
                    throw McpToolException.NodeNotFound("Host '$host' could not be resolved: ${e.message}")
                }
            }

        val json =
            buildJsonObject {
                put("host", host)
                put("canonical_host_name", addresses.firstOrNull()?.canonicalHostName)
                putJsonArray("addresses") {
                    addresses.forEach { address ->
                        add(
                            buildJsonObject {
                                put("address", address.hostAddress)
                                put("ip_version", if (address is java.net.Inet6Address) 6 else 4)
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
                "Resolves a host name into its IP addresses using the device's DNS resolvers, " +
                    "returning every A and AAAA record the system resolver reports together with " +
                    "the canonical host name. Use it to check that a host resolves, to see whether " +
                    "a name points at IPv4, IPv6 or both, or to obtain an IP before a lower-level " +
                    "probe. Fails with a not-found error when resolution yields nothing.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("host") {
                                put("type", "string")
                                put("description", "Host name to resolve, for example example.com")
                            }
                        },
                    required = listOf("host"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "dns_lookup"
        private const val TAG = "MCP:DnsLookupHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ping_host
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `ping_host`.
 *
 * **Input**: `{ "host": <string>, "timeout_ms": <int>? }`
 * **Output**: JSON object with reachability and measured round-trip time.
 */
class PingHostHandler {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val host = McpToolUtils.requireString(arguments, "host").trim()
        if (host.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'host' must not be blank")
        }
        val timeoutMs = McpToolUtils.optionalLong(arguments, "timeout_ms", DEFAULT_PING_TIMEOUT_MS)
        McpToolUtils.validatePositiveRange(timeoutMs, "timeout_ms", MAX_PING_TIMEOUT_MS)

        Log.d(TAG, "Executing ping_host")

        val probe =
            withContext(Dispatchers.IO) {
                val address =
                    try {
                        InetAddress.getByName(host)
                    } catch (e: UnknownHostException) {
                        throw McpToolException.NodeNotFound("Host '$host' could not be resolved: ${e.message}")
                    }
                val startedNs = System.nanoTime()
                val reachable =
                    try {
                        address.isReachable(timeoutMs.toInt())
                    } catch (e: IOException) {
                        throw McpToolException.ActionFailed("Probe of '$host' failed: ${e.message}")
                    }
                Triple(address.hostAddress, reachable, (System.nanoTime() - startedNs) / NANOS_PER_MILLI)
            }

        val json =
            buildJsonObject {
                put("host", host)
                put("resolved_address", probe.first)
                put("reachable", probe.second)
                put("elapsed_ms", probe.third)
                put("timeout_ms", timeoutMs)
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
                "Checks whether a host answers, resolving the name first and measuring how long the " +
                    "probe took. This is a reachability test rather than a strict ICMP ping: it uses " +
                    "the platform's echo mechanism, so a host that silently drops probes can report " +
                    "as unreachable even while its web service works — pair it with check_tcp_port " +
                    "when that distinction matters. 'timeout_ms' (default 5000, max 30000) bounds how " +
                    "long the probe waits. Fails with a not-found error when the name does not resolve.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("host") {
                                put("type", "string")
                                put("description", "Host name or IP address to probe")
                            }
                            putJsonObject("timeout_ms") {
                                put("type", "integer")
                                put("description", "Probe timeout in milliseconds (default 5000, max 30000)")
                            }
                        },
                    required = listOf("host"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "ping_host"
        private const val TAG = "MCP:PingHostHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// check_tcp_port
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `check_tcp_port`.
 *
 * **Input**: `{ "host": <string>, "port": <int>, "timeout_ms": <int>? }`
 * **Output**: JSON object with the connection result and latency.
 */
class CheckTcpPortHandler {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val host = McpToolUtils.requireString(arguments, "host").trim()
        if (host.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'host' must not be blank")
        }
        val port = McpToolUtils.requireInt(arguments, "port")
        if (port < MIN_PORT || port > MAX_PORT) {
            throw McpToolException.InvalidParams("Parameter 'port' must be between $MIN_PORT and $MAX_PORT")
        }
        val timeoutMs = McpToolUtils.optionalLong(arguments, "timeout_ms", DEFAULT_TCP_TIMEOUT_MS)
        McpToolUtils.validatePositiveRange(timeoutMs, "timeout_ms", MAX_TCP_TIMEOUT_MS)

        Log.d(TAG, "Executing check_tcp_port")

        val probe =
            withContext(Dispatchers.IO) {
                val socket = Socket()
                val startedNs = System.nanoTime()
                val error =
                    try {
                        socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                        null
                    } catch (e: IOException) {
                        e.message ?: e::class.java.simpleName
                    } finally {
                        runCatching { socket.close() }
                    }
                Triple(error, (System.nanoTime() - startedNs) / NANOS_PER_MILLI, socket.inetAddress?.hostAddress)
            }

        val json =
            buildJsonObject {
                put("host", host)
                put("port", port)
                put("open", probe.first == null)
                put("elapsed_ms", probe.second)
                put("resolved_address", probe.third)
                probe.first?.let { put("error", it) }
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
                "Opens a TCP connection to a host and port to find out whether something is " +
                    "listening there, reporting the result plus how long the handshake took. Use it " +
                    "to verify that a service the user is running is reachable from the phone, to " +
                    "locate a server by trying common ports, or to tell an open port apart from a " +
                    "firewalled one. A refused connection is reported as 'open': false with the " +
                    "reason, not as a tool error. 'timeout_ms' (default 5000, max 30000) bounds the " +
                    "connect attempt, and 'port' must be between $MIN_PORT and $MAX_PORT.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("host") {
                                put("type", "string")
                                put("description", "Host name or IP address to connect to")
                            }
                            putJsonObject("port") {
                                put("type", "integer")
                                put("description", "TCP port to test, between $MIN_PORT and $MAX_PORT")
                            }
                            putJsonObject("timeout_ms") {
                                put("type", "integer")
                                put("description", "Connect timeout in milliseconds (default 5000, max 30000)")
                            }
                        },
                    required = listOf("host", "port"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "check_tcp_port"
        private const val TAG = "MCP:CheckTcpPortHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// list_wifi_networks
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_wifi_networks`.
 *
 * **Input**: `{ "limit": <int>? }`
 * **Output**: JSON array of nearby Wi-Fi networks, strongest first.
 */
class ListWifiNetworksHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.ACCESS_FINE_LOCATION, TOOL_NAME)

        val limit = McpToolUtils.optionalInt(arguments, "limit", DEFAULT_SCAN_LIMIT)
        McpToolUtils.validatePositiveRange(limit.toLong(), "limit", MAX_SCAN_LIMIT.toLong())

        Log.d(TAG, "Executing list_wifi_networks")

        val networks =
            withContext(Dispatchers.IO) {
                val wifiManager =
                    context.applicationContext.getSystemService(WifiManager::class.java)
                        ?: throw McpToolException.ActionFailed("Wi-Fi is unavailable on this device")
                wifiManager.scanResults
                    .sortedByDescending { it.level }
                    .take(limit)
                    .map { scan ->
                        buildJsonObject {
                            put("ssid", scan.SSID)
                            put("bssid", scan.BSSID)
                            put("rssi_dbm", scan.level)
                            put("frequency_mhz", scan.frequency)
                            put("capabilities", scan.capabilities)
                            put("age_ms", System.currentTimeMillis() - scan.timestamp)
                        }
                    }
            }

        val json =
            buildJsonObject {
                put("network_count", networks.size)
                putJsonArray("networks") { networks.forEach { add(it) } }
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
                "Lists the Wi-Fi networks this device can currently see, strongest signal first, " +
                    "with SSID, BSSID, signal strength in dBm, frequency in MHz, the security " +
                    "capabilities string, and how old the cached scan result is. Android throttles " +
                    "background scans, so results may be a few minutes old; the reported age tells " +
                    "you by how much. Wi-Fi network names are location-revealing, so this tool " +
                    "requires the ACCESS_FINE_LOCATION permission, which is also what makes Android " +
                    "return real SSIDs instead of redacting them. 'limit' (default 30, max 200) caps " +
                    "the results.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum number of networks to return (default 30, max 200)")
                            }
                        },
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_wifi_networks"
        private const val TAG = "MCP:ListWifiNetworksHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_wifi_info
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_wifi_info`.
 *
 * **Input**: none.
 * **Output**: JSON object describing the Wi-Fi network the device is joined to.
 */
class GetWifiInfoHandler(
    private val context: Context,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        RuntimePermissionGate.require(context, Manifest.permission.ACCESS_FINE_LOCATION, TOOL_NAME)

        Log.d(TAG, "Executing get_wifi_info")
        val info = withContext(Dispatchers.IO) { describeWifi() }
        return McpToolUtils.untrustedTextResult(info.toString())
    }

    private fun describeWifi(): JsonObject {
        val manager =
            context.getSystemService(ConnectivityManager::class.java)
                ?: throw McpToolException.ActionFailed("Connectivity services are unavailable on this device")
        val network = manager.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return buildJsonObject {
                put("connected", false)
                put("reason", "The active network is not Wi-Fi")
            }
        }

        val wifiInfo = capabilities.transportInfo as? WifiInfo
        if (wifiInfo == null) {
            return buildJsonObject {
                put("connected", false)
                put("reason", "The platform did not expose Wi-Fi details for the active network")
            }
        }

        return buildJsonObject {
            put("connected", true)
            put("ssid", wifiInfo.getSSID())
            put("bssid", wifiInfo.getBSSID())
            put("rssi_dbm", wifiInfo.getRssi())
            put("link_speed_mbps", wifiInfo.getLinkSpeed())
            put("rx_link_speed_mbps", wifiInfo.getRxLinkSpeedMbps())
            put("tx_link_speed_mbps", wifiInfo.getTxLinkSpeedMbps())
            put("frequency_mhz", wifiInfo.getFrequency())
            put("max_rx_link_speed_mbps", wifiInfo.getMaxSupportedRxLinkSpeedMbps())
            put("max_tx_link_speed_mbps", wifiInfo.getMaxSupportedTxLinkSpeedMbps())
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
                "Reports the Wi-Fi network the device is currently joined to: SSID, BSSID, signal " +
                    "strength in dBm, negotiated link speed and the current and maximum receive and " +
                    "transmit link rates, and the frequency band in MHz. Use it to check signal " +
                    "quality or to confirm which access point the phone is on. Returns " +
                    "'connected': false when the active connection is not Wi-Fi. Requires the " +
                    "ACCESS_FINE_LOCATION permission, which is what lets Android reveal the SSID " +
                    "and BSSID instead of redacting them.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_wifi_info"
        private const val TAG = "MCP:GetWifiInfoHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// get_network_interfaces
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_network_interfaces`.
 *
 * **Input**: none.
 * **Output**: JSON array of the device's network interfaces.
 */
class GetNetworkInterfacesHandler {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing get_network_interfaces")
        val interfaces = withContext(Dispatchers.IO) { describeInterfaces() }
        val json =
            buildJsonObject {
                put("interface_count", interfaces.size)
                putJsonArray("interfaces") { interfaces.forEach { add(it) } }
            }
        return McpToolUtils.untrustedTextResult(json.toString())
    }

    private fun describeInterfaces(): List<JsonObject> {
        val enumeration =
            try {
                NetworkInterface.getNetworkInterfaces()
            } catch (e: IOException) {
                throw McpToolException.ActionFailed("Network interfaces are unavailable: ${e.message}")
            }
        if (enumeration == null) return emptyList()

        return enumeration.toList().map { networkInterface ->
            buildJsonObject {
                put("name", networkInterface.name)
                put("display_name", networkInterface.displayName)
                put("up", networkInterface.isUp)
                put("loopback", networkInterface.isLoopback)
                put("virtual", networkInterface.isVirtual)
                put("point_to_point", networkInterface.isPointToPoint)
                put("mtu", runCatching { networkInterface.mtu }.getOrDefault(0))
                put("hardware_address", formatMac(networkInterface.hardwareAddress))
                putJsonArray("addresses") {
                    networkInterface.inetAddresses.toList().forEach { address ->
                        add(
                            buildJsonObject {
                                put("address", address.hostAddress)
                                put("ip_version", if (address is java.net.Inet6Address) 6 else 4)
                            },
                        )
                    }
                }
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
                "Lists every network interface on the device — Wi-Fi, mobile data, the VPN tunnel, " +
                    "loopback and so on — with its name, whether it is up, its MTU, its MAC address " +
                    "and all of its IPv4 and IPv6 addresses. Use it for low-level network diagnosis, " +
                    "to find the VPN's virtual interface, or to read the device's MAC addresses. " +
                    "MAC addresses of Wi-Fi interfaces are randomised per network on modern Android, " +
                    "so the reported value is a per-network address rather than a hardware serial.",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "get_network_interfaces"
        private const val TAG = "MCP:GetNetworkInterfacesHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Registration and shared helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Registers every network and connectivity tool enabled by [perms]. */
fun registerNetworkTools(
    registrar: LoggedToolRegistrar,
    context: Context,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(HttpRequestHandler.TOOL_NAME)) {
        HttpRequestHandler().register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(GetNetworkInfoHandler.TOOL_NAME)) {
        GetNetworkInfoHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(GetPublicIpHandler.TOOL_NAME)) {
        GetPublicIpHandler().register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(DnsLookupHandler.TOOL_NAME)) {
        DnsLookupHandler().register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(PingHostHandler.TOOL_NAME)) {
        PingHostHandler().register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(CheckTcpPortHandler.TOOL_NAME)) {
        CheckTcpPortHandler().register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ListWifiNetworksHandler.TOOL_NAME)) {
        ListWifiNetworksHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(GetWifiInfoHandler.TOOL_NAME)) {
        GetWifiInfoHandler(context).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(GetNetworkInterfacesHandler.TOOL_NAME)) {
        GetNetworkInterfacesHandler().register(registrar, toolNamePrefix)
    }
}

/** Validates an http/https URL and rejects everything else. */
private fun parseHttpUri(url: String): URI {
    val uri =
        try {
            URI(url.trim())
        } catch (_: URISyntaxException) {
            throw McpToolException.InvalidParams("Parameter 'url' is not a valid URI: '$url'")
        }
    val scheme = uri.scheme?.lowercase()
    if (scheme != SCHEME_HTTP && scheme != SCHEME_HTTPS) {
        throw McpToolException.InvalidParams(
            "Parameter 'url' must use the http or https scheme, got: '$url'",
        )
    }
    if (uri.host.isNullOrBlank()) {
        throw McpToolException.InvalidParams("Parameter 'url' must include a host, got: '$url'")
    }
    return uri
}

/** Extracts the optional `headers` object into a plain map of strings. */
private fun headerMap(arguments: JsonObject?): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    (arguments?.get("headers") as? JsonObject)?.forEach { (name, value) ->
        headers[name] = (value as? JsonPrimitive)?.content ?: value.toString()
    }
    return headers
}

/**
 * Reads at most [maxChars] characters from [stream], reporting whether more remained so the
 * caller can tell a complete body from a truncated one.
 */
private fun readCapped(
    stream: InputStream?,
    maxChars: Int,
): Pair<String, Boolean> {
    if (stream == null) return "" to false
    val builder = StringBuilder()
    val buffer = CharArray(READ_CHUNK_CHARS)
    stream.bufferedReader().use { reader ->
        while (builder.length <= maxChars) {
            val read = reader.read(buffer)
            if (read < 0) break
            builder.append(buffer, 0, read)
        }
    }
    val truncated = builder.length > maxChars
    return builder.toString().take(maxChars) to truncated
}

/** Human-readable label for the active transport reported by [NetworkCapabilities]. */
private fun transportLabel(capabilities: NetworkCapabilities?): String =
    when {
        capabilities == null -> "UNKNOWN"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "USB"
        else -> "OTHER"
    }

/** Formats a raw MAC address, or null when the interface does not expose one. */
private fun formatMac(address: ByteArray?): String? = address?.joinToString(":") { byte -> "%02X".format(byte) }

private const val SCHEME_HTTP = "http"
private const val SCHEME_HTTPS = "https"
private const val HTTP_OK = 200
private const val DEFAULT_HTTP_TIMEOUT_MS = 30_000L
private const val MAX_HTTP_TIMEOUT_MS = 120_000L
private const val DEFAULT_MAX_RESPONSE_CHARS = 20_000
private const val MAX_MAX_RESPONSE_CHARS = 200_000
private const val READ_CHUNK_CHARS = 8_192
private const val NANOS_PER_MILLI = 1_000_000L
private const val DEFAULT_PUBLIC_IP_ENDPOINT = "https://api.ipify.org"
private const val PUBLIC_IP_MAX_CHARS = 128
private const val DEFAULT_PING_TIMEOUT_MS = 5_000L
private const val MAX_PING_TIMEOUT_MS = 30_000L
private const val DEFAULT_TCP_TIMEOUT_MS = 5_000L
private const val MAX_TCP_TIMEOUT_MS = 30_000L
private const val MIN_PORT = 1
private const val MAX_PORT = 65_535
private const val DEFAULT_SCAN_LIMIT = 30
private const val MAX_SCAN_LIMIT = 200
