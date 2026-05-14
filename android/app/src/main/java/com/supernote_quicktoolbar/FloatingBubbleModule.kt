package com.supernote_quicktoolbar

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

class FloatingBubbleModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "FloatingBubble"

    private val TAG = "FloatingBubble"
    private val handler = Handler(Looper.getMainLooper())

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

        @Volatile @JvmStatic private var pageHeight = 1872
        @Volatile @JvmStatic private var screenHeight = 1872

        @Volatile @JvmStatic private var stickyY: Int = 80

        @Volatile @JvmStatic private var cachedActionsJson: String = "[]"

        @Volatile @JvmStatic var lastShownText: String = ""
        @Volatile @JvmStatic var lastShownMode: String = ""

        @Volatile @JvmStatic
        private var currentInstance: FloatingBubbleModule? = null

        @JvmStatic fun reshowLast(ctx: ReactApplicationContext) {
            if (lastShownText.isEmpty()) return
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                try {
                    if (bubbleView != null) {
                        statusText?.text = lastShownText
                    } else {
                        val inst = currentInstance
                            ?: try { ctx.getNativeModule(FloatingBubbleModule::class.java) } catch (_: Exception) { null }
                        inst?.createBubble(lastShownText)
                    }
                } catch (e: Exception) { Log.w("FloatingBubble", "reshowLast: ${e.message}") }
            }
        }

        @JvmStatic fun hideStatic() {
            val h = Handler(Looper.getMainLooper())
            h.post {
                if (bubbleView != null) {
                    try { windowManager?.removeView(bubbleView) } catch (_: Exception) {}
                    bubbleView = null; statusText = null; dotView = null; actionRow = null; layoutParams = null
                }
            }
        }
    }

    @ReactMethod fun show(text: String, mode: String?) {
        lastShownText = text
        lastShownMode = mode ?: ""
        handler.post {
            try {
                if (bubbleView != null) { statusText?.text = text; return@post }
                createBubble(text)
            } catch (e: Exception) { Log.e(TAG, "show: ${e.message}", e) }
        }
    }

    @ReactMethod fun hide() {
        lastShownText = ""
        lastShownMode = ""
        handler.post { try { removeBubble() } catch (e: Exception) { Log.e(TAG, "hide: ${e.message}", e) } }
    }

    @ReactMethod fun updateText(text: String) { handler.post { statusText?.text = text } }

    @ReactMethod fun setActionButtons(json: String) {
        cachedActionsJson = json
        handler.post { try { rebuildActionRow() } catch (e: Exception) { Log.e(TAG, "setActionButtons: ${e.message}", e) } }
    }

    @ReactMethod fun setPageHeight(height: Int) { pageHeight = height }
    @ReactMethod fun setScreenHeight(height: Int) { screenHeight = height }

    @ReactMethod fun setPositionY(pageY: Int) {

        Log.d(TAG, "setPositionY($pageY) ignored — bubble position is sticky")
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
            Log.e(TAG, "requestOverlayPermission: ${e.message}", e)
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
            else m.invoke(pm, *arrayOfNulls(m.parameterCount))
        } catch (e: Exception) { Log.e(TAG, "callShowPluginView: ${e.message}", e) }
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
        val d = dm.density

        bubbleView = TouchSinkLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14*d).toInt(), (10*d).toInt(), (14*d).toInt(), (10*d).toInt())
            minimumWidth = (240*d).toInt()
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#f6f4ee")); setStroke((1.4f*d).toInt(), Color.parseColor("#1a1812")); cornerRadius = 3f*d
            }
        }

        val statusRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dotView = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((12*d).toInt(), (12*d).toInt()).apply { rightMargin = (10*d).toInt() }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#1a1812")) }
        }
        statusRow.addView(dotView)
        statusText = TextView(context).apply {
            this.text = text; textSize = 16f; setTextColor(Color.parseColor("#1a1812")); typeface = Typeface.DEFAULT_BOLD; maxLines = 1
        }
        statusRow.addView(statusText)
        bubbleView!!.addView(statusRow)

        actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (8*d).toInt() }
        }
        bubbleView!!.addView(actionRow)
        rebuildActionRow()

        val wmType = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            wmType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = stickyY }

        statusRow.setOnTouchListener { _, ev ->
            val lp = layoutParams ?: return@setOnTouchListener false
            val view = bubbleView ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; startRawX = ev.rawX; startRawY = ev.rawY
                    isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX; val dy = ev.rawY - startRawY
                    if (!isDragging && (Math.abs(dx)>10||Math.abs(dy)>10)) { isDragging = true }
                    if (isDragging) { lp.x = startX+dx.toInt(); lp.y = startY+dy.toInt(); try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {} }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        stickyY = lp.y
                        val sy = lp.y.toFloat(); val r = pageHeight.toFloat()/screenHeight.toFloat()
                        emitEvent("onBubbleDragEnd", Arguments.createMap().apply { putDouble("screenY",sy.toDouble()); putInt("pageY",(sy*r).toInt()) })
                    } else { emitEvent("onBubbleTap", Arguments.createMap()) }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(bubbleView, layoutParams)
        Log.i(TAG, "bubble shown: '$text'")
    }

    private fun rebuildActionRow() {
        val row = actionRow ?: return
        row.removeAllViews()
        try {
            val arr = JSONArray(cachedActionsJson)
            if (arr.length() == 0) { row.visibility = View.GONE; tryUpdateLayout(); return }
            row.visibility = View.VISIBLE
            val d = reactApplicationContext.resources.displayMetrics.density
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val actionId = obj.getString("id"); val icon = obj.optString("icon","?"); val label = obj.optString("label", actionId)
                if (i > 0) { row.addView(View(reactApplicationContext).apply { layoutParams = LinearLayout.LayoutParams((8*d).toInt(),1) }) }
                row.addView(TextView(reactApplicationContext).apply {
                    text = icon; textSize = 15f; setTextColor(Color.parseColor("#1a1812")); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                    minWidth = (44*d).toInt(); minHeight = (40*d).toInt()
                    setPadding((14*d).toInt(),(10*d).toInt(),(14*d).toInt(),(10*d).toInt())
                    background = GradientDrawable().apply { setColor(Color.parseColor("#efece3")); setStroke((1*d).toInt(), Color.parseColor("#b0aa9a")); cornerRadius = 2f*d }
                    contentDescription = label
                    setOnClickListener {
                        android.util.Log.i(TAG, "[LASSO-DBG/Kt] bubble action tapped: $actionId")
                        emitEvent("onBubbleAction", Arguments.createMap().apply { putString("actionId", actionId) })
                    }
                })
            }
            tryUpdateLayout()
        } catch (e: Exception) { Log.e(TAG, "rebuildActionRow: ${e.message}"); row.visibility = View.GONE }
    }

    private fun tryUpdateLayout() { try { if (bubbleView != null && layoutParams != null) windowManager?.updateViewLayout(bubbleView, layoutParams) } catch (_: Exception) {} }

    private fun removeBubble() {
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
        Log.i(TAG, "onCatalystInstanceDestroy — keeping bubble alive")
        super.onCatalystInstanceDestroy()
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
