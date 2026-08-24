package com.supernote_quicktoolbar.ui_common

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.ReactApplicationContext

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

    fun divider(host: PanelHost, dark: Boolean = true): View =
        View(host.ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(if (dark) Color.BLACK else Color.parseColor("#D8D8D8"))
        }

    fun emptyView(host: PanelHost, text: String): LinearLayout =
        LinearLayout(host.ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(host.dp(30), host.dp(60), host.dp(30), 0)
            addView(TextView(host.ctx).apply {
                this.text = text; textSize = host.sp(14f)
                setTextColor(Color.parseColor("#999999")); gravity = Gravity.CENTER
            })
        }

    fun emptyView(host: PanelHost, title: String, hint: String): LinearLayout =
        LinearLayout(host.ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(host.dp(20), host.dp(60), host.dp(20), 0)
            addView(TextView(host.ctx).apply {
                text = title; textSize = host.sp(15f)
                setTextColor(Color.parseColor("#999999"))
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(TextView(host.ctx).apply {
                text = hint; textSize = host.sp(12f)
                setTextColor(Color.parseColor("#BBBBBB"))
                gravity = Gravity.CENTER
                setPadding(host.dp(20), host.dp(8), host.dp(20), 0)
            })
        }

    fun bottomBar(
        host: PanelHost,
        leftButtons: List<View> = emptyList(),
        leftFlex: View? = null,
        rightButtons: List<View>
    ): LinearLayout {
        val wrapper = LinearLayout(host.ctx).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(divider(host))
        val bar = LinearLayout(host.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(28), host.dp(28), host.dp(28), host.dp(28))
        }
        for (btn in leftButtons) bar.addView(btn)
        if (leftFlex != null) {
            bar.addView(leftFlex)
        } else {
            bar.addView(View(host.ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
        }
        for (btn in rightButtons) bar.addView(btn)
        wrapper.addView(bar)
        return wrapper
    }

    fun formatSize(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${"%.1f".format(size / 1024.0)} KB"
        else -> "${"%.1f".format(size / (1024.0 * 1024.0))} MB"
    }
}

class PanelChips(
    private val ctx: ReactApplicationContext,
    private val keys: List<String>,
    private val onSelect: (selected: String?) -> Unit
) {
    private val scale = ScreenScale.factor(ctx)
    private fun dp(v: Int) = ScreenScale.dp(ctx, v)

    private var container: LinearLayout? = null
    private var selected: String? = null

    fun setSelection(key: String?) {
        selected = key
    }

    fun getSelection(): String? = selected

    fun createView(): LinearLayout {
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val row = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), dp(20), dp(26), dp(6))
        }
        rebuildChips()
        row.addView(container)
        wrapper.addView(row)
        return wrapper
    }

    fun rebuildChips() {
        val c = container ?: return
        c.removeAllViews()
        (c.parent as? HorizontalScrollView)?.scrollTo(0, 0)
        for ((index, key) in keys.withIndex()) {
            val isActive = selected == key
            val chip = TextView(ctx).apply {
                text = key
                textSize = 14f * scale
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (isActive) Color.WHITE else Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    setColor(if (isActive) Color.BLACK else Color.WHITE)
                    setStroke(2, Color.BLACK)
                    cornerRadius = dp(53).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) leftMargin = dp(20) }
                setOnClickListener {
                    if (selected == key) return@setOnClickListener
                    selected = key
                    rebuildChips()
                    onSelect(selected)
                }
            }
            c.addView(chip)
        }
    }
}

class PanelCheckbox(
    private val ctx: ReactApplicationContext,
    label: String,
    onClick: () -> Unit
) {
    private fun dp(v: Int) = ScreenScale.dp(ctx, v)
    private fun sp(v: Float) = ScreenScale.sp(ctx, v)

    private val gray = Color.parseColor("#999999")

    private val box: View
    private val mark: TextView
    private val labelView: TextView
    val view: LinearLayout

    init {
        view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            alpha = 0.4f
            setOnClickListener { onClick() }
        }

        val checkSize = dp(28)
        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(checkSize, checkSize).apply { rightMargin = dp(10) }
        }
        box = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        frame.addView(box)
        mark = TextView(ctx).apply {
            text = "✓"; textSize = sp(17f); setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        frame.addView(mark)
        view.addView(frame)

        labelView = TextView(ctx).apply {
            text = label; textSize = sp(17f); setTextColor(gray)
        }
        view.addView(labelView)

        setChecked(false)
    }

    fun setChecked(checked: Boolean) {
        if (checked) {
            box.background = GradientDrawable().apply {
                setColor(Color.BLACK); cornerRadius = dp(3).toFloat()
            }
            mark.visibility = View.VISIBLE
            labelView.setTextColor(Color.BLACK)
        } else {
            box.background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(dp(2), gray); cornerRadius = dp(3).toFloat()
            }
            mark.visibility = View.GONE
            labelView.setTextColor(gray)
        }
    }

    fun setLabel(text: String) {
        labelView.text = text
    }

    fun setActive(active: Boolean) {
        view.alpha = if (active) 1f else 0.4f
    }
}

class MultiSelectState {
    var isActive = false
        private set

    private val paths = linkedSetOf<String>()

    val count: Int get() = paths.size
    val selectedPaths: List<String> get() = paths.toList()

    fun activate(seed: String? = null) {
        isActive = true
        seed?.let { paths.add(it) }
    }

    fun deactivate() {
        isActive = false
        paths.clear()
    }

    fun toggle(path: String): Boolean =
        if (path in paths) {
            paths.remove(path); false
        } else {
            paths.add(path); true
        }

    fun isSelected(path: String): Boolean = path in paths

    fun clear() {
        isActive = false
        paths.clear()
    }
}
