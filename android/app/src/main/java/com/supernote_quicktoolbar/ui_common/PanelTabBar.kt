package com.supernote_quicktoolbar.ui_common

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.ReactApplicationContext
import kotlin.math.roundToInt

 }
 *   tabs.setSelection(0)
 *   root.addView(tabs.createView())
 */
class PanelTabBar(
    private val ctx: ReactApplicationContext,
    private val tabs: List<Tab>,
    private val sideMarginDp: Int = 26,
    private val onSelect: (index: Int) -> Unit
) {
    sealed class Tab {
        data class Icon(val assetPath: String, val contentDesc: String) : Tab()
        data class Text(val label: String) : Tab()
    }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private var selected: Int = 0
    private val iconViews = mutableListOf<ImageView?>()
    private val textViews = mutableListOf<TextView?>()
    private val indicators = mutableListOf<View>()

    fun setSelection(i: Int) {
        selected = i.coerceIn(0, tabs.size - 1)
        if (indicators.isNotEmpty()) updateStyles()
    }

    fun getSelection(): Int = selected

    fun createView(): View {
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val barHeight = dp(76)
        val iconSize = dp(34)
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

        iconViews.clear(); textViews.clear(); indicators.clear()

        for ((idx, tab) in tabs.withIndex()) {
            if (idx > 0) {
                bar.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                        topMargin = dp(31); bottomMargin = dp(31)
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

            var icon: ImageView? = null
            var text: TextView? = null
            when (tab) {
                is Tab.Icon -> {
                    icon = ImageView(ctx).apply {
                        val d = UiUtils.loadAssetIcon(ctx, tab.assetPath, iconSize, Color.BLACK)
                        if (d != null) setImageDrawable(d)
                        contentDescription = tab.contentDesc
                        layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
                    }
                    frame.addView(icon)
                }
                is Tab.Text -> {
                    text = TextView(ctx).apply {
                        this.text = tab.label
                        textSize = 14f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                    }
                    frame.addView(text)
                }
            }

            val indicator = View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM
                )
            }
            frame.addView(indicator)

            iconViews.add(icon)
            textViews.add(text)
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
            textViews.getOrNull(i)?.setTextColor(
                if (active) Color.BLACK else Color.parseColor("#AAAAAA")
            )
            indicators.getOrNull(i)?.setBackgroundColor(
                if (active) Color.BLACK else Color.TRANSPARENT
            )
        }
    }
}
