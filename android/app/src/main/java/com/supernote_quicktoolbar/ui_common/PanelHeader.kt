package com.supernote_quicktoolbar.ui_common

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.ReactApplicationContext
import kotlin.math.roundToInt

object PanelHeader {

    fun create(ctx: ReactApplicationContext, title: String, onClose: (() -> Unit)? = null): LinearLayout {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(26), dp(16), dp(26))
        }
        bar.addView(TextView(ctx).apply {
            text = title
            textSize = 20f; setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        wrapper.addView(bar)
        wrapper.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.BLACK)
        })
        return wrapper
    }
}
