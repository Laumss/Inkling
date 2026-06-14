package com.supernote_quicktoolbar.ui_common

import android.content.Context
import kotlin.math.roundToInt

object ScreenScale {
    private const val DESIGN_WIDTH_PX = 1920

    private const val DESIGN_DENSITY = 1.875f

    fun factor(ctx: Context): Float {
        val dm = ctx.resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        return if (shortSide >= DESIGN_WIDTH_PX) 1.0f else shortSide.toFloat() / DESIGN_WIDTH_PX
    }

    fun px(ctx: Context, designPx: Number): Int {
        val dm = ctx.resources.displayMetrics
        return (designPx.toFloat() * dm.density / DESIGN_DENSITY).roundToInt()
    }

    fun textPx(ctx: Context, designPx: Number): Float = px(ctx, designPx).toFloat()

    fun dp(ctx: Context, v: Int): Int {
        val dm = ctx.resources.displayMetrics
        return (v * dm.density * factor(ctx)).roundToInt()
    }

    fun dp(ctx: Context, v: Float): Int {
        val dm = ctx.resources.displayMetrics
        return (v * dm.density * factor(ctx)).roundToInt()
    }

    fun sp(ctx: Context, v: Float): Float = v * factor(ctx)
}
