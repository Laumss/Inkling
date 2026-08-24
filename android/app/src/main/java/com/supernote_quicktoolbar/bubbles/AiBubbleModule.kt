package com.supernote_quicktoolbar.bubbles

import com.supernote_quicktoolbar.TouchInput
import com.supernote_quicktoolbar.FloatingPenGuard
import com.supernote_quicktoolbar.BuildConfig
import com.supernote_quicktoolbar.*
import com.supernote_quicktoolbar.overlays.*
import com.supernote_quicktoolbar.panels.*
import com.supernote_quicktoolbar.ui_common.ScreenScale
import com.supernote_quicktoolbar.ui_common.VectorAssets

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import kotlin.math.roundToInt

class AiBubbleModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "AiBubble"

    private val TAG = "AiBubble"
    private val handler = Handler(Looper.getMainLooper())

    private val CLR_INK       = Color.parseColor("#1E1E1B")
    private val CLR_INK_FAINT = Color.parseColor("#9A9A92")
    private val CLR_PANEL     = Color.parseColor("#F4F4F0")
    private val CLR_LINE      = Color.parseColor("#CBCBC4")
    private val CLR_SEL_FG    = Color.parseColor("#F4F4F0")

    private val MODE_AI    = "ai"
    private val MODE_VOICE = "voice"

    init { currentInstance = this }

    companion object {
        @Volatile @JvmStatic private var windowManager: WindowManager? = null
        @Volatile @JvmStatic private var bubbleView: View? = null
        @Volatile @JvmStatic private var statusText: TextView? = null
        @Volatile @JvmStatic private var iconView: ImageView? = null
        @Volatile @JvmStatic private var timeText: TextView? = null
        @Volatile @JvmStatic private var waveView: View? = null
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
        @Volatile @JvmStatic private var cachedInboxJson: String = "[]"
        @Volatile @JvmStatic private var inboxCol: LinearLayout? = null
        @Volatile @JvmStatic private var pendingLongPress: Runnable? = null
        private const val LONG_PRESS_MS = 600L

        @JvmStatic private fun clampY(desired: Int, viewH: Int): Int =
            BubbleWindowSupport.clampToBand(screenHeight, desired, viewH)

        /** Main pill width; capsules below are +28% (spec), both centered. */
        private const val BAR_WIDTH_DP = 420
        private const val CAPSULE_WIDTH_DP = 537   // 420 × 1.28
        private const val MAX_CAPSULES = 4

        @Volatile @JvmStatic var lastShownText: String = ""
        @Volatile @JvmStatic private var currentMode: String = "ai"

        /** Star-icon tap collapses the whole window into a small round ball
         *  (ScreenshotBubble style); tapping the ball expands it again.
         *  Survives hide/reshow cycles until the bubble is hidden for real. */
        @Volatile @JvmStatic private var collapsedBall = false

        @Volatile @JvmStatic
        private var currentInstance: AiBubbleModule? = null

        @JvmStatic fun reshowLast(ctx: ReactApplicationContext) {
            if (lastShownText.isEmpty()) return
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                if (FloatingToolbarModule.anyNativeOverlayOwnsScreen()) return@post
                try {
                    if (bubbleView != null) {
                        statusText?.text = lastShownText
                    } else {
                        val inst = currentInstance
                            ?: try { ctx.getNativeModule(AiBubbleModule::class.java) } catch (_: Exception) { null }
                        inst?.createBubble(lastShownText)
                    }
                } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w("AiBubble", "reshowLast: ${e.message}") }
            }
        }

        @JvmStatic fun hideStatic() {
            val h = Handler(Looper.getMainLooper())
            h.post {
                pendingLongPress?.let { h.removeCallbacks(it) }
                pendingLongPress = null
                if (bubbleView != null) {
                    try { windowManager?.removeView(bubbleView) } catch (_: Exception) {}
                    bubbleView = null; statusText = null; iconView = null
                    timeText = null; waveView = null; actionRow = null; inboxCol = null
                    layoutParams = null
                }
            }
        }

        @JvmStatic fun hideAndForget() {
            lastShownText = ""
            collapsedBall = false
            hideStatic()
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
                    lp.y = clampY(lp.y, vh)
                    stickyY = lp.y
                    try { windowManager?.updateViewLayout(v, lp) } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
        }
    }

    @ReactMethod fun show(text: String, mode: String) {
        lastShownText = text
        val m = if (mode == MODE_VOICE) MODE_VOICE else MODE_AI
        handler.post {
            try {
                if (bubbleView != null && currentMode == m && !collapsedBall) {
                    statusText?.text = text; return@post
                }
                currentMode = m
                collapsedBall = false
                createBubble(text)
            } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "show: ${e.message}", e) }
        }
    }

    /** Opens the AI entry as the single-button collected form without starting AIRelay. */
    @ReactMethod fun showCollapsed(text: String) {
        lastShownText = text
        handler.post {
            try {
                currentMode = MODE_AI
                collapsedBall = true
                createBubble(text)
            } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "showCollapsed: ${e.message}", e) }
        }
    }

    @ReactMethod fun hide() {
        lastShownText = ""
        collapsedBall = false
        handler.post { try { removeBubble() } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "hide: ${e.message}", e) } }
    }

    @ReactMethod fun updateText(text: String) {
        lastShownText = text
        handler.post { statusText?.text = text }
    }

    @ReactMethod fun setActionButtons(json: String) {
        cachedActionsJson = json
        handler.post { try { rebuildActionRow() } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "setActionButtons: ${e.message}", e) } }
    }

    /** Relay inbox previews (JSON [{id,title,preview,source,time}]) → capsule bars. */
    @ReactMethod fun setInboxItems(json: String) {
        cachedInboxJson = json
        handler.post { try { rebuildInboxCol() } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "setInboxItems: ${e.message}", e) } }
    }

    @ReactMethod fun setPageHeight(height: Int) { pageHeight = height }

    private val sf: Float by lazy { ScreenScale.factor(reactApplicationContext) }
    private fun dp(v: Int): Int = (v * reactApplicationContext.resources.displayMetrics.density * sf).roundToInt()
    private fun dp(v: Float): Int = (v * reactApplicationContext.resources.displayMetrics.density * sf).roundToInt()
    private fun sp(v: Float): Float = v * sf

    private fun createBubble(text: String) {
        val context = reactApplicationContext
        removeBubble()
        windowManager = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val dm = context.resources.displayMetrics
        screenHeight = dm.heightPixels
        screenWidth = dm.widthPixels

        if (collapsedBall) { createCollapsedBall(); return }

        val isVoice = currentMode == MODE_VOICE
        val bgColor = if (isVoice) CLR_INK else CLR_PANEL
        val fgColor = if (isVoice) CLR_SEL_FG else CLR_INK
        val fgFaint = if (isVoice) Color.parseColor("#8A8A82") else CLR_INK_FAINT
        val borderColor = CLR_INK
        val borderW = if (isVoice) 0 else dp(2)
        val cornerR = dp(16).toFloat()

        val outerWrapper = FrameLayout(context)

        val mainBarAndActions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val capsule = TouchSinkLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(bgColor)
                if (borderW > 0) setStroke(borderW, borderColor)
                this.cornerRadius = cornerR
            }
            // The window is capsule-column wide; the main pill stays at its
            // classic width, centered.
            layoutParams = LinearLayout.LayoutParams(
                dp(BAR_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

        val iconSz = dp(28)
        iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSz, iconSz).apply {
                rightMargin = dp(14)
            }
            val assetName = if (isVoice) "icons/icon_sound.xml" else "icons/icon_ai_spark.xml"
            val bmp = VectorAssets.loadBitmapTinted(context, assetName, iconSz, fgColor)
            if (bmp != null) setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        capsule.addView(iconView)

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusText = TextView(context).apply {
            this.text = text; textSize = sp(17f); setTextColor(fgColor)
            typeface = Typeface.DEFAULT_BOLD; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            letterSpacing = 0.02f
        }
        textCol.addView(statusText)
        capsule.addView(textCol)

        val waveSz = dp(22)
        waveView = WaveformView(context, fgColor, waveSz).apply {
            layoutParams = LinearLayout.LayoutParams(dp(80), waveSz).apply {
                leftMargin = dp(12); rightMargin = dp(12)
            }
        }
        capsule.addView(waveView)

        timeText = TextView(context).apply {
            this.text = ""; textSize = sp(17f); setTextColor(fgColor)
            typeface = Typeface.DEFAULT_BOLD
            setFontFeatureSettings("tnum")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(14) }
        }
        capsule.addView(timeText)

        val gripW = dp(18); val gripH = dp(26)
        val gripView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(gripW, gripH)
            val bmp = VectorAssets.loadBitmapTinted(context, "icons/icon_grip_h.xml", gripW, fgFaint)
            if (bmp != null) setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.55f
        }
        capsule.addView(gripView)

        mainBarAndActions.addView(capsule)

        // Relay inbox capsules — "flash idea" bars directly under the status
        // pill (above the action buttons, which stay at the very bottom).
        inboxCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            visibility = View.GONE
        }
        mainBarAndActions.addView(inboxCol)
        rebuildInboxCol()

        actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            visibility = View.GONE
        }
        mainBarAndActions.addView(actionRow)
        rebuildActionRow()

        outerWrapper.addView(mainBarAndActions)
        bubbleView = outerWrapper

        val barW = dp(CAPSULE_WIDTH_DP)
        layoutParams = BubbleWindowSupport.overlayParams(
            barW, WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0; y = clampY(stickyY, 60)
        }

        // Content height changes (inbox capsules / action row) or the first
        // measure can push the window bottom past the 94% band — re-clamp
        // after every layout pass that changes the height.
        outerWrapper.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val lp = layoutParams ?: return@addOnLayoutChangeListener
            val ny = clampY(lp.y, bottom - top)
            if (ny != lp.y) {
                lp.y = ny; stickyY = ny
                try { windowManager?.updateViewLayout(v, lp) } catch (_: Exception) {}
            }
        }

        val longPressR = Runnable {
            if (!isDragging && bubbleView != null) {
                longPressFired = true
                emitEvent("onAiBubbleLongPress", Arguments.createMap())
            }
        }
        pendingLongPress = longPressR
        capsule.setOnTouchListener { _, ev ->
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
                    if (!TouchInput.isFinger(ev)) return@setOnTouchListener true
                    val dx = ev.rawX - startRawX; val dy = ev.rawY - startRawY
                    if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        isDragging = true; handler.removeCallbacks(longPressR)
                    }
                    if (isDragging) {
                        lp.y = clampY(startY + dy.toInt(), view.height.takeIf { it > 0 } ?: 60)
                        try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {}
                    }; true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressR)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressR)
                    if (longPressFired) {  }
                    else if (isDragging) {
                        stickyY = lp.y
                        val sy = lp.y.toFloat()
                        val r = pageHeight.toFloat() / screenHeight.toFloat()
                        emitEvent("onAiBubbleDragEnd", Arguments.createMap().apply {
                            putDouble("screenY", sy.toDouble())
                            putInt("pageY", (sy * r).toInt())
                        })
                    } else if (ev.x <= (iconView?.right ?: 0) + dp(10)) {
                        // Tap on the star/spark icon: collapse the whole
                        // window into a small round ball.
                        collapsedBall = true
                        createBubble(lastShownText)
                    } else if (ev.x >= gripView.left - dp(10)) {
                        Unit
                    } else {
                        emitEvent("onAiBubbleTap", Arguments.createMap())
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(outerWrapper, layoutParams)
        FloatingPenGuard.track("ai_bubble", outerWrapper)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "AI bubble shown: mode=$currentMode text='$text'")
    }

    /**
     * Collapsed form: a small round ball (ScreenshotBubble style — white
     * circle, ink border) with the AI spark icon centered. Tap → expand back
     * to the full pill; vertical drag repositions within the 6%–94% band.
     */
    private fun createCollapsedBall() {
        val context = reactApplicationContext
        val borderW = dp(2)
        val ballIconSz = dp(32)
        val pad = dp(10)
        val total = ballIconSz + pad * 2 + borderW * 2

        val ball = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(CLR_PANEL)
                setStroke(borderW, CLR_INK)
                cornerRadius = total / 2f
            }
            val assetName = if (currentMode == MODE_VOICE) "icons/icon_sound.xml" else "icons/icon_ai_spark.xml"
            val bmp = VectorAssets.loadBitmapTinted(context, assetName, ballIconSz, CLR_INK)
            addView(ImageView(context).apply {
                if (bmp != null) setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(ballIconSz, ballIconSz, Gravity.CENTER)
            })
        }
        bubbleView = ball

        layoutParams = BubbleWindowSupport.overlayParams(total, total).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(20)
            y = clampY(stickyY, total)
        }

        val collapsedLongPress = Runnable {
            if (!isDragging && collapsedBall && bubbleView === ball) {
                longPressFired = true
                emitEvent("onAiBubbleLongPress", Arguments.createMap())
            }
        }
        pendingLongPress = collapsedLongPress

        ball.setOnTouchListener { _, ev ->
            val lp = layoutParams ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = lp.y
                    startRawY = ev.rawY
                    isDragging = false
                    longPressFired = false
                    handler.removeCallbacks(collapsedLongPress)
                    handler.postDelayed(collapsedLongPress, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!TouchInput.isFinger(ev)) return@setOnTouchListener true
                    val dy = ev.rawY - startRawY
                    if (!isDragging && Math.abs(dy) > 10) {
                        isDragging = true
                        handler.removeCallbacks(collapsedLongPress)
                    }
                    if (isDragging) {
                        lp.y = clampY(startY + dy.toInt(), total)
                        try { windowManager?.updateViewLayout(ball, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(collapsedLongPress)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(collapsedLongPress)
                    when {
                        longPressFired -> Unit
                        isDragging -> { stickyY = lp.y }
                        else -> {
                            // Short tap: ask JS to start AIRelay, then it calls expand().
                            emitEvent("onAiBubbleExpand", Arguments.createMap())
                        }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(ball, layoutParams)
        FloatingPenGuard.track("ai_bubble", ball)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "AI bubble collapsed to ball")
    }

    private fun rebuildActionRow() {
        val row = actionRow ?: return
        row.removeAllViews()
        if (currentMode == MODE_VOICE) { row.visibility = View.GONE; return }
        try {
            val arr = JSONArray(cachedActionsJson)
            if (arr.length() == 0) { row.visibility = View.GONE; tryUpdateLayout(); return }
            row.visibility = View.VISIBLE

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val actionId = obj.getString("id")
                val icon = obj.optString("icon", "?")
                val label = obj.optString("label", actionId)
                if (i > 0) {
                    row.addView(View(reactApplicationContext).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(8), 1)
                    })
                }
                val btnH = dp(38)
                val btn = TextView(reactApplicationContext).apply {
                    text = icon; textSize = sp(14f); setTextColor(CLR_INK)
                    typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                    minWidth = dp(48); minHeight = btnH
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    background = GradientDrawable().apply {
                        setColor(CLR_PANEL)
                        setStroke(dp(1.5f), CLR_INK)
                        cornerRadius = dp(10).toFloat()
                    }
                    contentDescription = label
                    setOnClickListener {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "AI bubble action: $actionId")
                        emitEvent("onAiBubbleAction", Arguments.createMap().apply {
                            putString("actionId", actionId)
                        })
                    }
                }
                row.addView(btn)
            }
            tryUpdateLayout()
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "rebuildActionRow: ${e.message}")
            row.visibility = View.GONE
        }
    }

    /** Rebuilds the "flash idea" capsule bars from cachedInboxJson. */
    private fun rebuildInboxCol() {
        val col = inboxCol ?: return
        col.removeAllViews()
        try {
            val arr = JSONArray(cachedInboxJson)
            if (arr.length() == 0) { col.visibility = View.GONE; tryUpdateLayout(); return }
            col.visibility = View.VISIBLE

            val count = minOf(arr.length(), MAX_CAPSULES)
            for (i in 0 until count) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.optString("title", "")
                val preview = obj.optString("preview", "")
                // Pure text: title line + body, clamped to three lines so all
                // four capsules share the same height.
                val body = if (preview.startsWith(title) || title.isEmpty()) preview
                           else title + "\n" + preview

                val bar = TextView(reactApplicationContext).apply {
                    text = body
                    textSize = sp(14f)
                    setTextColor(CLR_INK)
                    setLineSpacing(0f, 1.15f)
                    minLines = 3; maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                    background = GradientDrawable().apply {
                        setColor(CLR_PANEL)
                        setStroke(dp(2), CLR_INK)
                        cornerRadius = dp(16).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = if (i == 0) 0 else dp(8) }
                    setOnClickListener {
                        emitEvent("onAiCapsuleTap", Arguments.createMap().apply { putString("id", id) })
                    }
                    setOnLongClickListener {
                        emitEvent("onAiCapsuleLongPress", Arguments.createMap().apply { putString("id", id) })
                        true
                    }
                }
                col.addView(bar)
            }
            tryUpdateLayout()
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "rebuildInboxCol: ${e.message}")
            col.visibility = View.GONE
        }
    }

    private fun tryUpdateLayout() {
        try { if (bubbleView != null && layoutParams != null)
            windowManager?.updateViewLayout(bubbleView, layoutParams) } catch (_: Exception) {}
    }

    private fun removeBubble() {
        pendingLongPress?.let { handler.removeCallbacks(it) }
        pendingLongPress = null
        if (bubbleView != null) {
            try { windowManager?.removeView(bubbleView) } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "removeView: ${e.message}") }
            bubbleView = null; statusText = null; iconView = null
            timeText = null; waveView = null; actionRow = null; inboxCol = null
            layoutParams = null
        }
    }

    private fun emitEvent(name: String, params: WritableMap) =
        BubbleWindowSupport.emitEvent(reactApplicationContext, TAG, name, params)

    override fun onCatalystInstanceDestroy() {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onCatalystInstanceDestroy — keeping AI bubble alive")
        super.onCatalystInstanceDestroy()
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}

    private class WaveformView(
        context: android.content.Context,
        private val barColor: Int,
        private val viewHeight: Int
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = barColor; style = Paint.Style.FILL
        }
        private val barCount = 16
        private val barWidth = 3f
        private val barGap = 3.2f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = height.toFloat()
            val d = resources.displayMetrics.density
            val bw = barWidth * d
            val bg = barGap * d
            val totalW = barCount * (bw + bg) - bg
            var x = (width - totalW) / 2f

            for (i in 0 until barCount) {
                val r = (Math.sin(i * 1.7) * 0.5 + Math.sin(i * 0.6) * 0.5 + 1.0).toFloat() / 2f
                val bh = Math.max(3f * d, r * h)
                val y = (h - bh) / 2f
                canvas.drawRoundRect(x, y, x + bw, y + bh, bw / 2, bw / 2, paint)
                x += bw + bg
            }
        }
    }
}
