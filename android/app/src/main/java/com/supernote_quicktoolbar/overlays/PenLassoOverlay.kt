package com.supernote_quicktoolbar.overlays
import com.supernote_quicktoolbar.BuildConfig
import com.supernote_quicktoolbar.*
import com.supernote_quicktoolbar.panels.*
import com.supernote_quicktoolbar.bubbles.*

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.ui_common.ScreenScale

class PenLassoOverlay(private val context: ReactApplicationContext) {

    companion object {
        private const val TAG = "PenLassoOverlay"
        private const val DOT_RADIUS_BASE = 3.2f
        private const val DOT_SPACING_BASE = 10f
        private const val STROKE_COLOR = 0xE6000000.toInt()
        private const val TIMEOUT_MS = 30_000L
        private const val FULL_AUTO_RELEASE_DELAY_MS = 140L
        private const val ADJUST_FULL_AUTO_RELEASE_DELAY_MS = 300L
        private const val MIN_POINT_DIST_SQ = 9f
        private const val PEN_GUARD_OWNER = "pen-lasso"

        private const val FRAME_STROKE_DP = 2f
        private const val DASH_ON_DP = 16
        private const val DASH_OFF_DP = 10
        private const val CORNER_ARM_DP = 28
        private const val CORNER_WIDTH_DP = 10
        private const val CORNER_STROKE_DP = 2f
        private const val CORNER_OUTSET_DP = 4
        private const val MID_CAP_LONG_DP = 28
        private const val MID_CAP_SHORT_DP = 10
        private const val MID_CAP_STROKE_DP = 2f
        private const val MOVE_HANDLE_DP = 34
        private const val EDGE_HIT_ZONE_DP = 40
        private const val MIN_BOX_DP = 50
        private const val TAP_SLOP_DP = 12

        private const val ACTION_BTN_DIAMETER_DP = 60
        private const val ACTION_BTN_GAP_DP = 12
        private const val ACTION_BAR_FRAME_GAP_DP = 18
        private const val ACTION_BAR_TOP_OFFSET_DP = 8
        private const val ACTION_BAR_SCREEN_MARGIN_DP = 12
        private const val ACTION_ICON_STROKE_DP = 2f

        private val REARM_DELAYS_MS = longArrayOf(400L, 900L, 1700L)
    }

    enum class Action {
        CONFIRM,
        SEND_TO_DEVICES,
        ADD_TO_DOC_SCREENSHOTS,
        INSERT_CURRENT_PAGE,
        APPEND_PAGE_WITH_LINK,
        CANCEL,
    }

    private val dotRadius: Float get() = DOT_RADIUS_BASE * ScreenScale.factor(context)
    private val dotSpacing: Float get() = DOT_SPACING_BASE * ScreenScale.factor(context)
    private fun dp(v: Int) = ScreenScale.dp(context, v)
    private fun dp(v: Float) = ScreenScale.dp(context, v)

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var lassoPathView: LassoPathView? = null
    private var actionBar: LinearLayout? = null
    private var actions: List<Action> = emptyList()
    private var onAction: ((action: Action, left: Int, top: Int, right: Int, bottom: Int) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var fullAutoEnabled = false
    private val releaseFullAutoRunnable = Runnable { setFullUiAuto(false, "release-delay") }

    private val points = mutableListOf<PointF>()

    /** InkHub ScrollbarRecyclerContainer uses this mode while content is moving. */
    private fun setFullUiAuto(enable: Boolean, reason: String) {
        handler.removeCallbacks(releaseFullAutoRunnable)
        if (fullAutoEnabled == enable) return
        try {
            val eink = context.getSystemService("eink") ?: return
            val methods = eink.javaClass.methods
            val twoArg = methods.firstOrNull {
                it.name == "enableFullUiAuto" && it.parameterTypes.contentEquals(
                    arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                )
            }
            if (twoArg != null) {
                twoArg.invoke(eink, enable, true)
            } else {
                val oneArg = methods.firstOrNull {
                    it.name == "enableFullUiAuto" && it.parameterTypes.contentEquals(
                        arrayOf(Boolean::class.javaPrimitiveType)
                    )
                } ?: return
                oneArg.invoke(eink, enable)
            }
            fullAutoEnabled = enable
            if (BuildConfig.ENABLE_DEBUG) android.util.Log.i(TAG, "fullUiAuto=$enable reason=$reason")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) android.util.Log.w(TAG, "fullUiAuto call exception reason=$reason: ${e.message}")
        }
    }

