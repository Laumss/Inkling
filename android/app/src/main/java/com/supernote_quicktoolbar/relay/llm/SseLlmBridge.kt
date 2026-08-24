package com.supernote_quicktoolbar.relay.llm

import android.util.Log
import com.supernote_quicktoolbar.relay.net.HttpJson
import com.supernote_quicktoolbar.relay.net.RawHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [LlmBridge] implementation for conversation-style LLM web servers speaking
 * REST + SSE:
 *   - POST /api/conversations                  → create conversation
 *   - POST /api/conversations/{id}/messages    → send user message (triggers completion)
 *   - GET  /api/conversations/{id}/stream      → SSE conversation updates (streamed reply)
 *   - GET  /api/conversations/active           → session the user has on screen
 *   - GET  /api/conversations/stream           → global invalidate events
 *
 * Also follows the conversation currently open on the host device, so replies
 * to manually typed messages flow through the same callbacks.
 */
class SseLlmBridge(
    private val baseUrl: String,  // e.g. "http://192.168.1.2:8080"
    private val followActive: Boolean = true,
) : LlmBridge {
    companion object {
        private const val TAG = "SseLlmBridge"
        const val DEFAULT_PORT = 8080
        private const val SSE_RECONNECT_DELAY_MS = 3000L
        private const val HTTP_TIMEOUT_MS = 30_000
        private const val SSE_READ_TIMEOUT_MS = 90_000
        /** prefill-branches blocks until all branch completions finish. */
        private const val PREFILL_TIMEOUT_MS = 300_000
    }

    override var onStreamToken: ((String) -> Unit)? = null
    override var onReplyComplete: ((String) -> Unit)? = null
    override var onConnectionChanged: ((Boolean) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private val currentConversationId = AtomicReference<String?>(null)
    private var sseThread: Thread? = null
    private var globalStreamThread: Thread? = null

    /**
     * Live SSE responses, kept so they can be force-closed.
     *
     * Thread.interrupt() does NOT unblock a read on a java.net.Socket (it did
     * abort HttpURLConnection, which this used to rely on). Closing the socket
     * is the only way to break a reader out of a blocking read, so a
     * conversation switch or stop() must close the response rather than just
     * interrupting the thread — otherwise the old stream lingers for up to
     * SSE_READ_TIMEOUT_MS and keeps delivering replies from the previous
     * conversation.
     */
    private val activeSse = AtomicReference<RawHttp.Response?>(null)
    private val activeGlobalSse = AtomicReference<RawHttp.Response?>(null)

    @Volatile override var isConnected = false; private set

    // ── Reply extraction state ──────────────────────────────────────────
    @Volatile private var lastKnownAssistantText = ""
    @Volatile private var wasGenerating = false
    /**
     * Right after an SSE connection is established the server pushes a
     * snapshot containing the full history. This flag skips delivering that
     * initial snapshot — it is prior state, not a new reply. Reset to true at
     * the start of every connectSse().
     */
    @Volatile private var isInitialSseSnapshot = true
    /**
     * Last text actually delivered to [onReplyComplete]. Whatever code path
     * fires the callback compares against this first, so the same reply is
     * never forwarded twice.
     */
    @Volatile private var lastDeliveredAssistantText = ""
    /**
     * Whether the current sendUserMessage round has already delivered.
     * Prevents non-assistant node updates from re-triggering the "instant
     * reply" branch on every subsequent SSE event ("1, 12, 123" pyramids).
     */
    @Volatile private var deliveredThisRound = false
    /**
     * Assistant text as it stood when the current round started. If the user
     * aborts generation before any new text is produced, the conversation's
     * newest assistant text is still the PREVIOUS round's answer — comparing
     * against this baseline prevents that stale answer from being delivered
     * as the current reply.
     */
    @Volatile private var roundBaselineText = ""
    /**
     * True after [sendUserMessage] is called, false after a reply is delivered.
     * Guards the "instant reply" path: text changes without an observed
     * generation phase are only treated as replies when a query is pending.
     * Branch switches and deletions on the phone change the assistant text
     * without any query from us — without this flag they would be forwarded
     * as spurious new replies.
     */
    @Volatile private var queryPending = false
    /**
     * A relay-query can stay open while RikkaHub creates prefill branches.
     * Its SSE stream exposes seeded full-message snapshots during that time;
     * the HTTP response remains the authoritative list of relay cards.
     */
    @Volatile private var awaitingRelayQueryResponse = false

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun start(conversationId: String?) {
        if (running.getAndSet(true)) {
            Log.w(TAG, "start: already running")
            return
        }
        Log.i(TAG, "start: baseUrl=$baseUrl conversationId=$conversationId")

        Thread {
            try {
                val convId = conversationId ?: createConversation()
                if (convId == null) {
                    Log.e(TAG, "start: failed to create conversation")
                    running.set(false)
                    isConnected = false
                    onConnectionChanged?.invoke(false)
                    return@Thread
                }
                currentConversationId.set(convId)
                Log.i(TAG, "start: conversationId=$convId — starting SSE")
                // Do NOT report connected here: with a stored conversationId no
                // network I/O has happened yet, so "connected" would be a lie
                // until connectSse() actually gets a 200 (it reports there).

                // Global stream watcher runs on its own thread; the
                // per-conversation SSE loop below blocks this one.
                if (followActive) startGlobalStreamLoop()
                startSseLoop(convId)
            } catch (e: Exception) {
                Log.e(TAG, "start failed: ${e.message}", e)
                running.set(false)
                isConnected = false
                onConnectionChanged?.invoke(false)
            }
        }.also { it.name = "LlmBridge-init"; it.isDaemon = true; it.start() }
    }

    override fun stop() {
        Log.i(TAG, "stop")
        running.set(false)
        // Close first, then interrupt: closing is what actually aborts a
        // blocking socket read; the interrupt only covers threads parked in
        // Thread.sleep() between reconnect attempts.
        closeQuietly(activeSse)
        closeQuietly(activeGlobalSse)
        sseThread?.interrupt()
        sseThread = null
        globalStreamThread?.interrupt()
        globalStreamThread = null
        isConnected = false
        onConnectionChanged?.invoke(false)
    }

    private fun closeQuietly(ref: AtomicReference<RawHttp.Response?>) {
        ref.getAndSet(null)?.let {
            try { it.close() } catch (_: Exception) {}
        }
    }

    // ── Upstream: user messages ──────────────────────────────────────────

    override fun sendUserMessage(text: String) {
        if (currentConversationId.get() == null) {
            Log.w(TAG, "sendUserMessage: no conversation, dropping")
            return
        }
        Log.i(TAG, "sendUserMessage: ${text.take(50)}")

        // Reset synchronously, before the worker thread starts, to avoid
        // racing the SSE thread which may set wasGenerating=true meanwhile.
        wasGenerating = false
        queryPending = true
        awaitingRelayQueryResponse = true
        roundBaselineText = lastKnownAssistantText
        // deliveredThisRound is NOT reset here — only after the POST
        // succeeds, so a failed send can't push a stale reply out.

        Thread {
            try {
                // Follow the conversation the user currently has open.
                val activeId = if (followActive) fetchActive()?.first else null
                val convId = if (activeId != null && activeId != currentConversationId.get()) {
                    Log.i(TAG, "sendUserMessage: active conversation changed → $activeId, switching")
                    switchConversation(activeId)
                    activeId
                } else {
                    currentConversationId.get() ?: return@Thread
                }

                // relay-query, not /messages: the host applies its own AIRelay
                // settings there, sending the query either as a normal user
                // message or as N prefill-continued branches. Prefill replies
                // come back in the response body; plain ones stream over SSE.
                val body = JSONObject().put("text", text)
                val resp = HttpJson.postJson(
                    "$baseUrl/api/conversations/$convId/relay-query",
                    body,
                    PREFILL_TIMEOUT_MS,
                )
                Log.i(TAG, "sendUserMessage response: ${resp.take(200)}")
                val handledAsPrefill = deliverRelayQueryBranches(resp)
                awaitingRelayQueryResponse = false
                if (!handledAsPrefill) {
                    deliveredThisRound = false  // ordinary message: allow SSE delivery
                    resumeBufferedRelayMessage()
                }
            } catch (e: Exception) {
                awaitingRelayQueryResponse = false
                queryPending = false
                Log.e(TAG, "sendUserMessage failed: ${e.message}", e)
                // POST failed: deliveredThisRound stays true, blocking stale replies.
            }
        }.also { it.name = "LlmBridge-send"; it.isDaemon = true; it.start() }
    }

    /**
     * relay-query answered in prefill mode: the branch texts are in the
     * response body. Deliver each as its own reply and suppress the SSE echo
     * of the newest one.
     */
    private fun deliverRelayQueryBranches(response: String): Boolean {
        val json = try { JSONObject(response) } catch (_: Exception) { return false }
        if (json.optString("mode") != "prefill_branches") return false
        val arr = json.optJSONArray("texts") ?: return true
        val texts = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotEmpty) }
        if (texts.isEmpty()) return true
        Log.i(TAG, "relay-query: ${texts.size} prefill branches")
        texts.lastOrNull()?.let {
            lastDeliveredAssistantText = it
        }
        queryPending = false
        deliveredThisRound = true
        texts.forEach { onReplyComplete?.invoke(it) }
        wasGenerating = false
        return true
    }

    private fun resumeBufferedRelayMessage() {
        val buffered = lastKnownAssistantText
        if (buffered.isEmpty() || buffered == roundBaselineText) return
        if (wasGenerating) {
            Log.i(TAG, "relay-query: replaying buffered SSE text (len=${buffered.length})")
            onStreamToken?.invoke(buffered)
        } else {
            Log.i(TAG, "relay-query: delivering buffered completed reply (len=${buffered.length})")
            deliverReply(buffered)
        }
    }

    // ── Session following ────────────────────────────────────────────────

    /** (conversationId, isOnScreen) of the host's active conversation, or null. */
    private fun fetchActive(): Pair<String, Boolean>? {
        val json = HttpJson.getJsonOrNull("$baseUrl/api/conversations/active", 3_000) ?: return null
        val id = json.optString("conversationId", "").ifEmpty { null } ?: return null
        return id to json.optBoolean("isOnScreen", false)
    }

    /** Re-point the SSE listener at another conversation. */
    private fun switchConversation(conversationId: String) {
        Log.i(TAG, "switchConversation: $conversationId")
        // Close the old stream before interrupting — see [activeSse].
        closeQuietly(activeSse)
        sseThread?.interrupt()
        currentConversationId.set(conversationId)
        // Pre-fill with the last delivered text (not ""), so the first
        // snapshot after switching doesn't re-deliver an old reply as new.
        lastKnownAssistantText = lastDeliveredAssistantText
        wasGenerating = false

        if (running.get()) {
            Thread { startSseLoop(conversationId) }
                .also { it.name = "LlmBridge-switch"; it.isDaemon = true; it.start() }
        }
    }

    // Event-driven, no polling: the server's global SSE stream pushes
    // invalidate events; after each one we check /active and switch if the
    // user opened a different conversation on the host device.
    private fun startGlobalStreamLoop() {
        Thread {
            while (running.get()) {
                try {
                    connectGlobalStream()
                } catch (e: InterruptedException) {
                    Log.i(TAG, "global stream interrupted, stopping")
                    break
                } catch (e: Exception) {
                    if (!running.get()) break
                    Log.w(TAG, "global stream error: ${e.message}, reconnect in ${SSE_RECONNECT_DELAY_MS}ms")
                    try { Thread.sleep(SSE_RECONNECT_DELAY_MS) } catch (_: InterruptedException) { break }
                }
            }
        }.also { globalStreamThread = it; it.name = "LlmBridge-globalStream"; it.isDaemon = true; it.start() }
    }

    private fun connectGlobalStream() {
        val conn = openSse("$baseUrl/api/conversations/stream") ?: return
        activeGlobalSse.getAndSet(conn)?.let { try { it.close() } catch (_: Exception) {} }
        try {
            Log.i(TAG, "global conversation stream connected")
            followActiveConversation()

            val reader = BufferedReader(InputStreamReader(conn.body, Charsets.UTF_8))
            var sawEvent = false
            while (running.get()) {
                val line = reader.readLine() ?: break  // disconnected
                when {
                    // event:/data: lines mark an incoming event; ": " lines are heartbeats
                    line.startsWith("event:") || line.startsWith("data:") -> sawEvent = true
                    line.isEmpty() && sawEvent -> {
                        sawEvent = false
                        followActiveConversation()
                    }
                }
            }
        } finally {
            activeGlobalSse.compareAndSet(conn, null)
            conn.close()
        }
    }

    /**
     * Switch to the host's on-screen conversation when it differs from the
     * one being listened to. isOnScreen=false (nothing on screen, server fell
     * back to most-recent) does not trigger a switch.
     */
    private fun followActiveConversation() {
        val (id, isOnScreen) = fetchActive() ?: return
        if (!isOnScreen) return
        if (id != currentConversationId.get()) {
            Log.i(TAG, "followActive: active conversation → $id, switching")
            switchConversation(id)
        }
    }

    private fun createConversation(): String? {
        try {
            val resp = HttpJson.postJson("$baseUrl/api/conversations", JSONObject(), HTTP_TIMEOUT_MS)
            val json = JSONObject(resp)
            val id = json.optString("conversationId", "").ifEmpty { json.optString("id", "") }
            if (id.isNotEmpty()) {
                Log.i(TAG, "createConversation: id=$id")
                return id
            }
        } catch (e: Exception) {
            Log.w(TAG, "createConversation via POST failed: ${e.message}")
        }
        // Server without a create route: conversations materialize on first
        // touch of any /{id} endpoint, so a fresh client-side UUID works.
        val id = java.util.UUID.randomUUID().toString()
        Log.i(TAG, "createConversation: fallback client-side id=$id")
        return id
    }

    // ── Downstream: SSE reply stream ─────────────────────────────────────

    /**
     * Opens an SSE stream. The response body is left open and streams live:
     * [RawHttp]'s chunked decoder hands each chunk over as it arrives, so
     * tokens reach [onStreamToken] without waiting for the reply to finish.
     */
    private fun openSse(url: String): RawHttp.Response? {
        val resp = RawHttp.request(
            method = "GET",
            url = HttpJson.sseUrl(url),
            headers = mapOf(
                "Accept" to "text/event-stream",
                "Cache-Control" to "no-cache",
            ),
            connectTimeoutMs = HTTP_TIMEOUT_MS,
            // Per-read timeout, not stream lifetime: the server heartbeats
            // every 15s, so silence beyond this means the link is dead.
            readTimeoutMs = SSE_READ_TIMEOUT_MS,
        )
        if (resp.status != 200) {
            Log.e(TAG, "SSE connect failed: $url → ${resp.status}")
            resp.close()
            return null
        }
        return resp
    }

    private fun startSseLoop(conversationId: String) {
        while (running.get()) {
            try {
                connectSse(conversationId)
            } catch (e: InterruptedException) {
                Log.i(TAG, "SSE interrupted, stopping")
                break
            } catch (e: Exception) {
                // A socket closed by stop()/switchConversation() lands here
                // too; that is deliberate teardown, not a fault.
                if (!running.get() || Thread.currentThread().isInterrupted) {
                    Log.i(TAG, "SSE closed during teardown")
                    break
                }
                Log.w(TAG, "SSE error: ${e.message}, reconnecting in ${SSE_RECONNECT_DELAY_MS}ms")
                isConnected = false
                onConnectionChanged?.invoke(false)
                try {
                    Thread.sleep(SSE_RECONNECT_DELAY_MS)
                } catch (_: InterruptedException) {
                    Log.i(TAG, "SSE reconnect wait interrupted, stopping")
                    break
                }
            }
        }
    }

    private fun connectSse(conversationId: String) {
        // Every new SSE connection begins with an initial snapshot.
        isInitialSseSnapshot = true
        val conn = openSse("$baseUrl/api/conversations/$conversationId/stream")
        if (conn == null) {
            // Non-200 from the server: report down and back off like any
            // other failure instead of silently busy-looping.
            if (isConnected) {
                isConnected = false
                onConnectionChanged?.invoke(false)
            }
            Thread.sleep(SSE_RECONNECT_DELAY_MS)
            return
        }
        sseThread = Thread.currentThread()
        // Publish before reading so stop()/switchConversation() can abort us.
        activeSse.getAndSet(conn)?.let { try { it.close() } catch (_: Exception) {} }

        try {
            isConnected = true
            onConnectionChanged?.invoke(true)
            Log.i(TAG, "SSE connected for conversation $conversationId")

            val reader = BufferedReader(InputStreamReader(conn.body, Charsets.UTF_8))
            var eventType = ""
            val dataLines = StringBuilder()

            while (running.get()) {
                val line = reader.readLine() ?: break  // disconnected
                when {
                    line.startsWith("event:") -> eventType = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        if (dataLines.isNotEmpty()) dataLines.append("\n")
                        dataLines.append(line.removePrefix("data:").trim())
                    }
                    line.isEmpty() && dataLines.isNotEmpty() -> {
                        try {
                            processSseEvent(eventType, dataLines.toString())
                        } catch (e: Exception) {
                            Log.w(TAG, "SSE event parse error: ${e.message}")
                        }
                        eventType = ""
                        dataLines.clear()
                    }
                    // ": heartbeat" — ignore
                }
            }
        } finally {
            activeSse.compareAndSet(conn, null)
            conn.close()
        }
    }

    /**
     * Extract the assistant reply from SSE events:
     *   1. find the latest assistant message text in snapshot/node_update
     *   2. while isGenerating: diff against previous text → [onStreamToken]
     *   3. isGenerating true→false transition: reply done → [onReplyComplete]
     */
    private fun processSseEvent(event: String, data: String) {
        when (event) {
            "snapshot" -> {
                val conv = JSONObject(data).getJSONObject("conversation")
                val isGenerating = conv.optBoolean("isGenerating", false)
                val assistantText = extractLastAssistantText(conv)
                Log.d(TAG, "SSE snapshot: isGenerating=$isGenerating textLen=${assistantText?.length ?: -1} isInitial=$isInitialSseSnapshot")

                if (isInitialSseSnapshot && !isGenerating) {
                    isInitialSseSnapshot = false
                    if (wasGenerating && assistantText != null && assistantText != lastKnownAssistantText) {
                        Log.i(TAG, "SSE reconnect: reply completed while disconnected, delivering (len=${assistantText.length})")
                        handleAssistantUpdate(assistantText, false)
                        return
                    }
                    // Initial snapshot at rest: prime lastKnownAssistantText
                    // only — history must not be re-delivered as a new reply.
                    assistantText?.let { lastKnownAssistantText = it }
                    Log.i(TAG, "SSE initial snapshot primed: textLen=${assistantText?.length ?: 0}")
                    return
                }
                isInitialSseSnapshot = false
                handleAssistantUpdate(assistantText, isGenerating)
            }
            "node_update" -> {
                val json = JSONObject(data)
                val isGenerating = json.optBoolean("isGenerating", false)
                val node = json.getJSONObject("node")
                val nodeText = extractTextFromNode(node)
                val role = extractRoleFromNode(node)
                Log.d(TAG, "SSE node_update: role=$role isGenerating=$isGenerating textLen=${nodeText?.length ?: -1}")
                if (role == "ASSISTANT") {
                    handleAssistantUpdate(nodeText, isGenerating)
                } else if (!isGenerating && wasGenerating) {
                    // Generation can end on a non-assistant node; deliver what we have.
                    Log.i(TAG, "generation ended on non-assistant node, delivering last known text")
                    deliverReply(lastKnownAssistantText)
                    wasGenerating = false
                }
            }
        }
    }

    private fun handleAssistantUpdate(text: String?, isGenerating: Boolean) {
        if (text == null) {
            if (isGenerating) wasGenerating = true
            return
        }

        Log.d(TAG, "handleAssistantUpdate: isGen=$isGenerating wasGen=$wasGenerating textLen=${text.length} knownLen=${lastKnownAssistantText.length}")

        if (awaitingRelayQueryResponse) {
            lastKnownAssistantText = text
            wasGenerating = isGenerating
            Log.d(TAG, "relay-query: buffered SSE update until response mode resolves")
            return
        }

        if (isGenerating) {
            // ── Streaming: emit the delta ──
            if (!wasGenerating) {
                // A new round started (including messages typed on the host
                // device directly) — open the delivery gate and snapshot the
                // pre-round assistant text as the staleness baseline.
                deliveredThisRound = false
                roundBaselineText = lastKnownAssistantText
            }
            if (text.length > lastKnownAssistantText.length && text.startsWith(lastKnownAssistantText)) {
                val delta = text.substring(lastKnownAssistantText.length)
                if (delta.isNotEmpty()) onStreamToken?.invoke(delta)
            } else if (text != lastKnownAssistantText && text.isNotEmpty()) {
                // Not a simple append (text was rearranged) — push it whole.
                onStreamToken?.invoke(text)
            }
            lastKnownAssistantText = text
            wasGenerating = true

        } else if (wasGenerating) {
            // ── Generation just finished (isGenerating true→false) ──
            if (text.isNotEmpty() && text != lastKnownAssistantText &&
                text.length > lastKnownAssistantText.length && text.startsWith(lastKnownAssistantText)
            ) {
                val delta = text.substring(lastKnownAssistantText.length)
                if (delta.isNotEmpty()) onStreamToken?.invoke(delta)
            }
            Log.i(TAG, "reply complete (streamed): len=${text.length}: ${text.take(80)}")
            deliverReply(text)
            lastKnownAssistantText = text
            wasGenerating = false

        } else if (text != lastKnownAssistantText && text.isNotEmpty() && queryPending) {
            // ── New text without ever seeing isGenerating=true ──
            // Generation finished between SSE events, or an instant reply.
            // Only fires when we actually sent a query (queryPending) — text
            // changes from branch switches or deletions on the phone are
            // navigation, not replies.
            Log.i(TAG, "reply complete (instant): len=${text.length}: ${text.take(80)}")
            deliveredThisRound = false
            deliverReply(text)
            lastKnownAssistantText = text

        } else {
            // Idle / navigation (branch switch, deletion): track the text
            // so the next real reply diffs correctly, but don't deliver.
            if (text != lastKnownAssistantText && text.isNotEmpty() && !queryPending) {
                Log.d(TAG, "text changed without pending query (branch switch/delete?), updating baseline only")
            }
            lastKnownAssistantText = text
        }
    }

    /**
     * Single delivery gate: at most one delivery per round, and never the
     * same text twice, regardless of which code path fires.
     */
    private fun deliverReply(text: String) {
        if (text.isEmpty()) return
        if (text == roundBaselineText) {
            // Aborted round: no new assistant text was produced, the newest
            // assistant message is still last round's answer. Don't resend it.
            Log.i(TAG, "deliverReply: skip (unchanged since round start — aborted generation?)")
            return
        }
        if (text == lastDeliveredAssistantText) {
            Log.i(TAG, "deliverReply: skip (same as last delivered)")
            return
        }
        if (deliveredThisRound) {
            Log.w(TAG, "deliverReply: skip (already delivered this round)")
            return
        }
        deliveredThisRound = true
        queryPending = false
        lastDeliveredAssistantText = text
        onReplyComplete?.invoke(text)
    }

    // ── JSON extraction ──────────────────────────────────────────────────

    private fun extractLastAssistantText(conv: JSONObject): String? {
        val messages = conv.optJSONArray("messages") ?: return null
        for (i in messages.length() - 1 downTo 0) {
            val node = messages.getJSONObject(i)
            if (extractRoleFromNode(node) == "ASSISTANT") return extractTextFromNode(node)
        }
        return null
    }

    private fun selectedMessage(node: JSONObject): JSONObject? {
        val msgs = node.optJSONArray("messages") ?: return null
        val selectIndex = node.optInt("selectIndex", 0)
        return msgs.optJSONObject(selectIndex) ?: msgs.optJSONObject(0)
    }

    private fun extractRoleFromNode(node: JSONObject): String? =
        selectedMessage(node)?.optString("role")

    private fun extractTextFromNode(node: JSONObject): String? {
        val parts = selectedMessage(node)?.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optString("type") == "text") sb.append(part.optString("text", ""))
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }
}
