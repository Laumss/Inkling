package com.supernote_quicktoolbar.bubbles
import com.supernote_quicktoolbar.BuildConfig
import com.supernote_quicktoolbar.*
import com.supernote_quicktoolbar.overlays.*

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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.supernote_quicktoolbar.ui_common.VectorAssets
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject

class PaletteBubbleModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "PaletteBubble"

    private val TAG = "PaletteBubble"
    private val handler = Handler(Looper.getMainLooper())

    init { currentInstance = this }

    data class PenColorDef(val key: String, val value: Int, val swatch: Int, val textColor: Int)

    data class PresetData(val penId: String, val color: String, val thickness: Int, val penType: Int = 10)

    companion object {
        @Volatile @JvmStatic private var windowManager: WindowManager? = null
        @Volatile @JvmStatic private var bubbleView: LinearLayout? = null
        @Volatile @JvmStatic private var layoutParams: WindowManager.LayoutParams? = null

        @Volatile @JvmStatic private var startX = 0
        @Volatile @JvmStatic private var startY = 0
        @Volatile @JvmStatic private var startRawX = 0f
        @Volatile @JvmStatic private var startRawY = 0f
        @Volatile @JvmStatic private var isDragging = false

        @Volatile @JvmStatic private var screenHeight = 1872
        @Volatile @JvmStatic private var screenWidth = 1404

        @Volatile @JvmStatic private var stickyX: Int = 24
        @Volatile @JvmStatic private var stickyY: Int = 200

        @Volatile @JvmStatic private var wantShown: Boolean = false

        @Volatile @JvmStatic
        private var currentInstance: PaletteBubbleModule? = null

        @Volatile @JvmStatic
        private var presets: Array<PresetData?> = arrayOfNulls(MAX_SLOTS)

        private const val PREFS_NAME = "quicktoolbar_presets"
        private const val PRESETS_KEY = "palette_presets"
        private const val ACTIVE_COLS_KEY = "palette_active_cols"
        private const val COLS = 6
        private const val ROWS = 2
        private const val MAX_SLOTS = COLS * ROWS
        private const val MIN_ACTIVE_COLS = 4

        @Volatile @JvmStatic private var activeCols = MIN_ACTIVE_COLS

        val PEN_COLORS = listOf(
            PenColorDef("black",     0x00, Color.BLACK,                    Color.WHITE),
            PenColorDef("darkGray",  0x9D, Color.parseColor("#9D9D9D"),   Color.WHITE),
            PenColorDef("lightGray", 0xC9, Color.parseColor("#C9C9C9"),   Color.BLACK),
            PenColorDef("ghost",     0xFE, Color.WHITE,                    Color.BLACK),
        )

        @JvmStatic fun toggleStatic() {
            val h = Handler(Looper.getMainLooper())
            h.post {
                if (bubbleView != null) {
                    hideStatic()
                } else {
                    wantShown = true
                    reloadPresetsInternal()
                    currentInstance?.createBubble()
                }
            }
        }

        @JvmStatic fun hideStatic() {
            val h = Handler(Looper.getMainLooper())
            h.post {
                wantShown = false
                if (bubbleView != null) {
                    try { windowManager?.removeView(bubbleView) } catch (_: Exception) {}
                    bubbleView = null; layoutParams = null
                }
                currentInstance?.notifyToolbarHighlight()
            }
        }

        @JvmStatic fun reshowLast(ctx: ReactApplicationContext) {
            if (!wantShown) return
            val h = Handler(Looper.getMainLooper())
            h.post {
                if (bubbleView != null) return@post
                reloadPresetsInternal()
                val inst = currentInstance
                    ?: try { ctx.getNativeModule(PaletteBubbleModule::class.java) } catch (_: Exception) { null }
                inst?.createBubble()
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
                    val vw = v.width.takeIf { it > 0 } ?: 80
                    val vh = v.height.takeIf { it > 0 } ?: 80
                    lp.x = lp.x.coerceIn(0, (screenWidth - vw).coerceAtLeast(0))
                    lp.y = lp.y.coerceIn(0, (screenHeight - vh).coerceAtLeast(0))
                    stickyX = lp.x; stickyY = lp.y
                    try { windowManager?.updateViewLayout(v, lp) } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
        }

        @JvmStatic fun reloadPresets() {
            Handler(Looper.getMainLooper()).post {
                reloadPresetsInternal()
                if (bubbleView != null) {
                    currentInstance?.rebuildSlots()
                }
            }
        }

        private fun reloadPresetsInternal() {
            val inst = currentInstance ?: return
            try {
                val prefs = inst.reactApplicationContext.getSharedPreferences(PREFS_NAME, 0)
                activeCols = prefs.getInt(ACTIVE_COLS_KEY, MIN_ACTIVE_COLS)
                    .coerceIn(MIN_ACTIVE_COLS, COLS)
                val json = prefs.getString(PRESETS_KEY, null)
                val newPresets = arrayOfNulls<PresetData>(MAX_SLOTS)
                if (json != null) {
                    val arr = JSONArray(json)
                    for (i in 0 until minOf(arr.length(), MAX_SLOTS)) {
                        if (arr.isNull(i)) continue
                        val o = arr.getJSONObject(i)
                        val penId = o.optString("penId", "")
                        val color = o.optString("color", "black")
                        val rawPt = o.optInt("penType", 10)
                        val pt = when (rawPt) { 0 -> 10; 1 -> 16; 2 -> 10; else -> rawPt }
                        val finalPenId = if (penId.isEmpty()) {
                            when {
                                pt == 11 -> when (color) {
                                    "darkGray" -> "mk-gy"; "ghost" -> "mk-wt"; else -> "mk-bk"
                                }
                                pt == 16 -> "ball"
                                pt == 14 -> "brush"
                                else -> "needle"
                            }
                        } else penId
                        newPresets[i] = PresetData(
                            penId = finalPenId,
                            color = color,
                            thickness = PenSizeSpec.snap(pt, o.optInt("thickness", 200)),
                            penType = pt
                        )
                    }
                }
                presets = newPresets
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.w("PaletteBubble", "reloadPresets: ${e.message}")
            }
        }

        fun colorDefFor(key: String): PenColorDef? = PEN_COLORS.find { it.key == key }
    }

    @ReactMethod fun show() {
        handler.post {
            wantShown = true
            if (bubbleView != null) return@post
            reloadPresetsInternal()
            createBubble()
        }
    }

    @ReactMethod fun hide() {
        wantShown = false
        handler.post { removeBubble() }
    }

    @ReactMethod fun isShowing(promise: Promise) { promise.resolve(bubbleView != null) }

    private fun createBubble() {
        val context = reactApplicationContext
        removeBubble()
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) return

        windowManager = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val dm = context.resources.displayMetrics
        screenHeight = dm.heightPixels
        screenWidth = dm.widthPixels
        val d = dm.density
        val sf = maxOf(0.86f, com.supernote_quicktoolbar.ui_common.ScreenScale.factor(context))
        val borderW = (1.5f * d * sf).toInt().coerceAtLeast(1)
        val slotSz = (48 * d * sf).toInt()
        val gap = (4.5f * d * sf).toInt()
        val pad = (9 * d * sf).toInt()

        bubbleView = TouchSinkLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F4F4F0"))
                setStroke((2f * d * sf).toInt().coerceAtLeast(1), Color.parseColor("#1E1E1B"))
                cornerRadius = 14 * d * sf
            }
        }

        for (row in 0 until ROWS) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (row > 0) topMargin = gap }
            }
            for (col in 0 until activeCols) {
                val idx = row * COLS + col
                rowLayout.addView(makeSlotView(context, idx, slotSz, d * sf, gap))
            }
            bubbleView!!.addView(rowLayout)
        }

        @Suppress("DEPRECATION")
        val wmType = WindowManager.LayoutParams.TYPE_PHONE
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            wmType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = stickyX.coerceIn(0, (screenWidth - 80).coerceAtLeast(0))
            y = stickyY.coerceIn(0, (screenHeight - 80).coerceAtLeast(0))
        }

        bubbleView!!.setOnTouchListener { _, ev ->
            val lp = layoutParams ?: return@setOnTouchListener false
            val view = bubbleView ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    startRawX = ev.rawX; startRawY = ev.rawY
                    isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX; val dy = ev.rawY - startRawY
                    if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) isDragging = true
                    if (isDragging) {
                        lp.x = (startX + dx.toInt()).coerceIn(0, (screenWidth - 80).coerceAtLeast(0))
                        lp.y = (startY + dy.toInt()).coerceIn(0, (screenHeight - 80).coerceAtLeast(0))
                        try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {}
                    }; true
                }
                MotionEvent.ACTION_CANCEL -> true
                MotionEvent.ACTION_UP -> {
                    if (isDragging) { stickyX = lp.x; stickyY = lp.y }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(bubbleView, layoutParams)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "palette bubble shown (${ROWS}x${activeCols}, ${activeCols * ROWS} slots)")
        notifyToolbarHighlight()
    }

    private fun makeSlotView(context: android.content.Context, index: Int, slotSz: Int, scaledD: Float, gap: Int): View {
        val preset = presets[index]
        val borderW = (1.5f * scaledD).toInt().coerceAtLeast(1)
        val ctx = context as ReactApplicationContext
        val sf = maxOf(0.86f, com.supernote_quicktoolbar.ui_common.ScreenScale.factor(ctx))

        val col = index % COLS
        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(slotSz, slotSz).apply {
                if (col > 0) marginStart = gap
            }
        }

        if (preset == null) {
            frame.background = GradientDrawable().apply {
                setColor(Color.parseColor("#F4F4F0"))
                setStroke(borderW, Color.parseColor("#CBCBC4"))
                cornerRadius = 0f
            }
            frame.addView(TextView(context).apply {
                text = "—"
                textSize = 15f * sf
                setTextColor(Color.parseColor("#9A9A92"))
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        } else {
            val colorDef = colorDefFor(preset.color)
            val swatchColor = colorDef?.swatch ?: Color.BLACK
            val textColor = colorDef?.textColor ?: Color.WHITE
            val isGhost = preset.color == "ghost"

            frame.background = GradientDrawable().apply {
                setColor(swatchColor)
                setStroke(borderW, if (isGhost) Color.parseColor("#CBCBC4") else Color.parseColor("#1E1E1B"))
                cornerRadius = 0f
            }

            val thicknessText = PenSizeSpec.label(preset.penType, preset.thickness)
            val padPx = (3 * scaledD).toInt()

            frame.addView(TextView(context).apply {
                text = thicknessText
                textSize = 13.5f * sf
                setTextColor(textColor)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.BOTTOM or Gravity.END
                setPadding(0, 0, padPx, padPx)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })

            val iconSz = (14 * scaledD).toInt()
            frame.addView(ImageView(context).apply {
                setImageBitmap(VectorAssets.loadBitmapTinted(
                    ctx, PenSizeSpec.penIconAsset(preset.penType), iconSz, textColor))
                layoutParams = FrameLayout.LayoutParams(iconSz, iconSz, Gravity.TOP or Gravity.START).apply {
                    leftMargin = padPx; topMargin = padPx
                }
            })

            frame.setOnClickListener {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "slot $index tapped: color=${preset.color} thickness=${preset.thickness} penType=${preset.penType}")
                emitEvent("onPaletteBubbleSlotTap", Arguments.createMap().apply {
                    putInt("slotIndex", index)
                    putString("color", preset.color)
                    putInt("thickness", preset.thickness)
                    putInt("penType", preset.penType)
                })
            }
        }

        return frame
    }

    private fun rebuildSlots() {
        val view = bubbleView ?: return
        view.removeAllViews()
        val context = reactApplicationContext
        val dm = context.resources.displayMetrics
        val d = dm.density
        val sf = maxOf(0.86f, com.supernote_quicktoolbar.ui_common.ScreenScale.factor(context))
        val slotSz = (48 * d * sf).toInt()
        val gap = (4.5f * d * sf).toInt()

        for (row in 0 until ROWS) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (row > 0) topMargin = gap }
            }
            for (col in 0 until activeCols) {
                val idx = row * COLS + col
                rowLayout.addView(makeSlotView(context, idx, slotSz, d * sf, gap))
            }
            view.addView(rowLayout)
        }

        try { windowManager?.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
    }

    private fun removeBubble() {
        if (bubbleView != null) {
            try { windowManager?.removeView(bubbleView) } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "removeView: ${e.message}") }
            bubbleView = null; layoutParams = null
        }
        notifyToolbarHighlight()
    }

    private fun emitEvent(name: String, params: WritableMap) {
        try { reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(name, params) }
        catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "emitEvent($name): ${e.message}") }
    }

    private fun notifyToolbarHighlight() {
        try {
            FloatingToolbarModule.currentInstance?.setToolActive("invert_ink", bubbleView != null)
        } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "notifyToolbarHighlight: ${e.message}") }
    }

    override fun onCatalystInstanceDestroy() {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onCatalystInstanceDestroy — keeping palette bubble alive")
        super.onCatalystInstanceDestroy()
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}