package com.supernote_quicktoolbar.ui_common

import android.view.View
import android.widget.LinearLayout
import com.facebook.react.bridge.ReactApplicationContext
import kotlin.math.roundToInt

object PanelGrid {

    private const val THREE_COL_MIN_PX = 1920

    fun <T> build(
        ctx: ReactApplicationContext,
        host: PanelScrollHost,
        screenWidthPx: Int,
        panelWidthPx: Int,
        items: List<T>,
        sideLeftDp: Int = 22,
        sideRightDp: Int = 0,
        midGapDp: Int = 18,
        rowTopPadDp: Int = 6,
        cell: (item: T, colWidthPx: Int) -> View
    ) {
        val density = ctx.resources.displayMetrics.density
        val scale = ScreenScale.factor(ctx)
        fun dp(v: Int) = (v * density * scale).roundToInt()

        val grid = host.content

        val sideLeft = dp(sideLeftDp)
        val sideRight = dp(sideRightDp)
        val midGap = dp(midGapDp)
        val rowTopPad = dp(rowTopPadDp)
        val innerW = host.availableContentWidth(panelWidthPx)
        val cols = if (screenWidthPx >= THREE_COL_MIN_PX) 3 else 2
        val colW = (innerW - sideLeft - sideRight - midGap * (cols - 1)) / cols

        var row: LinearLayout? = null
        for ((idx, item) in items.withIndex()) {
            if (idx % cols == 0) {
                row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(sideLeft, rowTopPad, sideRight, 0)
                }
                grid.addView(row)
            }
            val view = cell(item, colW)
            (view.layoutParams as? LinearLayout.LayoutParams)?.apply {
                if (idx % cols != cols - 1) rightMargin = midGap
            }
            row?.addView(view)
        }

        val remainder = items.size % cols
        if (remainder != 0) {
            for (i in 0 until (cols - remainder)) {
                row?.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(colW, 1)
                })
            }
        }
    }
}
