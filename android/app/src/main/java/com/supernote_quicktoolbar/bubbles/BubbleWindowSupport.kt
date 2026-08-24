package com.supernote_quicktoolbar.bubbles

import android.graphics.PixelFormat
import android.util.Log
import android.view.WindowManager
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.supernote_quicktoolbar.BuildConfig
import com.supernote_quicktoolbar.ui_common.ScreenScale
import kotlin.math.roundToInt

internal object BubbleWindowSupport {

    @Suppress("DEPRECATION")
    fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

    private const val V_MARGIN_RATIO = 0.06f

    fun clampToBand(screenH: Int, desired: Int, viewH: Int): Int {
        val minY = (screenH * V_MARGIN_RATIO).roundToInt()
        val maxY = ((screenH * (1f - V_MARGIN_RATIO)).roundToInt() - viewH).coerceAtLeast(minY)
        return desired.coerceIn(minY, maxY)
    }

    fun bubbleScale(ctx: ReactApplicationContext): Float =
        maxOf(0.86f, ScreenScale.factor(ctx))

    fun emitEvent(ctx: ReactApplicationContext, tag: String, name: String, params: WritableMap) {
        try {
            ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(name, params)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(tag, "emitEvent($name): ${e.message}")
        }
    }
}
