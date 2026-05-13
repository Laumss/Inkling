package com.screenshot_crop

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

/**
 * Native module for screenshot capture.
 *
 * captureAndReopen():
 *   1. Saves the current Activity's Intent (for restart)
 *   2. Starts a background thread (survives bridge destruction)
 *   3. Thread finishes the Activity (closes plugin view)
 *   4. Thread takes screencap (document is now visible)
 *   5. Thread waits delayMs (e-ink settle)
 *   6. Thread restarts the plugin Activity
 *   7. On next mount, JS calls getPendingPath() to retrieve the screenshot
 *
 * pendingPath is a companion object (static) so it survives bridge restarts.
 */
class ScreenshotModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "ScreenshotModule"

    companion object {
        @Volatile
        var pendingPath: String? = null
    }

    private val cacheDir: String
        get() = reactApplicationContext.cacheDir.absolutePath

    /** Simple screencap — call when plugin view is already closed. */
    @ReactMethod
    fun takeScreenshot(promise: Promise) {
        Thread {
            try {
                val ts = System.currentTimeMillis()
                val outPath = "$cacheDir/screenshot_crop_$ts.png"
                val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outPath))
                val exitCode = process.waitFor()
                val file = File(outPath)
                if (exitCode == 0 && file.exists() && file.length() > 500) {
                    promise.resolve(outPath)
                } else {
                    promise.reject("SCREENCAP_FAILED", "exit=$exitCode size=${file.length()}")
                }
            } catch (e: Exception) {
                promise.reject("SCREENCAP_ERROR", e.message, e)
            }
        }.also { it.isDaemon = false }.start()
    }

    /**
     * Close plugin view → screencap → wait → reopen plugin view.
     * Runs entirely in a background thread so it survives bridge destruction.
     * The screenshot path is stored in pendingPath (static) for the next JS instance.
     */
    @ReactMethod
    fun captureAndReopen(delayMs: Int, promise: Promise) {
        val appContext = reactApplicationContext.applicationContext
        val cachePath = cacheDir

        // Resolve promise immediately (bridge may die soon)
        promise.resolve(true)

        Thread {
            try {
                // Step 0: Wait for activity to become available (max 5s)
                var activity = currentActivity
                if (activity == null) {
                    android.util.Log.i("ScreenshotModule", "Waiting for activity...")
                    for (i in 0 until 50) {
                        Thread.sleep(100)
                        activity = currentActivity
                        if (activity != null) break
                    }
                }
                if (activity == null) {
                    android.util.Log.e("ScreenshotModule", "No activity after waiting 5s")
                    return@Thread
                }

                // Save intent before finishing — used to restart the plugin view
                val restartIntent = Intent(activity.intent).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                // Step 1: Close the plugin view
                android.util.Log.i("ScreenshotModule", "Finishing activity...")
                activity.finish()

                // Step 2: Wait for activity close + e-ink refresh
                Thread.sleep(800)

                // Step 3: Take screenshot
                val ts = System.currentTimeMillis()
                val outPath = "$cachePath/screenshot_crop_$ts.png"
                android.util.Log.i("ScreenshotModule", "Taking screencap: $outPath")
                val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outPath))
                val exitCode = proc.waitFor()
                val file = File(outPath)

                if (exitCode == 0 && file.exists() && file.length() > 500) {
                    pendingPath = outPath
                    android.util.Log.i("ScreenshotModule", "Screenshot saved: $outPath")
                } else {
                    android.util.Log.e("ScreenshotModule", "Screencap failed: exit=$exitCode size=${file.length()}")
                }

                // Step 4: Wait the requested delay (e-ink settle time)
                Thread.sleep(delayMs.toLong())

                // Step 5: Restart the plugin Activity
                android.util.Log.i("ScreenshotModule", "Restarting plugin activity...")
                appContext.startActivity(restartIntent)

            } catch (e: Exception) {
                android.util.Log.e("ScreenshotModule", "captureAndReopen error: ${e.message}", e)
            }
        }.also { it.isDaemon = false }.start()
    }

    /** Called by JS on mount to retrieve the pre-captured screenshot path. */
    @ReactMethod
    fun getPendingPath(promise: Promise) {
        val path = pendingPath
        pendingPath = null
        android.util.Log.i("ScreenshotModule", "getPendingPath: $path")
        promise.resolve(path)
    }

    /**
     * Composite two images into one based on stitch parameters.
     *
     * @param paramsJson JSON string with structure:
     *   {
     *     "direction": "vertical"|"horizontal",
     *     "overlap": number (pixels),
     *     "topLayerIndex": 0|1,
     *     "images": [
     *       { "path": string, "width": int, "height": int,
     *         "cropTop": float, "cropBottom": float, "cropLeft": float, "cropRight": float },
     *       { ... }
     *     ]
     *   }
     */
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

                // Decode bitmaps
                val bitmaps = imgs.map { img ->
                    BitmapFactory.decodeFile(img.path) ?: throw Exception("Failed to decode ${img.path}")
                }

                // Calculate cropped source rects
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

                // Calculate output canvas size
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

                android.util.Log.i("ScreenshotModule", "Compositing: ${canvasW}x${canvasH}, overlap=$overlap, dir=$direction")

                val result = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

                // Destination rects for each image
                val dstRects = Array(2) { RectF() }
                if (direction == "vertical") {
                    dstRects[0].set(0f, 0f, effW[0].toFloat(), effH[0].toFloat())
                    dstRects[1].set(0f, (effH[0] - overlap).toFloat(), effW[1].toFloat(), (effH[0] - overlap + effH[1]).toFloat())
                } else {
                    dstRects[0].set(0f, 0f, effW[0].toFloat(), effH[0].toFloat())
                    dstRects[1].set((effW[0] - overlap).toFloat(), 0f, (effW[0] - overlap + effW[1]).toFloat(), effH[1].toFloat())
                }

                // Draw in layer order: bottom first, top second
                val drawOrder = if (topLayerIndex == 0) intArrayOf(1, 0) else intArrayOf(0, 1)
                for (idx in drawOrder) {
                    canvas.drawBitmap(bitmaps[idx], srcRects[idx], dstRects[idx], paint)
                }

                // Save output
                val ts = System.currentTimeMillis()
                val outPath = "$cacheDir/stitch_result_$ts.png"
                FileOutputStream(outPath).use { fos ->
                    result.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }

                // Clean up
                result.recycle()
                bitmaps.forEach { it.recycle() }

                android.util.Log.i("ScreenshotModule", "Composite saved: $outPath")
                promise.resolve(outPath)

            } catch (e: Exception) {
                android.util.Log.e("ScreenshotModule", "compositeImages error: ${e.message}", e)
                promise.reject("COMPOSITE_ERROR", e.message, e)
            }
        }.also { it.isDaemon = false }.start()
    }
}
