package com.supernote_quicktoolbar.ui_common

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.supernote_quicktoolbar.FloatingToolbarModule
import com.supernote_quicktoolbar.overlays.TouchSinkLayout
import kotlin.math.roundToInt

abstract class PanelBase(
    protected val reactContext: ReactApplicationContext,
    protected val toolbarModule: FloatingToolbarModule
) {

    abstract val tag: String
    abstract val panelName: String
    open fun onHide() {}

    open fun buildContent(root: LinearLayout) {}

    open val fullScreen: Boolean = false
    open fun buildFullScreenContent(): View? = null

    open val widthRatio: Double = 0.65
    open val heightRatio: Double = 0.72

    open val windowFlags: Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    protected val handler = Handler(Looper.getMainLooper())
    protected var windowManager: WindowManager? = null
    protected var rootView: View? = null

    val isShowing: Boolean get() = rootView != null

    protected val density get() = reactContext.resources.displayMetrics.density
    protected val screenW get() = reactContext.resources.displayMetrics.widthPixels
    protected val screenH get() = reactContext.resources.displayMetrics.heightPixels
    protected val winW get() = (screenW * widthRatio).toInt()
    protected val winH get() = (screenH * heightRatio).toInt()
    protected fun dp(v: Int) = (v * density).roundToInt()
    protected fun dp(v: Float) = (v * density).roundToInt()

    protected fun showPanel() {
        handler.post {
            if (rootView != null) return@post
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(reactContext)) {
                Log.e(tag, "no overlay permission")
                return@post
            }

            toolbarModule.enablePenBlock()
            windowManager = reactContext.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager

            val wmType = if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            if (fullScreen) {
                val root = buildFullScreenContent() ?: return@post
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    wmType,
                    windowFlags or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.START }

                rootView = root
                try {
                    windowManager?.addView(root, lp)
                    Log.i(tag, "full-screen panel shown")
                } catch (e: Exception) {
                    Log.e(tag, "addView failed: ${e.message}", e)
                    rootView = null; windowManager = null
                }
            } else {
                val root = TouchSinkLayout(reactContext).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        setStroke(dp(1), Color.BLACK)
                        cornerRadius = dp(12).toFloat()
                    }
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) {
                            o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat())
                        }
                    }
                }

                buildContent(root)

                val lp = WindowManager.LayoutParams(
                    winW, winH, wmType,
                    windowFlags,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.CENTER }

                rootView = root
                windowManager?.addView(root, lp)
                Log.i(tag, "panel shown ${winW}x$winH")
            }
        }
    }

    open fun hide() {
        handler.post {
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            rootView = null
            windowManager = null
            onHide()
            toolbarModule.disablePenBlock()
            emitCloseEvent()
        }
    }

    protected open fun emitCloseEvent() {
        toolbarModule.emitEventPublic(
            "onNativePanelClose",
            Arguments.createMap().apply { putString("panel", panelName) }
        )
    }

    open fun suspendVisibility() {
        handler.post { rootView?.visibility = View.GONE }
    }

    open fun resumeVisibility() {
        handler.post { rootView?.visibility = View.VISIBLE }
    }

    protected fun makeDivider(dark: Boolean = true): View {
        return View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(if (dark) Color.BLACK else Color.parseColor("#D8D8D8"))
        }
    }

    protected fun makeEmptyView(text: String): LinearLayout {
        return LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(60), dp(30), 0)
            addView(android.widget.TextView(reactContext).apply {
                this.text = text; textSize = 14f
                setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER
            })
        }
    }

    protected fun makeEmptyView(title: String, hint: String): LinearLayout {
        return LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(60), dp(20), 0)
            addView(android.widget.TextView(reactContext).apply {
                text = title; textSize = 15f
                setTextColor(Color.parseColor("#999999"))
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(android.widget.TextView(reactContext).apply {
                text = hint; textSize = 12f
                setTextColor(Color.parseColor("#BBBBBB"))
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(8), dp(20), 0)
            })
        }
    }

    protected fun makeOutlinedBtn(label: String, onClick: () -> Unit): android.widget.TextView {
        return android.widget.TextView(reactContext).apply {
            text = label; textSize = 17f; setTextColor(Color.BLACK)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(106); minHeight = dp(48)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(dp(1), Color.BLACK)
                cornerRadius = dp(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(28) }
            setOnClickListener { onClick() }
        }
    }

    protected fun makeFilledBtn(label: String, onClick: () -> Unit): android.widget.TextView {
        return android.widget.TextView(reactContext).apply {
            text = label; textSize = 17f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(106); minHeight = dp(48)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    protected fun formatSize(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${"%.1f".format(size / 1024.0)} KB"
        else -> "${"%.1f".format(size / (1024.0 * 1024.0))} MB"
    }

    protected fun makeBottomBar(
        leftButtons: List<View> = emptyList(),
        leftFlex: View? = null,
        rightButtons: List<View>
    ): LinearLayout {
        val wrapper = LinearLayout(reactContext).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(makeDivider(dark = true))
        val bar = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(28), dp(28), dp(28))
        }
        for (btn in leftButtons) bar.addView(btn)
        if (leftFlex != null) {
            bar.addView(leftFlex)
        } else {
            bar.addView(View(reactContext).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
        }
        for (btn in rightButtons) bar.addView(btn)
        wrapper.addView(bar)
        return wrapper
    }
}
