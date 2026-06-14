package com.supernote_quicktoolbar.ui_common

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.ReactApplicationContext
import kotlin.math.roundToInt

class SelectionButton(val view: TextView) {
    init { update(false) }

    fun update(enabled: Boolean) {
        view.alpha = if (enabled) 1f else 0.4f
        view.isEnabled = enabled
    }
}

object PanelHeader {

    fun create(ctx: ReactApplicationContext, title: String, onClose: (() -> Unit)? = null): LinearLayout {
        val scale = ScreenScale.factor(ctx)
        fun dp(v: Int) = ScreenScale.dp(ctx, v)

        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(22))
        }
        bar.addView(TextView(ctx).apply {
            text = title
            textSize = 23f * scale; setTextColor(Color.BLACK)
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

object PanelWidgets {

    fun outlinedButton(host: PanelHost, label: String, onClick: () -> Unit): TextView =
        TextView(host.ctx).apply {
            text = label; textSize = host.sp(17f); setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            minWidth = host.dp(106); minHeight = host.dp(44)
            setPadding(host.dp(16), 0, host.dp(16), 0)
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(host.dp(1), Color.BLACK)
                cornerRadius = 0f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = host.dp(32) }
            setOnClickListener { onClick() }
        }

    fun filledButton(host: PanelHost, label: String, onClick: () -> Unit): TextView =
        TextView(host.ctx).apply {
            text = label; textSize = host.sp(17f); setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = host.dp(106); minHeight = host.dp(44)
            setPadding(host.dp(16), 0, host.dp(16), 0)
            background = GradientDrawable().apply {
                setColor(Color.BLACK); cornerRadius = 0f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }

    fun divider(host: PanelHost): View =
        View(host.ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.BLACK)
        }

    fun emptyView(host: PanelHost, text: String): View =
        LinearLayout(host.ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(host.dp(30), host.dp(60), host.dp(30), 0)
            addView(TextView(host.ctx).apply {
                this.text = text; textSize = host.sp(14f)
                setTextColor(Color.parseColor("#999999")); gravity = Gravity.CENTER
            })
        }

    fun formatSize(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${"%.1f".format(size / 1024.0)} KB"
        else -> "${"%.1f".format(size / (1024.0 * 1024.0))} MB"
    }
}
