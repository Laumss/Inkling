package com.supernote_quicktoolbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule

class BroadcastBridge(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "BroadcastBridge"

    private var receiver: BroadcastReceiver? = null

    @ReactMethod
    fun startListening() {
        if (receiver != null) return

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "com.dictation.TEXT_TO_PLUGIN") {
                    val text = intent.getStringExtra("text") ?: return
                    reactApplicationContext
                        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                        .emit("onTextFromRelay", text)
                }
            }
        }

        reactApplicationContext.registerReceiver(
            receiver,
            IntentFilter("com.dictation.TEXT_TO_PLUGIN"),
            Context.RECEIVER_EXPORTED
        )
    }

    @ReactMethod
    fun sendAck(text: String, success: Boolean, error: String?) {
        val intent = Intent("com.dictation.ACK_FROM_PLUGIN").apply {
            putExtra("text", text)
            putExtra("success", success)
            error?.let { putExtra("error", it) }
        }
        reactApplicationContext.sendBroadcast(intent)
    }

    @ReactMethod
    fun sendQuery(text: String) {
        val intent = Intent("com.dictation.QUERY_FROM_PLUGIN").apply {
            putExtra("text", text)
        }
        reactApplicationContext.sendBroadcast(intent)
    }

    @ReactMethod
    fun sendAlive() {
        val intent = Intent("com.dictation.PLUGIN_ALIVE")
        reactApplicationContext.sendBroadcast(intent)
        Log.i("BroadcastBridge", "PLUGIN_ALIVE sent")
    }

    @ReactMethod
    fun sendImageQuery(imagePath: String, maskPath: String, prompt: String) {
        val intent = Intent("com.dictation.IMAGE_QUERY_FROM_PLUGIN").apply {
            putExtra("imagePath", imagePath)
            putExtra("maskPath", maskPath)
            putExtra("prompt", prompt)
        }
        reactApplicationContext.sendBroadcast(intent)
    }

    @ReactMethod
    fun sendInsertPosition(page: Int, top: Int) {
        val intent = Intent("com.dictation.INSERT_POSITION").apply {
            putExtra("page", page)
            putExtra("top", top)
        }
        reactApplicationContext.sendBroadcast(intent)
    }

    @ReactMethod
    fun stopListening() {
        receiver?.let {
            reactApplicationContext.unregisterReceiver(it)
            receiver = null
        }
    }

    @ReactMethod
    fun launchRelayApp() {
        val intent = Intent().apply {
            setClassName("com.dictation.server", "com.dictation.server.MainActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            reactApplicationContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e("BroadcastBridge", "launchRelayApp failed: ${e.message}")
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}

    override fun onCatalystInstanceDestroy() {
        stopListening()
        Log.i("BroadcastBridge", "onCatalystInstanceDestroy — receiver unregistered")
        super.onCatalystInstanceDestroy()
    }
}
