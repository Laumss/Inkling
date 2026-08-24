package com.supernote_quicktoolbar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.supernote_quicktoolbar.relay.AIRelayCore
import java.io.File
import java.io.FileOutputStream

class AIRelayModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext), AIRelayCore.Listener {
    override fun getName() = "AIRelayModule"

    private val core by lazy { AIRelayCore.get(reactApplicationContext) }
    private var listening = false
    private var inboxListening = false
    private var signatureLogged = false

    private fun logHostResizeSignature() {
        if (signatureLogged) return
        signatureLogged = true
        try {
            val cls = Class.forName("com.ratta.supernote.pluginlib.modules.CommAPIModule")
            cls.declaredMethods.filter { it.name == "resizeLassoRect" }.forEach { method ->
                Log.i("AIRelayModule", "host resizeLassoRect(${method.parameterTypes.joinToString { it.simpleName }})")
            }
        } catch (error: Throwable) {
            Log.w("AIRelayModule", "resizeLassoRect signature probe failed: $error")
        }
    }

    @ReactMethod
    fun captureScreenRegion(left: Int, top: Int, right: Int, bottom: Int, margin: Int, outPath: String, promise: Promise) {
        Thread {
            var temporary: File? = null
            try {
                temporary = File(reactApplicationContext.cacheDir, "formula_screencap_${System.currentTimeMillis()}.png")
                val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p", temporary.absolutePath))
                if (process.waitFor() != 0 || !temporary.exists() || temporary.length() < 500) {
                    promise.resolve(null)
                    return@Thread
                }
                val bitmap = BitmapFactory.decodeFile(temporary.absolutePath) ?: run { promise.resolve(null); return@Thread }
                val x = (left - margin).coerceIn(0, bitmap.width - 1)
                val y = (top - margin).coerceIn(0, bitmap.height - 1)
                val width = ((right + margin).coerceAtMost(bitmap.width) - x).coerceAtLeast(1)
                val height = ((bottom + margin).coerceAtMost(bitmap.height) - y).coerceAtLeast(1)
                val cropped = Bitmap.createBitmap(bitmap, x, y, width, height)
                bitmap.recycle()
                File(outPath).parentFile?.mkdirs()
                FileOutputStream(outPath).use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
                cropped.recycle()
                promise.resolve(outPath)
            } catch (error: Exception) {
                Log.e("AIRelayModule", "captureScreenRegion failed", error)
                promise.resolve(null)
            } finally {
                runCatching { temporary?.delete() }
            }
        }.start()
    }

    @ReactMethod
    fun startListening() {
        logHostResizeSignature()
        ensureCoreListener()
    }

    @ReactMethod
    fun startInboxListening() {
        inboxListening = true
        ensureCoreListener()
        emitInbox()
    }

    private fun ensureCoreListener() {
        if (listening) return
        listening = true
        core.addListener(this)
    }

    @ReactMethod
    fun isRelayEnabled(promise: Promise) {
        promise.resolve(core.isEnabled())
    }

    @ReactMethod
    fun toggleRelayEnabled(promise: Promise) {
        ensureCoreListener()
        promise.resolve(core.toggleEnabledByUser())
    }

    @ReactMethod
    fun setRelayEnabled(enabled: Boolean, promise: Promise) {
        ensureCoreListener()
        promise.resolve(core.setEnabledByUser(enabled))
    }

    override fun onInboxChanged(messages: List<com.supernote_quicktoolbar.relay.model.RelayMessage>) {
        if (inboxListening) emitInbox()
    }

    override fun onInsertionRequested(request: AIRelayCore.InsertionRequest) {
        val emitter = reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        when (request) {
            is AIRelayCore.InsertionRequest.Text -> emitter.emit("onTextFromRelay", request.text)
            is AIRelayCore.InsertionRequest.Blocks -> emitter.emit(
                if (request.replace) "onReplaceFromRelay" else "onBlocksFromRelay",
                Arguments.createMap().apply {
                    putString("text", request.text)
                    putString("blocks", request.blocks)
                },
            )
        }
    }

    override fun onFormulaReplyReady(messageId: String) {
        // Do not start an external Activity. When the panel is visible it opens
        // itself; otherwise the inbox callback adds the new capsule.
    }

    private fun emitInbox() {
        reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onInboxFromRelay", core.capsuleItemsJson())
    }

    @ReactMethod
    fun sendInsertRequest(id: String) { ensureCoreListener(); core.insertMessage(id) }

    // Keep these JS methods during the transition. The package parameter is no
    // longer meaningful because AIRelay is inside this PluginHost process.
    @ReactMethod
    fun openRelayMain(@Suppress("UNUSED_PARAMETER") pkg: String, promise: Promise) = openAirRelay(null, promise)

    @ReactMethod
    fun openRelayDetail(@Suppress("UNUSED_PARAMETER") pkg: String, id: String, promise: Promise) = openAirRelay(id, promise)

    private fun openAirRelay(detailId: String?, promise: Promise) {
        val toolbar = FloatingToolbarModule.currentInstance
        if (toolbar == null) {
            promise.resolve(false)
            return
        }
        toolbar.openPanel(detailId?.let { "airRelayDetail:$it" } ?: "airRelay")
        promise.resolve(true)
    }

    @ReactMethod
    fun sendAck(text: String, success: Boolean, error: String?) {
        ensureCoreListener()
        core.ackInsertion(text, success, error)
    }

    @ReactMethod
    fun sendQuery(text: String) { ensureCoreListener(); core.submitQuery(text) }

    /**
     * Same as [sendQuery] but reports what actually happened: "disabled",
     * "sent", "queued", "no_endpoint" or "empty". JS needs this to avoid showing
     * "waiting for AI" for a query that was never delivered anywhere.
     */
    @ReactMethod
    fun sendQueryWithResult(text: String, promise: Promise) {
        ensureCoreListener()
        val result = core.submitQuery(text)
        promise.resolve(result.name.lowercase())
    }

    @ReactMethod
    fun pingRelay(promise: Promise) {
        ensureCoreListener()
        promise.resolve(if (core.isBackendReachable()) "1" else "0")
    }

    @ReactMethod
    fun sendAlive() { ensureCoreListener() }

    @ReactMethod
    fun startHeartbeat() { ensureCoreListener() }

    @ReactMethod
    fun stopHeartbeat() {}

    @ReactMethod
    fun sendFormulaSessionReset() { ensureCoreListener(); core.resetFormulaSession() }

    @ReactMethod
    fun sendImageQueryKind(imagePath: String, maskPath: String, prompt: String, kind: String) {
        ensureCoreListener()
        core.submitImageQuery(imagePath, maskPath, prompt, kind)
    }

    @ReactMethod
    fun sendInsertPosition(page: Int, top: Int) { ensureCoreListener(); core.updateInsertPosition(page, top) }

    @ReactMethod
    fun stopListening() {
        if (!listening) return
        listening = false
        inboxListening = false
        core.removeListener(this)
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}

    override fun invalidate() {
        stopListening()
        super.invalidate()
    }
}
