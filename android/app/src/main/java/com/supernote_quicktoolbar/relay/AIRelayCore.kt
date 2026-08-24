package com.supernote_quicktoolbar.relay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.NativeLocale
import com.supernote_quicktoolbar.net.LocalSendDiscovery
import com.supernote_quicktoolbar.relay.core.AppPrefs
import com.supernote_quicktoolbar.relay.core.FormulaBlocks
import com.supernote_quicktoolbar.relay.core.FormulaImageRenderer
import com.supernote_quicktoolbar.relay.core.MarkdownDocumentParser
import com.supernote_quicktoolbar.relay.llm.SseLlmBridge
import com.supernote_quicktoolbar.relay.net.HttpJson
import com.supernote_quicktoolbar.relay.net.RawHttp
import com.supernote_quicktoolbar.relay.model.RelayMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Relay runtime hosted directly by Inkling's PluginHost process.
 *
 * This deliberately is not an Android Service: both the inbox panel and the RN
 * insertion bridge live in this same process, so data and commands stay in
 * memory instead of crossing the old relay-plugin broadcast boundary.
 *
 * The backend is RikkaHub's built-in web server, found via multicast
 * discovery (LocalSendDiscovery listens on the LocalSend group for
 * `service:"dictation"` announcements it broadcasts). An unknown server
 * triggers a Ratta pairing dialog; once accepted its fingerprint is persisted
 * and later announcements silently follow IP changes.
 *
 * Everything speaks RikkaHub's REST + SSE API via [SseLlmBridge]: queries are
 * POSTed into whatever conversation the user has open there (followActive), and
 * replies stream back over SSE into the inbox. There is no intermediate relay
 * app and no conversation picker on this device.
 */
class AIRelayCore private constructor(context: Context) {
    companion object {
        private const val TAG = "AIRelay"
        private const val WATCHDOG_MS = 5_000L
        private const val FORMULA_CONV_TTL_MS = 30 * 60_000L
        private const val PENDING_KIND_TTL_MS = 5 * 60_000L
        private const val INBOX_CAPSULE_COUNT = 4
        private const val INBOX_PREVIEW_CHARS = 240
        /** Debounce for reconnects that are not plain IP moves. */
        private const val RECONNECT_DEBOUNCE_MS = 2_000L

        @Volatile private var instance: AIRelayCore? = null

        fun get(context: Context): AIRelayCore = (instance ?: synchronized(this) {
            instance ?: AIRelayCore(context.applicationContext).also { instance = it }
        }).also { core ->
            if (context is ReactApplicationContext) core.reactContext = context
        }
    }

    interface Listener {
        fun onInboxChanged(messages: List<RelayMessage>) {}
        fun onConnectionChanged(phoneConnected: Boolean) {}
        fun onInsertionRequested(request: InsertionRequest) {}
        fun onFormulaReplyReady(messageId: String) {}
    }

    sealed class InsertionRequest {
        abstract val text: String
        data class Text(override val text: String, val messageId: String) : InsertionRequest()
        data class Blocks(
            override val text: String,
            val blocks: String,
            val messageId: String,
            val replace: Boolean,
        ) : InsertionRequest()
    }

    /** RikkaHub's web server: one port serves both REST and SSE. */
    private data class PhoneEndpoint(
        val fingerprint: String,
        val host: String,
        val llmPort: Int,
    )

    data class PairedPhoneInfo(
        val fingerprint: String,
        val host: String,
        val port: Int,
    )

    private val appContext = context.applicationContext
    /** Latest React context seen via [get]; used for the host pairing dialog. */
    @Volatile private var reactContext: ReactApplicationContext? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    val inbox = RelayInboxStore(appContext)

    /** User-controlled switch for this PluginHost process. Never persisted. */
    @Volatile private var enabled = false
    @Volatile private var started = false
    @Volatile private var phoneConnected = false

