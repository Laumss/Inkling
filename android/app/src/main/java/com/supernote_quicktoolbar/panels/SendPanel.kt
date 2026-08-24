package com.supernote_quicktoolbar.panels
import com.supernote_quicktoolbar.BuildConfig
import com.supernote_quicktoolbar.*
import com.supernote_quicktoolbar.overlays.*
import com.supernote_quicktoolbar.bubbles.*

import android.content.Context
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
import com.supernote_quicktoolbar.net.LocalSendDiscovery
import com.supernote_quicktoolbar.ui_common.PanelBase
import com.supernote_quicktoolbar.ui_common.PanelScrollHost
import com.supernote_quicktoolbar.ui_common.PanelWidgets
import java.io.*
import java.math.BigInteger
import java.net.*
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.cert.X509Certificate
import java.security.cert.CertificateFactory
import java.text.SimpleDateFormat
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
    private val toolbar: FloatingToolbarModule
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

    private var sessionGen = 0

    fun show(
        fromBubble: Boolean = false,
        syncClipboard: Boolean = false,
        onShowResult: ((Boolean) -> Unit)? = null
    ) {
        if (!LocalSendModule.isWifiConnectedStatic(reactContext)) {
            com.supernote_quicktoolbar.ui_common.Dialog.tip(reactContext, NativeLocale.t("no_wifi"))
            onShowResult?.invoke(false)
            return
        }
        currentInstance = this
        sessionGen++
        pendingText = ""; pendingImages = emptyList(); pendingLinkedFiles = emptyList()
        selectedPeer = null; sending = false
        cameFromBubble = fromBubble
        clipboardSyncMode = syncClipboard
        showPanel { shown ->
            if (shown) {
                if (!LocalSendModule.staticIsRunning) {
                    toolbarModule.emitEvent("startLocalSendFromNative", Arguments.createMap())
                }
                startPeerPolling()
                LocalSendModule.probeDefaultPeer(reactContext)
                LocalSendModule.reprobeKnownPeers()
                LocalSendModule.triggerScan()
            }
            onShowResult?.invoke(shown)
        }
    }

    override fun hide() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            stopPeerPolling()
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            FloatingPenGuard.setFullScreenActive(false, "panel:$panelName")
            rootView = null; windowManager = null
            onHide()
        }
    }

    override fun onHide() {
        autoSendArmed = false
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
            autoSendArmed = rootView != null && !clipboardSyncMode &&
                LocalSendModule.getDefaultPeer(reactContext) != null
            maybeAutoSendToDefault()
        }
    }

    private var autoSendArmed = false

    private fun maybeAutoSendToDefault() {
        if (!autoSendArmed || sending || clipboardSyncMode) return
        val def = LocalSendModule.getDefaultPeer(reactContext) ?: run { autoSendArmed = false; return }
        val textOnly = pendingText.isNotEmpty() && pendingImages.isEmpty() && pendingLinkedFiles.isEmpty()
        val singleFile = pendingText.isEmpty() && pendingImages.size == 1 && pendingLinkedFiles.isEmpty()
        if (!textOnly && !singleFile) { autoSendArmed = false; return }
        val live = LocalSendModule.getPeersSnapshot()
            .firstOrNull { it.fingerprint == def.fingerprint } ?: return
        autoSendArmed = false
        selectedPeer = live
        refreshPeerList()
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "auto-send to default peer ${live.alias} (${if (textOnly) "text" else "file"})")
        if (textOnly) handleSendText() else handleSendFile(pendingImages.first())
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

            val defaultPeer = LocalSendModule.getDefaultPeer(reactContext)
            if (defaultPeer != null) {
                peers.firstOrNull { it.fingerprint == defaultPeer.fingerprint }?.let { live ->
                    if (live.ip != defaultPeer.ip || live.port != defaultPeer.port) {
                        LocalSendModule.setDefaultPeer(reactContext, live)
                    }
                }
            }

            if (selectedPeer == null || peers.none { it.fingerprint == selectedPeer?.fingerprint }) {
                selectedPeer = peers.firstOrNull { it.fingerprint == defaultPeer?.fingerprint }
                    ?: peers.first()
            }

            for (peer in peers) container.addView(createPeerRow(peer))
            updateSendBtnState()
            scrollHost?.refreshThumb()
            maybeAutoSendToDefault()
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
                autoSendArmed = false
                selectedPeer = peer
                refreshPeerList()
                updateSendBtnState()
            }
            setOnLongClickListener {
                val cur = LocalSendModule.getDefaultPeer(reactContext)
                LocalSendModule.setDefaultPeer(
                    reactContext,
                    if (cur?.fingerprint == peer.fingerprint) null else peer
                )
                refreshPeerList()
                true
            }
        }
        val isDefault = LocalSendModule.getDefaultPeer(reactContext)?.fingerprint == peer.fingerprint

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

        if (isDefault) {
            row.addView(TextView(reactContext).apply {
                text = NativeLocale.t("peer_default_badge")
                textSize = sp(10f); setTextColor(Color.WHITE)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply {
                    setColor(Color.BLACK); cornerRadius = dp(3).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(6) }
            })
        }

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

    /** Ensure host INTERNET permission before an upload; resets sending state on denial. */
    private fun withInternetPermission(onGranted: () -> Unit) {
        toolbar.requestPluginInternetPermission { granted ->
            if (!granted) {
                sending = false
                statusTextView?.text = NativeLocale.t("perm_required")
                updateSendBtnState()
                return@requestPluginInternetPermission
            }
            onGranted()
        }
    }

    private fun handleSendText() = handleSendText(pendingText)

    private fun handleSendText(text: String) {
        val peer = selectedPeer ?: return
        if (text.isEmpty() || sending) return
        sending = true
        statusTextView?.text = NativeLocale.t("sending")
        updateSendBtnState()
        withInternetPermission {
            thread(isDaemon = true) {
                try {
                    LocalSendModule.sendTextDirect(peer.ip, peer.port, text, peer.useTls)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        statusTextView?.text = NativeLocale.t("send_success")
                        scheduleAutoClose()
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
    }

    private fun handleSendFile(path: String) {
        val peer = selectedPeer ?: return
        if (sending) return
        sending = true
        statusTextView?.text = NativeLocale.t("sending")
        updateSendBtnState()
        toolbar.requestPluginFileReadPermission { granted ->
            if (!granted) {
                sending = false
                statusTextView?.text = NativeLocale.t("file_read_permission_needed")
                updateSendBtnState()
                toolbar.openPluginSettingsAfterFileReadDenied()
                return@requestPluginFileReadPermission
            }
            withInternetPermission {
            thread(isDaemon = true) {
                try {
                    LocalSendModule.sendFileDirect(peer.ip, peer.port, path, peer.useTls)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        statusTextView?.text = NativeLocale.t("send_success")
                        scheduleAutoClose()
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
        }
    }

    private fun handleSyncClipboard() {
        val peer = selectedPeer ?: return
        if (sending) return
        sending = true
        statusTextView?.text = NativeLocale.t("sync_packaging")
        updateSendBtnState()
        toolbar.requestPluginFileReadPermission { readGranted ->
            if (!readGranted) {
                sending = false
                statusTextView?.text = NativeLocale.t("file_read_permission_needed")
                updateSendBtnState()
                toolbar.openPluginSettingsAfterFileReadDenied()
                return@requestPluginFileReadPermission
            }
            toolbar.requestPluginFileWritePermission { writeGranted ->
                if (!writeGranted) {
                    sending = false
                    statusTextView?.text = NativeLocale.t("perm_required")
                    updateSendBtnState()
                    toolbar.openPluginSettingsAfterFileWriteDenied()
                    return@requestPluginFileWritePermission
                }
                withInternetPermission {
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
                            scheduleAutoClose()
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

    private fun scheduleAutoClose() {
        val gen = sessionGen
        handler.postDelayed({
            if (gen != sessionGen) return@postDelayed
            closeAndRestore()
        }, 800)
    }

    private fun closeAndRestore() {
        val fromBubble = cameFromBubble
        hide()
        toolbar.cancelPendingScreen()
        toolbar.requestClosePluginView()
        toolbarModule.emitEvent("onNativePanelClose",
            Arguments.createMap().apply {
                putString("panel", "send")
                putBoolean("cameFromBubble", fromBubble)
            })
        ScreenshotBubble.reshowIfPending()
        if (fromBubble) {
            val gen = sessionGen
            handler.postDelayed({
                if (gen != sessionGen) return@postDelayed
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

            override fun onAvailable(network: Network) {
                // Rebind the shared discovery socket onto the new interface
                // (no-op when no consumer needs it).
                LocalSendDiscovery.restartForNetworkChange()
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

    override fun invalidate() {
        val cm = reactApplicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        networkCallback = null

        // Release only the LocalSend demand on the shared discovery socket:
        // AIRelayCore's dictation listening must survive an RN reload.
        forceCloseAll()
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "invalidate: LocalSend server stopped")
        super.invalidate()
    }

    companion object {
        private const val TAG = "LocalSendModule"
        private const val PROTOCOL_VERSION = "2.0"
        private const val DEFAULT_PORT = 53317
        private const val API_BASE = "/api/localsend/v2"
        private const val API_BASE_V1 = "/api/localsend/v1"

        @Volatile private var staticServerSocket: ServerSocket? = null
        @Volatile @JvmStatic var staticIsRunning = false

        /**
         * Stops the LocalSend HTTP server and drops LocalSend's demand on the
         * shared discovery socket. Does NOT touch the multicast socket
         * directly — LocalSendDiscovery keeps it alive while AIRelay still
         * listens for the phone's dictation announcements.
         */
        fun forceCloseAll() {
            staticIsRunning = false
            try { staticServerSocket?.close() } catch (_: Exception) {}
            staticServerSocket = null
            LocalSendDiscovery.stopLocalSendDiscovery()
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

        private const val PEER_TTL_MS = 15_000L

        data class StoredSecurityContext(
            val keyAlias: String,
            val privateKey: String,
            val publicKey: String,
            val certificate: String,
            val certificateHash: String
        )

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
            staticDiscoveredPeers.entries.removeIf {
                now - it.value.lastSeen > PEER_TTL_MS ||
                    isLocalAddressStatic(it.value.ip) ||
                    it.value.fingerprint == staticDeviceFingerprint
            }
            return staticDiscoveredPeers.values.toList()
        }

        private const val DEFAULT_PEER_KEY = "localsend_default_peer"

        @JvmStatic
        fun getDefaultPeer(ctx: Context): DiscoveredPeer? {
            return try {
                val json = ctx.getSharedPreferences("quicktoolbar_presets", 0)
                    .getString(DEFAULT_PEER_KEY, null) ?: return null
                val o = JSONObject(json)
                val fp = o.optString("fingerprint", "")
                if (fp.isEmpty()) null else DiscoveredPeer(
                    alias = o.optString("alias", "?"),
                    ip = o.optString("ip", ""),
                    port = o.optInt("port", DEFAULT_PORT),
                    deviceType = o.optString("deviceType", "desktop"),
                    fingerprint = fp,
                    useTls = o.optBoolean("useTls", true)
                )
            } catch (_: Exception) { null }
        }

        @JvmStatic
        fun setDefaultPeer(ctx: Context, peer: DiscoveredPeer?) {
            val prefs = ctx.getSharedPreferences("quicktoolbar_presets", 0)
            if (peer == null) {
                prefs.edit().remove(DEFAULT_PEER_KEY).apply()
                return
            }
            val o = JSONObject().apply {
                put("alias", peer.alias)
                put("ip", peer.ip)
                put("port", peer.port)
                put("deviceType", peer.deviceType)
                put("fingerprint", peer.fingerprint)
                put("useTls", peer.useTls)
            }
            prefs.edit().putString(DEFAULT_PEER_KEY, o.toString()).apply()
        }

        @JvmStatic
        fun isWifiConnectedStatic(ctx: Context): Boolean {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        @JvmStatic
        fun packageClipboardStatic(ctx: Context): String? {
            val prefs = ctx.getSharedPreferences("quicktoolbar_presets", 0)
            val clipsJson = prefs.getString("preset_99", null) ?: return null
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] packageClipboard raw preset_99: $clipsJson")
            val clips = JSONObject(clipsJson)

            val filteredClips = JSONObject()
            val keys = clips.keys()
            while (keys.hasNext()) {
                val slot = keys.next()
                val path = clips.optString(slot, "")
                val fileExists = path.isNotEmpty() && File(path).exists()
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] packageClipboard slot=$slot path='$path' exists=$fileExists")
                if (fileExists) filteredClips.put(slot, path)
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] packageClipboard filtered: $filteredClips (${filteredClips.length()} slots)")
            if (filteredClips.length() == 0) return null

            val zipFile = File(ctx.cacheDir, "clipboard_sync_${System.currentTimeMillis()}.zip")
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

        /** Probe the bound default peer's last-known address so it appears
         *  in the peer list immediately, without waiting for a full scan. */
        @JvmStatic
        fun probeDefaultPeer(ctx: Context) {
            val def = getDefaultPeer(ctx) ?: return
            if (def.ip.isEmpty()) return
            kotlin.concurrent.thread(isDaemon = true, name = "LocalSend-ProbeDefault") {
                probeHostStatic(def.ip, def.port)
            }
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

        private const val SECURITY_PREFS = "quicktoolbar_presets"
        private const val SECURITY_CONTEXT_KEY = "ls_security_context"
        private const val KEY_ALIAS = "inkling_localsend_tls_pem_v1"
        private val KEYSTORE_PASSWORD = "inkling".toCharArray()

        @Volatile private var staticServerTlsContext: SSLContext? = null
        @Volatile private var staticStoredSecurityContext: StoredSecurityContext? = null

        @Synchronized
        fun ensureSecurityContext(ctx: android.content.Context): StoredSecurityContext {
            staticStoredSecurityContext?.let { return it }

            val prefs = ctx.getSharedPreferences(SECURITY_PREFS, 0)
            val stored = prefs.getString(SECURITY_CONTEXT_KEY, null)
                ?.let { parseStoredSecurityContext(it) }
                ?.takeIf { it.keyAlias == KEY_ALIAS && it.privateKey.contains("BEGIN PRIVATE KEY") && it.certificate.contains("BEGIN CERTIFICATE") }

            val context = stored ?: loadOrGenerateSecurityContext()
            if (stored == null) {
                prefs.edit().putString(SECURITY_CONTEXT_KEY, securityContextToJson(context)).apply()
            }
            staticStoredSecurityContext = context
            return context
        }

        @JvmStatic
        fun initServerTlsContext(ctx: android.content.Context) {
            if (staticServerTlsContext != null) return
            try {
                val securityContext = ensureSecurityContext(ctx)
                val privateKey = parsePrivateKeyPem(securityContext.privateKey)
                val certificate = parseCertificatePem(securityContext.certificate)
                val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null)
                    setKeyEntry(securityContext.keyAlias, privateKey, KEYSTORE_PASSWORD, arrayOf(certificate))
                }
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(ks, KEYSTORE_PASSWORD)
                val keyManagers = kmf.keyManagers.map { km ->
                    if (km is X509KeyManager) AliasKeyManager(km, securityContext.keyAlias) else km
                }.toTypedArray()
                staticServerTlsContext = SSLContext.getInstance("TLS").apply {
                    init(keyManagers, null, SecureRandom())
                }
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "[LS-DIAG] server TLS context initialized fingerprint=${securityContext.certificateHash}")
                }
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[LS-DIAG] server TLS init exception", e)
            }
        }

        @JvmStatic
        fun serverProtocol(): String = if (staticServerTlsContext != null) "https" else "http"

        private fun parseStoredSecurityContext(raw: String): StoredSecurityContext? {
            return try {
                val o = JSONObject(raw)
                StoredSecurityContext(
                    keyAlias = o.optString("keyAlias", KEY_ALIAS),
                    privateKey = o.optString("privateKey", ""),
                    publicKey = o.optString("publicKey", ""),
                    certificate = o.optString("certificate", ""),
                    certificateHash = o.optString("certificateHash", "")
                ).takeIf { it.certificateHash.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }

        private fun securityContextToJson(context: StoredSecurityContext): String =
            JSONObject().apply {
                put("keyAlias", context.keyAlias)
                put("privateKey", context.privateKey)
                put("publicKey", context.publicKey)
                put("certificate", context.certificate)
                put("certificateHash", context.certificateHash)
            }.toString()

        private fun loadOrGenerateSecurityContext(): StoredSecurityContext {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, SecureRandom())
            }.generateKeyPair()
            val cert = generateSelfSignedCertificate(keyPair)

            return StoredSecurityContext(
                keyAlias = KEY_ALIAS,
                privateKey = pemBlock("PRIVATE KEY", keyPair.private.encoded),
                publicKey = pemBlock("PUBLIC KEY", cert.publicKey.encoded),
                certificate = pemBlock("CERTIFICATE", cert.encoded),
                certificateHash = staticSha256Hex(cert.encoded)
            )
        }

        private fun parsePrivateKeyPem(pem: String): PrivateKey {
            val der = parsePemBlock(pem)
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        }

        private fun parseCertificatePem(pem: String): X509Certificate {
            val der = parsePemBlock(pem)
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }

        private fun parsePemBlock(pem: String): ByteArray {
            val content = pem.replace("\r\n", "\n")
                .split("\n")
                .filter { it.isNotBlank() && !it.startsWith("-----") }
                .joinToString("")
            return android.util.Base64.decode(content, android.util.Base64.DEFAULT)
        }

        private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
            val now = System.currentTimeMillis()
            val notBefore = Date(now - TimeUnit.DAYS.toMillis(1))
            val notAfter = Date(now + TimeUnit.DAYS.toMillis(365L * 10L))
            val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)
            val algorithm = derSequence(
                derOid("1.2.840.113549.1.1.11"),
                derNull()
            )
            val name = derName("LocalSend User")
            val tbs = derSequence(
                derInteger(serial),
                algorithm,
                name,
                derSequence(derTime(notBefore), derTime(notAfter)),
                name,
                keyPair.public.encoded
            )
            val sig = Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(tbs)
            }.sign()
            val certDer = derSequence(tbs, algorithm, derBitString(sig))
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        }

        private fun pemBlock(type: String, der: ByteArray): String {
            val base64 = android.util.Base64.encodeToString(
                der,
                android.util.Base64.NO_WRAP
            )
            val body = base64.chunked(64).joinToString("\n")
            return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
        }

        private fun derSequence(vararg parts: ByteArray): ByteArray =
            derTagged(0x30, parts.flatMap { it.asIterable() }.toByteArray())

        private fun derSet(vararg parts: ByteArray): ByteArray =
            derTagged(0x31, parts.flatMap { it.asIterable() }.toByteArray())

        private fun derTagged(tag: Int, value: ByteArray): ByteArray =
            byteArrayOf(tag.toByte()) + derLength(value.size) + value

        private fun derLength(length: Int): ByteArray {
            if (length < 128) return byteArrayOf(length.toByte())
            val bytes = mutableListOf<Byte>()
            var n = length
            while (n > 0) {
                bytes.add(0, (n and 0xff).toByte())
                n = n ushr 8
            }
            return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
        }

        private fun derInteger(value: BigInteger): ByteArray =
            derTagged(0x02, value.toByteArray())

        private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

        private fun derOid(oid: String): ByteArray {
            val parts = oid.split(".").map { it.toLong() }
            val encoded = mutableListOf<Byte>()
            encoded.add((parts[0] * 40 + parts[1]).toByte())
            for (part in parts.drop(2)) {
                val stack = mutableListOf<Byte>()
                var n = part
                stack.add((n and 0x7f).toByte())
                n = n ushr 7
                while (n > 0) {
                    stack.add(0, ((n and 0x7f) or 0x80).toByte())
                    n = n ushr 7
                }
                encoded.addAll(stack)
            }
            return derTagged(0x06, encoded.toByteArray())
        }

        private fun derUtf8(value: String): ByteArray =
            derTagged(0x0c, value.toByteArray(Charsets.UTF_8))

        private fun derName(commonName: String): ByteArray =
            derSequence(
                derSet(
                    derSequence(
                        derOid("2.5.4.3"),
                        derUtf8(commonName)
                    )
                )
            )

        private fun derTime(date: Date): ByteArray {
            val text = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(date)
            return derTagged(0x17, text.toByteArray(Charsets.US_ASCII))
        }

        private fun derBitString(value: ByteArray): ByteArray =
            derTagged(0x03, byteArrayOf(0x00) + value)

        private class AliasKeyManager(
            private val delegate: X509KeyManager,
            private val alias: String
        ) : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String>? =
                if (isRsaKeyType(keyType)) arrayOf(alias) else delegate.getClientAliases(keyType, issuers)

            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<java.security.Principal>?,
                socket: Socket?
            ): String? =
                if (keyType?.any { isRsaKeyType(it) } == true) alias else delegate.chooseClientAlias(keyType, issuers, socket)

            override fun getServerAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String> =
                if (isRsaKeyType(keyType)) arrayOf(alias) else emptyArray()

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<java.security.Principal>?,
                socket: Socket?
            ): String? =
                if (isRsaKeyType(keyType)) alias else delegate.chooseServerAlias(keyType, issuers, socket)

            override fun getCertificateChain(alias: String?): Array<java.security.cert.X509Certificate>? =
                delegate.getCertificateChain(this.alias)

            override fun getPrivateKey(alias: String?): PrivateKey? =
                delegate.getPrivateKey(this.alias)

            override fun chooseEngineClientAlias(
                keyType: Array<out String>?,
                issuers: Array<java.security.Principal>?,
                engine: SSLEngine?
            ): String? =
                if (keyType?.any { isRsaKeyType(it) } == true) alias else null

            override fun chooseEngineServerAlias(
                keyType: String?,
                issuers: Array<java.security.Principal>?,
                engine: SSLEngine?
            ): String? =
                if (isRsaKeyType(keyType)) alias else null

            private fun isRsaKeyType(keyType: String?): Boolean {
                val normalized = keyType?.uppercase(Locale.US) ?: return false
                return normalized == "RSA" || normalized.startsWith("RSA_")
            }
        }

        // The SSLSocketFactory.createSocket(Socket, InputStream consumed, boolean)
        // overload exists on the API 24+ base class, but the device's Conscrypt
        // doesn't override it — the base impl throws UnsupportedOperationException
        // (observed as "TLS handshake failed: InvocationTargetException"). So
        // instead we wrap the accepted socket in a delegating Socket whose input
        // stream re-injects the peeked bytes, then use the universally supported
        // createSocket(Socket, String, Int, Boolean) overload; Conscrypt's wrapped
        // SSLSocket reads via the underlying socket's getInputStream().
        private class PrereadSocket(private val delegate: Socket, prefix: ByteArray) : Socket() {
            private val input: InputStream =
                SequenceInputStream(ByteArrayInputStream(prefix), delegate.getInputStream())

            override fun getInputStream(): InputStream = input
            override fun getOutputStream(): OutputStream = delegate.getOutputStream()
            override fun getInetAddress(): InetAddress? = delegate.inetAddress
            override fun getLocalAddress(): InetAddress = delegate.localAddress
            override fun getPort(): Int = delegate.port
            override fun getLocalPort(): Int = delegate.localPort
            override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
            override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
            override fun isConnected(): Boolean = delegate.isConnected
            override fun isBound(): Boolean = delegate.isBound
            override fun isClosed(): Boolean = delegate.isClosed
            override fun isInputShutdown(): Boolean = delegate.isInputShutdown
            override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
            override fun getSoTimeout(): Int = delegate.soTimeout
            override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
            override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
            override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
            override fun getKeepAlive(): Boolean = delegate.keepAlive
            override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
            override fun getSoLinger(): Int = delegate.soLinger
            override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)
            override fun getReuseAddress(): Boolean = delegate.reuseAddress
            override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
            override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
            override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
            override fun getSendBufferSize(): Int = delegate.sendBufferSize
            override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
            override fun shutdownInput() = delegate.shutdownInput()
            override fun shutdownOutput() = delegate.shutdownOutput()
            override fun close() = delegate.close()
            override fun toString(): String = "PrereadSocket($delegate)"
        }

        @JvmStatic
        private fun wrapServerTlsSocket(tls: SSLContext, socket: Socket, consumed: ByteArray): SSLSocket {
            val host = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: ""
            return tls.socketFactory.createSocket(
                PrereadSocket(socket, consumed), host, socket.port, true
            ) as SSLSocket
        }

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
                put("hash", sha)
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
                    put("port", DEFAULT_PORT)
                    put("protocol", serverProtocol())
                    put("download", false)
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
                if (tokenMap == null || tokenMap.length() == 0) {
                    // Receiver accepted but issued no tokens: everything was
                    // consumed at prepare-upload time (e.g. text delivered via
                    // the preview field short path). Nothing left to upload.
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SEND-DBG] no tokens issued, consumed at prepare-upload; done")
                    return sessionId
                }
                for ((fileId, data) in fileData) {
                    val token = tokenMap?.optString(fileId, "") ?: ""
                    if (token.isEmpty()) {
                        throw IOException("prepare-upload returned no token for $fileId: $prepareResp")
                    }
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
                staticDiscoveredPeers.clear()
                try {
                    val localIp = getLocalIpStatic()
                    if (localIp == "0.0.0.0") return@thread
                    val localIps = getLocalIpsStatic()
                    val subnet = localIp.substringBeforeLast('.')
                    val executor = Executors.newFixedThreadPool(50)
                    for (i in 1..254) {
                        val ip = "$subnet.$i"
                        if (ip in localIps) continue
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

        private fun getLocalIpsStatic(): Set<String> {
            val result = linkedSetOf<String>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            addr.hostAddress?.let { result.add(it) }
                        }
                    }
                }
            } catch (_: Exception) {}
            return result
        }

        private fun isLocalAddressStatic(ip: String): Boolean = ip in getLocalIpsStatic()

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
            if (isLocalAddressStatic(ip)) return
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
            if (isLocalAddressStatic(ip)) return
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
    @Volatile private var serverStartError: Exception? = null

    data class DiscoveredPeer(
        val alias: String,
        val ip: String,
        val port: Int,
        val deviceType: String,
        val fingerprint: String,
        val lastSeen: Long = System.currentTimeMillis(),
        val useTls: Boolean = true
    )
    // Single source of truth: the static map, so peers found by static scans
    // before startServer() aren't discarded by a map swap.
    private val discoveredPeers: ConcurrentHashMap<String, DiscoveredPeer>
        get() = staticDiscoveredPeers

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
            val securityContext = ensureSecurityContext(reactApplicationContext)
            deviceFingerprint = securityContext.certificateHash
            staticDeviceFingerprint = deviceFingerprint

            File(receiveDir).mkdirs()

            initServerTlsContext(reactApplicationContext)

            isRunning = true
            serverStartError = null
            val serverReady = java.util.concurrent.CountDownLatch(1)

            thread(isDaemon = true, name = "LocalSend-Server") {
                runHttpServer(serverReady)
            }

            if (!serverReady.await(2, TimeUnit.SECONDS)) {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "startServer: HTTP bind did not report ready within 2s")
            }
            serverStartError?.let { throw it }

            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "startServer: bound HTTP port=$serverPort, localIp=${getLocalIp()}, receiveDir=$receiveDir")
            }

            // Shared discovery socket: listen for LocalSend peers and start
            // announcing. Idempotent — if AIRelay's dictation listener already
            // bound 53317 this only installs our handlers and flips the flag.
            LocalSendDiscovery.startLocalSendDiscovery(
                reactApplicationContext,
                announcementProvider = { buildLocalSendAnnouncement() },
                onLocalSendAnnouncement = { senderIp, dto -> handleLocalSendAnnouncement(senderIp, dto) },
            )
            LocalSendDiscovery.setLocalSendAnnouncing(true)

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
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "Error closing sockets", e)
        }
        serverSocket = null
        // Drops only LocalSend's demand; dictation discovery (AIRelay) keeps
        // the multicast socket alive if it is listening.
        LocalSendDiscovery.stopLocalSendDiscovery()
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
        discoveredPeers.entries.removeIf { now - it.value.lastSeen > PEER_TTL_MS || isLocalAddress(it.value.ip) || it.value.fingerprint == deviceFingerprint }

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
                val localIps = getLocalIpsStatic()

                val knownCopy = synchronized(knownSenderIps) { knownSenderIps.toList() }
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "scanForPeers: phase1 known IPs: $knownCopy")
                for (ip in knownCopy) {
                    if (ip in localIps) continue
                    executor.submit { probeHost(ip, DEFAULT_PORT) }
                }

                val skipIps = knownCopy.toSet() + localIps
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
        if (isLocalAddress(ip)) return
        try {
            val url = "https://$ip:$port$API_BASE/info"
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "probeHost: trying $url")
            val conn = openConn(url, connectTimeout = 500, readTimeout = 500)
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "probeHost: $url -> HTTP $code")
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
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "probeHost: trying http://$ip:$port$API_BASE/info (raw)")
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
        if (isLocalAddress(ip)) return
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
                put("port", serverPort)
                put("protocol", serverProtocol())
                put("download", false)
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

        if (tokenMap == null || tokenMap.length() == 0) {
            // Receiver accepted but issued no tokens: everything was consumed
            // at prepare-upload time (e.g. text delivered via the preview
            // field short path). Nothing left to upload.
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "doLocalSendUpload: no tokens issued, consumed at prepare-upload; done")
            sendEvent("onSendComplete", Arguments.createMap().apply {
                putString("sessionId", sessionId)
            })
            return sessionId
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
                throw IOException("prepare-upload returned no token for $fileId: $prepareResp")
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

    private fun runHttpServer(ready: java.util.concurrent.CountDownLatch? = null) {
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
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LS-DIAG] bind OK on tcp/$tryPort")
                    break
                } catch (e: java.net.BindException) {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "[LS-DIAG] bind failed on tcp/$tryPort (${e.message}), trying next")
                }
            }
            if (!bindOk) {
                sock.close()
                throw java.net.BindException("No available port in range $serverPort..${serverPort+9}")
            }
            serverPort = boundPort
            serverSocket = sock
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "HTTP server listening on port $serverPort")
            ready?.countDown()

            while (isRunning) {
                try {
                    val client = serverSocket?.accept() ?: break
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.i(TAG, "[LS-DIAG] accepted tcp connection from ${client.remoteSocketAddress} on local=${client.localSocketAddress}")
                    }
                    thread(isDaemon = true) {
                        handleClient(client)
                    }
                } catch (e: SocketException) {
                    if (isRunning) if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "Socket accept error", e)
                }
            }
        } catch (e: Exception) {
            serverStartError = e
            ready?.countDown()
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "HTTP server error", e)
            sendEvent("onServerError", Arguments.createMap().apply {
                putString("error", e.message ?: "Unknown error")
            })
        }
    }

    private fun handleClient(socket: Socket) {
        var effectiveSocket: Socket = socket
        try {
            socket.soTimeout = 30000
            val remoteIp = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "0.0.0.0"

            // Peek exactly 1 byte from the raw stream: 0x16 = TLS ClientHello.
            val raw = socket.getInputStream()
            val firstByte = raw.read()
            if (firstByte < 0) return

            val input: BufferedInputStream
            val output: BufferedOutputStream
            if (firstByte == 0x16) {
                val tls = staticServerTlsContext
                if (tls == null) {
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.w(TAG, "[RECV-DBG] TLS ClientHello from $remoteIp but no server TLS context; closing")
                    }
                    return
                }
                val sslSocket = try {
                    // Layer an SSLSocket over the accepted socket; PrereadSocket
                    // re-injects the peeked 0x16 byte into the handshake stream.
                    (wrapServerTlsSocket(tls, socket, byteArrayOf(0x16))).apply {
                        useClientMode = false
                        startHandshake()
                    }
                } catch (e: Exception) {
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.w(TAG, "[RECV-DBG] TLS handshake failed from $remoteIp: ${e.javaClass.simpleName}: ${e.message}" +
                            (e.cause?.let { " (cause: ${it.javaClass.simpleName}: ${it.message})" } ?: ""))
                    }
                    return
                }
                effectiveSocket = sslSocket
                input = BufferedInputStream(sslSocket.inputStream)
                output = BufferedOutputStream(sslSocket.outputStream)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[RECV-DBG] TLS session established with $remoteIp")
            } else {
                input = BufferedInputStream(
                    SequenceInputStream(ByteArrayInputStream(byteArrayOf(firstByte.toByte())), raw)
                )
                output = BufferedOutputStream(socket.outputStream)
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

            // Official LocalSend clients probe the legacy v1 info endpoint
            // (GET /api/localsend/v1/info) before listing a device; serve v1
            // paths with the same handlers as v2.
            val apiPath = when {
                path.startsWith("$API_BASE/") -> path.removePrefix(API_BASE)
                path.startsWith("$API_BASE_V1/") -> path.removePrefix(API_BASE_V1)
                else -> path
            }

            when {
                method == "GET" && apiPath == "/info" ->
                    handleInfo(output)

                method == "POST" && apiPath == "/register" -> {
                    val body = readBody(input, contentLength)
                    handleRegister(output, body, remoteIp)
                }

                method == "POST" && apiPath == "/prepare-upload" -> {
                    val body = readBody(input, contentLength)
                    handlePrepareUpload(output, body, remoteIp, queryParams)
                }

                method == "POST" && apiPath == "/upload" ->
                    handleUpload(output, input, remoteIp, queryParams, contentLength)

                method == "POST" && apiPath == "/cancel" ->
                    handleCancel(output, queryParams)

                else ->
                    sendHttpResponse(output, 404, """{"error":"Not found"}""")
            }
        } catch (e: SSLException) {
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "[RECV-DBG] TLS error from client, ignoring: ${e.message}")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "Client handler error", e)
        } finally {
            try { effectiveSocket.close() } catch (_: Exception) {}
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
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LS-DIAG] /info response port=$serverPort protocol=${serverProtocol()} body=$info")
        sendHttpResponse(output, 200, info.toString())
    }

    private fun handleRegister(output: BufferedOutputStream, body: String, remoteIp: String) {
        try {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LS-DIAG] /register from $remoteIp body=${body.take(500)}")
            val data = JSONObject(body)
            val peerAlias = data.optString("alias", "Unknown")
            val peerPort = data.optInt("port", DEFAULT_PORT)
            val peerDeviceType = data.optString("deviceType", "desktop")
            val peerFingerprint = data.optString("fingerprint", remoteIp)
            val peerProtocol = data.optString("protocol", "https")

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
                fingerprint = peerFingerprint,
                useTls = peerProtocol != "http"
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
                put("protocol", serverProtocol())
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
            var allFilesAreText = true
            val keys0 = files.keys()
            while (keys0.hasNext()) {
                val fileId = keys0.next()
                val fileData = files.getJSONObject(fileId)
                val preview = fileData.optString("preview", "")
                val fileType = fileData.optString("fileType", "")
                val fileName = fileData.optString("fileName", "")
                if (preview.isNotEmpty() && fileType.startsWith("text/")) {
                    allPreviews.add(preview)
                }
                if (!fileType.startsWith("text/") && !isTextFile(fileName)) {
                    allFilesAreText = false
                }
            }
            if (allFilesAreText && !FloatingBubbleModule.isTextReceiverShowing()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Text receive rejected from [$senderAlias]: receiver bubble closed")
                notifyTextReceiverClosed()
                sendHttpResponse(output, 403, """{"error":"Text receiver is closed"}""")
                return
            }
            if (allPreviews.isNotEmpty() && allPreviews.size == files.length()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Text message(s) from [$senderAlias] via preview field: ${allPreviews.size}")
                val combined = allPreviews.joinToString("\n")
                deliverLocalSendText(combined, "message.txt")

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

            val isSingleImage = fileNames.size == 1 && isImageFile(fileNames.first())

            if (isClipboardSync) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var accepted = false
                val msg = NativeLocale.t("sync_clipboard_ask").replace("%s", senderAlias)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[SYNC-DBG] confirm for prepare-upload")
                com.supernote_quicktoolbar.ui_common.Dialog.confirmResult(reactApplicationContext, msg) { ok ->
                    accepted = ok
                    latch.countDown()
                }
                latch.await(30, TimeUnit.SECONDS)
                if (!accepted) {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Clipboard sync rejected by user from [$senderAlias]")
                    sendHttpResponse(output, 403, """{"error":"Rejected by user"}""")
                    return
                }
            }

            if (isSingleImage) {
                if (!FloatingToolbarModule.isInNoteApp()) {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Image from [$senderAlias] accepted to inbox (not in note)")
                    session.insertImagesImmediately = false
                } else {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var choice = com.supernote_quicktoolbar.ui_common.Dialog.ImageReceiveChoice.REJECT
                    val msg = NativeLocale.t("image_receive_ask").replace("%s", senderAlias)
                    com.supernote_quicktoolbar.ui_common.Dialog.chooseImageReceive(reactApplicationContext, msg) {
                        choice = it
                        latch.countDown()
                    }
                    latch.await(60, TimeUnit.SECONDS)
                    if (choice == com.supernote_quicktoolbar.ui_common.Dialog.ImageReceiveChoice.REJECT) {
                        sendHttpResponse(output, 403, """{"error":"Image rejected by user"}""")
                        return
                    }
                    session.insertImagesImmediately =
                        choice == com.supernote_quicktoolbar.ui_common.Dialog.ImageReceiveChoice.INSERT_NOW
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

        val ext = fileInfo.fileName.substringAfterLast('.', "").lowercase()
        val saveDir = if (ext == "snplg") File("/sdcard/MyStyle") else File(receiveDir)
        saveDir.mkdirs()
        val destFile = safeFileName(fileInfo.fileName, saveDir)

        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "[LS-DIAG] receive target dir=${saveDir.absolutePath} exists=${saveDir.exists()} canWrite=${saveDir.canWrite()} dest=$destFile")
            Log.i(TAG, "Receiving file: ${fileInfo.fileName} -> $destFile")
        }

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
                deliverLocalSendText(textContent, fileInfo.fileName)
            } else {
                if (isImageFile(fileInfo.fileName)) {
                    addSessionReceivedImage(ReceivedFileInfo(
                        fileInfo.fileName, destFile.absolutePath,
                        received, destFile.lastModified(), true
                    ))
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Added to session received: ${destFile.absolutePath}, total=${getReceivedImageFiles().size}")
                    ImagePanel.currentInstance?.onFileReceived()
                    if (session.insertImagesImmediately && FloatingToolbarModule.isInNoteApp()) {
                        synchronized(ImagePanel::class.java) {
                            ImagePanel.pendingReceivedDeletes.add(destFile.absolutePath)
                        }
                        val toolbar = FloatingToolbarModule.currentInstance
                        if (toolbar != null) {
                            Handler(Looper.getMainLooper()).post {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    toolbar.requestInsertImage(destFile.absolutePath)
                                }, 300)
                            }
                        }
                    }
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

    /** LocalSend v2 announcement payload sent via the shared discovery socket. */
    private fun buildLocalSendAnnouncement(): JSONObject = JSONObject().apply {
        put("alias", deviceAlias)
        put("version", PROTOCOL_VERSION)
        put("deviceModel", "Supernote")
        put("deviceType", "mobile")
        put("fingerprint", deviceFingerprint)
        put("port", serverPort)
        put("protocol", serverProtocol())
        put("download", false)
        put("announce", true)
    }

    /** Handles a LocalSend peer announcement routed here by LocalSendDiscovery. */
    private fun handleLocalSendAnnouncement(senderIp: String, dto: JSONObject) {
        try {
            val fp = dto.optString("fingerprint", "")
            if (fp.isEmpty() || fp == deviceFingerprint) return
            val peerAlias = dto.optString("alias", "Unknown")
            val peerPort = dto.optInt("port", DEFAULT_PORT)
            val peerDeviceType = dto.optString("deviceType", "desktop")
            val peerProtocol = dto.optString("protocol", "https")
            val isAnnounce = dto.optBoolean("announce", dto.optBoolean("announcement", false))

            val existing = discoveredPeers[fp]
            // Re-put unconditionally: refreshes lastSeen so the peer stays alive.
            discoveredPeers[fp] = DiscoveredPeer(
                alias = peerAlias, ip = senderIp, port = peerPort,
                deviceType = peerDeviceType, fingerprint = fp,
                useTls = peerProtocol != "http"
            )
            if (existing == null) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "[LS-DIAG] multicast discovered peer $peerAlias @ $senderIp:$peerPort protocol=$peerProtocol announce=$isAnnounce")
                }
                sendEvent("onPeerFound", Arguments.createMap().apply {
                    putString("alias", peerAlias)
                    putString("ip", senderIp)
                    putString("deviceType", peerDeviceType)
                    putInt("port", peerPort)
                    putString("fingerprint", fp)
                })
            }
            if (isAnnounce) {
                thread(isDaemon = true, name = "LocalSend-AnnounceReply") {
                    respondToAnnouncement(senderIp, peerPort, peerProtocol)
                }
            }
        } catch (_: Exception) {}
    }

    /** Answer a peer's multicast announcement so it lists us: HTTP register, UDP fallback. */
    private fun respondToAnnouncement(ip: String, port: Int, peerProtocol: String) {
        val body = JSONObject().apply {
            put("alias", deviceAlias)
            put("version", PROTOCOL_VERSION)
            put("deviceModel", "Supernote")
            put("deviceType", "mobile")
            put("fingerprint", deviceFingerprint)
            put("port", serverPort)
            put("protocol", serverProtocol())
            put("download", false)
        }
        try {
            if (peerProtocol == "http") {
                staticRawSocketPostJson(ip, port, "$API_BASE/register", body.toString())
            } else {
                staticHttpPostJson("https://$ip:$port$API_BASE/register", body.toString())
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LS-DIAG] register response sent to $peerProtocol://$ip:$port")
        } catch (e: Exception) {
            try {
                body.put("announce", false)
                LocalSendDiscovery.sendUnicast(ip, body)
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "[LS-DIAG] register HTTP to $ip failed (${e.message}); UDP response sent instead")
                }
            } catch (_: Exception) {}
        }
    }

    private fun deliverLocalSendText(text: String, fileName: String) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "deliverLocalSendText: file=$fileName len=${text.length}")
        sendEvent("onTextReceived", Arguments.createMap().apply {
            putString("text", text)
            putString("fileName", fileName)
        })
    }

    private fun notifyTextReceiverClosed() {
        com.supernote_quicktoolbar.ui_common.Dialog.confirmResult(
            reactApplicationContext, NativeLocale.t("text_recv_closed")
        ) { confirmed ->
            if (confirmed) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "text_recv_closed confirmed: requesting text receiver bubble")
                sendEvent("onOpenTextReceiverRequested", Arguments.createMap())
            }
        }
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

    private fun isLocalAddress(ip: String): Boolean = isLocalAddressStatic(ip)

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
        return name == "message.txt"
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
            sendEvent("nativeDeleteFile", Arguments.createMap().apply {
                putString("path", zipFile.absolutePath)
            })
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

                try {
                    if (!zipFile.delete() && BuildConfig.ENABLE_DEBUG) {
                        Log.w(TAG, "[SYNC-DBG] importClipboardSync temp delete skipped: ${zipFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "[SYNC-DBG] importClipboardSync temp delete failed: ${e.message}")
                }
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
        val received: MutableMap<String, Boolean> = mutableMapOf(),
        var insertImagesImmediately: Boolean = false
    ) {
        fun isValid(): Boolean = (System.currentTimeMillis() - createdAt) < timeout
    }
}
