package com.supernote_quicktoolbar.ui_common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

class FolderCoverView(context: Context) : FrameLayout(context) {

    private val strokePx: Float = (context.resources.displayMetrics.density * 1.0f).coerceAtLeast(1.5f)

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = Color.BLACK
    }
    private val outlinePath = Path()
    private val labelPath = Path()
    private val tabSeamPath = Path()

    private val gridRoot: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val row1: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }
    private val row2: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    val child1: ImageView = makeChildSlot()
    val child2: ImageView = makeChildSlot()
    val child3: ImageView = makeChildSlot()
    val child4: ImageView = makeChildSlot()

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.WHITE)

        row1.addView(child1); row1.addView(child2)
        row2.addView(child3); row2.addView(child4)
        gridRoot.addView(row1)
        gridRoot.addView(row2)
        addView(
            gridRoot,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )
    }

    private fun makeChildSlot(): ImageView {
        return ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(strokePx.toInt().coerceAtLeast(1), Color.BLACK)
            }
            visibility = INVISIBLE
        }
    }

    fun setupChildSlots(cellWidthPx: Int, cellHeightPx: Int) {
        val thumbW = (cellWidthPx * 0.34f).toInt()
        val thumbH = (thumbW * 1.31f).toInt()
        val hGap = (cellWidthPx * 0.045f).toInt()
        val vGap = (cellHeightPx * 0.038f).toInt()
        val topOffset = (cellHeightPx * 0.13f).toInt()

        listOf(child1, child2, child3, child4).forEach {
            it.layoutParams = LinearLayout.LayoutParams(thumbW, thumbH)
        }
        (child2.layoutParams as LinearLayout.LayoutParams).leftMargin = hGap
        (child4.layoutParams as LinearLayout.LayoutParams).leftMargin = hGap

        row2.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = vGap }

        (gridRoot.layoutParams as LayoutParams).topMargin = topOffset
        requestLayout()
    }

    fun setChildBitmap(index: Int, bmp: Bitmap?) {
        val v = when (index) { 0 -> child1; 1 -> child2; 2 -> child3; else -> child4 }
        if (bmp == null) {
            v.visibility = INVISIBLE
        } else {
            v.setImageBitmap(bmp)
            v.visibility = VISIBLE
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildOutline(w.toFloat(), h.toFloat())
    }

    private fun rebuildOutline(w: Float, h: Float) {
        val inset = strokePx / 2f
        val left = inset
        val right = w - inset
        val top = inset
        val bottom = h - inset
        val tabBaseY = top + h * 0.045f
        val cutStart = left + w * 0.55f
        val cutEnd = left + w * 0.66f

        outlinePath.reset()
        outlinePath.moveTo(left, tabBaseY)
        outlinePath.lineTo(cutStart, tabBaseY)
        outlinePath.lineTo(cutEnd, top)
        outlinePath.lineTo(right, top)
        outlinePath.lineTo(right, bottom)
        outlinePath.lineTo(left, bottom)
        outlinePath.close()

        labelPath.reset()
        val labelY = tabBaseY - h * 0.018f
        labelPath.moveTo(left + w * 0.06f, labelY)
        labelPath.lineTo(left + w * 0.30f, labelY)

        tabSeamPath.reset()
        val seamY = tabBaseY + h * 0.018f
        tabSeamPath.moveTo(cutStart + w * 0.02f, seamY)
        tabSeamPath.lineTo(right - w * 0.01f, seamY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(outlinePath, outlinePaint)
        canvas.drawPath(labelPath, outlinePaint)
        canvas.drawPath(tabSeamPath, outlinePaint)
    }
}
