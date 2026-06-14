package com.supernote_quicktoolbar.panels
import com.supernote_quicktoolbar.BuildConfig

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.FloatingToolbarModule
import com.supernote_quicktoolbar.ui_common.UiUtils

object ScreenshotBubble {

    private const val TAG = "ScreenshotBubble"

    private val CLR_BG     = Color.WHITE
    private val CLR_BORDER = Color.parseColor("#111111")

    @Volatile private var wm: WindowManager? = null
    @Volatile private var btnView: View? = null
    @Volatile private var lp: WindowManager.LayoutParams? = null
    @Volatile private var ctx: ReactApplicationContext? = null
    @Volatile private var toolbar: FloatingToolbarModule? = null

    @Volatile var pendingReshow = false

    @Volatile var hiddenBySettings = false

    private val handler = Handler(Looper.getMainLooper())
    private var startRawX = 0f
    private var startRawY = 0f
    private var startX = 0
    private var startY = 0
    private var isDragging = false
    private var longPressTriggered = false
    private val longPressTimeout = 500L
    private val longPressRunnable = Runnable { onLongPress() }

    private fun density(): Float = ctx?.resources?.displayMetrics?.density ?: 2f
    private fun scale(): Float = ctx?.let { com.supernote_quicktoolbar.ui_common.ScreenScale.factor(it) } ?: 1f
    private fun dp(v: Int): Int = (v * density() * scale()).toInt()

    fun show(context: ReactApplicationContext, toolbarModule: FloatingToolbarModule) {
        handler.post {
            pendingReshow = false
            hiddenBySettings = false
            btnView?.let { v ->
                try { wm?.removeView(v) } catch (_: Exception) {}
                btnView = null; lp = null
            }
            ctx = context
            toolbar = toolbarModule

            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "no overlay permission"); return@post
            }

            val d = context.resources.displayMetrics.density * scale()
            val borderW = (2f * d).toInt()
            val iconSize = (30 * d).toInt()
            val pad = (14 * d).toInt()
            val totalSize = iconSize + pad * 2 + borderW * 2
            val radius = totalSize / 2f

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(CLR_BG)
                    setStroke(borderW, CLR_BORDER)
                    cornerRadius = radius
                }
            }

            val icon = UiUtils.loadAssetIcon(context, "icons/ic_tool_camera.xml", iconSize, CLR_BORDER)
            if (icon != null) {
                val iv = ImageView(context).apply {
                    setImageDrawable(icon)
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                }
                container.addView(iv)
            }

            btnView = container

            @Suppress("DEPRECATION")
            val wmType = WindowManager.LayoutParams.TYPE_PHONE

            lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                wmType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(20)
                y = dp(120)
            }

            container.setOnTouchListener { _, event ->
                val params = lp ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX; startRawY = event.rawY
                        startX = params.x; startY = params.y
                        isDragging = false
                        longPressTriggered = false
                        handler.postDelayed(longPressRunnable, longPressTimeout)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startRawX
                        val dy = event.rawY - startRawY
                        if (!isDragging && (dx * dx + dy * dy) > dp(8) * dp(8)) {
                            isDragging = true
                            handler.removeCallbacks(longPressRunnable)
                        }
                        if (isDragging) {
                            params.x = startX - dx.toInt()
                            params.y = startY + dy.toInt()
                            try { wm?.updateViewLayout(btnView, params) } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPressRunnable)
                        if (!isDragging && !longPressTriggered) onTap()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPressRunnable)
                        true
                    }
                    else -> false
                }
            }

            wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            try {
                wm?.addView(container, lp)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "shown")
                toolbarModule.hide()
                toolbarModule.disablePenBlock()
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "addView failed: ${e.message}")
            }
        }
    }

    fun hide() {
        hiddenBySettings = false
        handler.post {
            val v = btnView ?: return@post
            try { wm?.removeView(v) } catch (_: Exception) {}
            btnView = null; lp = null
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "hidden")
        }
    }

    fun hideForSettings() {
        if (btnView == null) return
        hiddenBySettings = true
        handler.post {
            val v = btnView ?: return@post
            try { wm?.removeView(v) } catch (_: Exception) {}
            btnView = null; lp = null
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "hidden (settings)")
        }
    }

    fun reshowIfHiddenBySettings() {
        if (!hiddenBySettings) return
        hiddenBySettings = false
        val c = ctx ?: return
        val t = toolbar ?: return
        show(c, t)
    }

    fun reshowIfPending() {
        if (!pendingReshow) return
        val c = ctx ?: run { pendingReshow = false; return }
        val t = toolbar ?: run { pendingReshow = false; return }
        show(c, t)
    }

    val isShowing: Boolean get() = btnView != null

    private fun onLongPress() {
        if (isDragging) return
        longPressTriggered = true
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "long press → switching to last opened note")
        hide()
        toolbar?.restoreToolbar()
        try {
            val c = ctx ?: return
            val intent = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(
                    "com.ratta.supernote.note",
                    "com.ratta.supernote.note.view.NoteInsidePagesActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            c.startActivity(intent)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "launch Note app failed: ${e.message}", e)
        }
    }

    private fun onTap() {
        val tb = toolbar ?: return

        if (FloatingToolbarModule.isInNoteApp()) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "tapped (in note) → inserting staged screenshot")
            pendingReshow = false
            btnView?.let { v ->
                try { wm?.removeView(v) } catch (_: Exception) {}
                btnView = null; lp = null
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "hidden")
            }
            handler.postDelayed({ tb.handleDocScreenshot() }, 150)
            return
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "tapped → starting screencap")
        pendingReshow = true
        btnView?.let { v ->
            try { wm?.removeView(v) } catch (_: Exception) {}
            btnView = null; lp = null
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "hidden")
        }
        handler.postDelayed({ tb.handleDocScreenshotCrop() }, 150)
    }
}
