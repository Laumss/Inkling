package com.screenshot_crop

import android.content.Intent
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import java.io.File

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
}
