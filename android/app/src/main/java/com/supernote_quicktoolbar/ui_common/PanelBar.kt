package com.supernote_quicktoolbar.ui_common

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.ReactApplicationContext
import kotlin.math.roundToInt

object PanelBar {

    sealed class Cell

    class TextBtn(val label: String, val onClick: () -> Unit) : Cell()

    class Action(val iconAsset: String, val label: String, val onClick: () -> Unit) : Cell()

    class Check(val label: String, val onToggle: () -> Unit) : Cell()

    data class Style(
        val bg: Int,
        val fg: Int,
        val absolutePx: Boolean,
        val height: Number,
        val leftCol: Number = 0,
        val rightCol: Number = 0,
        val leftPadStart: Number = 0,
        val horizontalPad: Number = 0,
        val btnSize: Number = 19,
        val btnPadH: Number = 0,
        val btnPadV: Number = 0,
        val labelSize: Number = 30,
        val iconSize: Number = 56,
        val labelGap: Number = 4,
        val actionPad: Number = 44,
        val btnBold: Boolean = false,
        val divider: Boolean = false,
        val rightAlign: Boolean = false
    ) {
        companion object {

            val INBOX = Style(
                bg = Color.BLACK, fg = Color.WHITE, absolutePx = true,
                height = 135, leftCol = 220, rightCol = 228, leftPadStart = 51.5,
                btnSize = 40, labelSize = 30, iconSize = 56, labelGap = 4, actionPad = 44,
                btnBold = false
            )
        }
    }

    class Handle(
        val view: View,
        val heightPx: Int,
        private val ctx: ReactApplicationContext,
        private val style: Style,
        private val checkIcon: ImageView?
    ) {
        var isChecked: Boolean = false
            private set

        fun setChecked(checked: Boolean) {
            isChecked = checked
            val icon = checkIcon ?: return
            val iconPx = measurePx(ctx, style, style.iconSize)
            val asset = if (checked) "icons/ic_check_on.xml" else "icons/ic_check_off.xml"
            icon.setImageDrawable(UiUtils.loadAssetIcon(ctx, asset, iconPx, style.fg, strokeWidth = 2.0f))
        }
    }

    fun build(
        ctx: ReactApplicationContext,
        style: Style,
        left: List<Cell> = emptyList(),
        center: List<Cell> = emptyList(),
        right: List<Cell> = emptyList()
    ): Handle {
        fun u(v: Number) = measurePx(ctx, style, v)

        val barHeight = u(style.height)
        var checkIcon: ImageView? = null

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(style.bg)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barHeight)
        }

        fun render(cell: Cell, inWeightCenter: Boolean): View = when (cell) {
            is TextBtn -> makeTextBtn(ctx, style, cell)
            is Action -> makeAction(ctx, style, cell, inWeightCenter)
            is Check -> makeCheck(ctx, style, cell).also { (_, icon) -> checkIcon = icon }.first
        }

        val fixedColumns = u(style.leftCol) > 0 || u(style.rightCol) > 0
        if (fixedColumns) {

            bar.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(u(style.leftPadStart), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(u(style.leftCol), LinearLayout.LayoutParams.MATCH_PARENT)
                left.forEach { addView(render(it, false)) }
            })

            val useWeight = center.size > 1
            bar.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                center.forEach { addView(render(it, useWeight)) }
            })

            bar.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (style.rightAlign) Gravity.CENTER_VERTICAL or Gravity.END else Gravity.CENTER
                setPadding(0, 0, if (style.rightAlign) u(style.leftPadStart) else 0, 0)
                layoutParams = LinearLayout.LayoutParams(u(style.rightCol), LinearLayout.LayoutParams.MATCH_PARENT)
                right.forEach { addView(render(it, false)) }
            })
        } else {

            val pad = u(style.horizontalPad)
            bar.setPadding(pad, 0, pad, 0)
            left.forEach { bar.addView(render(it, false)) }
            bar.addView(flex(ctx))
            center.forEach { bar.addView(render(it, false)) }
            bar.addView(flex(ctx))
            right.forEach { bar.addView(render(it, false)) }
        }

        val root: View = if (style.divider) {
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(bar)
                addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(style.fg)
                })
            }
        } else bar

        val handle = Handle(root, barHeight, ctx, style, checkIcon)
        if (checkIcon != null) handle.setChecked(false)
        return handle
    }

    private fun makeTextBtn(ctx: ReactApplicationContext, style: Style, cell: TextBtn): TextView =
        TextView(ctx).apply {
            text = cell.label; setTextColor(style.fg)
            applyTextSize(ctx, style, this, style.btnSize)
            if (style.btnBold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            val ph = measurePx(ctx, style, style.btnPadH)
            val pv = measurePx(ctx, style, style.btnPadV)
            setPadding(ph, pv, ph, pv)
            setOnClickListener { cell.onClick() }
        }

    private fun makeAction(ctx: ReactApplicationContext, style: Style, cell: Action, useWeight: Boolean): View {
        val iconPx = measurePx(ctx, style, style.iconSize)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER

            layoutParams = if (useWeight) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            } else {
                val p = measurePx(ctx, style, style.actionPad)
                setPadding(p, 0, p, 0)
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            }
            addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
                setImageDrawable(UiUtils.loadAssetIcon(ctx, cell.iconAsset, iconPx, style.fg, strokeWidth = 2.0f))
            })
            addView(TextView(ctx).apply {
                text = cell.label; setTextColor(style.fg)
                applyTextSize(ctx, style, this, style.labelSize)
                gravity = Gravity.CENTER
                setPadding(0, measurePx(ctx, style, style.labelGap), 0, 0)
            })
            setOnClickListener { cell.onClick() }
        }
    }

    private fun makeCheck(ctx: ReactApplicationContext, style: Style, cell: Check): Pair<LinearLayout, ImageView> {
        val iconPx = measurePx(ctx, style, style.iconSize)
        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(icon)
            addView(TextView(ctx).apply {
                text = cell.label; setTextColor(style.fg)
                applyTextSize(ctx, style, this, style.labelSize)
                gravity = Gravity.CENTER
                setPadding(0, measurePx(ctx, style, style.labelGap), 0, 0)
            })
            setOnClickListener { cell.onToggle() }
        }
        return col to icon
    }

    private fun flex(ctx: ReactApplicationContext): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    private fun measurePx(ctx: ReactApplicationContext, style: Style, v: Number): Int =
        if (style.absolutePx) {
            ScreenScale.px(ctx, v)
        } else {
            val dm = ctx.resources.displayMetrics
            (v.toFloat() * dm.density * ScreenScale.factor(ctx)).roundToInt()
        }

    private fun applyTextSize(ctx: ReactApplicationContext, style: Style, tv: TextView, v: Number) {
        if (style.absolutePx) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, ScreenScale.textPx(ctx, v))
        } else {

            tv.textSize = v.toFloat() * ScreenScale.factor(ctx)
        }
    }
}
