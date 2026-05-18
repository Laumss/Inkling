package com.supernote_quicktoolbar.ui_common

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.ReactApplicationContext
import kotlin.math.roundToInt

class PanelChips(
    private val ctx: ReactApplicationContext,
    private val keys: List<String>,
    private val onSelect: (selected: String?) -> Unit
) {
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private var container: LinearLayout? = null
    private var selected: String? = null

    fun setSelection(key: String?) {
        selected = key
    }

    fun getSelection(): String? = selected

    fun createView(): LinearLayout {
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val row = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), dp(20), dp(26), dp(6))
        }
        rebuildChips()
        row.addView(container)
        wrapper.addView(row)
        return wrapper
    }

    fun rebuildChips() {
        val c = container ?: return
        c.removeAllViews()
        (c.parent as? HorizontalScrollView)?.scrollTo(0, 0)
        for ((index, key) in keys.withIndex()) {
            val isActive = selected == key
            val chip = TextView(ctx).apply {
                text = key
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (isActive) Color.WHITE else Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    setColor(if (isActive) Color.BLACK else Color.WHITE)
                    setStroke(2, Color.BLACK)
                    cornerRadius = dp(53).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) leftMargin = dp(20) }
                setOnClickListener {
                    if (selected == key) return@setOnClickListener
                    selected = key
                    rebuildChips()
                    onSelect(selected)
                }
            }
            c.addView(chip)
        }
    }
}
