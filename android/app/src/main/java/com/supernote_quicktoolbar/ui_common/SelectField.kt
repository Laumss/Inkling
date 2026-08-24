package com.supernote_quicktoolbar.ui_common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

object SelectField {

    class Refs(
        val box: LinearLayout,
        val value: TextView,
        val list: LinearLayout,
        val dots: List<View>,
        val options: List<String>,
        var selected: Int,
        var popup: PopupWindow? = null,
    )

    private val activePopups = ArrayList<PopupWindow>()

    fun dismissAllPopups() {
        val copy = activePopups.toList()
        activePopups.clear()
        copy.forEach { try { it.dismiss() } catch (_: Exception) {} }
    }

    fun create(
        ctx: Context,
        label: String,
        options: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ): LinearLayout {
        fun px(v: Int) = ScreenScale.px(ctx, v)
        val field = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (label.isNotEmpty()) {
            field.addView(TextView(ctx).apply {
                text = label
                textSize = ScreenScale.sp(ctx, 15.5f)
                setTextColor(Color.BLACK)
                setPadding(0, 0, 0, ScreenScale.dp(ctx, 8))
            })
        }
        val value = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ScreenScale.textPx(ctx, 34))
            setTextColor(Color.BLACK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val dots = ArrayList<View>(options.size)
        options.forEachIndexed { index, option ->
            if (index > 0) {
                list.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, px(1).coerceAtLeast(1))
                    setBackgroundColor(Color.BLACK)
                })
            }
            val dot = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(px(16), px(16))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.BLACK)
                }
                visibility = View.INVISIBLE
            }
            dots.add(dot)
            list.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = px(88)
                setPadding(px(30), 0, px(30), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                addView(TextView(ctx).apply {
                    text = option
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, ScreenScale.textPx(ctx, 34))
                    setTextColor(Color.BLACK)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(dot)
                setOnClickListener {
                    refs(field)?.popup?.dismiss()
                    onSelect(index)
                }
            })
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(88)
            setPadding(px(30), 0, px(30), 0)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(px(3), Color.BLACK)
                cornerRadius = 0f
            }
            addView(value)
            addView(TextView(ctx).apply {
                text = "▼"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, ScreenScale.textPx(ctx, 22))
                setTextColor(Color.BLACK)
            })
            setOnClickListener { togglePopup(ctx, field) }
        }
        field.addView(box)
        field.tag = Refs(box, value, list, dots, options, selected)
        setValue(field, selected)
        return field
    }

    fun refs(field: LinearLayout): Refs? = field.tag as? Refs

    fun setValue(field: LinearLayout, selected: Int) {
        val r = refs(field) ?: return
        r.selected = selected
        val index = selected.coerceIn(0, (r.options.size - 1).coerceAtLeast(0))
        r.dots.forEachIndexed { i, dot ->
            dot.visibility = if (i == index) View.VISIBLE else View.INVISIBLE
        }
        r.options.getOrNull(index)?.let { r.value.text = it }
    }

    private fun togglePopup(ctx: Context, field: LinearLayout) {
        val r = refs(field) ?: return
        if (r.popup?.isShowing == true) {
            r.popup?.dismiss()
            return
        }
        dismissAllPopups()
        val boxW = r.box.width
        if (boxW <= 0) return
        val gap = ScreenScale.dp(ctx, 4)
        val maxH = ScreenScale.dp(ctx, 400)
        r.list.measure(
            View.MeasureSpec.makeMeasureSpec(boxW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val contentH = minOf(r.list.measuredHeight, maxH)
        val border = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(ScreenScale.px(ctx, 3), Color.BLACK)
                cornerRadius = 0f
            }
        }
        (r.list.parent as? ViewGroup)?.removeView(r.list)
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        scroll.addView(r.list)
        border.addView(scroll)
        val popup = PopupWindow(border, boxW, contentH, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            animationStyle = 0
            setOnDismissListener {
                activePopups.remove(this)
                if (r.popup === this) r.popup = null
            }
        }
        r.popup = popup
        activePopups.add(popup)
        val loc = IntArray(2)
        r.box.getLocationOnScreen(loc)
        val screenH = ctx.resources.displayMetrics.heightPixels
        val below = screenH - (loc[1] + r.box.height) - gap
        val above = loc[1] - gap
        if (below < contentH && above > below) {
            popup.showAtLocation(r.box, Gravity.NO_GRAVITY, loc[0], above - contentH)
        } else {
            popup.showAsDropDown(r.box, 0, gap)
        }
    }
}
