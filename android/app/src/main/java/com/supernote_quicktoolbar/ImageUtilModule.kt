package com.supernote_quicktoolbar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.File
import java.io.FileOutputStream

class ImageUtilModule(
    reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "InklingImageUtil"

    @ReactMethod
    fun cropRegion(
        srcPath: String,
        refW: Double,
        refH: Double,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        outPath: String,
        promise: Promise,
    ) {
        try {
            val srcFile = File(srcPath)
            if (!srcFile.isFile) {
                promise.reject("E_CROP_SRC", "source not found: $srcPath")
                return
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(srcPath, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) {
                promise.reject("E_CROP_DECODE", "bad source dimensions: ${srcW}x$srcH")
                return
            }
            val sx = if (refW > 0) srcW / refW else 1.0
            val sy = if (refH > 0) srcH / refH else 1.0
            val rect = Rect(
                (left * sx).toInt().coerceIn(0, srcW),
                (top * sy).toInt().coerceIn(0, srcH),
                (right * sx).toInt().coerceIn(0, srcW),
                (bottom * sy).toInt().coerceIn(0, srcH),
            )
            if (rect.width() <= 0 || rect.height() <= 0) {
                promise.reject("E_CROP_RECT", "degenerate crop rect: $rect (src=${srcW}x$srcH)")
                return
            }
            var cropped: Bitmap? = null
            var decoder: BitmapRegionDecoder? = null
            try {
                @Suppress("DEPRECATION")
                decoder = BitmapRegionDecoder.newInstance(srcPath, false)
                cropped = decoder?.decodeRegion(rect, null)
                if (cropped == null) {
                    promise.reject("E_CROP_REGION", "decodeRegion returned null")
                    return
                }
                File(outPath).parentFile?.mkdirs()
                FileOutputStream(outPath).use { out ->
                    if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        promise.reject("E_CROP_ENCODE", "PNG encode failed")
                        return
                    }
                }
                promise.resolve(outPath)
            } finally {
                try { cropped?.recycle() } catch (_: Throwable) {}
                try { decoder?.recycle() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            promise.reject("E_CROP", t.message ?: "crop failed", t)
        }
    }
}
