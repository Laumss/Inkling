package com.supernote_quicktoolbar.ui_common

import android.content.Context
import android.os.Build
import kotlin.math.roundToInt

object ScreenScale {
    private const val DESIGN_WIDTH_PX = 1920

    private val isA5X2: Boolean = Build.BOARD == "A5X2"

    private val isA5X: Boolean =
        !isA5X2 &&
            (Build.MODEL.equals("Supernote A5 X", ignoreCase = true) ||
                (Build.MODEL.contains("A5 X", ignoreCase = true) &&
                    !Build.MODEL.contains("X2", ignoreCase = true)) ||
                Build.MODEL.equals("A5X", ignoreCase = true) ||
                Build.MODEL.endsWith(" A5X", ignoreCase = true))

    fun isCompact1404(ctx: Context): Boolean {
        val dm = ctx.resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        val longSide = maxOf(dm.widthPixels, dm.heightPixels)
        return shortSide == 1404 && longSide == 1872 && !isA5X
    }

    fun gridColumns(screenW: Int, screenH: Int): Int {
        if (screenW <= screenH) return 3
        if (isA5X || isA5X2) return 5
        return 4
    }

    private const val COMPACT_GRID_TEXT_BOOST = 1.22f

    fun gridSp(ctx: Context, v: Float): Float {
        val base = sp(ctx, v)
        return if (isCompact1404(ctx)) base * COMPACT_GRID_TEXT_BOOST else base
    }

    private const val DESIGN_DENSITY = 1.875f

    private const val MIN_FACTOR = 0.88f

    private const val LOW_DENSITY_BOOST = 1.37f

    private const val A5X_TOOLBAR_BOOST = 5.2f / 3.8f

    private fun needsBoost(ctx: Context): Boolean {
        val dm = ctx.resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        return shortSide < DESIGN_WIDTH_PX && dm.density <= 1.0f
    }

    private fun densityBoost(ctx: Context): Float =
        if (needsBoost(ctx)) LOW_DENSITY_BOOST else 1.0f

    fun factor(ctx: Context): Float {
        val dm = ctx.resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        val raw = if (shortSide >= DESIGN_WIDTH_PX) 1.0f else shortSide.toFloat() / DESIGN_WIDTH_PX
        return maxOf(raw, MIN_FACTOR)
    }

    private fun isA5XToolbar(ctx: Context): Boolean {
        if (isA5X) return true
        if (isA5X2) return false
        val dm = ctx.resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        val longSide = maxOf(dm.widthPixels, dm.heightPixels)
        return shortSide == 1404 && longSide == 1872 && dm.density <= 1.5f
    }

    fun toolbarFactor(ctx: Context): Float {
        val base = factor(ctx)
        return when {
            isA5XToolbar(ctx) -> base * A5X_TOOLBAR_BOOST
            else -> base * densityBoost(ctx)
        }
    }

    private fun boostedFactor(ctx: Context): Float = factor(ctx) * densityBoost(ctx)

    fun px(ctx: Context, designPx: Number): Int {
        val dm = ctx.resources.displayMetrics
        return (designPx.toFloat() * dm.density * densityBoost(ctx) / DESIGN_DENSITY).roundToInt()
    }

    fun textPx(ctx: Context, designPx: Number): Float = px(ctx, designPx).toFloat()

    fun dp(ctx: Context, v: Int): Int {
        val dm = ctx.resources.displayMetrics
        return (v * dm.density * boostedFactor(ctx)).roundToInt()
    }

    fun dp(ctx: Context, v: Float): Int {
        val dm = ctx.resources.displayMetrics
        return (v * dm.density * boostedFactor(ctx)).roundToInt()
    }

    fun sp(ctx: Context, v: Float): Float = v * boostedFactor(ctx)

    private const val PANEL_WIDTH_DP = 558.93f

    private const val LARGE_SCREEN_LONG_PX = 2560

    private const val PANEL_WIDTH_PCT_PORTRAIT_LARGE = 0.64f
    private const val PANEL_WIDTH_PCT_LANDSCAPE_LARGE = 0.70f
    private const val PANEL_WIDTH_PCT_LANDSCAPE_SMALL = 0.68f

    private const val PANEL_HEIGHT_PCT_PORTRAIT_LARGE = 0.76
    private const val PANEL_HEIGHT_PCT_LANDSCAPE_LARGE = 0.82
    private const val PANEL_HEIGHT_PCT_LANDSCAPE_SMALL = 0.88

    private fun isLargeScreen(screenLong: Int) = screenLong >= LARGE_SCREEN_LONG_PX

    private fun useLargePanelPct(screenLong: Int) = isA5X || isLargeScreen(screenLong)

    fun panelWidth(density: Float, screenW: Int, screenH: Int, screenLong: Int): Int {
        val landscape = screenW > screenH
        return when {
            useLargePanelPct(screenLong) -> {
                val ratio = if (landscape) PANEL_WIDTH_PCT_LANDSCAPE_LARGE else PANEL_WIDTH_PCT_PORTRAIT_LARGE
                (screenW * ratio).toInt()
            }
            landscape -> (screenW * PANEL_WIDTH_PCT_LANDSCAPE_SMALL).toInt()
            else -> (PANEL_WIDTH_DP * density * (if (density <= 1.0f) LOW_DENSITY_BOOST else 1.0f)).toInt()
        }
    }

    fun panelHeight(screenH: Int, screenW: Int, screenLong: Int, defaultRatio: Double): Int {
        val landscape = screenW > screenH
        val ratio = when {
            useLargePanelPct(screenLong) ->
                if (landscape) PANEL_HEIGHT_PCT_LANDSCAPE_LARGE else PANEL_HEIGHT_PCT_PORTRAIT_LARGE
            landscape -> maxOf(defaultRatio, PANEL_HEIGHT_PCT_LANDSCAPE_SMALL)
            else -> defaultRatio
        }
        return (screenH * ratio).toInt()
    }
}
