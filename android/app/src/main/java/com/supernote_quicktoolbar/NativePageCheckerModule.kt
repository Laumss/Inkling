package com.supernote_quicktoolbar

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule

class NativePageCheckerModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "NativePageChecker"

    private val TAG = "NativePageChecker"
    private val handler = Handler(Looper.getMainLooper())
    private var intervalMs = 500L
    private var running = false
    private var tickCount = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            tickCount++
            try {
                reactApplicationContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("onPageCheckTick", tickCount.toDouble())
            } catch (e: Exception) {

                Log.w(TAG, "emit onPageCheckTick failed: ${e.message}")
            }
            handler.postDelayed(this, intervalMs)
        }
    }

    @ReactMethod
    fun startPolling(ms: Int) {
        handler.removeCallbacks(tickRunnable)
        intervalMs = ms.toLong().coerceAtLeast(100L)
        running = true
        tickCount = 0L
        handler.postDelayed(tickRunnable, intervalMs)
        Log.i(TAG, "startPolling intervalMs=$intervalMs")
    }

    @ReactMethod
    fun stopPolling() {
        running = false
        handler.removeCallbacks(tickRunnable)
        Log.i(TAG, "stopPolling (totalTicks=$tickCount)")
    }

    @ReactMethod
    fun addListener(eventName: String) {

    }

    @ReactMethod
    fun removeListeners(count: Int) {

    }

    override fun onCatalystInstanceDestroy() {
        stopPolling()
        super.onCatalystInstanceDestroy()
    }
}
