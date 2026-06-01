package com.supernote_quicktoolbar.bubbles
import com.supernote_quicktoolbar.*
import com.supernote_quicktoolbar.overlays.*
import com.supernote_quicktoolbar.panels.*

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray

class AiBubbleModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "AiBubble"

    private val TAG = "AiBubble"
    private val handler = Handler(Looper.getMainLooper())

    private val CLR_BG       = Color.WHITE
    private val CLR_BORDER   = Color.parseColor("#111111")
    private val CLR_TEXT     = Color.parseColor("#1A1A1A")
    private val CLR_DOT     = Color.parseColor("#111111")
    private val CLR_BTN_BG   = Color.WHITE
    private val CLR_BTN_BORDER = Color.parseColor("#111111")
    private val CLR_BTN_TEXT = Color.parseColor("#1A1A1A")
    private val CLR_SEP      = Color.parseColor("#E8E8E5")

    init { currentInstance = this }

    companion object {
        @Volatile @JvmStatic private var windowManager: WindowManager? = null
        @Volatile @JvmStatic private var bubbleView: LinearLayout? = null
        @Volatile @JvmStatic private var statusText: TextView? = null
        @Volatile @JvmStatic private var dotView: View? = null
        @Volatile @JvmStatic private var actionRow: LinearLayout? = null
        @Volatile @JvmStatic private var layoutParams: WindowManager.LayoutParams? = null

        @Volatile @JvmStatic private var startX = 0
        @Volatile @JvmStatic private var startY = 0
        @Volatile @JvmStatic private var startRawX = 0f
        @Volatile @JvmStatic private var startRawY = 0f
        @Volatile @JvmStatic private var isDragging = false
        @Volatile @JvmStatic private var longPressFired = false

        @Volatile @JvmStatic private var pageHeight = 1872
        @Volatile @JvmStatic private var screenHeight = 1872
        @Volatile @JvmStatic private var screenWidth = 1404

        @Volatile @JvmStatic private var stickyY: Int = 120

        @Volatile @JvmStatic private var cachedActionsJson: String = "[]"
        @Volatile @JvmStatic private var pendingLongPress: Runnable? = null
        private const val LONG_PRESS_MS = 600L

        @Volatile @JvmStatic var lastShownText: String = ""

        @Volatile @JvmStatic
        private var currentInstance: AiBubbleModule? = null

        @JvmStatic fun reshowLast(ctx: ReactApplicationContext) {
            if (lastShownText.isEmpty()) return
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                try {
                    if (bubbleView != null) {
                        statusText?.text = lastShownText
                    } else {
                        val inst = currentInstance
                            ?: try { ctx.getNativeModule(AiBubbleModule::class.java) } catch (_: Exception) { null }
                        inst?.createBubble(lastShownText)
                    }
                } catch (e: Exception) { Log.w("AiBubble", "reshowLast: ${e.message}") }
            }
        }

        @JvmStatic fun hideStatic() {
            val h = Handler(Looper.getMainLooper())
            h.post {
                pendingLongPress?.let { h.removeCallbacks(it) }
                pendingLongPress = null
                if (bubbleView != null) {
                    try { windowManager?.removeView(bubbleView) } catch (_: Exception) {}
                    bubbleView = null; statusText = null; dotView = null; actionRow = null; layoutParams = null
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
                    lp.y = lp.y.coerceIn(0, (screenHeight - vh).coerceAtLeast(0))
                    stickyY = lp.y
                    try { windowManager?.updateViewLayout(v, lp) } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
        }
    }

    @ReactMethod fun show(text: String) {
        lastShownText = text
        handler.post {
            try {
                if (bubbleView != null) { statusText?.text = text; return@post }
                createBubble(text)
            } catch (e: Exception) { Log.e(TAG, "show: ${e.message}", e) }
        }
    }

    @ReactMethod fun hide() {
        lastShownText = ""
        handler.post { try { removeBubble() } catch (e: Exception) { Log.e(TAG, "hide: ${e.message}", e) } }
    }

    @ReactMethod fun updateText(text: String) {
        lastShownText = text
        handler.post { statusText?.text = text }
    }

    @ReactMethod fun setActionButtons(json: String) {
        cachedActionsJson = json
        handler.post { try { rebuildActionRow() } catch (e: Exception) { Log.e(TAG, "setActionButtons: ${e.message}", e) } }
    }

    @ReactMethod fun setPageHeight(height: Int) { pageHeight = height }
    @ReactMethod fun setScreenHeight(height: Int) { screenHeight = height }

    @ReactMethod fun isShowing(promise: Promise) { promise.resolve(bubbleView != null) }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun isShowingSync(): Boolean = bubbleView != null

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
            Log.e(TAG, "requestOverlayPermission: ${e.message}", e)
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
        } catch (e: Exception) { Log.e(TAG, "callShowPluginView: ${e.message}", e) }
    }

    private fun createBubble(text: String) {
        val context = reactApplicationContext
        removeBubble()
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            emitEvent("onAiBubblePermissionDenied", Arguments.createMap()); return
        }
        windowManager = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val dm = context.resources.displayMetrics
        screenHeight = dm.heightPixels
        screenWidth = dm.widthPixels
        val d = dm.density
        val borderW = (1.5f * d).toInt()

        bubbleView = TouchSinkLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12*d).toInt(), (8*d).toInt(), (12*d).toInt(), (8*d).toInt())
            background = GradientDrawable().apply {
                setColor(CLR_BG)
                setStroke(borderW, CLR_BORDER)
                cornerRadius = 0f
            }
        }

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dotView = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((8*d).toInt(), (8*d).toInt()).apply {
                rightMargin = (8*d).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CLR_DOT)
            }
        }
        statusRow.addView(dotView)
        statusText = TextView(context).apply {
            this.text = text; textSize = 14f; setTextColor(CLR_TEXT)
            typeface = Typeface.DEFAULT_BOLD; maxLines = 1
        }
        statusRow.addView(statusText)
        bubbleView!!.addView(statusRow)

        actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6*d).toInt() }
        }
        bubbleView!!.addView(actionRow)
        rebuildActionRow()

        @Suppress("DEPRECATION")
        val wmType = WindowManager.LayoutParams.TYPE_PHONE
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            wmType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24; y = stickyY.coerceIn(0, (screenHeight - 60).coerceAtLeast(0))
        }

        val longPressR = Runnable { if (!isDragging && bubbleView != null) { longPressFired = true; emitEvent("onAiBubbleLongPress", Arguments.createMap()) } }
        pendingLongPress = longPressR
        statusRow.setOnTouchListener { _, ev ->
            val lp = layoutParams ?: return@setOnTouchListener false
            val view = bubbleView ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    startRawX = ev.rawX; startRawY = ev.rawY
                    isDragging = false; longPressFired = false
                    handler.postDelayed(longPressR, LONG_PRESS_MS); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX; val dy = ev.rawY - startRawY
                    if (!isDragging && (Math.abs(dx)>10||Math.abs(dy)>10)) { isDragging = true; handler.removeCallbacks(longPressR) }
                    if (isDragging) {
                        lp.y = (startY + dy.toInt()).coerceIn(0, screenHeight - 60)
                        try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {}
                    }; true
                }
                MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(longPressR); true }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressR)
                    if (longPressFired) {  }
                    else if (isDragging) {
                        stickyY = lp.y
                        val sy = lp.y.toFloat()
                        val r = pageHeight.toFloat() / screenHeight.toFloat()
                        emitEvent("onAiBubbleDragEnd", Arguments.createMap().apply {
                            putDouble("screenY", sy.toDouble())
                            putInt("pageY", (sy*r).toInt())
                        })
                    } else {
                        emitEvent("onAiBubbleTap", Arguments.createMap())
                    }; true
                }
                else -> false
            }
        }

        windowManager?.addView(bubbleView, layoutParams)
        Log.i(TAG, "AI bubble shown: '$text'")
    }

    private fun rebuildActionRow() {
        val row = actionRow ?: return
        row.removeAllViews()
        try {
            val arr = JSONArray(cachedActionsJson)
            if (arr.length() == 0) { row.visibility = View.GONE; tryUpdateLayout(); return }
            row.visibility = View.VISIBLE
            val d = reactApplicationContext.resources.displayMetrics.density
            val borderW = (1 * d).toInt()

            if (arr.length() > 0) {
                val sep = View(reactApplicationContext).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()
                    ).apply { bottomMargin = (6*d).toInt() }
                    setBackgroundColor(CLR_SEP)
                }

                val parent = row.parent as? LinearLayout
                if (parent != null) {
                    val idx = parent.indexOfChild(row)
                    if (idx > 0) {

                        val prev = parent.getChildAt(idx - 1)
                        if (prev.tag != "ai_sep") {
                            sep.tag = "ai_sep"
                            parent.addView(sep, idx)
                        }
                    }
                }
            }

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val actionId = obj.getString("id"); val icon = obj.optString("icon","?")
                val label = obj.optString("label", actionId)
                if (i > 0) { row.addView(View(reactApplicationContext).apply {
                    layoutParams = LinearLayout.LayoutParams((6*d).toInt(),1) })
                }
                row.addView(TextView(reactApplicationContext).apply {
                    text = icon; textSize = 14f; setTextColor(CLR_BTN_TEXT)
                    typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                    minWidth = (40*d).toInt(); minHeight = (36*d).toInt()
                    setPadding((12*d).toInt(),(8*d).toInt(),(12*d).toInt(),(8*d).toInt())
                    background = GradientDrawable().apply {
                        setColor(CLR_BTN_BG)
                        setStroke(borderW, CLR_BTN_BORDER)
                        cornerRadius = 0f
                    }
                    contentDescription = label
                    setOnClickListener {
                        Log.i(TAG, "AI bubble action tapped: $actionId")
                        emitEvent("onAiBubbleAction", Arguments.createMap().apply {
                            putString("actionId", actionId)
                        })
                    }
                })
            }
            tryUpdateLayout()
        } catch (e: Exception) { Log.e(TAG, "rebuildActionRow: ${e.message}"); row.visibility = View.GONE }
    }

    private fun tryUpdateLayout() {
        try { if (bubbleView != null && layoutParams != null)
            windowManager?.updateViewLayout(bubbleView, layoutParams) } catch (_: Exception) {}
    }

    private fun removeBubble() {
        pendingLongPress?.let { handler.removeCallbacks(it) }
        pendingLongPress = null

        if (bubbleView != null) {
            try { windowManager?.removeView(bubbleView) } catch (e: Exception) { Log.w(TAG, "removeView: ${e.message}") }
            bubbleView = null; statusText = null; dotView = null; actionRow = null; layoutParams = null
        }
    }

    private fun emitEvent(name: String, params: WritableMap) {
        try { reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(name, params) }
        catch (e: Exception) { Log.w(TAG, "emitEvent($name): ${e.message}") }
    }

    override fun onCatalystInstanceDestroy() {
        Log.i(TAG, "onCatalystInstanceDestroy — keeping AI bubble alive")
        super.onCatalystInstanceDestroy()
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