    private fun scheduleFullUiAutoRelease(
        reason: String,
        delayMs: Long = FULL_AUTO_RELEASE_DELAY_MS
    ) {
        handler.removeCallbacks(releaseFullAutoRunnable)
        handler.postDelayed(releaseFullAutoRunnable, delayMs)
        if (BuildConfig.ENABLE_DEBUG) {
            android.util.Log.i(TAG, "schedule fullUiAuto release reason=$reason delayMs=$delayMs")
        }
    }

    fun show(
        actions: List<Action>,
        onAction: (action: Action, left: Int, top: Int, right: Int, bottom: Int) -> Unit,
        onCancel: () -> Unit
    ) {
        android.util.Log.i(TAG, "show() called actions=$actions")
        this.actions = actions
        this.onAction = onAction
        this.onCancel = onCancel

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val root = object : FrameLayout(context) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                super.dispatchTouchEvent(ev)
                return true
            }
            override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
                if (ev.isFromSource(InputDevice.SOURCE_STYLUS)) return true
                return super.dispatchGenericMotionEvent(ev)
            }
        }.apply {
            setBackgroundColor(0x0A000000)
        }

        val lasso = LassoPathView(context)
        lassoPathView = lasso
        root.addView(lasso, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        buildActionBar(root)

        @Suppress("DEPRECATION")
        val wmType = WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            wmType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        rootView = root
        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) android.util.Log.e(TAG, "addView failed: ${e.message}", e)
            fireCancel("addView-failed")
            return
        }

        resetTimeout()
        if (BuildConfig.ENABLE_DEBUG) android.util.Log.i(TAG, "overlay shown, waiting for pen stroke...")

        armDrawPath("show-initial")
        scheduleRearm(Stage.DRAW)
    }

    fun dismiss() {
        if (BuildConfig.ENABLE_DEBUG) android.util.Log.i(TAG, "dismiss() called (external)")
        handler.post { fireCancel("dismiss()") }
    }

    fun dismissSilently() {
        if (BuildConfig.ENABLE_DEBUG) android.util.Log.i(TAG, "dismissSilently() called")
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup()
        else handler.post { cleanup() }
    }

    private fun resetTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        val firesAt = System.currentTimeMillis() + TIMEOUT_MS
        timeoutRunnable = Runnable {
            if (BuildConfig.ENABLE_DEBUG) android.util.Log.w(TAG, "30s idle timeout fired")
            fireCancel("timeout")
        }
        if (BuildConfig.ENABLE_DEBUG) android.util.Log.i(TAG, "resetTimeout: will fire at epoch=$firesAt")
        handler.postDelayed(timeoutRunnable!!, TIMEOUT_MS)
    }

    private fun armDrawPath(reason: String) {
        if (BuildConfig.ENABLE_DEBUG) android.util.Log.i(TAG, "armDrawPath reason=$reason")
        FloatingPenGuard.setFullScreenActive(true, PEN_GUARD_OWNER)
        FloatingPenGuard.reassert("pen lasso arm: $reason")
    }

    private fun muteDrawPath(reason: String) {
        if (BuildConfig.ENABLE_DEBUG) android.util.Log.i(TAG, "muteDrawPath reason=$reason")
        FloatingPenGuard.setFullScreenActive(true, PEN_GUARD_OWNER)
        FloatingPenGuard.reassert("pen lasso mute: $reason")
    }

    private fun scheduleRearm(expectStage: Stage) {
        for (d in REARM_DELAYS_MS) {
            handler.postDelayed({
                if (rootView != null && stage == expectStage) armDrawPath("rearm+${d}ms")
            }, d)
        }
    }

    private enum class Stage { DRAW, ADJUST }
    private var stage = Stage.DRAW

    private enum class DragMode {
        MOVE, TOP, BOTTOM, LEFT, RIGHT,
        TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
        TAP_INSIDE, TAP_OUTSIDE
    }

    private inner class LassoPathView(ctx: Context) : View(ctx) {

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = STROKE_COLOR
            style = Paint.Style.FILL
        }
        private var dotBitmap: Bitmap? = null
        private var dotCanvas: Canvas? = null
        private val lastDotInput = PointF()
        private var hasLastDotInput = false
        private var distanceSinceDot = 0f

        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE
            strokeWidth = dp(FRAME_STROKE_DP).toFloat()
            pathEffect = DashPathEffect(floatArrayOf(dp(DASH_ON_DP).toFloat(), dp(DASH_OFF_DP).toFloat()), 0f)
        }
        private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE
            strokeWidth = dp(CORNER_STROKE_DP).toFloat()
            strokeJoin = Paint.Join.MITER
        }
        private val midCapStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE
            strokeWidth = dp(MID_CAP_STROKE_DP).toFloat()
        }

        private val box = RectF()
        private var dragMode: DragMode? = null
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var dragStartBox = RectF()
        private var dragMovedSq = 0f
        private var currentGestureIsStylus = false

        private val edgeHitZone get() = dp(EDGE_HIT_ZONE_DP).toFloat()
        private val minBox get() = dp(MIN_BOX_DP).toFloat()
        private val moveHandle get() = dp(MOVE_HANDLE_DP).toFloat()
        private val tapSlopSq get() = dp(TAP_SLOP_DP).toFloat().let { it * it }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            dotBitmap?.recycle()
            dotBitmap = if (w > 0 && h > 0) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) else null
            dotCanvas = dotBitmap?.let(::Canvas)
            resetDottedLasso()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            return when (stage) {
                Stage.DRAW -> onTouchDraw(event)
                Stage.ADJUST -> onTouchAdjust(event)
            }
        }

        private fun isStylus(event: MotionEvent): Boolean =
            event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                event.isFromSource(InputDevice.SOURCE_STYLUS)

        private fun onTouchDraw(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN && BuildConfig.ENABLE_DEBUG) {
                android.util.Log.i(TAG, "DRAW DOWN toolType=${event.getToolType(0)} " +
                    "source=0x${Integer.toHexString(event.source)} device=${event.deviceId} accepted=${isStylus(event)}")
            }
            if (!isStylus(event)) return false
            val x = event.x; val y = event.y
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    setFullUiAuto(true, "draw-down")
                    points.clear()
                    points.add(PointF(x, y))
                    beginDottedLasso(x, y)
                    resetTimeout()
                }
                MotionEvent.ACTION_MOVE -> {
                    val last = points.lastOrNull() ?: return true
                    val dx = x - last.x; val dy = y - last.y
                    if (dx * dx + dy * dy < MIN_POINT_DIST_SQ) return true
                    points.add(PointF(x, y))
                    appendDottedLasso(x, y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scheduleFullUiAutoRelease(if (event.action == MotionEvent.ACTION_UP) "draw-up" else "draw-cancel")
                    if (points.size >= 2) enterAdjust()
                    else { points.clear(); resetDottedLasso(); invalidate() }
                }
            }
            return true
        }

        private fun enterAdjust() {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (p in points) {
                if (p.x < minX) minX = p.x; if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x; if (p.y > maxY) maxY = p.y
            }
            if (maxX - minX < 2f || maxY - minY < 2f) { fireCancel("degenerate-box"); return }
            if (maxX - minX < minBox) { val c = (minX + maxX) / 2f; minX = c - minBox / 2f; maxX = c + minBox / 2f }
            if (maxY - minY < minBox) { val c = (minY + maxY) / 2f; minY = c - minBox / 2f; maxY = c + minBox / 2f }
            box.set(minX, minY, maxX, maxY); clampBoxToScreen()
            stage = Stage.ADJUST
            points.clear(); resetDottedLasso()
            muteDrawPath("enterAdjust"); resetTimeout(); invalidate()
            showActionButtons()
            if (BuildConfig.ENABLE_DEBUG) android.util.Log.i(TAG, "enter ADJUST box=$box")
        }

        private fun backToDraw() {
            stage = Stage.DRAW
            points.clear(); resetDottedLasso()
            hideActionButtons()
            armDrawPath("backToDraw"); resetTimeout(); invalidate()
            scheduleRearm(Stage.DRAW)
            if (BuildConfig.ENABLE_DEBUG) android.util.Log.i(TAG, "back to DRAW")
        }

        private fun onTouchAdjust(event: MotionEvent): Boolean {
            val x = event.x; val y = event.y
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentGestureIsStylus = isStylus(event)
                    dragMode = detectDragMode(x, y)
                    dragStartX = x; dragStartY = y
                    dragStartBox.set(box); dragMovedSq = 0f
                    resetTimeout()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val mode = dragMode ?: return true
                    setFullUiAuto(true, "adjust-move")
                    val dx = x - dragStartX; val dy = y - dragStartY
                    dragMovedSq = maxOf(dragMovedSq, dx * dx + dy * dy)
                    val o = dragStartBox
                    var nl = o.left; var nt = o.top; var nr = o.right; var nb = o.bottom
                    when (mode) {
                        DragMode.MOVE -> { nl += dx; nt += dy; nr += dx; nb += dy }
                        DragMode.TOP -> nt += dy
                        DragMode.BOTTOM -> nb += dy
                        DragMode.LEFT -> nl += dx
                        DragMode.RIGHT -> nr += dx
                        DragMode.TOP_RIGHT -> { nr += dx; nt += dy }
                        DragMode.BOTTOM_LEFT -> { nl += dx; nb += dy }
                        DragMode.BOTTOM_RIGHT -> { nr += dx; nb += dy }
                        DragMode.TAP_INSIDE, DragMode.TAP_OUTSIDE -> return true
                    }
                    applyBox(nl, nt, nr, nb, mode == DragMode.MOVE)
                    positionActionBar()
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val mode = dragMode; dragMode = null
                    scheduleFullUiAutoRelease(
                        if (event.action == MotionEvent.ACTION_UP) "adjust-up" else "adjust-cancel",
                        ADJUST_FULL_AUTO_RELEASE_DELAY_MS
                    )
                    if (event.action == MotionEvent.ACTION_UP && dragMovedSq < tapSlopSq) {
                        when (mode) {
                            DragMode.TAP_OUTSIDE -> {
                                if (currentGestureIsStylus) fireCancel("tap-outside-pen") else backToDraw()
                            }
                            DragMode.TAP_INSIDE -> {}
                            else -> {}
                        }
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun detectDragMode(x: Float, y: Float): DragMode {
            if (Math.abs(x - box.left) < edgeHitZone && Math.abs(y - box.top) < edgeHitZone) return DragMode.MOVE
            val nearL = Math.abs(x - box.left) < edgeHitZone
            val nearR = Math.abs(x - box.right) < edgeHitZone
            val nearT = Math.abs(y - box.top) < edgeHitZone
            val nearB = Math.abs(y - box.bottom) < edgeHitZone
            val insideX = x in box.left..box.right
            val insideY = y in box.top..box.bottom
            if (nearT && nearR) return DragMode.TOP_RIGHT
            if (nearB && nearL) return DragMode.BOTTOM_LEFT
            if (nearB && nearR) return DragMode.BOTTOM_RIGHT
            if (nearT && (insideX || nearL || nearR)) return DragMode.TOP
            if (nearB && (insideX || nearL || nearR)) return DragMode.BOTTOM
            if (nearL && (insideY || nearT || nearB)) return DragMode.LEFT
            if (nearR && (insideY || nearT || nearB)) return DragMode.RIGHT
            if (insideX && insideY) return DragMode.TAP_INSIDE
            return DragMode.TAP_OUTSIDE
        }

        private fun applyBox(l: Float, t: Float, r: Float, b: Float, isMove: Boolean) {
            val w = width.toFloat(); val h = height.toFloat()
            if (isMove) {
                var nl = l; var nt = t
                val bw = r - l; val bh = b - t
                nl = nl.coerceIn(0f, w - bw); nt = nt.coerceIn(0f, h - bh)
                box.set(nl, nt, nl + bw, nt + bh)
            } else {
                val nl = minOf(l, r - minBox).coerceAtLeast(0f)
                val nt = minOf(t, b - minBox).coerceAtLeast(0f)
                val nr = maxOf(r, nl + minBox).coerceAtMost(w)
                val nb = maxOf(b, nt + minBox).coerceAtMost(h)
                box.set(nl, nt, nr, nb)
            }
        }

        private fun clampBoxToScreen() {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            box.left = box.left.coerceIn(0f, w - minBox)
            box.top = box.top.coerceIn(0f, h - minBox)
            box.right = box.right.coerceIn(box.left + minBox, w)
            box.bottom = box.bottom.coerceIn(box.top + minBox, h)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            when (stage) {
                Stage.DRAW -> dotBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                Stage.ADJUST -> drawViewfinder(canvas)
            }
        }

        private fun beginDottedLasso(x: Float, y: Float) {
            resetDottedLasso()
            dotCanvas?.drawCircle(x, y, dotRadius, dotPaint)
            lastDotInput.set(x, y)
            hasLastDotInput = true
            invalidateDot(x, y)
        }

        private fun appendDottedLasso(x: Float, y: Float) {
            if (!hasLastDotInput) {
                beginDottedLasso(x, y)
                return
            }
            val startX = lastDotInput.x
            val startY = lastDotInput.y
            val dx = x - startX
            val dy = y - startY
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            if (length <= 0f) return

            var offset = dotSpacing - distanceSinceDot
            var lastEmittedOffset = -1f
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            while (offset <= length) {
                val ratio = offset / length
                val px = startX + dx * ratio
                val py = startY + dy * ratio
                dotCanvas?.drawCircle(px, py, dotRadius, dotPaint)
                minX = minOf(minX, px); minY = minOf(minY, py)
                maxX = maxOf(maxX, px); maxY = maxOf(maxY, py)
                lastEmittedOffset = offset
                offset += dotSpacing
            }
            distanceSinceDot = if (lastEmittedOffset >= 0f) {
                length - lastEmittedOffset
            } else {
                distanceSinceDot + length
            }
            if (distanceSinceDot >= dotSpacing) distanceSinceDot %= dotSpacing
            lastDotInput.set(x, y)
            if (lastEmittedOffset >= 0f) invalidateDots(minX, minY, maxX, maxY)
        }

        private fun resetDottedLasso() {
            dotBitmap?.eraseColor(Color.TRANSPARENT)
            hasLastDotInput = false
            distanceSinceDot = 0f
        }

        private fun invalidateDot(x: Float, y: Float) = invalidateDots(x, y, x, y)

        private fun invalidateDots(minX: Float, minY: Float, maxX: Float, maxY: Float) {
            val pad = kotlin.math.ceil(dotRadius + 2f).toInt()
            invalidate(
                kotlin.math.floor(minX).toInt() - pad,
                kotlin.math.floor(minY).toInt() - pad,
                kotlin.math.ceil(maxX).toInt() + pad,
                kotlin.math.ceil(maxY).toInt() + pad
            )
        }

        fun release() {
            dotCanvas = null
            dotBitmap?.recycle()
            dotBitmap = null
        }

        private fun drawViewfinder(canvas: Canvas) {
            canvas.drawRect(box, framePaint)

            val mh = moveHandle
            val hl = box.left - mh / 2f
            val ht = box.top - mh / 2f
            val cx = hl + mh / 2f; val cy = ht + mh / 2f
            canvas.drawRect(hl, ht, hl + mh, ht + mh, handleFillPaint)
            canvas.drawRect(hl, ht, hl + mh, ht + mh, handleStrokePaint)
            val arm = mh * 0.30f
            val ah = mh * 0.14f
            for (dx in intArrayOf(-1, 1)) for (dy in intArrayOf(-1, 1)) {
                val ex = cx + dx * arm; val ey = cy + dy * arm
                canvas.drawLine(cx, cy, ex, ey, handleStrokePaint)
                canvas.drawLine(ex, ey, ex - dx * ah, ey, handleStrokePaint)
                canvas.drawLine(ex, ey, ex, ey - dy * ah, handleStrokePaint)
            }

            drawCorner(canvas, box.right, box.top, 1, -1)
            drawCorner(canvas, box.left, box.bottom, -1, 1)
            drawCorner(canvas, box.right, box.bottom, 1, 1)

            val midX = box.centerX(); val midY = box.centerY()
            drawMidCap(canvas, midX, box.top, horizontal = true)
            drawMidCap(canvas, midX, box.bottom, horizontal = true)
            drawMidCap(canvas, box.left, midY, horizontal = false)
            drawMidCap(canvas, box.right, midY, horizontal = false)
        }

        private fun drawCorner(canvas: Canvas, cx: Float, cy: Float, dx: Int, dy: Int) {
            val o = dp(CORNER_OUTSET_DP).toFloat()
            val arm = dp(CORNER_ARM_DP).toFloat()
            val cw = dp(CORNER_WIDTH_DP).toFloat()
            val ox = cx + dx * o; val oy = cy + dy * o
            val p = Path().apply {
                moveTo(ox, oy)
                lineTo(ox - dx * arm, oy); lineTo(ox - dx * arm, oy - dy * cw)
                lineTo(ox - dx * cw, oy - dy * cw); lineTo(ox - dx * cw, oy - dy * arm)
                lineTo(ox, oy - dy * arm); close()
            }
            canvas.drawPath(p, handleFillPaint)
            canvas.drawPath(p, handleStrokePaint)
        }

        private fun drawMidCap(canvas: Canvas, cx: Float, cy: Float, horizontal: Boolean) {
            val w = if (horizontal) dp(MID_CAP_LONG_DP).toFloat() else dp(MID_CAP_SHORT_DP).toFloat()
            val h = if (horizontal) dp(MID_CAP_SHORT_DP).toFloat() else dp(MID_CAP_LONG_DP).toFloat()
            val r = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            canvas.drawRect(r, handleFillPaint)
            canvas.drawRect(r, midCapStrokePaint)
        }

        fun currentBox(): RectF = RectF(box)
    }

    private inner class ActionIconButton(ctx: Context, private val action: Action) : View(ctx) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = dp(ACTION_ICON_STROKE_DP).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        init {
            isClickable = true
            isFocusable = true
            contentDescription = action.name
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(width, height) / 2f - strokePaint.strokeWidth
            canvas.drawCircle(cx, cy, r, fillPaint)
            canvas.drawCircle(cx, cy, r, strokePaint)
            when (action) {
                Action.CONFIRM -> {
                    canvas.drawLine(cx - r * 0.42f, cy, cx - r * 0.08f, cy + r * 0.32f, strokePaint)
                    canvas.drawLine(cx - r * 0.08f, cy + r * 0.32f, cx + r * 0.45f, cy - r * 0.34f, strokePaint)
                }
                Action.CANCEL -> {
                    canvas.drawLine(cx - r * 0.34f, cy - r * 0.34f, cx + r * 0.34f, cy + r * 0.34f, strokePaint)
                    canvas.drawLine(cx - r * 0.34f, cy + r * 0.34f, cx + r * 0.34f, cy - r * 0.34f, strokePaint)
                }
                Action.SEND_TO_DEVICES -> {
                    val device = RectF(cx - r * 0.48f, cy - r * 0.38f, cx - r * 0.02f, cy + r * 0.38f)
                    canvas.drawRoundRect(device, r * 0.08f, r * 0.08f, strokePaint)
                    canvas.drawLine(cx - r * 0.02f, cy, cx + r * 0.48f, cy, strokePaint)
                    canvas.drawLine(cx + r * 0.48f, cy, cx + r * 0.24f, cy - r * 0.22f, strokePaint)
                    canvas.drawLine(cx + r * 0.48f, cy, cx + r * 0.24f, cy + r * 0.22f, strokePaint)
                }
                Action.ADD_TO_DOC_SCREENSHOTS -> {
                    val doc = RectF(cx - r * 0.38f, cy - r * 0.48f, cx + r * 0.22f, cy + r * 0.48f)
                    canvas.drawRect(doc, strokePaint)
                    canvas.drawLine(cx + r * 0.02f, cy, cx + r * 0.48f, cy, strokePaint)
                    canvas.drawLine(cx + r * 0.25f, cy - r * 0.23f, cx + r * 0.25f, cy + r * 0.23f, strokePaint)
                }
                Action.INSERT_CURRENT_PAGE -> {
                    canvas.drawRect(cx - r * 0.46f, cy - r * 0.28f, cx + r * 0.14f, cy + r * 0.40f, strokePaint)
                    canvas.drawRect(cx - r * 0.14f, cy - r * 0.48f, cx + r * 0.46f, cy + r * 0.20f, strokePaint)
                }
                Action.APPEND_PAGE_WITH_LINK -> {
                    val left = RectF(cx - r * 0.52f, cy - r * 0.24f, cx - r * 0.02f, cy + r * 0.24f)
                    val right = RectF(cx + r * 0.02f, cy - r * 0.24f, cx + r * 0.52f, cy + r * 0.24f)
                    canvas.save()
                    canvas.rotate(-35f, cx, cy)
                    canvas.drawOval(left, strokePaint)
                    canvas.drawOval(right, strokePaint)
                    canvas.drawLine(cx - r * 0.16f, cy, cx + r * 0.16f, cy, strokePaint)
                    canvas.restore()
                }
            }
        }
    }

    private fun buildActionBar(root: FrameLayout) {
        val diameter = dp(ACTION_BTN_DIAMETER_DP)
        val gap = dp(ACTION_BTN_GAP_DP)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        actions.forEachIndexed { index, action ->
            val button = ActionIconButton(context, action).apply {
                setOnClickListener {
                    if (action == Action.CANCEL) {
                        fireCancel("cancel-button")
                    } else {
                        val b = lassoPathView?.currentBox() ?: return@setOnClickListener
                        fireAction(action, b)
                    }
                }
            }
            bar.addView(button, LinearLayout.LayoutParams(diameter, diameter).apply {
                if (index > 0) topMargin = gap
            })
        }
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        })
        actionBar = bar
    }

    private fun positionActionBar() {
        val bar = actionBar ?: return
        val b = lassoPathView?.currentBox() ?: return
        val root = rootView ?: return
        val screenW = root.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val screenH = root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val diameter = dp(ACTION_BTN_DIAMETER_DP)
        val buttonGap = dp(ACTION_BTN_GAP_DP)
        val frameGap = dp(ACTION_BAR_FRAME_GAP_DP)
        val topOffset = dp(ACTION_BAR_TOP_OFFSET_DP)
        val margin = dp(ACTION_BAR_SCREEN_MARGIN_DP)
        val sideBarH = actions.size * diameter + (actions.size - 1).coerceAtLeast(0) * buttonGap
        val bottomBarW = actions.size * diameter + (actions.size - 1).coerceAtLeast(0) * buttonGap
        val rightX = b.right.toInt() + frameGap
        val leftX = b.left.toInt() - frameGap - diameter
        val fitsRight = rightX + diameter + margin <= screenW
        val fitsLeft = leftX >= margin
        val placeBelow = !fitsRight && !fitsLeft

        bar.orientation = if (placeBelow) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (i in 0 until bar.childCount) {
            bar.getChildAt(i).layoutParams = LinearLayout.LayoutParams(diameter, diameter).apply {
                if (i > 0) {
                    if (placeBelow) marginStart = buttonGap else topMargin = buttonGap
                }
            }
        }

        val lp = bar.layoutParams as? FrameLayout.LayoutParams ?: return
        if (placeBelow) {
            val desiredX = b.centerX().toInt() - bottomBarW / 2
            val maxX = (screenW - bottomBarW - margin).coerceAtLeast(margin)
            val desiredY = b.bottom.toInt() + frameGap
            val maxY = (screenH - diameter - margin).coerceAtLeast(margin)
            lp.leftMargin = desiredX.coerceIn(margin, maxX)
            lp.topMargin = desiredY.coerceIn(margin, maxY)
        } else {
            val desiredX = if (fitsRight) rightX else leftX
            val maxX = (screenW - diameter - margin).coerceAtLeast(margin)
            val desiredY = b.top.toInt() + topOffset
            val maxY = (screenH - sideBarH - margin).coerceAtLeast(margin)
            lp.leftMargin = desiredX.coerceIn(margin, maxX)
            lp.topMargin = desiredY.coerceIn(margin, maxY)
        }
        bar.layoutParams = lp
    }

    private fun showActionButtons() {
        positionActionBar()
        actionBar?.visibility = View.VISIBLE
    }
    private fun hideActionButtons() { actionBar?.visibility = View.GONE }

    private fun fireAction(action: Action, b: RectF) {
        if (BuildConfig.ENABLE_DEBUG) android.util.Log.i(TAG, "action=$action bbox=$b")
        val cb = onAction
        cleanup()
        cb?.invoke(action, b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
    }

    private fun fireCancel(reason: String) {
        android.util.Log.i(TAG, "fireCancel reason=$reason")
        val cb = onCancel
        cleanup()
        cb?.invoke()
    }

    private fun cleanup() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }; timeoutRunnable = null
        handler.removeCallbacks(releaseFullAutoRunnable)
        setFullUiAuto(false, "cleanup")
        if (rootView != null) {
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            rootView = null
        }
        windowManager = null; onAction = null; onCancel = null
        lassoPathView?.release()
        lassoPathView = null; actionBar = null
        actions = emptyList()
        points.clear()
        stage = Stage.DRAW
        FloatingPenGuard.setFullScreenActive(false, PEN_GUARD_OWNER)
    }
}
