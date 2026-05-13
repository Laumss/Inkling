package com.supernote_quicktoolbar

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class ScreenshotModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "ScreenshotModule"

    companion object {
        @Volatile
        var pendingPath: String? = null

        @Volatile
        var pendingLassoPath: String? = null
    }

    private val cacheDir: String
        get() = reactApplicationContext.cacheDir.absolutePath

    @ReactMethod
    fun takeScreenshot(promise: Promise) {
        android.util.Log.i("ScreenshotModule", "[LASSO-DBG/Kt] takeScreenshot invoked")
        Thread {
            try {
                val ts = System.currentTimeMillis()
                val outPath = "$cacheDir/screenshot_crop_$ts.png"
                android.util.Log.i("ScreenshotModule", "[LASSO-DBG/Kt] takeScreenshot running screencap -> $outPath")
                val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outPath))
                val exitCode = process.waitFor()
                val file = File(outPath)
                android.util.Log.i("ScreenshotModule", "[LASSO-DBG/Kt] screencap exit=$exitCode size=${file.length()}")
                if (exitCode == 0 && file.exists() && file.length() > 500) {
                    promise.resolve(outPath)
                } else {
                    promise.reject("SCREENCAP_FAILED", "exit=$exitCode size=${file.length()}")
                }
            } catch (e: Exception) {
                android.util.Log.e("ScreenshotModule", "[LASSO-DBG/Kt] takeScreenshot EX: ${e.message}", e)
                promise.reject("SCREENCAP_ERROR", e.message, e)
            }
        }.also { it.isDaemon = false }.start()
    }

    @ReactMethod
    fun captureAndReopen(delayMs: Int, promise: Promise) {
        val appContext = reactApplicationContext.applicationContext
        val cachePath = cacheDir
        promise.resolve(true)

        Thread {
            try {
                var activity = currentActivity
                if (activity == null) {
                    for (i in 0 until 50) {
                        Thread.sleep(100)
                        activity = currentActivity
                        if (activity != null) break
                    }
                }
                if (activity == null) return@Thread

                val restartIntent = Intent(activity.intent).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                activity.finish()
                Thread.sleep(800)

                val ts = System.currentTimeMillis()
                val outPath = "$cachePath/screenshot_crop_$ts.png"
                val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outPath))
                val exitCode = proc.waitFor()
                val file = File(outPath)

                if (exitCode == 0 && file.exists() && file.length() > 500) {
                    pendingPath = outPath
                }

                Thread.sleep(delayMs.toLong())
                appContext.startActivity(restartIntent)

            } catch (e: Exception) {
                android.util.Log.e("ScreenshotModule", "captureAndReopen error: ${e.message}", e)
            }
        }.also { it.isDaemon = false }.start()
    }

    @ReactMethod
    fun getPendingPath(promise: Promise) {
        val path = pendingPath
        pendingPath = null
        promise.resolve(path)
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun hasPendingPath(): Boolean = pendingPath != null

    @ReactMethod
    fun setPendingLassoPath(path: String?) {
        android.util.Log.i("ScreenshotModule", "[LASSO-DBG/Kt] setPendingLassoPath: $path (prev=$pendingLassoPath)")
        pendingLassoPath = path
    }

    @ReactMethod
    fun getPendingLassoPath(promise: Promise) {
        val path = pendingLassoPath
        android.util.Log.i("ScreenshotModule", "[LASSO-DBG/Kt] getPendingLassoPath returning: $path")
        pendingLassoPath = null
        promise.resolve(path)
    }

    @ReactMethod
    fun peekPendingLassoPath(promise: Promise) {
        android.util.Log.i("ScreenshotModule", "[LASSO-DBG/Kt] peekPendingLassoPath: $pendingLassoPath")
        promise.resolve(pendingLassoPath)
    }

    @ReactMethod
    fun compositeImages(paramsJson: String, promise: Promise) {
        Thread {
            try {
                val json = JSONObject(paramsJson)
                val direction = json.getString("direction")
                val overlap = json.getInt("overlap")
                val topLayerIndex = json.getInt("topLayerIndex")
                val imagesArr = json.getJSONArray("images")

                if (imagesArr.length() < 2) {
                    promise.reject("INVALID_PARAMS", "Need at least 2 images")
                    return@Thread
                }

                data class ImgInfo(
                    val path: String, val width: Int, val height: Int,
                    val cropTop: Float, val cropBottom: Float,
                    val cropLeft: Float, val cropRight: Float
                )

                val imgs = (0 until imagesArr.length()).map { i ->
                    val obj = imagesArr.getJSONObject(i)
                    val crop = obj.optJSONObject("crop")
                    ImgInfo(
                        path = obj.getString("path"),
                        width = obj.getInt("width"),
                        height = obj.getInt("height"),
                        cropTop = crop?.optDouble("cropTop", 0.0)?.toFloat() ?: 0f,
                        cropBottom = crop?.optDouble("cropBottom", 0.0)?.toFloat() ?: 0f,
                        cropLeft = crop?.optDouble("cropLeft", 0.0)?.toFloat() ?: 0f,
                        cropRight = crop?.optDouble("cropRight", 0.0)?.toFloat() ?: 0f,
                    )
                }

                val bitmaps = imgs.map { img ->
                    BitmapFactory.decodeFile(img.path) ?: throw Exception("Failed to decode ${img.path}")
                }

                val srcRects = imgs.mapIndexed { i, img ->
                    Rect(
                        (img.width * img.cropLeft).toInt(),
                        (img.height * img.cropTop).toInt(),
                        (img.width * (1f - img.cropRight)).toInt(),
                        (img.height * (1f - img.cropBottom)).toInt()
                    )
                }

                val effW = srcRects.map { it.width() }
                val effH = srcRects.map { it.height() }

                val canvasW: Int
                val canvasH: Int
                if (direction == "vertical") {
                    canvasW = maxOf(effW[0], effW[1])
                    canvasH = effH[0] + effH[1] - overlap
                } else {
                    canvasW = effW[0] + effW[1] - overlap
                    canvasH = maxOf(effH[0], effH[1])
                }

                if (canvasW <= 0 || canvasH <= 0) {
                    promise.reject("INVALID_SIZE", "Canvas size invalid: ${canvasW}x${canvasH}")
                    return@Thread
                }

                val result = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

                val dstRects = Array(2) { RectF() }
                if (direction == "vertical") {
                    dstRects[0].set(0f, 0f, effW[0].toFloat(), effH[0].toFloat())
                    dstRects[1].set(0f, (effH[0] - overlap).toFloat(), effW[1].toFloat(), (effH[0] - overlap + effH[1]).toFloat())
                } else {
                    dstRects[0].set(0f, 0f, effW[0].toFloat(), effH[0].toFloat())
                    dstRects[1].set((effW[0] - overlap).toFloat(), 0f, (effW[0] - overlap + effW[1]).toFloat(), effH[1].toFloat())
                }

                val drawOrder = if (topLayerIndex == 0) intArrayOf(1, 0) else intArrayOf(0, 1)
                for (idx in drawOrder) {
                    canvas.drawBitmap(bitmaps[idx], srcRects[idx], dstRects[idx], paint)
                }

                val ts = System.currentTimeMillis()
                val outPath = "$cacheDir/stitch_result_$ts.png"
                FileOutputStream(outPath).use { fos ->
                    result.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }

                result.recycle()
                bitmaps.forEach { it.recycle() }

                promise.resolve(outPath)

            } catch (e: Exception) {
                promise.reject("COMPOSITE_ERROR", e.message, e)
            }
        }.also { it.isDaemon = false }.start()
    }
}
