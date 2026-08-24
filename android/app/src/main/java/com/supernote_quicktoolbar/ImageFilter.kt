package com.supernote_quicktoolbar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log

object ImageFilter {

    const val FILTER_NONE = 0
    const val FILTER_ENHANCE = 1
    const val FILTER_TEXT_BW = 2

    private const val TAG = "ImageFilter"

    private val nativeReady: Boolean by lazy {
        try {
            System.loadLibrary("inkfilter_jni")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "loadLibrary failed: ${t.message}")
            false
        }
    }

    @JvmStatic
    private external fun nativeProcess(bitmap: Bitmap, filter: Int, density: Int): Int

    fun process(bitmap: Bitmap, filter: Int, density: Int): Boolean {
        val d = density.coerceIn(20, 100)
        if (filter == FILTER_NONE && d >= 100) return true
        if (nativeReady) {
            val rc = try {
                nativeProcess(bitmap, filter, d)
            } catch (t: Throwable) {
                Log.e(TAG, "nativeProcess threw: ${t.message}")
                -100
            }
            if (rc == 0) return true
            Log.e(TAG, "nativeProcess rc=$rc, falling back to density-only")
        }
        fallbackDensity(bitmap, d)
        return filter == FILTER_NONE
    }

    private fun fallbackDensity(bitmap: Bitmap, density: Int) {
        if (density >= 100) return
        val a = density / 100f
        val offset = 255f * (1 - a)
        val src = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                a, 0f, 0f, 0f, offset,
                0f, a, 0f, 0f, offset,
                0f, 0f, a, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        src.recycle()
    }
}
