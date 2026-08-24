package com.supernote_quicktoolbar.ui_common

import com.supernote_quicktoolbar.FloatingPenGuard
import com.supernote_quicktoolbar.BuildConfig

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.supernote_quicktoolbar.overlays.TouchSinkLayout
import kotlin.math.roundToInt

abstract class PanelBase(
    protected val reactContext: ReactApplicationContext,
    protected val toolbarModule: ToolbarHost
) {
    abstract val tag: String
    abstract val panelName: String
    open fun onHide() {}

    open fun buildContent(root: LinearLayout) {}

    open val fullScreen: Boolean = false
    open fun buildFullScreenContent(): View? = null

    open val heightRatio: Double = 0.81

    open val cornerRadiusDp: Int = 12

    open val windowFlags: Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    open val forcedOrientation: Int? = null

    open val closeOnRotation: Boolean get() = !fullScreen

    open fun onRotation(): Boolean {
        if (isShowing && closeOnRotation) { hide(); return true }
        return false
    }

    protected val handler = Handler(Looper.getMainLooper())
    protected var windowManager: WindowManager? = null
    // Setter mirrors membership into PanelRegistry so the live set exactly
    // tracks isShowing, including subclasses that null rootView themselves.
    protected var rootView: View? = null
        set(value) {
            field = value
            if (value != null) PanelRegistry.onShown(this) else PanelRegistry.onHidden(this)
        }
    protected var layoutParams: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = rootView != null

    protected val density get() = reactContext.resources.displayMetrics.density
    protected val screenW get() = toolbarModule.screenW
    protected val screenH get() = toolbarModule.screenH
    protected val isLandscape get() = screenW > screenH
    protected val screenLong get() = maxOf(screenW, screenH)
    protected val winW: Int get() = ScreenScale.panelWidth(density, screenW, screenH, screenLong)
    protected val winH: Int get() = ScreenScale.panelHeight(screenH, screenW, screenLong, heightRatio)
    protected fun dp(v: Int) = ScreenScale.dp(reactContext, v)
    protected fun dp(v: Float) = ScreenScale.dp(reactContext, v)
    protected fun sp(v: Float) = ScreenScale.sp(reactContext, v)

    protected fun showPanel(onResult: ((Boolean) -> Unit)? = null) {
        handler.post {
            if (rootView != null) {
                onResult?.invoke(true)
                return@post
            }
            fun failShow(error: Throwable? = null) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(tag, "show panel failed: ${error?.message}", error)
                FloatingPenGuard.setFullScreenActive(false, "panel:$panelName")
                rootView = null
                windowManager = null
                layoutParams = null
                onHide()
                onResult?.invoke(false)
            }

            try {
                toolbarModule.refreshScreenDimensions()
                FloatingPenGuard.setFullScreenActive(true, "panel:$panelName")
                windowManager = reactContext.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager

                @Suppress("DEPRECATION")
                val wmType = WindowManager.LayoutParams.TYPE_PHONE

                if (fullScreen) {
                    val root = buildFullScreenContent() ?: run {
                        failShow()
                        return@post
                    }
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        wmType,
                        windowFlags or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    ).apply { gravity = Gravity.TOP or Gravity.START }

                    forcedOrientation?.let { lp.screenOrientation = it }
                    layoutParams = lp
                    rootView = root
                    windowManager?.addView(root, lp)
                    FloatingPenGuard.track("panel:$panelName", root)
                    if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "full-screen panel shown")
                } else {
                    val cr = dp(cornerRadiusDp).toFloat()
                    val card = LinearLayout(reactContext).apply {
                        orientation = LinearLayout.VERTICAL
                        background = GradientDrawable().apply {
                            setColor(Color.WHITE)
                            setStroke(dp(1), Color.BLACK)
                            cornerRadius = cr
                        }
                        if (cr > 0f) {
                            clipToOutline = true
                            outlineProvider = object : ViewOutlineProvider() {
                                override fun getOutline(v: View, o: android.graphics.Outline) {
                                    o.setRoundRect(0, 0, v.width, v.height, cr)
                                }
                            }
                        }
                    }

                    buildContent(card)

                    val scrim = TouchSinkLayout(reactContext).apply {
                        gravity = Gravity.CENTER
                    }
                    scrim.addView(card, LinearLayout.LayoutParams(winW, winH))

                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        wmType,
                        windowFlags or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    ).apply { gravity = Gravity.TOP or Gravity.START }

                    forcedOrientation?.let { lp.screenOrientation = it }
                    layoutParams = lp
                    rootView = scrim
                    windowManager?.addView(scrim, lp)
                    if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "panel shown (full-screen scrim) card=${winW}x$winH screen=${screenW}x${screenH} landscape=$isLandscape")
                }
                onResult?.invoke(true)
            } catch (e: Exception) {
                failShow(e)
            } catch (e: OutOfMemoryError) {
                failShow(e)
            }
        }
    }

    open fun hide() = teardown(releasePen = true)

    protected fun dismissWithoutPenRelease() = teardown(releasePen = false)

    protected fun releasePenGuardOwner() =
        FloatingPenGuard.setFullScreenActive(false, "panel:$panelName")

    private fun teardown(releasePen: Boolean) {
        handler.post {
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            if (releasePen) FloatingPenGuard.setFullScreenActive(false, "panel:$panelName")
            rootView = null
            windowManager = null
            layoutParams = null
            onHide()
            runDslDisposers()
            emitCloseEvent()
        }
    }

    protected fun applyOrientation(orientation: Int) {
        handler.post {
            val lp = layoutParams ?: return@post
            if (lp.screenOrientation == orientation) return@post
            lp.screenOrientation = orientation
            try { windowManager?.updateViewLayout(rootView, lp) } catch (_: Exception) {}
        }
    }

    protected open fun emitCloseEvent() {
        toolbarModule.emitEvent(
            "onNativePanelClose",
            Arguments.createMap().apply { putString("panel", panelName) }
        )
    }

    open fun suspendVisibility() {
        handler.post {
            rootView?.visibility = View.GONE
            FloatingPenGuard.setFullScreenActive(false, "panel:$panelName")
        }
    }

    open fun resumeVisibility() {
        handler.post {
            rootView?.visibility = View.VISIBLE
            FloatingPenGuard.setFullScreenActive(true, "panel:$panelName")
            rootView?.let { FloatingPenGuard.refresh("panel:$panelName", it) }
        }
    }

    protected fun makeDivider(dark: Boolean = true): View = PanelWidgets.divider(dslHost, dark)

    protected fun makeEmptyView(text: String): LinearLayout = PanelWidgets.emptyView(dslHost, text)

    protected fun makeEmptyView(title: String, hint: String): LinearLayout =
        PanelWidgets.emptyView(dslHost, title, hint)

    protected fun makeOutlinedBtn(label: String, onClick: () -> Unit): android.widget.TextView =
        PanelWidgets.outlinedButton(dslHost, label, onClick)

    protected fun makeFilledBtn(label: String, onClick: () -> Unit): android.widget.TextView =
        PanelWidgets.filledButton(dslHost, label, onClick)

    protected fun formatSize(size: Long): String = PanelWidgets.formatSize(size)

    private val dslDisposers = mutableListOf<() -> Unit>()

    protected val dslHost: PanelHost = object : PanelHost {
        override val ctx: ReactApplicationContext get() = reactContext
        override fun dp(v: Int): Int = this@PanelBase.dp(v)
        override fun dp(v: Float): Int = this@PanelBase.dp(v)
        override fun sp(v: Float): Float = this@PanelBase.sp(v)
        override fun gridSp(v: Float): Float = ScreenScale.gridSp(reactContext, v)
        override fun close() { hide(); toolbarModule.restoreToolbar() }
        override fun onDispose(block: () -> Unit) { dslDisposers.add(block) }
        override val panelW: Int get() = winW
        override val screenW: Int get() = this@PanelBase.screenW
        override val screenH: Int get() = this@PanelBase.screenH
        override fun emitEvent(name: String, data: com.facebook.react.bridge.WritableMap) {
            toolbarModule.emitEvent(name, data)
        }
    }

    protected fun renderDsl(root: LinearLayout, build: PanelScope.() -> Unit) {
        val scope = PanelScope().apply(build)
        scope.components.forEach { root.addView(it.build(dslHost)) }
    }

    private fun runDslDisposers() {
        dslDisposers.forEach { runCatching { it() } }
        dslDisposers.clear()
    }

    protected fun makeBottomBar(
        leftButtons: List<View> = emptyList(),
        leftFlex: View? = null,
        rightButtons: List<View>
    ): LinearLayout = PanelWidgets.bottomBar(dslHost, leftButtons, leftFlex, rightButtons)
}
