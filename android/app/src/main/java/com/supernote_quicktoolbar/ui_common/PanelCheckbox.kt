package com.supernote_quicktoolbar.ui_common

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.ReactApplicationContext
import kotlin.math.roundToInt

class PanelCheckbox(
    ctx: ReactApplicationContext,
    label: String,
    onClick: () -> Unit
) {
    private val density = ctx.resources.displayMetrics.density
    private val scale = ScreenScale.factor(ctx)
    private fun dp(v: Int) = (v * density * scale).roundToInt()
    private fun sp(v: Float) = v * scale

    private val gray = Color.parseColor("#999999")

    private val box: View
    private val mark: TextView
    private val labelView: TextView
    val view: LinearLayout

    init {
        view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            alpha = 0.4f
            setOnClickListener { onClick() }
        }

        val checkSize = dp(28)
        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(checkSize, checkSize).apply { rightMargin = dp(10) }
        }
        box = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        frame.addView(box)
        mark = TextView(ctx).apply {
            text = "✓"; textSize = sp(17f); setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        frame.addView(mark)
        view.addView(frame)

        labelView = TextView(ctx).apply {
            text = label; textSize = sp(17f); setTextColor(gray)
        }
        view.addView(labelView)

        setChecked(false)
    }

    fun setChecked(checked: Boolean) {
        if (checked) {
            box.background = GradientDrawable().apply {
                setColor(Color.BLACK); cornerRadius = dp(3).toFloat()
            }
            mark.visibility = View.VISIBLE
            labelView.setTextColor(Color.BLACK)
        } else {
            box.background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(dp(2), gray); cornerRadius = dp(3).toFloat()
            }
            mark.visibility = View.GONE
            labelView.setTextColor(gray)
        }
    }

    fun setLabel(text: String) {
        labelView.text = text
    }

    fun setActive(active: Boolean) {
        view.alpha = if (active) 1f else 0.4f
    }
}
