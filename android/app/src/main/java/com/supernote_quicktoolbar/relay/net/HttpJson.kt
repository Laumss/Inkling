package com.supernote_quicktoolbar.relay.net

import android.util.Log
import org.json.JSONObject

/**
 * Minimal JSON REST helpers for the endpoints we talk to.
 *
 * Backed by [RawHttp] rather than HttpURLConnection: the plugin runs inside
 * PluginHost, whose network security policy forbids cleartext HTTP. See the
 * comment at the top of [RawHttp] for the full story.
 */
object HttpJson {
    private const val TAG = "HttpJson"

    /**
     * Bearer token attached to every request when set (JWT-protected servers).
     * SSE connections opened outside this object should use [sseUrl] instead,
     * since the server also accepts the token as an access_token query param.
     */
    @Volatile var authToken: String? = null

    /** Append the auth token as a query parameter (for SSE, where headers are awkward to retry). */
    fun sseUrl(url: String): String {
        val token = authToken ?: return url
        val sep = if ("?" in url) "&" else "?"
        return "$url${sep}access_token=$token"
    }

    /** [extra] plus the bearer header when a token is set. */
    fun authHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val token = authToken ?: return extra
        return extra + ("Authorization" to "Bearer $token")
    }

    /** "host" → "http://host:defaultPort"; full URLs pass through unchanged. */
    fun normalizeBaseUrl(host: String, defaultPort: Int): String {
        val h = host.trim()
        return if (h.startsWith("http")) h else "http://$h:$defaultPort"
    }

    /** GET [url]; returns the body on 2xx, null otherwise. Throws on I/O errors. */
    fun get(url: String, timeoutMs: Int = 5_000): String? =
        RawHttp.request(
            method = "GET",
            url = url,
            headers = authHeaders(),
            connectTimeoutMs = timeoutMs,
            readTimeoutMs = timeoutMs,
        ).use { resp ->
            if (resp.isSuccess) {
                resp.body.readBytes().toString(Charsets.UTF_8)
            } else {
                Log.w(TAG, "GET $url → ${resp.status}")
                null
            }
        }

    /** GET [url] and parse as JSON object; null on non-2xx, parse error, or I/O error. */
    fun getJsonOrNull(url: String, timeoutMs: Int = 5_000): JSONObject? =
        try {
            get(url, timeoutMs)?.let { JSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            null
        }

    /** DELETE [url]; true on 2xx. Never throws. */
    fun delete(url: String, timeoutMs: Int = 5_000): Boolean =
        try {
            RawHttp.request(
                method = "DELETE",
                url = url,
                headers = authHeaders(),
                connectTimeoutMs = timeoutMs,
                readTimeoutMs = timeoutMs,
            ).use { it.isSuccess }
        } catch (e: Exception) {
            Log.w(TAG, "DELETE $url failed: ${e.message}")
            false
        }

    /** POST [body] as JSON; returns response body (2xx or error body). Throws on I/O errors. */
    fun postJson(url: String, body: JSONObject, timeoutMs: Int = 30_000): String =
        post(
            url = url,
            body = body.toString().toByteArray(Charsets.UTF_8),
            contentType = "application/json",
            timeoutMs = timeoutMs,
        )

    /**
     * POST raw [body]. Returns the response text for any status; the caller
     * decides what a non-2xx body means (some endpoints answer with JSON error
     * details we want to surface).
     */
    fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        timeoutMs: Int = 30_000,
    ): String =
        RawHttp.request(
            method = "POST",
            url = url,
            headers = authHeaders(mapOf("Content-Type" to contentType)),
            body = body,
            // A slow model can take a while to answer; the connect phase should
            // still fail fast when the phone is simply gone.
            connectTimeoutMs = minOf(timeoutMs, 10_000),
            readTimeoutMs = timeoutMs,
        ).use { resp ->
            val text = resp.body.readBytes().toString(Charsets.UTF_8)
            if (!resp.isSuccess) {
                Log.w(TAG, "POST $url → ${resp.status}: ${text.take(200)}")
            }
            text
        }
}