    @Volatile private var activeEndpoint: PhoneEndpoint? = null
    @Volatile private var lastReconnectAt = 0L
    /** Fingerprint we are currently showing a pairing dialog for. */
    @Volatile private var pairingInFlight: String? = null
    /** Fingerprints the user declined this session — don't re-ask. */
    private val declinedFingerprints = CopyOnWriteArraySet<String>()
    /** Server we already warned about requiring auth; clears when auth goes off. */
    @Volatile private var authWarnedFingerprint: String? = null

    /**
     * Main channel to RikkaHub: REST for sending, SSE for streamed replies.
     * followActive=true keeps us on whatever conversation the user has open
     * there, so no conversation picker is needed on the device.
     */
    private var llmBridge: SseLlmBridge? = null
    private var formulaBridge: SseLlmBridge? = null
    private var formulaRenderer: FormulaImageRenderer? = null
    private var formulaConversationId: String? = null
    private var formulaConversationCreatedAt = 0L
    private var pendingReplyKind: String? = null
    private var pendingReplyKindAt = 0L
    private val pendingQueries = ArrayDeque<String>()

    private val dictationPeerListener = LocalSendDiscovery.DictationPeerListener { peer, endpointChanged ->
        // Repeated announcements matter when the first one arrived before the
        // React context existed or immediately after the user unpaired. The
        // decision method suppresses normal steady-state work itself.
        mainHandler.post { onDictationPeerSeen(peer, endpointChanged) }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!started) return
            // SseLlmBridge reconnects its own stream; this only re-dials after
            // a hard failure (server restarted, endpoint never reached).
            val endpoint = activeEndpoint
            if (endpoint != null && llmBridge?.isConnected != true) {
                startLlmBridge(endpoint)
            }
            mainHandler.postDelayed(this, WATCHDOG_MS)
        }
    }

    init {
        inbox.onChanged = { publishInbox() }
    }

    fun addListener(listener: Listener) {
        listeners += listener
        mainHandler.post {
            listener.onInboxChanged(inbox.snapshot())
            listener.onConnectionChanged(phoneConnected)
        }
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun toggleEnabledByUser(): Boolean = setEnabledByUser(!enabled)

    @Synchronized
    fun setEnabledByUser(value: Boolean): Boolean {
        if (enabled == value) return enabled
        enabled = value
        if (value) start() else stop()
        return enabled
    }

    fun start() {
        if (!enabled || started) return
        started = true
        inbox.load()
        HttpJson.authToken = AppPrefs.llmToken(appContext).ifEmpty { null }

        LocalSendDiscovery.addDictationPeerListener(dictationPeerListener)
        LocalSendDiscovery.startDictationListening(appContext)

        // Reconnect to the paired phone's last known address; discovery will
        // move us if its IP changed. Never dial a default address.
        val cachedFp = AppPrefs.phoneFingerprint(appContext)
        val cachedIp = AppPrefs.phoneIp(appContext)
        if (cachedFp.isNotEmpty() && cachedIp.isNotEmpty()) {
            connectTo(PhoneEndpoint(cachedFp, cachedIp, AppPrefs.phonePort(appContext)))
        }

        mainHandler.removeCallbacks(watchdog)
        mainHandler.post(watchdog)
        publishInbox()
    }

    fun stop() {
        if (!started) return
        started = false
        LocalSendDiscovery.removeDictationPeerListener(dictationPeerListener)
        LocalSendDiscovery.stopDictationListening()
        mainHandler.removeCallbacks(watchdog)
        llmBridge?.stop()
        llmBridge = null
        formulaBridge?.stop()
        formulaBridge = null
        formulaRenderer?.release()
        formulaRenderer = null
        synchronized(pendingQueries) { pendingQueries.clear() }
        activeEndpoint = null
        lastReconnectAt = 0L
        pairingInFlight = null
        authWarnedFingerprint = null
        pendingReplyKind = null
        pendingReplyKindAt = 0L
        formulaConversationId = null
        formulaConversationCreatedAt = 0L
        HttpJson.authToken = null
        inbox.flush()
        phoneConnected = false
        publishConnection()
    }

    fun pairedPhoneInfo(): PairedPhoneInfo? {
        val fingerprint = AppPrefs.phoneFingerprint(appContext)
        if (fingerprint.isEmpty()) return null
        return PairedPhoneInfo(
            fingerprint = fingerprint,
            host = AppPrefs.phoneIp(appContext),
            port = AppPrefs.phonePort(appContext),
        )
    }

    /** Forget the current phone so its next announcement requires confirmation. */
    fun unpairPhone() {
        val previous = pairedPhoneInfo()
        activeEndpoint = null
        pairingInFlight = null
        declinedFingerprints.clear()
        authWarnedFingerprint = null
        synchronized(pendingQueries) { pendingQueries.clear() }

        val oldBridge = llmBridge
        llmBridge = null
        oldBridge?.stop()
        formulaBridge?.stop()
        formulaBridge = null
        formulaConversationId = null
        formulaConversationCreatedAt = 0L

        AppPrefs.clearPhonePairing(appContext)
        HttpJson.authToken = null
        phoneConnected = false
        publishConnection()
        Log.i(
            TAG,
            "phone unpaired: fingerprint=${previous?.fingerprint.orEmpty()} " +
                "endpoint=${previous?.host.orEmpty()}:${previous?.port ?: 0}"
        )
    }

    // ── Discovery / pairing ──────────────────────────────────────────────

    private fun onDictationPeerSeen(
        peer: LocalSendDiscovery.DictationPeer,
        endpointChanged: Boolean,
    ) {
        if (!enabled || !started) return
        val pairedFp = AppPrefs.phoneFingerprint(appContext)
        when {
            peer.fingerprint == pairedFp -> {
                // Auth turned on after pairing: every request would 401 from
                // here on, so warn once instead of looping on failed reconnects.
                if (peer.authRequired) {
                    if (authWarnedFingerprint != peer.fingerprint) {
                        Log.i(
                            TAG,
                            "pairing decision: paired server requires auth " +
                                "discovered=${peer.fingerprint} paired=$pairedFp " +
                                "endpoint=${peer.ip}:${peer.llmPort}"
                        )
                    }
                    warnAuthUnsupportedOnce(peer)
                    return
                }
                authWarnedFingerprint = null
                // Our server: silently follow IP/port changes.
                val current = activeEndpoint
                val endpoint = PhoneEndpoint(peer.fingerprint, peer.ip, peer.llmPort)
                if (current == null || current.host != peer.ip || current.llmPort != peer.llmPort) {
                    Log.i(
                        TAG,
                        "pairing decision: same fingerprint, following endpoint " +
                            "discovered=${peer.fingerprint} paired=$pairedFp " +
                            "from=${current?.host.orEmpty()}:${current?.llmPort ?: 0} " +
                            "to=${peer.ip}:${peer.llmPort}"
                    )
                    connectTo(endpoint)
                } else if (endpointChanged) {
                    Log.i(
                        TAG,
                        "pairing decision: same fingerprint already active " +
                            "discovered=${peer.fingerprint} paired=$pairedFp " +
                            "endpoint=${peer.ip}:${peer.llmPort}"
                    )
                }
            }
            pairedFp.isNotEmpty() && llmBridge?.isConnected == true -> {
                // A different server while ours is alive: ignore.
                if (endpointChanged) {
                    Log.i(
                        TAG,
                        "pairing decision: ignored different server while paired endpoint is connected " +
                            "discovered=${peer.fingerprint} paired=$pairedFp " +
                            "endpoint=${peer.ip}:${peer.llmPort}"
                    )
                }
            }
            peer.fingerprint in declinedFingerprints -> {
                if (endpointChanged) {
                    Log.i(
                        TAG,
                        "pairing decision: declined this session " +
                            "discovered=${peer.fingerprint} paired=$pairedFp " +
                            "endpoint=${peer.ip}:${peer.llmPort}"
                    )
                }
            }
            pairingInFlight != null -> {
                if (endpointChanged) {
                    Log.i(
                        TAG,
                        "pairing decision: another confirmation is in flight " +
                            "discovered=${peer.fingerprint} paired=$pairedFp " +
                            "inFlight=$pairingInFlight endpoint=${peer.ip}:${peer.llmPort}"
                    )
                }
            }
            else -> {
                Log.i(
                    TAG,
                    "pairing decision: requesting confirmation " +
                        "discovered=${peer.fingerprint} paired=$pairedFp " +
                        "endpoint=${peer.ip}:${peer.llmPort} changed=$endpointChanged"
                )
                requestPairing(peer)
            }
        }
    }

    /** Unknown server announced itself: ask the user via the host Ratta dialog. */
    private fun requestPairing(peer: LocalSendDiscovery.DictationPeer) {
        val ctx = reactContext ?: run {
            // No RN context yet (panel-only startup): retry on a later announce.
            Log.w(TAG, "pairing request from ${peer.alias} deferred: no React context")
            return
        }
        if (peer.authRequired) {
            // Not added to declinedFingerprints: warnAuthUnsupportedOnce already
            // suppresses repeats, and this way turning auth off re-offers pairing.
            warnAuthUnsupportedOnce(peer)
            return
        }
        authWarnedFingerprint = null
        pairingInFlight = peer.fingerprint
        val message = NativeLocale.t("airrelay_pair_request", peer.alias, peer.ip)
        com.supernote_quicktoolbar.ui_common.Dialog.confirmResult(ctx, message) { accepted ->
            pairingInFlight = null
            if (!enabled || !started) return@confirmResult
            if (accepted) {
                Log.i(
                    TAG,
                    "pairing accepted: fingerprint=${peer.fingerprint} " +
                        "endpoint=${peer.ip}:${peer.llmPort}"
                )
                AppPrefs.setPhoneFingerprint(appContext, peer.fingerprint)
                connectTo(PhoneEndpoint(peer.fingerprint, peer.ip, peer.llmPort))
            } else {
                Log.i(
                    TAG,
                    "pairing declined: fingerprint=${peer.fingerprint} " +
                        "endpoint=${peer.ip}:${peer.llmPort}"
                )
                declinedFingerprints += peer.fingerprint
            }
        }
    }

    /**
     * RikkaHub's "enable authentication" requires a JWT obtained by posting the
     * access password, and this device has no password entry — so all requests
     * would 401. Warn once per server rather than every 3-second announcement.
     */
    private fun warnAuthUnsupportedOnce(peer: LocalSendDiscovery.DictationPeer) {
        if (authWarnedFingerprint == peer.fingerprint) return
        authWarnedFingerprint = peer.fingerprint
        Log.w(TAG, "server ${peer.alias} @ ${peer.ip} requires auth; AIRelay cannot connect")
        reactContext?.let { ctx ->
            com.supernote_quicktoolbar.ui_common.Dialog.tip(
                ctx, NativeLocale.t("airrelay_auth_unsupported", peer.alias)
            )
        }
    }

    private fun connectTo(endpoint: PhoneEndpoint) {
        if (!enabled || !started) return
        val now = System.currentTimeMillis()
        val current = activeEndpoint
        val ipMoved = current == null || current.host != endpoint.host
        if (!ipMoved && now - lastReconnectAt < RECONNECT_DEBOUNCE_MS) return
        lastReconnectAt = now
        activeEndpoint = endpoint
        AppPrefs.setPhoneIp(appContext, endpoint.host)
        AppPrefs.setPhonePort(appContext, endpoint.llmPort)
        startLlmBridge(endpoint)
    }

    // ── Queries ──────────────────────────────────────────────────────────

    /** Outcome of [submitQuery], so callers can tell "sent" from "went nowhere". */
    enum class SubmitResult {
        /** AIRelay has not been enabled from the Inkling AI bubble. */
        DISABLED,
        /** Handed to the phone. A reply should follow over SSE. */
        SENT,
        /** Server known but not connected: queued and a reconnect was kicked off. */
        QUEUED,
        /** Never paired with a phone — nothing will ever pick this up. */
        NO_ENDPOINT,
        /** Nothing to send. */
        EMPTY,
    }

    /**
     * Posts the query into RikkaHub's currently open conversation; the reply
     * streams back over SSE. Queued while the server is unreachable.
     *
     * The return value matters to the caller's UI: without a paired phone the
     * query is dropped outright, and showing "waiting for AI" for that case
     * leaves the user watching a reply that can never arrive.
     */
    fun submitQuery(text: String): SubmitResult {
        val query = text.trim()
        if (query.isEmpty()) return SubmitResult.EMPTY
        if (!enabled || !started) return SubmitResult.DISABLED

        val bridge = llmBridge
        if (bridge?.isConnected == true) {
            bridge.sendUserMessage(query)
            Log.i(TAG, "submitQuery: sent (${query.length} chars)")
            return SubmitResult.SENT
        }

        val endpoint = activeEndpoint
        if (endpoint == null) {
            // No paired phone: queueing would silently swallow the query, and
            // the 20-entry cap would eventually discard it without a trace.
            Log.w(TAG, "submitQuery: no paired phone, dropping (${query.length} chars)")
            return SubmitResult.NO_ENDPOINT
        }

        synchronized(pendingQueries) {
            pendingQueries.addLast(query)
            while (pendingQueries.size > 20) pendingQueries.removeFirst()
        }
        Log.i(TAG, "submitQuery: queued for ${endpoint.host}, reconnecting")
        startLlmBridge(endpoint)
        return SubmitResult.QUEUED
    }

    fun submitImageQuery(imagePath: String, maskPath: String, prompt: String, kind: String = "") {
        if (!enabled || !started) return
        if (kind == "formula") mainHandler.post {
            if (enabled && started) formulaRenderer().warmup()
        }
        Thread {
            try {
                if (enabled && started) sendImageQuery(imagePath, maskPath, prompt, kind)
            } catch (error: Exception) {
                Log.e(TAG, "Image query failed", error)
            }
        }.apply { isDaemon = true; name = "AIRelay-image-query" }.start()
    }

    fun resetFormulaSession() {
        formulaBridge?.stop()
        formulaBridge = null
        val id = formulaConversationId ?: return
        formulaConversationId = null
        formulaConversationCreatedAt = 0L
        if (!enabled || !started) return
        val url = llmBaseUrl() ?: return
        Thread {
            if (enabled && started) HttpJson.delete("$url/api/conversations/$id")
        }.apply { isDaemon = true }.start()
    }

    /**
     * Note-insertion result. RikkaHub has no ack endpoint (the old relay used
     * one to drain its retry queue); replies arrive over SSE and are stored in
     * the inbox, so a failure only needs to be visible in the log.
     */
    fun ackInsertion(text: String, success: Boolean, error: String?) {
        if (!success) Log.w(TAG, "note insertion failed: ${error ?: "unknown"} for ${text.take(40)}")
    }

    fun isBackendReachable(): Boolean = phoneConnected

    /** Kept as an internal context hint for the existing TextInserter API. */
    fun updateInsertPosition(page: Int, top: Int) {
        Log.d(TAG, "note insertion position page=$page top=$top")
    }

    fun insertMessage(id: String): Boolean {
        val message = inbox.get(id) ?: return false
        val document = MarkdownDocumentParser.parse(message.text)
        val payload = FormulaBlocks.payload(document, document.blocks.indices.toList())
        return if (payload.hasMath) insertBlocks(id, payload.plainText, payload.blocksJson, replace = false)
        else insertSelection(id, message.text)
    }

    fun insertSelection(id: String, text: String): Boolean {
        if (text.isBlank() || inbox.get(id) == null) return false
        notifyInsertion(InsertionRequest.Text(text, id))
        inbox.markInserted(id)
        return true
    }

    fun insertBlocks(id: String, plainText: String, blocksJson: String, replace: Boolean): Boolean {
        if (blocksJson.isBlank() || inbox.get(id) == null) return false
        val blocks = try { JSONArray(blocksJson) } catch (error: Exception) {
            Log.w(TAG, "Bad blocks JSON: ${error.message}")
            return false
        }
        renderMathBlocks(blocks, 0) { rendered ->
            if (!rendered) return@renderMathBlocks
            notifyInsertion(InsertionRequest.Blocks(plainText, blocks.toString(), id, replace))
            if (!replace) inbox.markInserted(id)
        }
        return true
    }

    fun replaceRecognizedBlocks(id: String): Boolean {
        val message = inbox.get(id) ?: return false
        if (message.kind != "formula") return false
        val document = MarkdownDocumentParser.parse(message.text)
        val indices = FormulaBlocks.recognizedIndices(document)
        if (indices.isEmpty()) return false
        val payload = FormulaBlocks.payload(document, indices)
        return insertBlocks(id, payload.plainText, payload.blocksJson, replace = true)
    }

    fun delete(id: String) = inbox.delete(id)
    fun deleteMany(ids: Collection<String>) = inbox.deleteMany(ids)
    fun readd(ids: Collection<String>) = inbox.readd(ids)

    fun capsuleItemsJson(): String {
        val visible = inbox.snapshot().filter { !it.inserted }
        val selected = (visible.filter { it.pinned } + visible.filterNot { it.pinned })
            .take(INBOX_CAPSULE_COUNT)
            .sortedByDescending { it.updatedAt }
            .reversed()
        return JSONArray().apply {
            selected.forEach { message ->
                put(JSONObject().apply {
                    put("id", message.id)
                    put("title", message.title.take(80))
                    put("preview", message.text.take(INBOX_PREVIEW_CHARS))
                    put("source", message.source)
                    put("time", message.updatedAt)
                    put("pinned", message.pinned)
                    if (message.kind.isNotEmpty()) put("kind", message.kind)
                })
            }
        }.toString()
    }

    // ── RikkaHub link ────────────────────────────────────────────────────

    /**
     * (Re)opens the main REST+SSE bridge. followActive=true makes it track the
     * conversation open on the RikkaHub device, so replies to messages typed
     * there also land in our inbox.
     */
    private fun startLlmBridge(endpoint: PhoneEndpoint) {
        if (!enabled || !started) return
        llmBridge?.stop()
        val baseUrl = HttpJson.normalizeBaseUrl(endpoint.host, endpoint.llmPort)
        val bridge = SseLlmBridge(baseUrl)
        val streamedThisReply = AtomicBoolean(false)
        llmBridge = bridge.apply {
            onStreamToken = { token ->
                if (llmBridge === bridge && enabled && started) {
                    // Use the final source name while streaming so closeHead() closes
                    // this same card instead of leaving a separate llm-stream card.
                    streamedThisReply.set(true)
                    inbox.append(token, "llm", replyKindFor("llm"))
                }
            }
            onReplyComplete = { text ->
                if (llmBridge === bridge && enabled && started) {
                    val cleaned = stripLeadingUuidLine(text).trim()
                    // The completion callback contains the whole assistant message.
                    // It is a fallback only when no incremental token was delivered;
                    // appending it after streaming would duplicate every reply.
                    if (!streamedThisReply.getAndSet(false) && cleaned.isNotEmpty()) {
                        inbox.append(cleaned, "llm", replyKindFor("llm"))
                    }
                    pendingReplyKind = null
                    inbox.closeHead("llm")
                }
            }
            onConnectionChanged = { connected ->
                // Ignore a stale bridge's late callback after a newer one started.
                if (llmBridge === bridge) {
                    phoneConnected = connected
                    publishConnection()
                    if (connected) flushPendingQueries()
                }
            }
            start(null)
        }
    }

    /** Base URL of RikkaHub's web server, or null when never paired. */
    private fun llmBaseUrl(): String? {
        val host = activeEndpoint?.host ?: AppPrefs.phoneIp(appContext).ifEmpty { return null }
        val port = activeEndpoint?.llmPort ?: AppPrefs.phonePort(appContext)
        return HttpJson.normalizeBaseUrl(host, port)
    }

    private fun flushPendingQueries() {
        if (llmBridge?.isConnected != true) return
        val queued = synchronized(pendingQueries) {
            pendingQueries.toList().also { pendingQueries.clear() }
        }
        // Connected, so each of these takes the SENT path; the result is only
        // interesting to the original caller.
        queued.forEach { submitQuery(it) }
    }

    private fun replyKindFor(source: String): String {
        val kind = pendingReplyKind ?: return ""
        if (!source.startsWith("llm") || System.currentTimeMillis() - pendingReplyKindAt > PENDING_KIND_TTL_MS) {
            if (System.currentTimeMillis() - pendingReplyKindAt > PENDING_KIND_TTL_MS) pendingReplyKind = null
            return ""
        }
        return kind
    }

    private fun sendImageQuery(imagePath: String, maskPath: String, prompt: String, kind: String) {
        if (!enabled || !started) return
        val baseUrl = llmBaseUrl() ?: run {
            Log.w(TAG, "image query dropped: no paired phone")
            return
        }
        val image = cropImageWithMask(imagePath, maskPath) ?: return
        val boundary = "----AIRelayBoundary${System.currentTimeMillis()}"
        val multipart = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"lasso.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
            write(image)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val uploadResponse = try {
            RawHttp.request(
                method = "POST",
                url = "$baseUrl/api/files/upload",
                headers = HttpJson.authHeaders(
                    mapOf("Content-Type" to "multipart/form-data; boundary=$boundary")),
                body = multipart,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 15_000,
            ).use { resp ->
                if (resp.status != 201) {
                    Log.w(TAG, "image upload → ${resp.status}")
                    return
                }
                resp.body.readBytes().toString(Charsets.UTF_8)
            }
        } catch (error: Exception) {
            Log.w(TAG, "image upload failed: ${error.message}")
            return
        }
        if (!enabled || !started) return
        val fileUrl = JSONObject(uploadResponse).optJSONArray("files")
            ?.optJSONObject(0)?.optString("url") ?: return
        var conversationId: String? = null
        var messagePrompt = prompt
        if (kind == "formula") {
            conversationId = ensureFormulaConversation(baseUrl, prompt)
            if (conversationId != null) messagePrompt = "请按系统指令识别这张图片中的手写内容。"
        }
        if (conversationId == null) {
            if (kind.isNotEmpty()) {
                pendingReplyKind = kind
                pendingReplyKindAt = System.currentTimeMillis()
            }
            conversationId = HttpJson.getJsonOrNull("$baseUrl/api/conversations/active")
                ?.optString("conversationId", "")?.ifEmpty { null }
        }
        conversationId ?: return
        if (!enabled || !started) return
        val parts = JSONArray().put(JSONObject().put("type", "image").put("url", fileUrl))
        if (messagePrompt.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", messagePrompt))
        HttpJson.postJson("$baseUrl/api/conversations/$conversationId/messages", JSONObject().put("parts", parts), 10_000)
    }

    @Synchronized
    private fun ensureFormulaConversation(baseUrl: String, prompt: String): String? {
        if (!enabled || !started) return null
        formulaConversationId?.let { id ->
            if (System.currentTimeMillis() - formulaConversationCreatedAt < FORMULA_CONV_TTL_MS) return id
            resetFormulaSession()
        }
        return try {
            val id = JSONObject(HttpJson.postJson("$baseUrl/api/conversations", JSONObject()
                .put("customSystemPrompt", prompt).put("title", "公式识别·临时"), 10_000))
                .optString("conversationId", "").ifEmpty { null } ?: return null
            if (HttpJson.getJsonOrNull("$baseUrl/api/conversations/$id")
                    ?.optString("customSystemPrompt", "").isNullOrBlank()) {
                HttpJson.delete("$baseUrl/api/conversations/$id")
                return null
            }
            formulaConversationId = id
            formulaConversationCreatedAt = System.currentTimeMillis()
            startFormulaBridge(baseUrl, id)
            id
        } catch (error: Exception) {
            Log.w(TAG, "Formula conversation unavailable: ${error.message}")
            null
        }
    }

    private fun startFormulaBridge(baseUrl: String, conversationId: String) {
        if (!enabled || !started) return
        formulaBridge?.stop()
        val bridge = SseLlmBridge(baseUrl, followActive = false)
        formulaBridge = bridge.apply {
            onReplyComplete = { text ->
                mainHandler.post {
                    if (formulaBridge === bridge && enabled && started) {
                        inbox.append(text.trim(), "llm", "formula")
                        inbox.closeHead("llm")
                        inbox.snapshot().firstOrNull { it.kind == "formula" }?.id?.let { id ->
                            listeners.forEach { it.onFormulaReplyReady(id) }
                        }
                    }
                }
            }
            start(conversationId)
        }
    }

    private fun cropImageWithMask(imagePath: String, maskPath: String): ByteArray? {
        val source = BitmapFactory.decodeFile(imagePath) ?: return null
        val rect = try {
            if (maskPath.isBlank()) null else JSONObject(File(maskPath).readText()).optJSONObject("boundingBox")?.let {
                Rect(it.optInt("x").coerceAtLeast(0), it.optInt("y").coerceAtLeast(0),
                    (it.optInt("x") + it.optInt("w")).coerceAtMost(source.width),
                    (it.optInt("y") + it.optInt("h")).coerceAtMost(source.height))
            }
        } catch (_: Exception) { null }
        val cropped = if (rect != null && rect.width() > 0 && rect.height() > 0) {
            Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        } else source
        return ByteArrayOutputStream().use { out ->
            cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (cropped !== source) cropped.recycle()
            source.recycle()
            out.toByteArray()
        }
    }

    private fun formulaRenderer(): FormulaImageRenderer = formulaRenderer
        ?: FormulaImageRenderer(appContext).also { formulaRenderer = it }

    private fun renderMathBlocks(blocks: JSONArray, start: Int, done: (Boolean) -> Unit) {
        var index = start
        while (index < blocks.length()) {
            val block = blocks.optJSONObject(index)
            if (block?.optString("type") == "math" && !block.has("image")) break
            index++
        }
        if (index >= blocks.length()) { done(true); return }
        val block = blocks.optJSONObject(index) ?: run { done(false); return }
        formulaRenderer().render(block.optString("content"), onResult = { json ->
            try {
                block.put("image", JSONObject(json))
                renderMathBlocks(blocks, index + 1, done)
            } catch (_: Exception) { done(false) }
        }, onError = { error -> Log.w(TAG, "Formula render failed: $error"); done(false) })
    }

    private fun notifyInsertion(request: InsertionRequest) = mainHandler.post {
        listeners.forEach { it.onInsertionRequested(request) }
    }

    private fun publishInbox() = mainHandler.post {
        val snapshot = inbox.snapshot()
        listeners.forEach { it.onInboxChanged(snapshot) }
    }

    private fun publishConnection() = mainHandler.post {
        listeners.forEach { it.onConnectionChanged(phoneConnected) }
    }

    private fun stripLeadingUuidLine(text: String): String {
        val newline = text.indexOf('\n')
        if (newline <= 0) return text
        val first = text.substring(0, newline).trim()
        return if (Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(first)) text.substring(newline + 1) else text
    }
}
