package com.supernote_quicktoolbar.panels

import com.supernote_quicktoolbar.TouchInput
import com.supernote_quicktoolbar.FloatingPenGuard
import com.supernote_quicktoolbar.BuildConfig

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.FloatingToolbarModule
import com.supernote_quicktoolbar.bubbles.BubbleWindowSupport
import com.supernote_quicktoolbar.ui_common.UiUtils

object ScreenshotBubble {

    private const val TAG = "ScreenshotBubble"
    private const val PRESS_GUARD_OWNER = "screenshot-bubble-press"
    private const val PRESS_GUARD_RELEASE_DELAY_MS = 600L
    private const val PREFS_NAME = "quicktoolbar_presets"
    private const val PREF_POSITION_X = "screenshot_bubble_x"
    private const val PREF_POSITION_Y = "screenshot_bubble_y"

    private val CLR_BG     = Color.WHITE
    private val CLR_BORDER = Color.parseColor("#111111")

    @Volatile private var wm: WindowManager? = null
    @Volatile private var btnView: View? = null
    @Volatile private var lp: WindowManager.LayoutParams? = null
    @Volatile private var ctx: ReactApplicationContext? = null
    @Volatile private var toolbar: FloatingToolbarModule? = null

    @Volatile var pendingReshow = false

    @Volatile var hiddenBySettings = false
    @Volatile var hiddenByInactiveCanvas = false

    private val handler = Handler(Looper.getMainLooper())
    private var startRawX = 0f
    private var startRawY = 0f
    private var startX = 0
    private var startY = 0
    private var isDragging = false
    private var longPressTriggered = false
    private var pressGuardClaimed = false
    private val longPressTimeout = 500L
    private val longPressRunnable = Runnable { onLongPress() }
    private val releasePressGuardRunnable = Runnable {
        FloatingPenGuard.setFullScreenActive(false, PRESS_GUARD_OWNER)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "press guard released")
    }

    private fun density(): Float = ctx?.resources?.displayMetrics?.density ?: 2f
    private fun scale(): Float = ctx?.let { com.supernote_quicktoolbar.ui_common.ScreenScale.factor(it) } ?: 1f
    private fun dp(v: Int): Int = (v * density() * scale()).toInt()

    private fun detachView() {
        val v = btnView ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        btnView = null; lp = null
    }

    fun show(context: ReactApplicationContext, toolbarModule: FloatingToolbarModule) {
        handler.post {
            pendingReshow = false
            hiddenBySettings = false
            hiddenByInactiveCanvas = false
            detachView()
            ctx = context
            toolbar = toolbarModule

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

            lp = BubbleWindowSupport.overlayParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                val prefs = context.getSharedPreferences(PREFS_NAME, 0)
                val defaultX = dp(20)
                val defaultY = dp(120)
                val maxX = (context.resources.displayMetrics.widthPixels - totalSize).coerceAtLeast(0)
                val maxY = (context.resources.displayMetrics.heightPixels - totalSize).coerceAtLeast(0)
                x = prefs.getInt(PREF_POSITION_X, defaultX).coerceIn(0, maxX)
                y = prefs.getInt(PREF_POSITION_Y, defaultY).coerceIn(0, maxY)
            }

            container.setOnTouchListener { _, event ->
                val params = lp ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX; startRawY = event.rawY
                        startX = params.x; startY = params.y
                        isDragging = false
                        longPressTriggered = false
                        if (!TouchInput.isFinger(event)) claimPressGuard()
                        handler.postDelayed(longPressRunnable, longPressTimeout)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!TouchInput.isFinger(event)) return@setOnTouchListener true
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
                        if (isDragging) savePosition(params.x, params.y)
                        if (longPressTriggered) {
                            finishLongPressGesture()
                        } else {
                            if (!isDragging) onTap()
                            releasePressGuardAfterGesture()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPressRunnable)
                        if (isDragging) savePosition(params.x, params.y)
                        if (longPressTriggered) finishLongPressGesture() else releasePressGuardAfterGesture()
                        true
                    }
                    else -> false
                }
            }

            wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            try {
                wm?.addView(container, lp)
                FloatingPenGuard.track("screenshot_bubble", container)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "shown")
                toolbarModule.hide()
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "addView failed: ${e.message}")
            }
        }
    }

    fun hide() {
        hiddenBySettings = false
        hiddenByInactiveCanvas = false
        handler.post {
            detachView()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "hidden")
        }
    }

    fun hideForInactiveCanvas() {
        if (btnView == null) return
        hiddenByInactiveCanvas = true
        handler.post {
            detachView()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "hidden (inactive canvas)")
        }
    }

    fun hideForSettings() {
        if (btnView == null) return
        hiddenBySettings = true
        handler.post {
            detachView()
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

    fun reshowIfHiddenByInactiveCanvas() {
        if (!hiddenByInactiveCanvas) return
        hiddenByInactiveCanvas = false
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
        claimPressGuard()
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "long press → switching to last opened note")
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

    private fun finishLongPressGesture() {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "long press gesture finished; hiding after pen up")
        hide()
        releasePressGuardAfterGesture()
    }

    private fun claimPressGuard() {
        handler.removeCallbacks(releasePressGuardRunnable)
        pressGuardClaimed = true
        FloatingPenGuard.setFullScreenActive(true, PRESS_GUARD_OWNER)
    }

    private fun releasePressGuardAfterGesture() {
        if (!pressGuardClaimed) return
        pressGuardClaimed = false
        handler.removeCallbacks(releasePressGuardRunnable)
        handler.postDelayed(releasePressGuardRunnable, PRESS_GUARD_RELEASE_DELAY_MS)
    }

    private fun savePosition(x: Int, y: Int) {
        val context = ctx ?: return
        context.getSharedPreferences(PREFS_NAME, 0)
            .edit()
            .putInt(PREF_POSITION_X, x)
            .putInt(PREF_POSITION_Y, y)
            .apply()
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "position saved: x=$x y=$y")
    }

    private fun onTap() {
        val tb = toolbar ?: return

        if (FloatingToolbarModule.isInNoteApp()) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "tapped (in note) → inserting staged screenshot")
            pendingReshow = false
            detachView()
            handler.postDelayed({ tb.handleDocScreenshot() }, 150)
            return
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "tapped → starting screencap")
        pendingReshow = true
        detachView()
        handler.postDelayed({ tb.handleDocScreenshotCrop() }, 150)
    }
}
