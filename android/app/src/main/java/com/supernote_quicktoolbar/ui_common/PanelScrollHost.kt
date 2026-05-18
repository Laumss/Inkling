package com.supernote_quicktoolbar.ui_common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.facebook.react.bridge.ReactApplicationContext
import kotlin.math.roundToInt

class PanelScrollHost(
    private val ctx: ReactApplicationContext,
    private val overlayScrollbar: Boolean = false
) {
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dpf(v: Float) = v * density

    companion object {

        const val SCROLLBAR_LANE_DP = 28
    }

    val scrollBarLaneWidthPx: Int = dp(SCROLLBAR_LANE_DP)

    val view: LinearLayout
    val content: LinearLayout
    private val scrollView: ScrollView
    private val scrollBar: RattaScrollBar

    init {

        view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        scrollView = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = false
            addView(content)
            viewTreeObserver.addOnScrollChangedListener { syncBarFromScroll() }
        }

        scrollBar = RattaScrollBar(ctx, density).apply {
            onDragScrollPercent = { pct ->
                scrollView.getChildAt(0)?.let { child ->
                    val maxScroll = (child.height - scrollView.height).coerceAtLeast(0)
                    scrollView.scrollTo(0, (pct * maxScroll).toInt())
                }
            }
        }

        if (overlayScrollbar) {
            scrollView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            scrollBar.layoutParams = FrameLayout.LayoutParams(
                scrollBarLaneWidthPx, FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.END }
            val innerFrame = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply {
                    topMargin = dp(20)
                    bottomMargin = dp(12)
                }
            }
            innerFrame.addView(scrollView)
            innerFrame.addView(scrollBar)
            view.addView(innerFrame)
        } else {
            scrollView.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
            scrollBar.layoutParams = LinearLayout.LayoutParams(
                scrollBarLaneWidthPx, LinearLayout.LayoutParams.MATCH_PARENT
            )
            val innerRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply {
                    topMargin = dp(20)
                    bottomMargin = dp(16)
                }
            }
            innerRow.addView(scrollView)
            innerRow.addView(scrollBar)
            view.addView(innerRow)
        }
    }

    fun availableContentWidth(panelWidth: Int): Int =
        if (overlayScrollbar) panelWidth - content.paddingLeft - content.paddingRight
        else panelWidth - scrollBarLaneWidthPx - content.paddingLeft - content.paddingRight

    fun prepareForContentChange() {
        scrollView.scrollTo(0, 0)
        scrollBar.visibility = View.INVISIBLE
    }

    fun refreshThumb() {
        scrollView.post { syncBarFromScroll() }
    }

    fun scrollToTop() {
        scrollView.scrollTo(0, 0)
        scrollView.post { syncBarFromScroll() }
    }

    private fun syncBarFromScroll() {
        val child = scrollView.getChildAt(0) ?: return
        val contentH = child.height
        val viewH = scrollView.height
        if (contentH <= viewH || viewH <= 0) {
            scrollBar.visibility = View.INVISIBLE
            return
        }
        if (scrollBar.visibility != View.VISIBLE) scrollBar.visibility = View.VISIBLE
        val percent = scrollView.scrollY.toFloat() /
            (contentH - viewH).coerceAtLeast(1).toFloat()
        scrollBar.setScrollPercent(percent.coerceIn(0f, 1f))
    }
}

private class RattaScrollBar(
    ctx: Context,
    density: Float
) : View(ctx) {

    private val barW = 18f * density
    private val barH = 48f * density
    private val rightPad = 4f * density
    private val strokeW = 2f * density
    private val lineInset = 4f * density
    private val lineH = 2f * density
    private val lineGap = 2f * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = strokeW
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.FILL
    }

    private var thumbTopY: Float = 0f
    private var isDragging = false
    private var touchOffset = 0f

    var onDragScrollPercent: ((Float) -> Unit)? = null

    fun setScrollPercent(percent: Float) {
        val maxTop = (height - barH).coerceAtLeast(0f)
        thumbTopY = percent.coerceIn(0f, 1f) * maxTop
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != VISIBLE) return

        val left = width - barW - rightPad
        val top = thumbTopY
        val right = left + barW
        val bottom = top + barH
        val corner = barW / 2f

        val inset = strokeW / 2f
        val body = RectF(left + inset, top + inset, right - inset, bottom - inset)
        canvas.drawRoundRect(body, corner, corner, fillPaint)
        canvas.drawRoundRect(body, corner, corner, strokePaint)

        val centerY = (top + bottom) / 2f
        val lineLeft = left + lineInset
        val lineRight = right - lineInset
        val step = lineH + lineGap
        for (i in -1..1) {
            val ly = centerY + i * step
            val r = RectF(lineLeft, ly - lineH / 2f, lineRight, ly + lineH / 2f)
            canvas.drawRoundRect(r, lineH / 2f, lineH / 2f, linePaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        val maxTop = (height - barH).coerceAtLeast(0f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val y = event.y
                if (y >= thumbTopY && y <= thumbTopY + barH) {
                    isDragging = true
                    touchOffset = y - thumbTopY
                } else {

                    isDragging = true
                    touchOffset = barH / 2f
                    thumbTopY = (y - touchOffset).coerceIn(0f, maxTop)
                    emitPercent(maxTop)
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    thumbTopY = (event.y - touchOffset).coerceIn(0f, maxTop)
                    emitPercent(maxTop)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun emitPercent(maxTop: Float) {
        if (maxTop <= 0f) return
        onDragScrollPercent?.invoke(thumbTopY / maxTop)
    }
}
