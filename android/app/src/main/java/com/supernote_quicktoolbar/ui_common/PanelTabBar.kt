package com.supernote_quicktoolbar.ui_common

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.facebook.react.bridge.ReactApplicationContext
import kotlin.math.roundToInt

class PanelTabBar(
    private val ctx: ReactApplicationContext,
    private val tabs: List<Tab>,
    private val sideMarginDp: Int = 44,
    private val onSelect: (index: Int) -> Unit
) {
    sealed class Tab {
        data class Icon(val assetPath: String, val contentDesc: String) : Tab()
    }

    private val density = ctx.resources.displayMetrics.density
    private val scale = ScreenScale.factor(ctx)
    private fun dp(v: Int) = (v * density * scale).roundToInt()

    private var selected: Int = 0
    private val iconViews = mutableListOf<ImageView?>()
    private val indicators = mutableListOf<View>()

    fun setSelection(i: Int) {
        selected = i.coerceIn(0, tabs.size - 1)
        if (indicators.isNotEmpty()) updateStyles()
    }

    fun getSelection(): Int = selected

    fun createView(): View {
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val barHeight = dp(60)
        val iconSize = dp(32)
        val sideMargin = dp(sideMarginDp)

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, barHeight
            ).apply {
                leftMargin = sideMargin
                rightMargin = sideMargin
            }
        }

        iconViews.clear(); indicators.clear()

        for ((idx, tab) in tabs.withIndex()) {
            if (idx > 0) {
                bar.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                        topMargin = dp(18); bottomMargin = dp(18)
                    }
                    setBackgroundColor(Color.BLACK)
                })
            }

            val frame = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    if (selected != idx) {
                        selected = idx
                        updateStyles()
                        onSelect(idx)
                    }
                }
            }

            val icon = when (tab) {
                is Tab.Icon -> ImageView(ctx).apply {
                    val d = UiUtils.loadAssetIcon(ctx, tab.assetPath, iconSize, Color.BLACK)
                    if (d != null) setImageDrawable(d)
                    contentDescription = tab.contentDesc
                    layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
                }
            }
            frame.addView(icon)

            val indicator = View(ctx).apply {
                layoutParams = (FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM
                )).also { it.bottomMargin = 1 }
            }
            frame.addView(indicator)

            iconViews.add(icon)
            indicators.add(indicator)
            bar.addView(frame)
        }

        wrapper.addView(bar)
        wrapper.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply {
                leftMargin = sideMargin
                rightMargin = sideMargin
            }
            setBackgroundColor(Color.BLACK)
        })
        updateStyles()
        return wrapper
    }

    private fun updateStyles() {
        for (i in tabs.indices) {
            val active = i == selected
            iconViews.getOrNull(i)?.alpha = if (active) 1f else 0.4f
            indicators.getOrNull(i)?.setBackgroundColor(
                if (active) Color.BLACK else Color.TRANSPARENT
            )
        }
    }
}
