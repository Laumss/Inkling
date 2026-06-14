package com.supernote_quicktoolbar.panels
import com.supernote_quicktoolbar.BuildConfig
import com.supernote_quicktoolbar.*
import com.supernote_quicktoolbar.overlays.*
import com.supernote_quicktoolbar.bubbles.*

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.facebook.react.bridge.*
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.supernote_quicktoolbar.ui_common.PanelBase
import com.supernote_quicktoolbar.ui_common.PanelScrollHost
import com.supernote_quicktoolbar.ui_common.PanelWidgets
import java.io.*
import java.net.*
import java.security.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.net.ssl.*
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

class SendPanel(
    ctx: ReactApplicationContext,
    toolbar: FloatingToolbarModule
) : PanelBase(ctx, toolbar) {

    override val tag = "SendPanel"
    override val panelName = "send"

    companion object {
        @Volatile var currentInstance: SendPanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): SendPanel {
            val inst = currentInstance ?: SendPanel(ctx, module)
            currentInstance = inst
            return inst
        }
    }

    private var pendingText: String = ""
    private var pendingImages: List<String> = emptyList()
    private var pendingLinkedFiles: List<Triple<String, Int, String>> = emptyList()
    private var selectedPeer: LocalSendModule.DiscoveredPeer? = null
    private var sending = false
    private var peerPollRunnable: Runnable? = null
    private var cameFromBubble = false
    private var clipboardSyncMode = false

    private var statusTextView: TextView? = null
    private var previewTextView: TextView? = null
    private var scrollHost: PanelScrollHost? = null
    private var peerContainer: LinearLayout? = null
    private var sendTextBtn: TextView? = null
    private var cancelBtn: View? = null
    private var fileButtonsContainer: LinearLayout? = null

    fun show(fromBubble: Boolean = false, syncClipboard: Boolean = false) {
        currentInstance = this
        pendingText = ""; pendingImages = emptyList(); pendingLinkedFiles = emptyList()
        selectedPeer = null; sending = false
        cameFromBubble = fromBubble
        clipboardSyncMode = syncClipboard
        showPanel()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            startPeerPolling()
            LocalSendModule.reprobeKnownPeers()
            LocalSendModule.triggerScan()
        }
    }

    override fun hide() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            stopPeerPolling()
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            rootView = null; windowManager = null
            onHide()
            toolbarModule.disablePenBlock()
        }
    }

    override fun onHide() {
        statusTextView = null; previewTextView = null; peerContainer = null; scrollHost = null
        sendTextBtn = null; cancelBtn = null; fileButtonsContainer = null
        currentInstance = null
    }

    override fun suspendVisibility() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            rootView?.visibility = View.GONE
            stopPeerPolling()
        }
    }

    override fun resumeVisibility() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            rootView?.visibility = View.VISIBLE
            startPeerPolling()
        }
    }

    fun updateLassoData(
        text: String,
        imagePaths: List<String>,
        linkedFiles: List<Triple<String, Int, String>> = emptyList()
    ) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            pendingText = text
            pendingImages = imagePaths
            pendingLinkedFiles = linkedFiles
            val parts = mutableListOf<String>()
            if (text.isNotEmpty()) parts.add("${text.length} chars")
            if (imagePaths.isNotEmpty()) parts.add("${imagePaths.size} img")
            if (linkedFiles.isNotEmpty()) parts.add("${linkedFiles.size} link")
            statusTextView?.text = if (parts.isNotEmpty()) parts.joinToString(" + ") else NativeLocale.t("peers_scanning")
            if (text.isNotEmpty()) {
                previewTextView?.text = text.take(200) + if (text.length > 200) "..." else ""
                previewTextView?.visibility = View.VISIBLE
            }
            rebuildFileButtons()
            updateSendBtnState()
        }
    }

    override fun buildContent(root: LinearLayout) {
        renderDsl(root) {
            header(NativeLocale.t("send_title"))

            custom { host ->
                LinearLayout(host.ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(host.dp(16), host.dp(12), host.dp(16), host.dp(12))
                    statusTextView = TextView(host.ctx).apply {
                        text = NativeLocale.t("extracting")
                        textSize = host.sp(13f); setTextColor(Color.parseColor("#666666"))
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    previewTextView = TextView(host.ctx).apply {
                        textSize = host.sp(12f); setTextColor(Color.parseColor("#999999"))
                        maxLines = 3; visibility = View.GONE
                        setPadding(0, host.dp(6), 0, 0)
                        setLineSpacing(host.dp(2).toFloat(), 1f)
                    }
                    addView(statusTextView)
                    addView(previewTextView)
                    addView(View(host.ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, host.dp(1)
                        ).apply { topMargin = host.dp(12) }
                        setBackgroundColor(Color.parseColor("#D8D8D8"))
                    })
                }
            }

            custom { host ->
                val sh = PanelScrollHost(host.ctx, overlayScrollbar = true)
                scrollHost = sh
                peerContainer = sh.content.apply {
                    setPadding(host.dp(5), host.dp(5), host.dp(5), host.dp(5))
                }
                refreshPeerList()
                sh.view
            }

            custom { host ->
                fileButtonsContainer = LinearLayout(host.ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val fileScroll = HorizontalScrollView(host.ctx).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(fileButtonsContainer)
                }

                val rescanBtn = PanelWidgets.outlinedButton(host, NativeLocale.t("rescan")) {
                    statusTextView?.text = NativeLocale.t("peers_scanning")
                    selectedPeer = null
                    refreshPeerList()
                    thread(isDaemon = true) {
                        LocalSendModule.triggerScan()
                        android.os.Handler(android.os.Looper.getMainLooper()).post { refreshPeerList() }
                    }
                }
                cancelBtn = PanelWidgets.outlinedButton(host, NativeLocale.t("cancel")) { closeAndRestore() }
                sendTextBtn = PanelWidgets.filledButton(host,
                    if (clipboardSyncMode) NativeLocale.t("sync_clipboard_btn") else NativeLocale.t("send_text_btn")
                ) { if (clipboardSyncMode) handleSyncClipboard() else handleSendText() }
                updateSendBtnState()

                val wrapper = LinearLayout(host.ctx).apply { orientation = LinearLayout.VERTICAL }
                wrapper.addView(PanelWidgets.divider(host))
                val bar = LinearLayout(host.ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(host.dp(28), host.dp(28), host.dp(28), host.dp(28))
                }
                bar.addView(fileScroll)
                bar.addView(rescanBtn)
                bar.addView(cancelBtn!!)
                bar.addView(sendTextBtn!!)
                wrapper.addView(bar)
                wrapper
            }
        }
    }

    private fun refreshPeerList() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val container = peerContainer ?: return@post
            container.removeAllViews()
            val peers = LocalSendModule.getPeersSnapshot()

            if (peers.isEmpty()) {
                container.addView(makeEmptyView(
                    NativeLocale.t("peers_scanning"),
                    NativeLocale.t("peers_none")
                ))
                updateSendBtnState()
                return@post
            }

            if (selectedPeer == null || peers.none { it.fingerprint == selectedPeer?.fingerprint }) {
                selectedPeer = peers.first()
            }

            for (peer in peers) container.addView(createPeerRow(peer))
            updateSendBtnState()
            scrollHost?.refreshThumb()
        }
    }

    private fun createPeerRow(peer: LocalSendModule.DiscoveredPeer): LinearLayout {
        val isSelected = selectedPeer?.fingerprint == peer.fingerprint
        val row = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(if (isSelected) Color.parseColor("#F0F0F0") else Color.WHITE)
                setStroke(if (isSelected) dp(2) else dp(1),
                    if (isSelected) Color.BLACK else Color.parseColor("#E0E0E0"))
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            setOnClickListener {
                selectedPeer = peer
                refreshPeerList()
                updateSendBtnState()
            }
        }

        val iconText = when (peer.deviceType) {
            "desktop" -> "PC"; "mobile" -> "MB"; "tablet" -> "TB"; "web" -> "WB"; else -> "DV"
        }
        row.addView(TextView(reactContext).apply {
            text = iconText; textSize = sp(14f); setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(10) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5F5F5")); cornerRadius = dp(4).toFloat()
            }
        })

        val info = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(reactContext).apply {
            text = peer.alias; textSize = sp(13f); setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(TextView(reactContext).apply {
            text = "${peer.ip}:${peer.port} · ${peer.deviceType}"
            textSize = sp(10f); setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(info)

        if (isSelected) {
            row.addView(TextView(reactContext).apply {
                text = "✓"; textSize = sp(16f); setTextColor(Color.BLACK)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            })
        }
        return row
    }

    private fun handleSendText() = handleSendText(pendingText)

    private fun handleSendText(text: String) {
        val peer = selectedPeer ?: return
        if (text.isEmpty() || sending) return
        sending = true
        statusTextView?.text = NativeLocale.t("sending")
        updateSendBtnState()
        thread(isDaemon = true) {
            try {
                LocalSendModule.sendTextDirect(peer.ip, peer.port, text, peer.useTls)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    statusTextView?.text = NativeLocale.t("send_success")
                    handler.postDelayed({ closeAndRestore() }, 800)
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    sending = false
                    statusTextView?.text = "${NativeLocale.t("send_failed")}: ${e.message}"
                    updateSendBtnState()
                }
            }
        }
    }

    private fun handleSendFile(path: String) {
        val peer = selectedPeer ?: return
        if (sending) return
        sending = true
        statusTextView?.text = NativeLocale.t("sending")
        updateSendBtnState()
        thread(isDaemon = true) {
            try {
                LocalSendModule.sendFileDirect(peer.ip, peer.port, path, peer.useTls)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    statusTextView?.text = NativeLocale.t("send_success")
                    handler.postDelayed({ closeAndRestore() }, 800)
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    sending = false
                    statusTextView?.text = "${NativeLocale.t("send_failed")}: ${e.message}"
                    updateSendBtnState()
                }
            }
        }
    }

    private fun handleSyncClipboard() {
        val peer = selectedPeer ?: return
        if (sending) return
        sending = true
        statusTextView?.text = NativeLocale.t("sync_packaging")
        updateSendBtnState()
        thread(isDaemon = true) {
            try {
                val zipPath = packageClipboard()
                if (zipPath == null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        sending = false
                        statusTextView?.text = NativeLocale.t("sync_clipboard_empty")
                        updateSendBtnState()
                    }
                    return@thread
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post { statusTextView?.text = NativeLocale.t("sync_waiting") }
                LocalSendModule.sendFileDirect(peer.ip, peer.port, zipPath, peer.useTls)
                try { File(zipPath).delete() } catch (_: Exception) {}
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    statusTextView?.text = NativeLocale.t("send_success")
                    handler.postDelayed({ closeAndRestore() }, 800)
                }
            } catch (e: Exception) {
                val msg = if (e.message?.contains("403") == true)
                    NativeLocale.t("sync_rejected")
                else "${NativeLocale.t("send_failed")}: ${e.message}"
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    sending = false
                    statusTextView?.text = msg
                    updateSendBtnState()
                }
            }
        }
    }

    private fun packageClipboard(): String? {
        val prefs = reactContext.getSharedPreferences("quicktoolbar_presets", 0)
        val clipsJson = prefs.getString("preset_99", null) ?: return null
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "[SYNC-DBG] packageClipboard raw preset_99: $clipsJson")
        val clips = JSONObject(clipsJson)

        val filteredClips = JSONObject()
        val keys = clips.keys()
        while (keys.hasNext()) {
            val slot = keys.next()
            val rawVal = clips.opt(slot)
            val path = clips.optString(slot, "")
            val fileExists = path.isNotEmpty() && File(path).exists()
            if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "[SYNC-DBG] packageClipboard slot=$slot raw=$rawVal path='$path' exists=$fileExists")
            if (fileExists) {
                filteredClips.put(slot, path)
            }
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "[SYNC-DBG] packageClipboard filtered: $filteredClips (${filteredClips.length()} slots)")
        if (filteredClips.length() == 0) return null

        val cacheDir = reactContext.cacheDir
        val zipFile = File(cacheDir, "clipboard_sync_${System.currentTimeMillis()}.zip")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->

            zos.putNextEntry(ZipEntry("clips.json"))
            zos.write(filteredClips.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val keys2 = filteredClips.keys()
            while (keys2.hasNext()) {
                val slot = keys2.next()
                val f = File(filteredClips.getString(slot))
                zos.putNextEntry(ZipEntry("stickers/${f.name}"))
                f.inputStream().buffered().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile.absolutePath
    }

    private var pollCount = 0

    private fun startPeerPolling() {
        stopPeerPolling()
        pollCount = 0
        peerPollRunnable = object : Runnable {
            override fun run() {
                pollCount++
                if (pollCount % 5 == 0) LocalSendModule.reprobeKnownPeers()
                refreshPeerList()
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(peerPollRunnable!!)
    }

    private fun stopPeerPolling() {
        peerPollRunnable?.let { handler.removeCallbacks(it) }
        peerPollRunnable = null
    }

    private fun closeAndRestore() {
        val fromBubble = cameFromBubble
        hide()
        toolbarModule.cancelPendingScreen()
        toolbarModule.requestClosePluginView()
        toolbarModule.emitEventPublic("onNativePanelClose",
            Arguments.createMap().apply {
                putString("panel", "send")
                putBoolean("cameFromBubble", fromBubble)
            })
        if (fromBubble) {
            handler.postDelayed({
                toolbarModule.restoreToolbar()
                FloatingBubbleModule.reshowLast(reactContext)
                AiBubbleModule.reshowLast(reactContext)
            }, 350)
        } else {
            toolbarModule.restoreToolbar()
        }
    }

    private fun updateSendBtnState() {
        val hasPeer = selectedPeer != null
        val textEnabled = if (clipboardSyncMode) hasPeer && !sending
                          else hasPeer && pendingText.isNotEmpty() && !sending
        sendTextBtn?.apply { alpha = if (textEnabled) 1f else 0.4f; isEnabled = textEnabled }
        val fileEnabled = hasPeer && !sending
        val container = fileButtonsContainer ?: return
        for (i in 0 until container.childCount) {
            container.getChildAt(i)?.apply {
                alpha = if (fileEnabled) 1f else 0.4f; isEnabled = fileEnabled
            }
        }
    }

    private fun rebuildFileButtons() {
        val container = fileButtonsContainer ?: return
        container.removeAllViews()
        for ((idx, path) in pendingImages.withIndex()) {
            val btn = makeOutlinedBtn("${NativeLocale.t("send_files_btn")} ${idx + 1}") {
                handleSendFile(path)
            }
            (btn.layoutParams as? LinearLayout.LayoutParams)?.rightMargin = dp(10)
            container.addView(btn)
        }
        for ((path, linkType, label) in pendingLinkedFiles) {
            val displayLabel = if (label.length > 16) label.take(14) + ".." else label
            if (linkType == 4) {
                val btn = makeOutlinedBtn("URL: $displayLabel") { handleSendText(path) }
                (btn.layoutParams as? LinearLayout.LayoutParams)?.rightMargin = dp(10)
                container.addView(btn)
            } else {
                val btn = makeOutlinedBtn(displayLabel) { handleSendFile(path) }
                (btn.layoutParams as? LinearLayout.LayoutParams)?.rightMargin = dp(10)
                container.addView(btn)
            }
        }
    }
}

class LocalSendModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    init {
        startInboxWatcher()
        registerNetworkListener()
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkListener() {
        val cm = reactApplicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (!staticIsRunning) return
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "WiFi network lost – stopping LocalSend server")
                forceCloseAll()
                sendEvent("onLocalSendStopped", Arguments.createMap())
            }
        }
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "WiFi network callback registered")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    override fun onCatalystInstanceDestroy() {
        val cm = reactApplicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        networkCallback = null

        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { multicastSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        multicastSocket = null
        forceCloseAll()
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onCatalystInstanceDestroy: LocalSend server stopped")
    }

    companion object {
        private const val TAG = "LocalSendModule"
        private const val PROTOCOL_VERSION = "2.0"
        private const val DEFAULT_PORT = 53317
        private const val MULTICAST_ADDR = "224.0.0.167"
        private const val MULTICAST_PORT = 53317
        private const val API_BASE = "/api/localsend/v2"

        @Volatile private var staticServerSocket: ServerSocket? = null
        @Volatile private var staticMulticastSocket: MulticastSocket? = null
        @Volatile @JvmStatic var staticIsRunning = false

        fun forceCloseAll() {
            staticIsRunning = false
            try { staticServerSocket?.close() } catch (_: Exception) {}
            try { staticMulticastSocket?.close() } catch (_: Exception) {}
            staticServerSocket = null
            staticMulticastSocket = null
        }

        data class PendingText(val id: String, val text: String, val fileName: String)
        private val pendingTexts = ConcurrentHashMap<String, PendingText>()

        fun addPendingText(text: String, fileName: String): PendingText {
            val pt = PendingText(
                id = UUID.randomUUID().toString().substring(0, 8),
                text = text,
                fileName = fileName
            )
            pendingTexts[pt.id] = pt
            return pt
        }

        fun ackPendingText(id: String) {
            pendingTexts.remove(id)
        }

        fun drainPendingTexts(): List<PendingText> {
            val copy = pendingTexts.values.toList()
            pendingTexts.clear()
            return copy
        }

        @Volatile @JvmStatic
        var staticReceiveDir: String = "/sdcard/LocalSend"

        @Volatile @JvmStatic
        var staticDeviceAlias: String = "Supernote"

        @Volatile @JvmStatic
        var staticDeviceFingerprint: String = ""

        @Volatile @JvmStatic
        var staticDiscoveredPeers: ConcurrentHashMap<String, DiscoveredPeer> = ConcurrentHashMap()

        private const val INBOX_DIR = "/sdcard/INBOX"
        private val IMAGE_EXTS_RECV = setOf("jpg", "jpeg", "png", "bmp", "gif", "webp")

        private val sessionReceivedImages = mutableListOf<ReceivedFileInfo>()

        @JvmStatic
        fun addSessionReceivedImage(info: ReceivedFileInfo) {
            synchronized(sessionReceivedImages) { sessionReceivedImages.add(0, info) }
        }

        @JvmStatic
        fun getReceivedImageFiles(): List<ReceivedFileInfo> {
            val inboxImages = try {
                val dir = File(INBOX_DIR)
                if (dir.exists() && dir.isDirectory) {
                    (dir.listFiles() ?: emptyArray())
                        .filter { !it.isDirectory && !it.name.startsWith(".") }
                        .filter { IMAGE_EXTS_RECV.contains(it.extension.lowercase()) }
                        .map { ReceivedFileInfo(it.name, it.absolutePath, it.length(), it.lastModified(), true) }
                } else emptyList()
            } catch (_: Exception) { emptyList() }

            val sessionImages = synchronized(sessionReceivedImages) {
                sessionReceivedImages.filter { File(it.path).exists() }.toList()
            }

            val seen = mutableSetOf<String>()
            val merged = mutableListOf<ReceivedFileInfo>()
            for (f in inboxImages + sessionImages) {
                if (seen.add(f.path)) merged.add(f)
            }
            merged.sortByDescending { it.modified }
            return merged
        }

        private var inboxObserver: FileObserver? = null

        @JvmStatic
        fun startInboxWatcher() {
            if (inboxObserver != null) return
            File(INBOX_DIR).mkdirs()
            @Suppress("DEPRECATION")
            inboxObserver = object : FileObserver(INBOX_DIR, CREATE or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    val ext = path.substringAfterLast('.', "").lowercase()
                    if (IMAGE_EXTS_RECV.contains(ext)) {
                        ImagePanel.currentInstance?.onFileReceived()
                    }
                    if (DocLinkPanel.DOC_EXTS.contains(ext)) {
                        DocLinkPanel.currentInstance?.onFileReceived()
                    }
                }
            }
            inboxObserver!!.startWatching()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "INBOX watcher started: $INBOX_DIR")
        }

        @JvmStatic
        fun getPeersSnapshot(): List<DiscoveredPeer> {
            val now = System.currentTimeMillis()
            staticDiscoveredPeers.entries.removeIf { now - it.value.lastSeen > 30_000 }
            return staticDiscoveredPeers.values.toList()
        }

        @Volatile private var reprobeRunning = false

        @JvmStatic
        fun reprobeKnownPeers() {
            if (reprobeRunning) return
            val known = staticDiscoveredPeers.values.map { it.ip to it.port }.distinct()
            if (known.isEmpty()) return
            kotlin.concurrent.thread(isDaemon = true, name = "NativePanel-Reprobe") {
                reprobeRunning = true
                try {
                    for ((ip, port) in known) probeHostStatic(ip, port)
                } finally {
                    reprobeRunning = false
                }
            }
        }

        @JvmStatic
        private fun isImageFileStatic(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in listOf("jpg", "jpeg", "png", "bmp", "gif", "webp")
        }

        data class ReceivedFileInfo(
            val name: String, val path: String, val size: Long,
            val modified: Long, val isImage: Boolean
        )

        private val staticTrustAllSsl: SSLContext by lazy {
            val tm = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            })
            SSLContext.getInstance("TLS").apply { init(null, tm, SecureRandom()) }
        }
        private val staticHostnameVerifier = HostnameVerifier { _, _ -> true }

        private fun staticOpenConn(url: String, connectTimeout: Int = 10000, readTimeout: Int = 30000): HttpURLConnection {
            val conn = URL(url).openConnection() as HttpURLConnection
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = staticTrustAllSsl.socketFactory
                conn.hostnameVerifier = staticHostnameVerifier
            }
            conn.connectTimeout = connectTimeout
            conn.readTimeout = readTimeout
            return conn
        }

        private fun staticHttpPostJson(url: String, jsonBody: String): String {
            val conn = staticOpenConn(url)
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                throw Exception("HTTP $code: ${err ?: "no body"}")
            }
            conn.disconnect()
            return body
        }

        private fun staticHttpPostBinary(url: String, data: ByteArray) {
            val conn = staticOpenConn(url, readTimeout = 60000)
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Content-Length", data.size.toString())
            conn.doOutput = true
            conn.outputStream.use { os ->
                var offset = 0
                while (offset < data.size) {
                    val len = minOf(65536, data.size - offset)
                    os.write(data, offset, len)
                    offset += len
                }
                os.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                conn.disconnect()
                throw Exception("Upload HTTP $code: ${err ?: "no body"}")
            }
            conn.disconnect()
        }

        private fun staticSha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        private fun buildSingleFileJson(
            fileId: String, fileName: String, size: Int, fileType: String,
            sha: String, preview: String? = null
        ): JSONObject = JSONObject().apply {
            put(fileId, JSONObject().apply {
                put("id", fileId); put("fileName", fileName)
                put("size", size); put("fileType", fileType)
                put("sha256", sha)
                if (preview != null) put("preview", preview)
            })
        }

        private fun staticGuessMimeType(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "txt" -> "text/plain"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
                "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun sendTextDirect(ip: String, port: Int, text: String, useTls: Boolean = true): String {
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val fileId = "text-${UUID.randomUUID().toString().substring(0, 8)}"
            val filesJson = buildSingleFileJson(
                fileId, "message.txt", textBytes.size, "text/plain",
                staticSha256Hex(textBytes), preview = text
            )
            return doUploadDirect(ip, port, filesJson, mapOf(fileId to textBytes), useTls)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun sendFileDirect(ip: String, port: Int, filePath: String, useTls: Boolean = true): String {
            val file = File(filePath)
            if (!file.exists()) throw Exception("File not found: $filePath")
            val fileBytes = file.readBytes()
            val fileId = "file-${UUID.randomUUID().toString().substring(0, 8)}"
            val filesJson = buildSingleFileJson(
                fileId, file.name, fileBytes.size, staticGuessMimeType(file.name),
                staticSha256Hex(fileBytes)
            )
            return doUploadDirect(ip, port, filesJson, mapOf(fileId to fileBytes), useTls)
        }

        private fun doUploadDirect(
            ip: String, port: Int, filesJson: JSONObject, fileData: Map<String, ByteArray>,
            useTls: Boolean = true
        ): String {
            val prepareBody = JSONObject().apply {
                put("info", JSONObject().apply {
                    put("alias", staticDeviceAlias); put("version", PROTOCOL_VERSION)
                    put("deviceModel", "Supernote"); put("deviceType", "mobile")
                    put("fingerprint", staticDeviceFingerprint)
                })
                put("files", filesJson)
            }
            try {
                val prepareResp: String
                if (useTls) {
                    val baseUrl = "https://$ip:$port$API_BASE"
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND-DBG] doUploadDirect HTTPS baseUrl=$baseUrl")
                    prepareResp = staticHttpPostJson("$baseUrl/prepare-upload", prepareBody.toString())
                } else {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND-DBG] doUploadDirect raw-socket HTTP to $ip:$port")
                    prepareResp = staticRawSocketPostJson(ip, port,
                        "$API_BASE/prepare-upload", prepareBody.toString())
                }
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND-DBG] prepare-upload response: $prepareResp")
                val prepareJson = JSONObject(prepareResp)
                val sessionId = prepareJson.optString("sessionId", "")
                val tokenMap = prepareJson.optJSONObject("files")
                if (sessionId.isEmpty()) { if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND-DBG] auto-accepted"); return "auto" }
                for ((fileId, data) in fileData) {
                    val token = tokenMap?.optString(fileId, "") ?: ""
                    if (token.isEmpty()) continue
                    val path = "$API_BASE/upload?sessionId=$sessionId&fileId=$fileId&token=$token"
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND-DBG] uploading $fileId size=${data.size}")
                    if (useTls) {
                        staticHttpPostBinary("https://$ip:$port$path", data)
                    } else {
                        staticRawSocketPostBinary(ip, port, path, data)
                    }
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND-DBG] upload $fileId done")
                }
                return sessionId
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[SEND-DBG] doUploadDirect FAILED: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }

        private fun staticRawSocketPostJson(ip: String, port: Int, path: String, jsonBody: String): String {
            val body = jsonBody.toByteArray(Charsets.UTF_8)
            val sock = Socket()
            sock.connect(InetSocketAddress(ip, port), 5000)
            sock.soTimeout = 10000
            val out = sock.getOutputStream()
            val header = "POST $path HTTP/1.1\r\nHost: $ip:$port\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: keep-alive\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(body)
            out.flush()
            val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
            val statusLine = reader.readLine() ?: throw IOException("No response")
            if (!statusLine.contains("200")) { sock.close(); throw IOException("HTTP POST failed: $statusLine") }
            var contentLen = -1
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                if (h.lowercase().startsWith("content-length:")) contentLen = h.substringAfter(":").trim().toIntOrNull() ?: -1
            }
            val respBody = if (contentLen > 0) {
                val buf = CharArray(contentLen)
                var read = 0
                while (read < contentLen) { val r = reader.read(buf, read, contentLen - read); if (r < 0) break; read += r }
                String(buf, 0, read)
            } else reader.readText()
            sock.close()
            return respBody
        }

        private fun staticRawSocketPostBinary(ip: String, port: Int, path: String, data: ByteArray) {
            val sock = Socket()
            sock.connect(InetSocketAddress(ip, port), 5000)
            sock.soTimeout = 30000
            val out = sock.getOutputStream()
            val header = "POST $path HTTP/1.1\r\nHost: $ip:$port\r\nContent-Type: application/octet-stream\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(data)
            out.flush()
            val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
            val statusLine = reader.readLine() ?: throw IOException("No response")
            if (!statusLine.contains("200")) { sock.close(); throw IOException("HTTP POST failed: $statusLine") }
            sock.close()
        }

        @JvmStatic
        fun triggerScan() {
            if (scanRunningStatic) return
            thread(isDaemon = true, name = "NativePanel-Scan") {
                scanRunningStatic = true
                try {
                    val localIp = getLocalIpStatic()
                    if (localIp == "0.0.0.0") return@thread
                    val subnet = localIp.substringBeforeLast('.')
                    val executor = Executors.newFixedThreadPool(50)
                    for (i in 1..254) {
                        val ip = "$subnet.$i"
                        if (ip == localIp) continue
                        executor.submit { probeHostStatic(ip, DEFAULT_PORT) }
                    }
                    executor.shutdown()
                    executor.awaitTermination(6, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "triggerScan error", e)
                } finally {
                    scanRunningStatic = false
                }
            }
        }

        @Volatile private var scanRunningStatic = false

        private fun getLocalIpStatic(): String {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress ?: "0.0.0.0"
                    }
                }
                "0.0.0.0"
            } catch (_: Exception) { "0.0.0.0" }
        }

        private fun probeHostStatic(ip: String, port: Int) {

            try {
                val conn = staticOpenConn("https://$ip:$port$API_BASE/info", 1500, 1500)
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    registerPeerFromJson(body, ip, port, useTls = true)
                    return
                }
                conn.disconnect()
            } catch (_: Exception) {}

            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(ip, port), 1500)
                sock.soTimeout = 1500
                val out = sock.getOutputStream()
                val req = "GET $API_BASE/info HTTP/1.1\r\nHost: $ip:$port\r\nConnection: close\r\n\r\n"
                out.write(req.toByteArray(Charsets.UTF_8))
                out.flush()
                val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                val statusLine = reader.readLine() ?: ""
                if (!statusLine.contains("200")) { sock.close(); return }
                while (true) { if ((reader.readLine() ?: break).isEmpty()) break }
                val body = reader.readText()
                sock.close()
                registerPeerFromJson(body, ip, port, useTls = false)
            } catch (_: Exception) {}
        }

        private fun registerPeerFromJson(body: String, ip: String, port: Int, useTls: Boolean = true) {
            val data = JSONObject(body)
            val fp = data.optString("fingerprint", "")
            if (fp.isNotEmpty() && fp != staticDeviceFingerprint) {
                staticDiscoveredPeers[fp] = DiscoveredPeer(
                    data.optString("alias", "Unknown"), ip,
                    data.optInt("port", port), data.optString("deviceType", "desktop"), fp,
                    useTls = useTls
                )
            }
        }
    }

    private var serverSocket: ServerSocket?
        get() = staticServerSocket
        set(v) { staticServerSocket = v }
    private var multicastSocket: MulticastSocket?
        get() = staticMulticastSocket
        set(v) { staticMulticastSocket = v }
    private var isRunning: Boolean
        get() = staticIsRunning
        set(v) { staticIsRunning = v }
    private var serverPort = DEFAULT_PORT
    private var deviceAlias = "Supernote-${(1000..9999).random()}"
    private var deviceFingerprint = UUID.randomUUID().toString().replace("-", "")
    private var receiveDir = "/sdcard/LocalSend"
    private var pin = ""

    private val uploadSessions = ConcurrentHashMap<String, UploadSession>()
    private var activeUploadSession: String? = null

    data class DiscoveredPeer(
        val alias: String,
        val ip: String,
        val port: Int,
        val deviceType: String,
        val fingerprint: String,
        val lastSeen: Long = System.currentTimeMillis(),
        val useTls: Boolean = true
    )
    private val discoveredPeers = ConcurrentHashMap<String, DiscoveredPeer>()

    private val knownSenderIps = Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile private var scanRunning = false

    override fun getName(): String = "LocalSendModule"

    @ReactMethod
    fun startServer(config: ReadableMap, promise: Promise) {
        if (isRunning) {
            promise.resolve("already_running")
            return
        }

        forceCloseAll()

        try {
            deviceAlias = config.getString("alias") ?: "Supernote"
            serverPort = if (config.hasKey("port")) config.getInt("port") else DEFAULT_PORT
            receiveDir = config.getString("dest") ?: "/sdcard/LocalSend"
            pin = config.getString("pin") ?: ""

            staticReceiveDir = receiveDir
            staticDeviceAlias = deviceAlias
            staticDeviceFingerprint = deviceFingerprint
            staticDiscoveredPeers = discoveredPeers

            File(receiveDir).mkdirs()

            isRunning = true

            thread(isDaemon = true, name = "LocalSend-Server") {
                runHttpServer()
            }

            thread(isDaemon = true, name = "LocalSend-Multicast") {
                runMulticastDiscovery()
            }

            val localIp = getLocalIp()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "LocalSend server started on $localIp:$serverPort")
            sendEvent("onServerStarted", Arguments.createMap().apply {
                putString("ip", localIp)
                putInt("port", serverPort)
                putString("alias", deviceAlias)
            })
            promise.resolve("started")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "Failed to start server", e)
            isRunning = false
            promise.reject("START_FAILED", e.message)
        }
    }

    @ReactMethod
    fun stopServer(promise: Promise) {
        isRunning = false
        try {
            serverSocket?.close()
            multicastSocket?.close()
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "Error closing sockets", e)
        }
        serverSocket = null
        multicastSocket = null
        uploadSessions.clear()
        activeUploadSession = null
        discoveredPeers.clear()
        sendEvent("onServerStopped", Arguments.createMap())
        promise.resolve("stopped")
    }

    @ReactMethod
    fun isWifiConnected(promise: Promise) {
        val cm = reactApplicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) { promise.resolve(false); return }
        val net = cm.activeNetwork
        val caps = if (net != null) cm.getNetworkCapabilities(net) else null
        promise.resolve(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
    }

    @ReactMethod
    fun getServerStatus(promise: Promise) {
        val map = Arguments.createMap()
        map.putBoolean("running", isRunning)
        map.putString("ip", getLocalIp())
        map.putInt("port", serverPort)
        map.putString("alias", deviceAlias)
        map.putString("receiveDir", receiveDir)
        map.putInt("activeSessions", uploadSessions.size)
        promise.resolve(map)
    }

    @ReactMethod
    fun getReceivedFiles(promise: Promise) {
        try {
            val dir = File(receiveDir)
            val files = Arguments.createArray()
            if (dir.exists()) {
                dir.listFiles()
                    ?.filter { it.isFile }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { file ->
                        val fileMap = Arguments.createMap()
                        fileMap.putString("name", file.name)
                        fileMap.putString("path", file.absolutePath)
                        fileMap.putDouble("size", file.length().toDouble())
                        fileMap.putDouble("modified", file.lastModified().toDouble())
                        fileMap.putBoolean("isImage", isImageFile(file.name))
                        files.pushMap(fileMap)
                    }
            }
            promise.resolve(files)
        } catch (e: Exception) {
            promise.reject("LIST_ERROR", e.message)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}

    @ReactMethod
    fun getDiscoveredPeers(promise: Promise) {
        val now = System.currentTimeMillis()
        discoveredPeers.entries.removeIf { now - it.value.lastSeen > 30_000 }

        val arr = Arguments.createArray()
        discoveredPeers.values.forEach { peer ->
            arr.pushMap(Arguments.createMap().apply {
                putString("alias", peer.alias)
                putString("ip", peer.ip)
                putInt("port", peer.port)
                putString("deviceType", peer.deviceType)
                putString("fingerprint", peer.fingerprint)
            })
        }
        promise.resolve(arr)
    }

    @ReactMethod
    fun scanForPeers(promise: Promise) {
        if (scanRunning) {
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "scanForPeers: already running, skipping")
            promise.resolve("scan_already_running")
            return
        }
        thread(isDaemon = true, name = "LocalSend-Scan") {
            scanRunning = true
            discoveredPeers.clear()
            try {
                val localIp = getLocalIp()
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "scanForPeers: localIp=$localIp")
                if (localIp == "0.0.0.0") {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "scanForPeers: no network, aborting")
                    promise.resolve("no_network")
                    return@thread
                }

                val subnet = localIp.substringBeforeLast('.')
                val localSuffix = localIp.substringAfterLast('.').toIntOrNull() ?: 0
                val executor = Executors.newFixedThreadPool(50)

                val knownCopy = synchronized(knownSenderIps) { knownSenderIps.toList() }
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "scanForPeers: phase1 known IPs: $knownCopy")
                for (ip in knownCopy) {
                    executor.submit { probeHost(ip, DEFAULT_PORT) }
                }

                val skipIps = knownCopy.toSet() + localIp
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "scanForPeers: phase2 scanning subnet $subnet.1-254 (skipping ${skipIps.size} IPs)")
                for (i in 1..254) {
                    val ip = "$subnet.$i"
                    if (ip in skipIps) continue
                    executor.submit { probeHost(ip, DEFAULT_PORT) }
                }

                executor.shutdown()
                val finished = executor.awaitTermination(6, TimeUnit.SECONDS)
                if (!finished) {

                    executor.awaitTermination(4, TimeUnit.SECONDS)
                }
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "scanForPeers: done (allFinished=$finished), discovered ${discoveredPeers.size} peers total")
                promise.resolve("scan_done")
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "scanForPeers error", e)
                promise.reject("SCAN_ERROR", e.message)
            } finally {
                scanRunning = false
            }
        }
    }

    private fun probeHost(ip: String, port: Int) {

        try {
            val url = "https://$ip:$port$API_BASE/info"
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "probeHost: trying $url")
            val conn = openConn(url, connectTimeout = 500, readTimeout = 500)
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "probeHost: $url → HTTP $code")
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                registerPeerFromProbe(body, ip, port, useTls = true)
                return
            }
            conn.disconnect()
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "probeHost: https://$ip:$port failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(ip, port), 500)
            sock.soTimeout = 500
            val out = sock.getOutputStream()
            val req = "GET $API_BASE/info HTTP/1.1\r\nHost: $ip:$port\r\nConnection: close\r\n\r\n"
            out.write(req.toByteArray(Charsets.UTF_8))
            out.flush()
            val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
            val statusLine = reader.readLine() ?: ""
            if (!statusLine.contains("200")) { sock.close(); return }
            while (true) { if ((reader.readLine() ?: break).isEmpty()) break }
            val body = reader.readText()
            sock.close()
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "probeHost: http://$ip:$port (raw) → 200")
            registerPeerFromProbe(body, ip, port, useTls = false)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "probeHost: http://$ip:$port (raw) failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun registerPeerFromProbe(body: String, ip: String, port: Int, useTls: Boolean = true) {
        if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "probeHost: $ip response body: $body")
        val data = JSONObject(body)
        val fp = data.optString("fingerprint", "")
        if (fp.isNotEmpty() && fp != deviceFingerprint) {
            val peerAlias = data.optString("alias", "Unknown")
            val peerPort = data.optInt("port", port)
            val peerDeviceType = data.optString("deviceType", "desktop")
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "probeHost: FOUND peer $peerAlias @ $ip:$peerPort (tls=$useTls)")
            discoveredPeers[fp] = DiscoveredPeer(
                alias = peerAlias, ip = ip, port = peerPort,
                deviceType = peerDeviceType, fingerprint = fp,
                useTls = useTls
            )
            sendEvent("onPeerFound", Arguments.createMap().apply {
                putString("alias", peerAlias)
                putString("ip", ip)
                putString("deviceType", peerDeviceType)
                putInt("port", peerPort)
                putString("fingerprint", fp)
            })
        }
    }

    @ReactMethod
    fun sendText(ip: String, port: Int, text: String, promise: Promise) {
        thread(isDaemon = true, name = "LocalSend-SendText") {
            try {
                val textBytes = text.toByteArray(Charsets.UTF_8)
                val fileId = "text-${UUID.randomUUID().toString().substring(0, 8)}"
                val filesJson = buildSingleFileJson(
                    fileId, "message.txt", textBytes.size, "text/plain",
                    sha256Hex(textBytes), preview = text
                )
                val result = doLocalSendUpload(ip, port, filesJson, mapOf(fileId to textBytes))
                promise.resolve(result)
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "sendText failed", e)
                sendEvent("onSendError", Arguments.createMap().apply {
                    putString("error", e.message ?: "Unknown error")
                })
                promise.reject("SEND_FAILED", e.message)
            }
        }
    }

    @ReactMethod
    fun sendFile(ip: String, port: Int, filePath: String, promise: Promise) {
        thread(isDaemon = true, name = "LocalSend-SendFile") {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    promise.reject("FILE_NOT_FOUND", "File not found: $filePath")
                    return@thread
                }

                val fileBytes = file.readBytes()
                val fileId = "file-${UUID.randomUUID().toString().substring(0, 8)}"
                val filesJson = buildSingleFileJson(
                    fileId, file.name, fileBytes.size, guessMimeType(file.name),
                    sha256Hex(fileBytes)
                )
                val result = doLocalSendUpload(ip, port, filesJson, mapOf(fileId to fileBytes))
                promise.resolve(result)
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "sendFile failed", e)
                sendEvent("onSendError", Arguments.createMap().apply {
                    putString("error", e.message ?: "Unknown error")
                })
                promise.reject("SEND_FAILED", e.message)
            }
        }
    }

    @ReactMethod
    fun flushPendingTexts(promise: Promise) {
        val pending = drainPendingTexts()
        val result = Arguments.createArray()
        for (pt in pending) {
            result.pushMap(Arguments.createMap().apply {
                putString("_pendingId", pt.id)
                putString("text", pt.text)
                putString("fileName", pt.fileName)
            })
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "flushPendingTexts: returning ${pending.size} unacked text(s)")
        promise.resolve(result)
    }

    @ReactMethod
    fun ackPendingText(id: String) {
        Companion.ackPendingText(id)
        if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "ackPendingText: id=$id, remaining=${pendingTexts.size}")
    }

    private fun doLocalSendUpload(
        ip: String,
        port: Int,
        filesJson: JSONObject,
        fileData: Map<String, ByteArray>
    ): String {
        val peer = discoveredPeers.values.find { it.ip == ip && it.port == port }
        val scheme = if (peer?.useTls != false) "https" else "http"
        val baseUrl = "$scheme://$ip:$port$API_BASE"

        val prepareBody = JSONObject().apply {
            put("info", JSONObject().apply {
                put("alias", deviceAlias)
                put("version", PROTOCOL_VERSION)
                put("deviceModel", "Supernote")
                put("deviceType", "mobile")
                put("fingerprint", deviceFingerprint)
            })
            put("files", filesJson)
        }

        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "doLocalSendUpload: target=$baseUrl")
        if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "doLocalSendUpload: prepareBody=${prepareBody.toString().take(500)}")
        val prepareResp = httpPostJson("$baseUrl/prepare-upload", prepareBody.toString())
        if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "doLocalSendUpload: prepareResp=$prepareResp")
        val prepareJson = JSONObject(prepareResp)

        val sessionId = prepareJson.optString("sessionId", "")
        val tokenMap = prepareJson.optJSONObject("files")

        if (sessionId.isEmpty()) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "doLocalSendUpload: receiver auto-accepted (no sessionId), transfer complete")
            sendEvent("onSendComplete", Arguments.createMap().apply {
                putString("sessionId", "auto")
            })
            return "auto"
        }

        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Got sessionId=$sessionId, uploading ${fileData.size} file(s)")
        sendEvent("onSendStarted", Arguments.createMap().apply {
            putString("sessionId", sessionId)
            putInt("fileCount", fileData.size)
            putString("targetIp", ip)
        })

        for ((fileId, data) in fileData) {
            val token = tokenMap?.optString(fileId, "") ?: ""
            if (token.isEmpty()) {
                if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "doLocalSendUpload: no token for $fileId, skipping upload")
                continue
            }

            val uploadUrl = "$baseUrl/upload?sessionId=$sessionId&fileId=$fileId&token=$token"
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Uploading fileId=$fileId (${data.size} bytes)")
            httpPostBinary(uploadUrl, data)

            val fileInfo = filesJson.optJSONObject(fileId)
            sendEvent("onSendProgress", Arguments.createMap().apply {
                putString("fileId", fileId)
                putString("fileName", fileInfo?.optString("fileName") ?: "")
                putInt("percent", 100)
            })
        }

        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Send complete for session $sessionId")
        sendEvent("onSendComplete", Arguments.createMap().apply {
            putString("sessionId", sessionId)
        })
        return sessionId
    }

    private fun httpPostJson(url: String, jsonBody: String): String = staticHttpPostJson(url, jsonBody)

    private fun httpPostBinary(url: String, data: ByteArray) = staticHttpPostBinary(url, data)

    private fun sha256Hex(data: ByteArray): String = staticSha256Hex(data)

    private fun guessMimeType(name: String): String = staticGuessMimeType(name)

    private fun runHttpServer() {
        try {
            val sock = ServerSocket()
            sock.reuseAddress = true
            var boundPort = serverPort
            var bindOk = false
            for (attempt in 0..9) {
                val tryPort = serverPort + attempt
                try {
                    sock.bind(InetSocketAddress(tryPort))
                    boundPort = tryPort
                    bindOk = true
                    break
                } catch (e: java.net.BindException) {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "Port $tryPort unavailable (${e.message}), trying next...")
                }
            }
            if (!bindOk) {
                sock.close()
                throw java.net.BindException("No available port in range $serverPort..${serverPort+9}")
            }
            serverPort = boundPort
            serverSocket = sock
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "HTTP server listening on port $serverPort")

            while (isRunning) {
                try {
                    val client = serverSocket?.accept() ?: break
                    thread(isDaemon = true) {
                        handleClient(client)
                    }
                } catch (e: SocketException) {
                    if (isRunning) if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "Socket accept error", e)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "HTTP server error", e)
            sendEvent("onServerError", Arguments.createMap().apply {
                putString("error", e.message ?: "Unknown error")
            })
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val remoteIp = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "0.0.0.0"
            val input: BufferedInputStream
            val output: BufferedOutputStream
            try {
                input = BufferedInputStream(socket.inputStream)
                output = BufferedOutputStream(socket.outputStream)
            } catch (e: SSLException) {
                if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "[RECV-DBG] TLS handshake failed from $remoteIp (non-TLS client?), ignoring")
                try { socket.close() } catch (_: Exception) {}
                return
            }

            val requestLine = readLine(input) ?: return
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "[RECV-DBG] request: $requestLine from $remoteIp")
            val parts = requestLine.split(" ")
            if (parts.size < 3) return

            val method = parts[0]
            val rawPath = parts[1]

            val headers = mutableMapOf<String, String>()
            var line = readLine(input)
            while (!line.isNullOrEmpty()) {
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    headers[line.substring(0, colonIdx).trim().lowercase()] =
                        line.substring(colonIdx + 1).trim()
                }
                line = readLine(input)
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

            val qIdx = rawPath.indexOf('?')
            val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
            val queryString = if (qIdx >= 0) rawPath.substring(qIdx + 1) else ""
            val queryParams = parseQuery(queryString)

            when {
                method == "GET" && path == "$API_BASE/info" ->
                    handleInfo(output)

                method == "POST" && path == "$API_BASE/register" -> {
                    val body = readBody(input, contentLength)
                    handleRegister(output, body, remoteIp)
                }

                method == "POST" && path == "$API_BASE/prepare-upload" -> {
                    val body = readBody(input, contentLength)
                    handlePrepareUpload(output, body, remoteIp, queryParams)
                }

                method == "POST" && path == "$API_BASE/upload" ->
                    handleUpload(output, input, remoteIp, queryParams, contentLength)

                method == "POST" && path == "$API_BASE/cancel" ->
                    handleCancel(output, queryParams)

                else ->
                    sendHttpResponse(output, 404, """{"error":"Not found"}""")
            }
        } catch (e: SSLException) {
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "[RECV-DBG] TLS error from client (non-TLS?), ignoring: ${e.message}")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "Client handler error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleInfo(output: BufferedOutputStream) {
        val info = JSONObject().apply {
            put("alias", deviceAlias)
            put("version", PROTOCOL_VERSION)
            put("deviceModel", "Supernote")
            put("deviceType", "mobile")
            put("fingerprint", deviceFingerprint)
            put("download", false)
        }
        sendHttpResponse(output, 200, info.toString())
    }

    private fun handleRegister(output: BufferedOutputStream, body: String, remoteIp: String) {
        try {
            val data = JSONObject(body)
            val peerAlias = data.optString("alias", "Unknown")
            val peerPort = data.optInt("port", DEFAULT_PORT)
            val peerDeviceType = data.optString("deviceType", "desktop")
            val peerFingerprint = data.optString("fingerprint", remoteIp)

            knownSenderIps.add(remoteIp)

            val existing = discoveredPeers[peerFingerprint]
            val isNew     = existing == null
            val isChanged = existing != null && (
                existing.alias != peerAlias ||
                existing.ip    != remoteIp  ||
                existing.port  != peerPort
            )

            discoveredPeers[peerFingerprint] = DiscoveredPeer(
                alias = peerAlias,
                ip = remoteIp,
                port = peerPort,
                deviceType = peerDeviceType,
                fingerprint = peerFingerprint
            )

            if (isNew || isChanged) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Device registered: $peerAlias from $remoteIp")
                sendEvent("onPeerFound", Arguments.createMap().apply {
                    putString("alias", peerAlias)
                    putString("ip", remoteIp)
                    putString("deviceType", peerDeviceType)
                    putInt("port", peerPort)
                    putString("fingerprint", peerFingerprint)
                })
            } else {
                if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "Device re-registered (no change): $peerAlias from $remoteIp")
            }

            val response = JSONObject().apply {
                put("alias", deviceAlias)
                put("version", PROTOCOL_VERSION)
                put("deviceModel", "Supernote")
                put("deviceType", "mobile")
                put("fingerprint", deviceFingerprint)
                put("port", serverPort)
                put("protocol", "http")
                put("download", false)
            }
            sendHttpResponse(output, 200, response.toString())
        } catch (e: Exception) {
            sendHttpResponse(output, 400, """{"error":"Invalid body"}""")
        }
    }

    private fun handlePrepareUpload(
        output: BufferedOutputStream, body: String,
        remoteIp: String, params: Map<String, String>
    ) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[RECV-DBG] handlePrepareUpload from $remoteIp body=${body.take(200)}")

        if (pin.isNotEmpty()) {
            val pinParam = params["pin"] ?: ""
            if (pinParam != pin) {
                val msg = if (pinParam.isEmpty()) "PIN required" else "Invalid PIN"
                sendHttpResponse(output, 401, """{"error":"$msg"}""")
                return
            }
        }

        activeUploadSession?.let { sid ->
            uploadSessions[sid]?.let { sess ->
                if (sess.isValid()) {
                    sendHttpResponse(output, 409, """{"error":"Blocked by another session"}""")
                    return
                }
            }
            activeUploadSession = null
        }

        try {
            val data = JSONObject(body)
            val files = data.optJSONObject("files")
            val info = data.optJSONObject("info")
            if (files == null || files.length() == 0) {
                sendHttpResponse(output, 400, """{"error":"Invalid body"}""")
                return
            }

            val senderAlias = info?.optString("alias", "Unknown") ?: "Unknown"

            knownSenderIps.add(remoteIp)

            val allPreviews = mutableListOf<String>()
            val keys0 = files.keys()
            while (keys0.hasNext()) {
                val fileId = keys0.next()
                val fileData = files.getJSONObject(fileId)
                val preview = fileData.optString("preview", "")
                val fileType = fileData.optString("fileType", "")
                if (preview.isNotEmpty() && fileType.startsWith("text/")) {
                    allPreviews.add(preview)
                }
            }
            if (allPreviews.isNotEmpty() && allPreviews.size == files.length()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Text message(s) from [$senderAlias] via preview field: ${allPreviews.size}")
                val combined = allPreviews.joinToString("\n")
                sendTextViaBroadcast(combined)

                val textSessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 22)
                val response = JSONObject().apply {
                    put("sessionId", textSessionId)
                    put("files", JSONObject())
                }
                sendHttpResponse(output, 200, response.toString())
                return
            }

            val sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 22)
            val session = UploadSession(sessionId, remoteIp, senderAlias = senderAlias)

            val tokens = JSONObject()
            val fileNames = mutableListOf<String>()

            val keys = files.keys()
            while (keys.hasNext()) {
                val fileId = keys.next()
                val fileData = files.getJSONObject(fileId)
                val fileName = fileData.optString("fileName", "unknown")
                val fileSize = fileData.optLong("size", 0)
                val fileType = fileData.optString("fileType", "application/octet-stream")
                val sha256 = fileData.optString("sha256", "")

                val token = UUID.randomUUID().toString().replace("-", "").substring(0, 22)
                session.files[fileId] = FileInfo(fileId, fileName, fileSize, fileType, sha256)
                session.tokens[fileId] = token
                session.received[fileId] = false
                tokens.put(fileId, token)
                fileNames.add(fileName)
            }

            val isClipboardSync = fileNames.any {
                it.startsWith("clipboard_sync_") && it.endsWith(".zip")
            }

            if (isClipboardSync) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var accepted = false
                val msg = NativeLocale.t("sync_clipboard_ask").replace("%s", senderAlias)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] showRattaDialog for prepare-upload confirm")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    com.ratta.supernote.pluginlib.api.HostUIAPI.getInstance().showRattaDialog(
                        reactApplicationContext.currentActivity, msg,
                        NativeLocale.t("cancel"), NativeLocale.t("confirm"), false,
                        object : com.ratta.supernote.pluginlib.callback.RattaDialogListener {
                            override fun onConfirm() { accepted = true; latch.countDown() }
                            override fun onCancel() { accepted = false; latch.countDown() }
                        }
                    )
                }
                latch.await(30, TimeUnit.SECONDS)
                if (!accepted) {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Clipboard sync rejected by user from [$senderAlias]")
                    sendHttpResponse(output, 403, """{"error":"Rejected by user"}""")
                    return
                }
            }

            uploadSessions[sessionId] = session
            activeUploadSession = sessionId

            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Accepted transfer from [$senderAlias]: ${fileNames.size} files")
            fileNames.forEach { if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "  - $it") }

            sendEvent("onTransferStarted", Arguments.createMap().apply {
                putString("sender", senderAlias)
                putInt("fileCount", fileNames.size)
                putString("sessionId", sessionId)
                val arr = Arguments.createArray()
                fileNames.forEach { arr.pushString(it) }
                putArray("fileNames", arr)
            })

            val response = JSONObject().apply {
                put("sessionId", sessionId)
                put("files", tokens)
            }
            sendHttpResponse(output, 200, response.toString())
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "prepare-upload error", e)
            sendHttpResponse(output, 400, """{"error":"Invalid body"}""")
        }
    }

    private fun handleUpload(
        output: BufferedOutputStream, input: BufferedInputStream,
        remoteIp: String, params: Map<String, String>, contentLength: Int
    ) {
        val sessionId = params["sessionId"] ?: ""
        val fileId = params["fileId"] ?: ""
        val token = params["token"] ?: ""

        if (sessionId.isEmpty() || fileId.isEmpty() || token.isEmpty()) {
            sendHttpResponse(output, 400, """{"error":"Missing parameters"}""")
            return
        }

        val session = uploadSessions[sessionId]
        if (session == null || !session.isValid()) {
            sendHttpResponse(output, 403, """{"error":"Invalid token or IP address"}""")
            return
        }
        if (session.tokens[fileId] != token) {
            sendHttpResponse(output, 403, """{"error":"Invalid token or IP address"}""")
            return
        }
        if (session.senderIp != remoteIp) {
            sendHttpResponse(output, 403, """{"error":"Invalid token or IP address"}""")
            return
        }

        val fileInfo = session.files[fileId]
        if (fileInfo == null) {
            sendHttpResponse(output, 400, """{"error":"Invalid file"}""")
            return
        }

        val dir = File(receiveDir)
        dir.mkdirs()
        val destFile = safeFileName(fileInfo.fileName, dir)

        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Receiving file: ${fileInfo.fileName} -> $destFile")

        try {
            var received = 0L
            val sha = MessageDigest.getInstance("SHA-256")
            val fos = FileOutputStream(destFile)
            val buf = ByteArray(65536)
            val total = if (contentLength > 0) contentLength.toLong() else fileInfo.size

            while (received < total) {
                val toRead = minOf(buf.size.toLong(), total - received).toInt()
                val n = input.read(buf, 0, toRead)
                if (n <= 0) break
                fos.write(buf, 0, n)
                sha.update(buf, 0, n)
                received += n

                if (received % 262144 < n.toLong()) {
                    val pct = if (total > 0) (received * 100 / total).toInt() else 0
                    sendEvent("onTransferProgress", Arguments.createMap().apply {
                        putString("fileName", fileInfo.fileName)
                        putDouble("received", received.toDouble())
                        putDouble("total", total.toDouble())
                        putInt("percent", pct)
                    })
                }
            }
            fos.close()

            if (fileInfo.sha256.isNotEmpty()) {
                val computed = sha.digest().joinToString("") { "%02x".format(it) }
                if (!computed.equals(fileInfo.sha256, ignoreCase = true)) {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "SHA256 mismatch for ${fileInfo.fileName}")
                }
            }

            session.received[fileId] = true
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "File received: ${fileInfo.fileName} -> $destFile ($received bytes)")

            if (fileInfo.fileName.startsWith("clipboard_sync_") && fileInfo.fileName.endsWith(".zip")) {
                handleClipboardSyncReceive(destFile, session.senderAlias ?: remoteIp)
            } else if (isTextFile(fileInfo.fileName)) {
                val textContent = destFile.readText(Charsets.UTF_8)
                destFile.delete()
                sendTextViaBroadcast(textContent)
            } else {
                if (isImageFile(fileInfo.fileName)) {
                    addSessionReceivedImage(ReceivedFileInfo(
                        fileInfo.fileName, destFile.absolutePath,
                        received, destFile.lastModified(), true
                    ))
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Added to session received: ${destFile.absolutePath}, total=${getReceivedImageFiles().size}")
                    ImagePanel.currentInstance?.onFileReceived()
                }
                if (DocLinkPanel.DOC_EXTS.contains(fileInfo.fileName.substringAfterLast('.', "").lowercase())) {
                    DocLinkPanel.currentInstance?.onFileReceived()
                }
                sendEvent("onFileReceived", Arguments.createMap().apply {
                    putString("fileName", fileInfo.fileName)
                    putString("path", destFile.absolutePath)
                    putDouble("size", received.toDouble())
                    putBoolean("isImage", isImageFile(fileInfo.fileName))
                })
            }

            if (session.received.values.all { it }) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Session $sessionId complete!")
                activeUploadSession = null
                sendEvent("onTransferComplete", Arguments.createMap().apply {
                    putString("sessionId", sessionId)
                })
            }

            sendHttpResponse(output, 200, "")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "File receive error", e)
            if (destFile.exists()) destFile.delete()
            sendHttpResponse(output, 500, """{"error":"Unknown error by receiver"}""")
        }
    }

    private fun handleCancel(output: BufferedOutputStream, params: Map<String, String>) {
        val sessionId = params["sessionId"] ?: ""
        if (sessionId.isNotEmpty()) {
            uploadSessions.remove(sessionId)
            if (activeUploadSession == sessionId) {
                activeUploadSession = null
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Session cancelled: $sessionId")
        }
        sendHttpResponse(output, 200, "")
    }

    private fun runMulticastDiscovery() {
        try {
            val group = InetAddress.getByName(MULTICAST_ADDR)
            multicastSocket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(MULTICAST_PORT))
                joinGroup(group)
            }

            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Multicast announce started on $MULTICAST_ADDR:$MULTICAST_PORT")

            while (isRunning) {
                try {
                    val announcement = JSONObject().apply {
                        put("alias", deviceAlias)
                        put("version", PROTOCOL_VERSION)
                        put("deviceModel", "Supernote")
                        put("deviceType", "mobile")
                        put("fingerprint", deviceFingerprint)
                        put("port", serverPort)
                        put("protocol", "http")
                        put("download", false)
                        put("announce", true)
                    }
                    val data = announcement.toString().toByteArray()
                    val packet = DatagramPacket(data, data.size, group, MULTICAST_PORT)
                    multicastSocket?.send(packet)
                } catch (e: Exception) {
                    if (isRunning) if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "Announce error: ${e.message}")
                }
                Thread.sleep(5000)
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "Multicast announce error", e)
        }
    }

    private fun sendTextViaBroadcast(text: String) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "sendTextViaBroadcast: len=${text.length}")
        val intent = Intent("com.dictation.TEXT_TO_PLUGIN").apply {
            putExtra("text", text)
        }
        reactApplicationContext.sendBroadcast(intent)
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        if (eventName == "onTextReceived") {

            val text = params.getString("text") ?: ""
            val fileName = params.getString("fileName") ?: "message.txt"
            val pt = addPendingText(text, fileName)
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "sendEvent → $eventName buffered id=${pt.id}, trying emit")
            params.putString("_pendingId", pt.id)
        }

        if (!reactApplicationContext.hasActiveCatalystInstance()) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "sendEvent → $eventName: bridge unavailable, will flush on next activation")
            return
        }

        try {
            reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "sendEvent → $eventName OK")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "sendEvent → $eventName FAILED (bridge transition): ${e.message}")
        }

    }

    private fun openConn(url: String, connectTimeout: Int = 10_000, readTimeout: Int = 30_000): HttpURLConnection =
        staticOpenConn(url, connectTimeout, readTimeout)

    private fun getLocalIp(): String = getLocalIpStatic()

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) {
                try {
                    java.net.URLDecoder.decode(kv[0], "UTF-8") to
                        java.net.URLDecoder.decode(kv[1], "UTF-8")
                } catch (e: Exception) { null }
            } else null
        }.toMap()
    }

    private fun sendHttpResponse(output: BufferedOutputStream, status: Int, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }

        val bodyBytes = body.toByteArray()
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        if (bodyBytes.isNotEmpty()) {
            output.write(bodyBytes)
        }
        output.flush()
    }

    private fun safeFileName(name: String, dir: File): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dotIdx = name.lastIndexOf('.')
        val stem = if (dotIdx > 0) name.substring(0, dotIdx) else name
        val ext = if (dotIdx > 0) name.substring(dotIdx) else ""
        var counter = 1
        while (target.exists()) {
            target = File(dir, "${stem}(${counter})${ext}")
            counter++
        }
        return target
    }

    private fun isImageFile(name: String): Boolean = isImageFileStatic(name)

    private fun isTextFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext == "txt"
    }

    private fun handleClipboardSyncReceive(zipFile: File, senderAlias: String) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Clipboard sync received from [$senderAlias], importing (user already confirmed in prepare-upload)")
        thread(isDaemon = true) { doImportClipboardSync(zipFile) }
    }

    private fun doImportClipboardSync(zipFile: File) {
        try {
            val stickerDir = File("/sdcard/MyStyle/Sticker")
            stickerDir.mkdirs()
            var clipsJson: String? = null
            val extractedFiles = mutableListOf<String>()
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "clips.json") {
                        clipsJson = zis.readBytes().toString(Charsets.UTF_8)
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] clips.json raw: $clipsJson")
                    } else if (entry.name.startsWith("stickers/")) {
                        val name = entry.name.removePrefix("stickers/")
                        if (name.isNotEmpty()) {
                            val dest = File(stickerDir, name)
                            dest.outputStream().buffered().use { out -> zis.copyTo(out) }
                            extractedFiles.add(dest.absolutePath)
                            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] extracted: ${dest.absolutePath} (${dest.length()} bytes)")
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] extracted ${extractedFiles.size} sticker files")

            if (clipsJson != null) {
                val parsed = JSONObject(clipsJson!!)
                for (i in 1..6) {
                    val slotKey = i.toString()
                    val raw = parsed.opt(slotKey)
                    val path = parsed.optString(slotKey, "")
                    val exists = path.isNotEmpty() && File(path).exists()
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] slot $i: raw=$raw path='$path' exists=$exists")
                }

                reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
                    .edit().putString("preset_99", clipsJson).apply()
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Clipboard sync imported, clips updated")
            }
            zipFile.delete()
            Handler(Looper.getMainLooper()).post {
                try {
                    com.ratta.supernote.pluginlib.api.HostUIAPI.getInstance().showTipDialog(
                        reactApplicationContext.currentActivity, true, NativeLocale.t("sync_clipboard_ok"),
                        object : com.ratta.supernote.pluginlib.callback.RattaDialogListener {
                            override fun onConfirm() {}
                            override fun onCancel() {}
                        }
                    )
                } catch (_: Exception) {}
                refreshToolbarClipIcons()
                sendEvent("clipsChanged", Arguments.createMap())
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "doImportClipboardSync failed", e)
        }
    }

    private fun refreshToolbarClipIcons() {
        val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
        val clipsJson = prefs.getString("preset_99", null) ?: return
        try {
            val obj = JSONObject(clipsJson)
            val filled = JSONArray()
            for (i in 1..6) {
                val path = obj.optString(i.toString(), "")
                val fileExists = path.isNotEmpty() && File(path).exists()
                filled.put(fileExists)
                if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "[SYNC-DBG] refreshClipIcons slot $i: path='$path' exists=$fileExists")
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] refreshClipIcons sending: $filled")
            FloatingToolbarModule.currentInstance?.updateTitleClips(filled.toString())
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "refreshToolbarClipIcons: ${e.message}")
        }
    }

    @ReactMethod
    fun importClipboardSync(zipPath: String, promise: Promise) {
        thread(isDaemon = true) {
            try {
                val zipFile = File(zipPath)
                if (!zipFile.exists()) { promise.resolve(false); return@thread }
                val stickerDir = File("/sdcard/MyStyle/Sticker")
                stickerDir.mkdirs()

                var clipsJson: String? = null
                ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "clips.json") {
                            clipsJson = zis.readBytes().toString(Charsets.UTF_8)
                            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] importClipboardSync clips.json: $clipsJson")
                        } else if (entry.name.startsWith("stickers/")) {
                            val name = entry.name.removePrefix("stickers/")
                            if (name.isNotEmpty()) {
                                val dest = File(stickerDir, name)
                                dest.outputStream().buffered().use { out -> zis.copyTo(out) }
                                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] importClipboardSync extracted: ${dest.absolutePath}")
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                if (clipsJson != null) {
                    val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
                    prefs.edit().putString("preset_99", clipsJson).apply()
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Clipboard sync imported, clips updated")
                }

                zipFile.delete()
                refreshToolbarClipIcons()
                sendEvent("clipsChanged", Arguments.createMap())
                promise.resolve(true)
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "importClipboardSync failed", e)
                promise.resolve(false)
            }
        }
    }

    data class FileInfo(
        val id: String,
        val fileName: String,
        val size: Long,
        val fileType: String,
        val sha256: String
    )

    data class UploadSession(
        val sessionId: String,
        val senderIp: String,
        var senderAlias: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val timeout: Long = 600_000L,
        val files: MutableMap<String, FileInfo> = mutableMapOf(),
        val tokens: MutableMap<String, String> = mutableMapOf(),
        val received: MutableMap<String, Boolean> = mutableMapOf()
    ) {
        fun isValid(): Boolean = (System.currentTimeMillis() - createdAt) < timeout
    }
}