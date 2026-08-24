package com.supernote_quicktoolbar.bubbles

import com.supernote_quicktoolbar.TouchInput
import com.supernote_quicktoolbar.FloatingPenGuard
import com.supernote_quicktoolbar.BuildConfig
import com.supernote_quicktoolbar.*
import com.supernote_quicktoolbar.overlays.*
import com.supernote_quicktoolbar.panels.*
import com.supernote_quicktoolbar.ui_common.ScreenScale

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlin.math.roundToInt

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

        @Volatile @JvmStatic private var stickyY: Int = 56
        // Remembers X across hide/reshow cycles: foreground switches recreate
        // the bubble, and snapping back to x=24 dragged the insert anchor to
        // the page's left margin (insert left=18 bug).
        @Volatile @JvmStatic private var stickyX: Int = 56

        @Volatile @JvmStatic private var pendingInitX: Int = -1
        @Volatile @JvmStatic private var pendingInitY: Int = -1

        @Volatile @JvmStatic var lastShownText: String = ""
        @Volatile @JvmStatic var lastShownMode: String = ""

        @Volatile @JvmStatic private var pendingState: Boolean = false

        @Volatile @JvmStatic
        private var currentInstance: FloatingBubbleModule? = null

        @JvmStatic fun reshowLast(ctx: ReactApplicationContext) {
            if (lastShownText.isEmpty()) return
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                if (FloatingToolbarModule.anyNativeOverlayOwnsScreen()) return@post
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

        @JvmStatic fun hideAndForget() {
            lastShownText = ""
            hideStatic()
        }

        @JvmStatic fun isTextReceiverShowing(): Boolean {
            if (bubbleView == null) return false
            val mode = lastShownMode
            return mode == "nospacing" || mode == "paragraph"
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
                    val vw = v.width.takeIf { it > 0 } ?: currentInstance?.bubbleSizePx() ?: 72
                    // Sticky values are visual overlay coordinates. Logical
                    // insertion X is independently normalized in TextInserter.
                    lp.x = lp.x.coerceIn(0, (screenWidth - vw).coerceAtLeast(0))
                    lp.y = clampY(lp.y, vh)
                    stickyX = lp.x
                    stickyY = lp.y
                    try { windowManager?.updateViewLayout(v, lp) } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
        }

        /** USER-DRAG visual limit; logical insertion X has its own matching guard. */
        @JvmStatic private fun maxBubbleX(bubbleW: Int): Int =
            (screenWidth * 3 / 5 - bubbleW).coerceAtLeast(0)

        @JvmStatic private fun clampY(desired: Int, viewH: Int): Int =
            BubbleWindowSupport.clampToBand(screenHeight, desired, viewH)

        // Programmatic placement is a visual projection only. The logical
        // insertion origin stays in TextInserter; this bubble sits above-left.
        private const val PROGRAMMATIC_GAP_X_DP = 12
        private const val PROGRAMMATIC_GAP_Y_DP = 12
        private const val BUBBLE_SIZE_DP = 48
    }

    private fun bubbleScaleFactor(): Float =
        BubbleWindowSupport.bubbleScale(reactApplicationContext)

    private fun bubbleSizePx(density: Float = reactApplicationContext.resources.displayMetrics.density): Int =
        (BUBBLE_SIZE_DP * density * bubbleScaleFactor()).roundToInt()

    @ReactMethod fun show(text: String, mode: String?) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[BUBBLE-DBG] show() called text='$text' mode=$mode bubbleExists=${bubbleView != null}")
        lastShownText = text
        lastShownMode = mode ?: ""
        handler.post {
            try {
                if (bubbleView != null) {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[BUBBLE-DBG] show: bubble already exists, skip create")
                    return@post
                }
                createBubble(text)
            } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "show: ${e.message}", e) }
        }
    }

    @ReactMethod fun showAt(text: String, pageX: Int, pageY: Int, mode: String?) {
        lastShownText = text
        lastShownMode = mode ?: ""
        handler.post {
            try {
                val dm = reactApplicationContext.resources.displayMetrics
                screenHeight = dm.heightPixels
                screenWidth = dm.widthPixels
                val insertionX = if (pageWidth > 0) (pageX.toFloat() * screenWidth / pageWidth).toInt() else pageX
                val insertionY = if (pageHeight > 0) (pageY.toFloat() * screenHeight / pageHeight).toInt() else pageY
                val bubbleSize = bubbleSizePx(dm.density)
                val gapX = (PROGRAMMATIC_GAP_X_DP * dm.density * bubbleScaleFactor()).roundToInt()
                val gapY = (PROGRAMMATIC_GAP_Y_DP * dm.density * bubbleScaleFactor()).roundToInt()
                pendingInitX = insertionX - bubbleSize - gapX
                pendingInitY = insertionY - bubbleSize - gapY
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG,
                    "showAt insertionPage=($pageX,$pageY) insertionScreen=($insertionX,$insertionY) " +
                        "visualScreen=($pendingInitX,$pendingInitY) gap=($gapX,$gapY)")

                if (bubbleView != null) {
                    updateBubblePosition(pendingInitX, pendingInitY)
                } else {
                    createBubble(text)
                }
            } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "showAt: ${e.message}", e) }
        }
    }

    /** Move an already-shown bubble to projected visual screen coordinates. */
    private fun updateBubblePosition(screenX: Int, screenY: Int) {
        val lp = layoutParams ?: return
        val v = bubbleView ?: return
        val bubbleSize = v.width.takeIf { it > 0 }
            ?: bubbleSizePx()

        // Programmatic visual placement: screen bounds + 6%–94% vertical band.
        lp.x = screenX.coerceIn(0, (screenWidth - bubbleSize).coerceAtLeast(0))
        lp.y = clampY(screenY, bubbleSize)
        stickyX = lp.x
        stickyY = lp.y

        try {
            windowManager?.updateViewLayout(v, lp)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "bubble moved to screen=(${lp.x},${lp.y})")
            emitBubbleCoords(v, lp, "onBubbleLayout")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "updateBubblePosition: ${e.message}", e)
        }
    }

    @ReactMethod fun hide() {
        lastShownText = ""
        lastShownMode = ""
        handler.post { try { removeBubble() } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "hide: ${e.message}", e) } }
    }

    @ReactMethod fun setPending(pending: Boolean) {
        pendingState = pending
        handler.post {
            (bubbleView?.getChildAt(0) as? PenNibBubbleView)?.pending = pending
        }
    }

    @ReactMethod fun setPageHeight(height: Int) { pageHeight = height }
    @ReactMethod fun setPageWidth(width: Int) { pageWidth = width }

    private fun createBubble(text: String) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[BUBBLE-DBG] createBubble enter text='$text'")
        val context = reactApplicationContext
        removeBubble()
        windowManager = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val dm = context.resources.displayMetrics
        screenHeight = dm.heightPixels
        screenWidth = dm.widthPixels
        val d = dm.density
        val scaledD = d * bubbleScaleFactor()
        val bubbleSize = bubbleSizePx(d)

        val iconView = PenNibBubbleView(context, scaledD)
        iconView.pending = pendingState
        iconView.layoutParams = LinearLayout.LayoutParams(bubbleSize, bubbleSize)

        bubbleView = TouchSinkLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(iconView)
        }

        layoutParams = BubbleWindowSupport.overlayParams(bubbleSize, bubbleSize).apply {
            gravity = Gravity.TOP or Gravity.START
            if (pendingInitX >= 0 && pendingInitY >= 0) {
                // Lasso anchor: screen bounds + vertical band, never the 3/5 drag limit.
                x = pendingInitX.coerceIn(0, (screenWidth - bubbleSize).coerceAtLeast(0))
                y = clampY(pendingInitY, bubbleSize)
                stickyX = x
                stickyY = y
            } else {
                // Reshow (e.g. returning to the note app): keep the last
                // position so the insert anchor doesn't jump to the margin.
                x = stickyX.coerceIn(0, (screenWidth - bubbleSize).coerceAtLeast(0))
                y = clampY(stickyY, bubbleSize)
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
                    if (!TouchInput.isFinger(ev)) return@setOnTouchListener true
                    val dx = ev.rawX - startRawX; val dy = ev.rawY - startRawY
                    if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        lp.x = (startX + dx.toInt()).coerceIn(0, maxBubbleX(view.width.takeIf { it > 0 } ?: 60))
                        lp.y = clampY(startY + dy.toInt(), view.height.takeIf { it > 0 } ?: 60)
                        try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        stickyX = lp.x
                        stickyY = lp.y
                        emitBubbleCoords(view, lp, "onBubbleDragEnd")
                    } else {
                        emitBubbleCoords(view, lp, "onBubbleTap")
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(bubbleView, layoutParams)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[BUBBLE-DBG] addView FAILED: ${e.message}", e)
            bubbleView = null; layoutParams = null
            return
        }
        bubbleView?.let { FloatingPenGuard.track("floating_bubble", it) }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[BUBBLE-DBG] bubble shown: '$text'")
        bubbleView?.post {
            val lp = layoutParams ?: return@post
            val v = bubbleView ?: return@post
            emitBubbleCoords(v, lp, "onBubbleLayout")
        }
    }

    private fun emitBubbleCoords(view: View, lp: WindowManager.LayoutParams, eventName: String) {
        // Events report the physical bubble only. Programmatic layout events
        // are observational; TextInserter already owns the logical origin.
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

    private fun emitEvent(name: String, params: WritableMap) =
        BubbleWindowSupport.emitEvent(reactApplicationContext, TAG, name, params)

    override fun onCatalystInstanceDestroy() {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onCatalystInstanceDestroy — keeping bubble alive")
        super.onCatalystInstanceDestroy()
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}

private class PenNibBubbleView(ctx: Context, private val density: Float) : View(ctx) {

    private val INK = Color.parseColor("#111111")

    var pending: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK; style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconPath = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = (w / 2f) - (1.5f * density)

        bgPaint.color = if (pending) INK else Color.WHITE
        iconPaint.color = if (pending) Color.WHITE else INK

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
