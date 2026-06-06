package com.supernote_quicktoolbar.ui_common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

class FolderCoverView(context: Context) : FrameLayout(context) {

    private val strokePx: Float = (context.resources.displayMetrics.density * 1.0f).coerceAtLeast(1.5f)

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
        try {
            val stream = context.assets.open("images/folder_cover.png")
            val bmp = BitmapFactory.decodeStream(stream)
            stream.close()
            background = BitmapDrawable(context.resources, bmp)
        } catch (_: Exception) {
            setBackgroundColor(Color.WHITE)
        }
        minimumWidth = 0
        minimumHeight = 0

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
}
