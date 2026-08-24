package com.supernote_quicktoolbar.ui_common
import com.supernote_quicktoolbar.BuildConfig

import android.util.Log
import android.view.View
import android.widget.LinearLayout
import com.facebook.react.bridge.ReactApplicationContext

object PanelGrid {

    fun <T> build(
        ctx: ReactApplicationContext,
        host: PanelScrollHost,
        screenWidthPx: Int,
        screenHeightPx: Int,
        panelWidthPx: Int,
        items: List<T>,
        sideLeftDp: Int = 22,
        sideRightDp: Int = 22,
        midGapDp: Int = 18,
        rowTopPadDp: Int = 6,
        cell: (item: T, colWidthPx: Int) -> View
    ) {
        fun dp(v: Int) = ScreenScale.dp(ctx, v)

        val grid = host.content
        val landscape = screenWidthPx > screenHeightPx
        val cols = ScreenScale.gridColumns(screenWidthPx, screenHeightPx)
        val sideDp = if (landscape) 16 else sideLeftDp
        val gapDp = if (landscape) 12 else midGapDp

        val sideLeft = dp(sideDp)
        val sideRight = dp(if (landscape) 16 else sideRightDp)
        val midGap = dp(gapDp)
        val rowTopPad = dp(rowTopPadDp)
        val innerW = host.availableContentWidth(panelWidthPx)
        val colW = (innerW - sideLeft - sideRight - midGap * (cols - 1)) / cols
        if (BuildConfig.ENABLE_DEBUG) Log.i("PanelGrid", "panelW=$panelWidthPx screenW=$screenWidthPx screenH=$screenHeightPx innerW=$innerW cols=$cols colW=$colW sideL=$sideLeft sideR=$sideRight midGap=$midGap padL=${grid.paddingLeft} padR=${grid.paddingRight} scrollbarLane=${host.scrollBarLaneWidthPx}")

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
