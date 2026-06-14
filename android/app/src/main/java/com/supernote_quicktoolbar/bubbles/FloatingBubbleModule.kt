package com.supernote_quicktoolbar.bubbles
import com.supernote_quicktoolbar.BuildConfig
import com.supernote_quicktoolbar.*
import com.supernote_quicktoolbar.overlays.*
import com.supernote_quicktoolbar.panels.*

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule

class FloatingBubbleModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "FloatingBubble"

    private val TAG = "FloatingBubble"
    private val handler = Handler(Looper.getMainLooper())

    init { currentInstance = this }

    companion object {
        @Volatile @JvmStatic private var windowManager: WindowManager? = null
        @Volatile @JvmStatic private var bubbleView: LinearLayout? = null
        @Volatile @JvmStatic private var layoutParams: WindowManager.LayoutParams? = null

        @Volatile @JvmStatic private var startX = 0
        @Volatile @JvmStatic private var startY = 0
        @Volatile @JvmStatic private var startRawX = 0f
        @Volatile @JvmStatic private var startRawY = 0f
        @Volatile @JvmStatic private var isDragging = false

        @Volatile @JvmStatic private var pageHeight = 1872
        @Volatile @JvmStatic private var screenHeight = 1872
        @Volatile @JvmStatic private var pageWidth = 1404
        @Volatile @JvmStatic private var screenWidth = 1404

        @Volatile @JvmStatic private var stickyY: Int = 80

        @Volatile @JvmStatic private var pendingInitX: Int = -1
        @Volatile @JvmStatic private var pendingInitY: Int = -1

        @Volatile @JvmStatic var lastShownText: String = ""
        @Volatile @JvmStatic var lastShownMode: String = ""

        @Volatile @JvmStatic
        private var currentInstance: FloatingBubbleModule? = null

        @JvmStatic fun reshowLast(ctx: ReactApplicationContext) {
            if (lastShownText.isEmpty()) return
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                try {
                    if (bubbleView != null) return@post
                    val inst = currentInstance
                        ?: try { ctx.getNativeModule(FloatingBubbleModule::class.java) } catch (_: Exception) { null }
                    inst?.createBubble(lastShownText)
                } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w("FloatingBubble", "reshowLast: ${e.message}") }
            }
        }

        @JvmStatic fun hideStatic() {
            val h = Handler(Looper.getMainLooper())
            h.post {
                if (bubbleView != null) {
                    try { windowManager?.removeView(bubbleView) } catch (_: Exception) {}
                    bubbleView = null; layoutParams = null
                }
            }
        }

        @JvmStatic fun handleOrientationChange() {
            Handler(Looper.getMainLooper()).post {
                val inst = currentInstance ?: return@post
                try {
                    val dm = inst.reactApplicationContext.resources.displayMetrics
                    val newW = dm.widthPixels; val newH = dm.heightPixels
                    if (newW == screenWidth && newH == screenHeight) return@post
                    screenWidth = newW; screenHeight = newH
                    val lp = layoutParams ?: return@post
                    val v = bubbleView ?: return@post
                    val vh = v.height.takeIf { it > 0 } ?: 60
                    val vw = v.width.takeIf { it > 0 } ?: 240
                    lp.x = lp.x.coerceIn(0, (screenWidth - vw).coerceAtLeast(0))
                    lp.y = lp.y.coerceIn(0, (screenHeight - vh).coerceAtLeast(0))
                    stickyY = lp.y
                    try { windowManager?.updateViewLayout(v, lp) } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
        }
    }

    @ReactMethod fun show(text: String, mode: String?) {
        lastShownText = text
        lastShownMode = mode ?: ""
        handler.post {
            try {
                if (bubbleView != null) return@post
                createBubble(text)
            } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "show: ${e.message}", e) }
        }
    }

    @ReactMethod fun showAt(text: String, pageX: Int, pageY: Int) {
        lastShownText = text
        handler.post {
            try {
                if (bubbleView != null) return@post
                val dm = reactApplicationContext.resources.displayMetrics
                screenHeight = dm.heightPixels
                screenWidth = dm.widthPixels
                pendingInitX = if (pageWidth > 0) (pageX.toFloat() * screenWidth / pageWidth).toInt() else pageX
                pendingInitY = if (pageHeight > 0) (pageY.toFloat() * screenHeight / pageHeight).toInt() else pageY
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "showAt page=($pageX,$pageY) -> screen=($pendingInitX,$pendingInitY)")
                createBubble(text)
            } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "showAt: ${e.message}", e) }
        }
    }

    @ReactMethod fun hide() {
        lastShownText = ""
        lastShownMode = ""
        handler.post { try { removeBubble() } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "hide: ${e.message}", e) } }
    }

    @ReactMethod fun updateText(text: String) {  }

    @ReactMethod fun setActionButtons(json: String) {  }

    @ReactMethod fun setPageHeight(height: Int) { pageHeight = height }
    @ReactMethod fun setScreenHeight(height: Int) { screenHeight = height }
    @ReactMethod fun setPageWidth(width: Int) { pageWidth = width }
    @ReactMethod fun setScreenWidth(width: Int) { screenWidth = width }

    @ReactMethod fun setPositionY(pageY: Int) {
        if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "setPositionY($pageY) ignored — bubble position is sticky")
    }

    @ReactMethod fun isShowing(promise: Promise) { promise.resolve(bubbleView != null) }

    @ReactMethod fun checkOverlayPermission(promise: Promise) {
        if (Build.VERSION.SDK_INT >= 23) promise.resolve(Settings.canDrawOverlays(reactApplicationContext))
        else promise.resolve(true)
    }

    @ReactMethod fun requestOverlayPermission() {
        try {
            reactApplicationContext.startActivity(
                android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${reactApplicationContext.packageName}"))
                    .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "requestOverlayPermission: ${e.message}", e)
            try {
                reactApplicationContext.startActivity(
                    android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${reactApplicationContext.packageName}"))
                        .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (_: Exception) {}
        }
    }

    private fun callShowPluginView() {
        try {
            val pm = reactApplicationContext.catalystInstance.getNativeModule("NativePluginManager") ?: return
            val methods = pm::class.java.methods.filter { it.name == "showPluginView" }
            if (methods.isEmpty()) return
            val m = methods.firstOrNull { it.parameterCount == 0 }
                ?: methods.firstOrNull { it.parameterCount == 1 }
                ?: methods.first()
            if (m.parameterCount == 0) m.invoke(pm)
            else m.invoke(pm, PromiseImpl(null, null))
        } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "callShowPluginView: ${e.message}", e) }
    }

    private fun createBubble(text: String) {
        val context = reactApplicationContext
        removeBubble()
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            emitEvent("onBubblePermissionDenied", Arguments.createMap()); return
        }
        windowManager = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val dm = context.resources.displayMetrics
        screenHeight = dm.heightPixels
        screenWidth = dm.widthPixels
        val d = dm.density
        val bubbleSize = (36 * d).toInt()

        val iconView = PenNibBubbleView(context, d)
        iconView.layoutParams = LinearLayout.LayoutParams(bubbleSize, bubbleSize)

        bubbleView = TouchSinkLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(iconView)
        }

        @Suppress("DEPRECATION")
        val wmType = WindowManager.LayoutParams.TYPE_PHONE
        layoutParams = WindowManager.LayoutParams(
            bubbleSize, bubbleSize,
            wmType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (pendingInitX >= 0 && pendingInitY >= 0) {

                x = pendingInitX.coerceIn(0, (screenWidth - bubbleSize).coerceAtLeast(0))
                y = pendingInitY.coerceIn(0, (screenHeight - bubbleSize).coerceAtLeast(0))
                stickyY = y
            } else {
                x = 24; y = stickyY.coerceIn(0, (screenHeight - 60).coerceAtLeast(0))
            }
        }

        pendingInitX = -1; pendingInitY = -1

        bubbleView!!.setOnTouchListener { _, ev ->
            val lp = layoutParams ?: return@setOnTouchListener false
            val view = bubbleView ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; startRawX = ev.rawX; startRawY = ev.rawY
                    isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX; val dy = ev.rawY - startRawY
                    if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) { isDragging = true }
                    if (isDragging) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {} }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        stickyY = lp.y
                        emitBubbleCoords(view, lp, "onBubbleDragEnd")
                    } else { emitEvent("onBubbleTap", Arguments.createMap()) }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(bubbleView, layoutParams)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "bubble shown: '$text'")

        bubbleView?.post {
            val lp = layoutParams ?: return@post
            val v = bubbleView ?: return@post
            emitBubbleCoords(v, lp, "onBubbleLayout")
        }
    }

    private fun emitBubbleCoords(view: View, lp: WindowManager.LayoutParams, eventName: String) {
        val sx = lp.x.toFloat()
        val sy = lp.y.toFloat()
        val bubbleH = view.height
        val bubbleW = view.width
        val sBottom = sy + bubbleH.toFloat()
        val ry = if (screenHeight > 0) pageHeight.toFloat() / screenHeight.toFloat() else 1f
        val rx = if (screenWidth > 0) pageWidth.toFloat() / screenWidth.toFloat() else 1f
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[COORD] $eventName sx=$sx sy=$sy bubbleH=$bubbleH bubbleW=$bubbleW" +
                " sBottom=$sBottom ry=$ry rx=$rx pageY=${(sy*ry).toInt()} pageBottomY=${(sBottom*ry).toInt()}")
        emitEvent(eventName, Arguments.createMap().apply {
            putDouble("screenY", sy.toDouble())
            putDouble("screenX", sx.toDouble())
            putDouble("screenBottomY", sBottom.toDouble())
            putInt("pageY", (sy * ry).toInt())
            putInt("pageX", (sx * rx).toInt())
            putInt("pageBottomY", (sBottom * ry).toInt())
            putInt("bubbleHeight", bubbleH)
            putInt("bubbleWidth", bubbleW)
            putDouble("ratioY", ry.toDouble())
            putDouble("ratioX", rx.toDouble())
        })
    }

    private fun removeBubble() {
        if (bubbleView != null) {
            try { windowManager?.removeView(bubbleView) } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "removeView: ${e.message}") }
            bubbleView = null; layoutParams = null
        }
    }

    private fun emitEvent(name: String, params: WritableMap) {
        try { reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(name, params) }
        catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "emitEvent($name): ${e.message}") }
    }

    override fun onCatalystInstanceDestroy() {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onCatalystInstanceDestroy — keeping bubble alive")
        super.onCatalystInstanceDestroy()
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}

