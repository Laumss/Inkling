package com.supernote_quicktoolbar

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout

class PenLassoOverlay(private val context: Context) {

    companion object {
        private const val TAG = "PenLassoOverlay"
        private const val STROKE_WIDTH = 4f
        private const val STROKE_COLOR = 0xCC333333.toInt()
        private const val TIMEOUT_MS = 30_000L
        private const val MIN_POINT_DIST_SQ = 9f
    }

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var onBbox: ((left: Int, top: Int, right: Int, bottom: Int) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private val points = mutableListOf<PointF>()
    private val path = Path()

    fun show(
        onBbox: (left: Int, top: Int, right: Int, bottom: Int) -> Unit,
        onCancel: () -> Unit
    ) {
        this.onBbox = onBbox
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

        root.addView(LassoPathView(context), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val wmType = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

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
            android.util.Log.e(TAG, "addView failed: ${e.message}", e)
            fireCancel()
            return
        }

        timeoutRunnable = Runnable { fireCancel() }
        handler.postDelayed(timeoutRunnable!!, TIMEOUT_MS)
        android.util.Log.i(TAG, "overlay shown, waiting for pen stroke...")
    }

    fun dismiss() {
        handler.post { fireCancel() }
    }

    private inner class LassoPathView(ctx: Context) : View(ctx) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = STROKE_COLOR
            style = Paint.Style.STROKE
            strokeWidth = STROKE_WIDTH
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x; val y = event.y
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    points.clear()
                    path.reset()
                    points.add(PointF(x, y))
                    path.moveTo(x, y)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val last = points.lastOrNull() ?: return true
                    val dx = x - last.x; val dy = y - last.y
                    if (dx * dx + dy * dy < MIN_POINT_DIST_SQ) return true
                    points.add(PointF(x, y))
                    path.lineTo(x, y)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (points.size >= 2) fireBbox() else fireCancel()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (points.size >= 2) canvas.drawPath(path, paint)
        }
    }

    private fun fireBbox() {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x; if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x; if (p.y > maxY) maxY = p.y
        }
        if (maxX - minX < 2f || maxY - minY < 2f) {
            android.util.Log.w(TAG, "bbox too small, cancel")
            fireCancel(); return
        }
        android.util.Log.i(TAG, "bbox: [$minX,$minY,$maxX,$maxY] from ${points.size} pts")
        val cb = onBbox
        cleanup()
        cb?.invoke(minX.toInt(), minY.toInt(), maxX.toInt(), maxY.toInt())
    }

    private fun fireCancel() {
        val cb = onCancel
        cleanup()
        cb?.invoke()
    }

    private fun cleanup() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }; timeoutRunnable = null
        if (rootView != null) {
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            rootView = null
        }
        windowManager = null; onBbox = null; onCancel = null
        points.clear(); path.reset()
    }
}
