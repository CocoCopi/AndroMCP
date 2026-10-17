package com.cococopi.andromcp.agent.llm

import com.cococopi.andromcp.agent.LlmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal POST seam so provider clients can be tested without a network and swapped for a Ktor or
 * OkHttp implementation later. Implementations MUST throw [LlmException] for any non-2xx status and
 * return the raw response body otherwise.
 */
fun interface HttpTransport {
    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): String
}

/**
 * [HttpTransport] over [HttpURLConnection]: no extra dependency, works on Android, and keeps the
 * blocking socket work off the caller's thread.
 *
 * Provider failures are surfaced as [LlmException] with the status code and a short body excerpt;
 * the excerpt is capped so a huge HTML error page cannot flood the chat, and request headers (which
 * carry the API key) are never included in the message.
 */
class UrlConnectionTransport(
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : HttpTransport {
    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): String =
        withContext(Dispatchers.IO) {
            val connection =
                try {
                    URL(url).openConnection() as HttpURLConnection
                } catch (e: IOException) {
                    throw LlmException("Could not reach $url: ${e.message}", e)
                }

            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

                connection.outputStream.use { it.write(body.toByteArray()) }

                val status = connection.responseCode
                val stream = if (status >= HTTP_ERROR_THRESHOLD) connection.errorStream else connection.inputStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (status >= HTTP_ERROR_THRESHOLD) {
                    throw LlmException(
                        "The model provider returned HTTP $status: ${text.take(ERROR_EXCERPT_CHARS).trim()}",
                    )
                }
                text
            } catch (e: IOException) {
                throw LlmException("Network error talking to $url: ${e.message}", e)
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        /** Generous enough for a long tool-using completion, short enough to fail visibly. */
        const val DEFAULT_TIMEOUT_MS = 180_000
        private const val HTTP_ERROR_THRESHOLD = 400
        private const val ERROR_EXCERPT_CHARS = 400
    }
}
