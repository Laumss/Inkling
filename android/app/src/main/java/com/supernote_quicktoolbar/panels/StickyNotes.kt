package com.supernote_quicktoolbar.panels

import com.supernote_quicktoolbar.TouchInput
import com.supernote_quicktoolbar.FloatingPenGuard
import com.supernote_quicktoolbar.BuildConfig

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.FloatingToolbarModule

object StickyNotes {

    private const val TAG = "StickyNotes"
    const val MAX_NOTES = 5

    private val CLR_BG = 0xD0FFFFFF.toInt()
    private val CLR_BORDER = Color.parseColor("#111111")

    private class Note(
        val view: FrameLayout,
        val lp: WindowManager.LayoutParams,
        val guardKey: String,
        val path: String
    )

    private val notes = mutableListOf<Note>()
    @Volatile private var wm: WindowManager? = null
    @Volatile private var ctx: ReactApplicationContext? = null
    @Volatile private var toolbar: FloatingToolbarModule? = null

    @Volatile var hiddenTemp = false
        private set

    private val handler = Handler(Looper.getMainLooper())

    private fun density(): Float = ctx?.resources?.displayMetrics?.density ?: 2f
    private fun scale(): Float = ctx?.let { com.supernote_quicktoolbar.ui_common.ScreenScale.factor(it) } ?: 1f
    private fun dp(v: Int): Int = (v * density() * scale()).toInt()

    val count: Int get() = notes.size
    val isFull: Boolean get() = notes.size >= MAX_NOTES

    fun add(context: ReactApplicationContext, toolbarModule: FloatingToolbarModule, path: String, onResult: (Boolean) -> Unit = {}) {
        handler.post { onResult(addInternal(context, toolbarModule, path)) }
    }

    private fun addInternal(context: ReactApplicationContext, toolbarModule: FloatingToolbarModule, path: String): Boolean {
        if (notes.size >= MAX_NOTES) return false
        ctx = context
        toolbar = toolbarModule
        if (wm == null) wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "decode bounds failed: $path"); return false
        }

        val dm = context.resources.displayMetrics
        val screenLong = maxOf(dm.widthPixels, dm.heightPixels)
        val imgLong = maxOf(bounds.outWidth, bounds.outHeight)
        val targetLong = screenLong * 2 / 5
        val scaleF = if (imgLong > targetLong) targetLong.toFloat() / imgLong else 1f
        val imgW = (bounds.outWidth * scaleF).toInt().coerceAtLeast(1)
        val imgH = (bounds.outHeight * scaleF).toInt().coerceAtLeast(1)

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= imgW && bounds.outHeight / (sample * 2) >= imgH) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: run { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "decode failed: $path"); return false }

        val d = density() * scale()
        val borderW = (2f * d).toInt()
        val pad = borderW

        val container = FrameLayout(context).apply {
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(CLR_BG)
                setStroke(borderW, CLR_BORDER)
            }
        }
        val iv = ImageView(context).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(imgW, imgH)
        }
        container.addView(iv)

        @Suppress("DEPRECATION")
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val totalW = imgW + pad * 2
            val offset = notes.size * dp(28)
            x = ((dm.widthPixels - totalW) / 2 + offset).coerceIn(0, (dm.widthPixels - totalW).coerceAtLeast(0))
            y = (dm.heightPixels / 4 + offset).coerceIn(0, (dm.heightPixels - imgH).coerceAtLeast(0))
        }

        val guardKey = "sticky_note_${System.currentTimeMillis()}"
        var startRawX = 0f; var startRawY = 0f
        var startX = 0; var startY = 0
        var isDragging = false
        var longPressTriggered = false
        var pressGuardClaimed = false
        val releaseGuard = Runnable {
            FloatingPenGuard.setFullScreenActive(false, guardKey)
        }
        val longPressRunnable = Runnable {
            if (!isDragging) {
                longPressTriggered = true
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "long press → close")
                removeByGuardKey(guardKey)
            }
        }
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX; startRawY = event.rawY
                    startX = lp.x; startY = lp.y
                    isDragging = false
                    longPressTriggered = false
                    if (!TouchInput.isFinger(event)) {
                        handler.removeCallbacks(releaseGuard)
                        pressGuardClaimed = true
                        FloatingPenGuard.setFullScreenActive(true, guardKey)
                    }
                    handler.postDelayed(longPressRunnable, 500)
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
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm?.updateViewLayout(container, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    val wasTap = !isDragging && !longPressTriggered && event.action == MotionEvent.ACTION_UP
                    if (pressGuardClaimed) {
                        pressGuardClaimed = false
                        handler.removeCallbacks(releaseGuard)
                        handler.postDelayed(releaseGuard, 600)
                    }
                    if (wasTap) insertAndClose(guardKey)
                    true
                }
                else -> false
            }
        }

        val note = Note(container, lp, guardKey, path)
        notes.add(note)
        if (!hiddenTemp) {
            try {
                wm?.addView(container, lp)
                FloatingPenGuard.track(guardKey, container)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "added ($path), total=${notes.size}")
            } catch (e: Exception) {
                notes.remove(note)
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "addView failed: ${e.message}")
                return false
            }
        }
        return true
    }

    private fun insertAndClose(guardKey: String) {
        val note = notes.firstOrNull { it.guardKey == guardKey } ?: return
        val tb = toolbar ?: return
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "tap → insert ${note.path}")
        kotlin.concurrent.thread(isDaemon = true) {
            ImagePanel.saveToInsertCacheStatic(
                note.path, FloatingToolbarModule.lastNotePath, FloatingToolbarModule.lastPageNum
            )
        }
        try { tb.requestInsertImage(note.path) } catch (_: Exception) {}
        removeByGuardKey(guardKey)
    }

    private fun removeByGuardKey(guardKey: String) {
        handler.post {
            val it = notes.iterator()
            while (it.hasNext()) {
                val n = it.next()
                if (n.guardKey == guardKey) {
                    try { wm?.removeView(n.view) } catch (_: Exception) {}
                    FloatingPenGuard.setFullScreenActive(false, n.guardKey)
                    it.remove()
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "closed by tap, remaining=${notes.size}")
                    return@post
                }
            }
        }
    }

    fun hideTemp() {
        if (notes.isEmpty()) return
        hiddenTemp = true
        handler.post {
            for (n in notes) {
                try { wm?.removeView(n.view) } catch (_: Exception) {}
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "hidden (temp), kept=${notes.size}")
        }
    }

    fun reshowIfTemp() {
        if (!hiddenTemp) return
        hiddenTemp = false
        handler.post {
            val manager = wm ?: return@post
            for (n in notes) {
                try {
                    manager.addView(n.view, n.lp)
                    FloatingPenGuard.track(n.guardKey, n.view)
                } catch (_: Exception) {}
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "reshown, total=${notes.size}")
        }
    }

    fun clearAll() {
        hiddenTemp = false
        handler.post {
            for (n in notes) {
                try { wm?.removeView(n.view) } catch (_: Exception) {}
                FloatingPenGuard.setFullScreenActive(false, n.guardKey)
            }
            notes.clear()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "cleared")
        }
    }
}
