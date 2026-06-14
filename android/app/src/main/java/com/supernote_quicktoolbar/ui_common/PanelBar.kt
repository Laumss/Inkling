package com.supernote_quicktoolbar.ui_common

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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

    class IconBtn(val iconAsset: String, val onClick: () -> Unit) : Cell()

    class OutlineBtn(val label: String, val onClick: () -> Unit) : Cell()

    class Title(val label: String) : Cell()

    class TabPair(
        val labelA: String,
        val labelB: String,
        val onSelect: (Int) -> Unit
    ) : Cell()

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

            val INBOX_LIGHT = INBOX.copy(
                bg = Color.WHITE, fg = Color.BLACK, divider = true
            )

            val PAGE_HEADER = Style(
                bg = Color.WHITE, fg = Color.BLACK, absolutePx = true,
                height = 135, btnSize = 40, iconSize = 48,
                divider = true, horizontalPad = 20
            )
        }
    }

    class Handle(
        val view: View,
        val heightPx: Int,
        private val ctx: ReactApplicationContext,
        private val style: Style,
        private val checkIcon: ImageView?,
        val outlineBtns: List<TextView> = emptyList(),
        private val tabUnderlines: List<View> = emptyList()
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

        fun setOutlineBtnEnabled(index: Int, enabled: Boolean) {
            val tv = outlineBtns.getOrNull(index) ?: return
            tv.alpha = if (enabled) 1f else 0.4f
            tv.isEnabled = enabled
            (tv.parent as? View)?.isEnabled = enabled
        }

        fun setOutlineBtnVisible(index: Int, visible: Boolean) {
            val container = outlineBtns.getOrNull(index)?.parent as? View ?: return
            container.visibility = if (visible) View.VISIBLE else View.GONE
        }

        fun setOutlineBtnLabel(index: Int, label: String) {
            outlineBtns.getOrNull(index)?.text = label
        }

        fun setActiveTab(index: Int) {
            tabUnderlines.forEachIndexed { i, v ->
                v.visibility = if (i == index) View.VISIBLE else View.INVISIBLE
            }
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
        val outlineBtnList = mutableListOf<TextView>()
        val tabUnderlineList = mutableListOf<View>()

        fun render(cell: Cell, inWeightCenter: Boolean): View = when (cell) {
            is TextBtn -> makeTextBtn(ctx, style, cell)
            is Action -> makeAction(ctx, style, cell, inWeightCenter)
            is Check -> makeCheck(ctx, style, cell).also { (_, icon) -> checkIcon = icon }.first
            is IconBtn -> makeIconBtn(ctx, style, cell, barHeight)
            is OutlineBtn -> makeOutlineBtn(ctx, style, cell).also { outlineBtnList.add(it.second) }.first
            is Title -> makeTitle(ctx, style, cell)
            is TabPair -> makeTabPair(ctx, style, cell).also { tabUnderlineList.addAll(it.second) }.first
        }

        val bar: View
        val fixedColumns = u(style.leftCol) > 0 || u(style.rightCol) > 0
        if (fixedColumns) {
            val linearBar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(style.bg)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barHeight)
            }

            linearBar.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(u(style.leftPadStart), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(u(style.leftCol), LinearLayout.LayoutParams.MATCH_PARENT)
                left.forEach { addView(render(it, false)) }
            })

            val useWeight = center.size > 1
            linearBar.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                center.forEach { addView(render(it, useWeight)) }
            })

            linearBar.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (style.rightAlign) Gravity.CENTER_VERTICAL or Gravity.END else Gravity.CENTER
                setPadding(0, 0, if (style.rightAlign) u(style.leftPadStart) else 0, 0)
                layoutParams = LinearLayout.LayoutParams(u(style.rightCol), LinearLayout.LayoutParams.MATCH_PARENT)
                right.forEach { addView(render(it, false)) }
            })
            bar = linearBar
        } else {
            val pad = u(style.horizontalPad)
            val frameBar = FrameLayout(ctx).apply {
                setBackgroundColor(style.bg)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barHeight)
            }
            if (left.isNotEmpty()) {
                frameBar.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.START or Gravity.CENTER_VERTICAL
                    ).apply { marginStart = pad }
                    left.forEach { addView(render(it, false)) }
                })
            }
            if (center.isNotEmpty()) {
                frameBar.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                    center.forEach { addView(render(it, false)) }
                })
            }
            if (right.isNotEmpty()) {
                frameBar.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.END or Gravity.CENTER_VERTICAL
                    ).apply { marginEnd = pad }
                    right.forEach { addView(render(it, false)) }
                })
            }
            bar = frameBar
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

        val handle = Handle(root, barHeight + if (style.divider) 1 else 0, ctx, style, checkIcon, outlineBtnList, tabUnderlineList)
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

    private fun measurePx(ctx: ReactApplicationContext, style: Style, v: Number): Int =
        if (style.absolutePx) {
            ScreenScale.px(ctx, v)
        } else {
            ScreenScale.dp(ctx, v.toFloat())
        }

    private fun makeTitle(ctx: ReactApplicationContext, style: Style, cell: Title): TextView =
        TextView(ctx).apply {
            text = cell.label; setTextColor(style.fg)
            applyTextSize(ctx, style, this, 40)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

    private fun makeIconBtn(ctx: ReactApplicationContext, style: Style, cell: IconBtn, barHeight: Int): View {
        val iconPx = measurePx(ctx, style, style.iconSize)
        return LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(barHeight, LinearLayout.LayoutParams.MATCH_PARENT)
            addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
                setImageDrawable(UiUtils.loadAssetIcon(ctx, cell.iconAsset, iconPx, style.fg, strokeWidth = 2.0f))
            })
            setOnClickListener { cell.onClick() }
        }
    }

    private fun makeTabPair(ctx: ReactApplicationContext, style: Style, cell: TabPair): Pair<View, List<View>> {
        val underlines = mutableListOf<View>()
        val underlineH = measurePx(ctx, style, 4)
        val tabPadH = measurePx(ctx, style, 55)

        fun makeTab(label: String, index: Int): View {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(tabPadH, 0, tabPadH, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
            col.addView(TextView(ctx).apply {
                text = label; setTextColor(style.fg)
                applyTextSize(ctx, style, this, 40)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f
                )
            })
            val line = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, underlineH
                )
                setBackgroundColor(style.fg)
                visibility = if (index == 0) View.VISIBLE else View.INVISIBLE
            }
            underlines.add(line)
            col.addView(line)
            col.setOnClickListener {
                cell.onSelect(index)
                underlines.forEachIndexed { i, v ->
                    v.visibility = if (i == index) View.VISIBLE else View.INVISIBLE
                }
            }
            return col
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            addView(makeTab(cell.labelA, 0))
            addView(View(ctx).apply {
                setBackgroundColor(style.fg)
                layoutParams = LinearLayout.LayoutParams(
                    measurePx(ctx, style, 2), measurePx(ctx, style, 40)
                ).apply { gravity = Gravity.CENTER_VERTICAL }
            })
            addView(makeTab(cell.labelB, 1))
        }
        return container to underlines
    }

    private fun makeOutlineBtn(ctx: ReactApplicationContext, style: Style, cell: OutlineBtn): Pair<View, TextView> {
        val btnW = measurePx(ctx, style, 145)
        val btnH = measurePx(ctx, style, 78)
        val cornerR = measurePx(ctx, style, 6).toFloat()
        val strokeW = measurePx(ctx, style, 3)
        val tv = TextView(ctx).apply {
            text = cell.label; setTextColor(Color.BLACK)
            applyTextSize(ctx, style, this, 30)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(btnW, btnH)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(strokeW, Color.BLACK)
                cornerRadius = cornerR
            }
        }
        val container = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            val padH = measurePx(ctx, style, 30)
            setPadding(padH, 0, padH, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            addView(tv)
            setOnClickListener { cell.onClick() }
        }
        return container to tv
    }

    private fun applyTextSize(ctx: ReactApplicationContext, style: Style, tv: TextView, v: Number) {
        if (style.absolutePx) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, ScreenScale.textPx(ctx, v))
        } else {

            tv.textSize = v.toFloat() * ScreenScale.factor(ctx)
        }
    }
}