private class PenNibBubbleView(ctx: Context, private val density: Float) : View(ctx) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111111"); style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111111"); style = Paint.Style.FILL
    }
    private val iconPath = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = (w / 2f) - (1.5f * density)

        canvas.drawCircle(cx, cy, r, bgPaint)

        canvas.drawCircle(cx, cy, r, borderPaint)

        drawPenIcon(canvas, cx, cy, r * 0.52f)
    }

    private fun drawPenIcon(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        iconPath.reset()

        val bodyTop = cy - size * 0.9f
        val bodyBottom = cy + size * 0.15f
        val bodyHalfW = size * 0.22f

        canvas.save()
        canvas.rotate(-35f, cx, cy)

        val bodyRect = RectF(cx - bodyHalfW, bodyTop, cx + bodyHalfW, bodyBottom)
        canvas.drawRoundRect(bodyRect, bodyHalfW * 0.4f, bodyHalfW * 0.4f, iconPaint)

        iconPath.reset()
        iconPath.moveTo(cx - bodyHalfW, bodyBottom)
        iconPath.lineTo(cx + bodyHalfW, bodyBottom)
        iconPath.lineTo(cx, cy + size * 0.85f)
        iconPath.close()
        canvas.drawPath(iconPath, iconPaint)

        val dotR = size * 0.06f
        canvas.drawCircle(cx, cy + size * 0.85f + dotR * 0.5f, dotR, iconPaint)

        canvas.restore()
    }
}
