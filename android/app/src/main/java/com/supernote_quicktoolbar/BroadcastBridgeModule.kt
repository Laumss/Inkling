package com.supernote_quicktoolbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule

class BroadcastBridgeModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

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
    fun stopListening() {
        receiver?.let {
            reactApplicationContext.unregisterReceiver(it)
            receiver = null
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
