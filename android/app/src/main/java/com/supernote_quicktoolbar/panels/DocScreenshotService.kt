package com.supernote_quicktoolbar.panels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object DocScreenshotService {

    private const val TAG = "DocScreenshotService"

    private const val STAGING_DIR       = "/sdcard/SCREENSHOT/.plugin_staging"
    private const val QUEUE_DIR         = "$STAGING_DIR/queue"
    private const val HISTORY_DIR       = "/sdcard/SCREENSHOT/.plugin_history"
    private const val SESSION_FILE      = "$STAGING_DIR/stitch_session.json"
    private const val STITCH_IMAGES_DIR = "$STAGING_DIR/stitch_images"
    private const val MAX_HISTORY       = 20

    fun cropAndSave(srcPath: String, crop: CropPanel.CropResult, destPath: String): Boolean {
        return try {
            val bmp = BitmapFactory.decodeFile(srcPath) ?: return false
            val cropped = Bitmap.createBitmap(bmp, crop.offsetX, crop.offsetY, crop.width, crop.height)
            FileOutputStream(destPath).use { fos ->
                cropped.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            cropped.recycle()
            bmp.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "cropAndSave failed: ${e.message}", e)
            false
        }
    }

    private fun ensureDirs() {
        for (d in listOf(STAGING_DIR, QUEUE_DIR, HISTORY_DIR, STITCH_IMAGES_DIR)) {
            File(d).mkdirs()
        }
    }

    fun stageToQueue(srcPath: String, crop: CropPanel.CropResult): String? {
        ensureDirs()
        val ts = System.currentTimeMillis()
        val dest = "$QUEUE_DIR/$ts.png"
        return if (cropAndSave(srcPath, crop, dest)) dest else null
    }

    fun saveToHistory(srcPath: String, crop: CropPanel.CropResult): String? {
        ensureDirs()
        val ts = System.currentTimeMillis()
        val dest = "$HISTORY_DIR/$ts.png"
        val ok = cropAndSave(srcPath, crop, dest)
        if (ok) pruneHistory()
        return if (ok) dest else null
    }

    private fun pruneHistory() {
        try {
            val files = File(HISTORY_DIR).listFiles()?.filter { it.name.endsWith(".png") }
                ?.sortedBy { it.name.removeSuffix(".png").toLongOrNull() ?: 0L } ?: return
            if (files.size > MAX_HISTORY) {
                files.take(files.size - MAX_HISTORY).forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }

    data class StitchImage(
        val path: String,
        val width: Int,
        val height: Int,
        var cropTop: Float = 0f,
        var cropBottom: Float = 0f,
        var cropLeft: Float = 0f,
        var cropRight: Float = 0f
    )

    data class StitchParams(
        var direction: String = "vertical",
        var overlap: Int = 100,
        var topLayerIndex: Int = 1,
        var cols: Int = 0
    )

    data class StitchSessionData(
        val images: MutableList<StitchImage>,
        val params: StitchParams,
        val createdAt: Long
    )

    fun hasActiveSession(): Boolean = File(SESSION_FILE).exists()

    fun loadSession(): StitchSessionData? {
        return try {
            val f = File(SESSION_FILE)
            if (!f.exists()) return null
            val json = JSONObject(f.readText())
            val imgs = json.getJSONArray("images")
            val imageList = mutableListOf<StitchImage>()
            for (i in 0 until imgs.length()) {
                val obj = imgs.getJSONObject(i)
                val crop = obj.optJSONObject("crop")
                val img = StitchImage(
                    path = obj.getString("path"),
                    width = obj.getInt("width"),
                    height = obj.getInt("height"),
                    cropTop = crop?.optDouble("cropTop", 0.0)?.toFloat() ?: 0f,
                    cropBottom = crop?.optDouble("cropBottom", 0.0)?.toFloat() ?: 0f,
                    cropLeft = crop?.optDouble("cropLeft", 0.0)?.toFloat() ?: 0f,
                    cropRight = crop?.optDouble("cropRight", 0.0)?.toFloat() ?: 0f,
                )
                if (!File(img.path).exists()) return null
                imageList.add(img)
            }
            val p = json.getJSONObject("params")
            val params = StitchParams(
                direction = p.optString("direction", "vertical"),
                overlap = p.optInt("overlap", 100),
                topLayerIndex = p.optInt("topLayerIndex", 1),
                cols = p.optInt("cols", 0),
            )
            StitchSessionData(imageList, params, json.optLong("createdAt", 0))
        } catch (e: Exception) {
            Log.e(TAG, "loadSession failed: ${e.message}")
            null
        }
    }

    private fun saveSession(session: StitchSessionData) {
        ensureDirs()
        val json = JSONObject().apply {
            put("createdAt", session.createdAt)
            put("params", JSONObject().apply {
                put("direction", session.params.direction)
                put("overlap", session.params.overlap)
                put("topLayerIndex", session.params.topLayerIndex)
                put("cols", session.params.cols)
            })
            put("images", JSONArray().apply {
                for (img in session.images) {
                    put(JSONObject().apply {
                        put("path", img.path)
                        put("width", img.width)
                        put("height", img.height)
                        put("crop", JSONObject().apply {
                            put("cropTop", img.cropTop.toDouble())
                            put("cropBottom", img.cropBottom.toDouble())
                            put("cropLeft", img.cropLeft.toDouble())
                            put("cropRight", img.cropRight.toDouble())
                        })
                    })
                }
            })
        }
        File(SESSION_FILE).writeText(json.toString())
    }

    fun startSession(imagePath: String, width: Int, height: Int): StitchSessionData {
        ensureDirs()
        val ts = System.currentTimeMillis()
        val dest = "$STITCH_IMAGES_DIR/${ts}_0.png"
        File(imagePath).copyTo(File(dest), overwrite = true)
        val session = StitchSessionData(
            images = mutableListOf(StitchImage(dest, width, height)),
            params = StitchParams(),
            createdAt = ts
        )
        saveSession(session)
        return session
    }

    fun addImage(imagePath: String, width: Int, height: Int): StitchSessionData? {
        val session = loadSession() ?: return null
        ensureDirs()
        val ts = System.currentTimeMillis()
        val idx = session.images.size
        val dest = "$STITCH_IMAGES_DIR/${ts}_$idx.png"
        File(imagePath).copyTo(File(dest), overwrite = true)
        session.images.add(StitchImage(dest, width, height))
        if (session.images.size == 2) {
            session.params.direction = "vertical"
            session.params.overlap = 100
            session.params.topLayerIndex = 1
        } else if (session.images.size > 2 && session.params.cols == 0) {
            session.params.cols = if (session.params.direction == "vertical") 1 else session.images.size
        }
        saveSession(session)
        return session
    }

    fun keepFirstOnly() {
        val session = loadSession() ?: return
        if (session.images.size < 2) return
        try { File(session.images[1].path).delete() } catch (_: Exception) {}
        session.images.removeAt(1)
        session.params.direction = "vertical"
        session.params.overlap = 100
        session.params.topLayerIndex = 1
        saveSession(session)
    }

    fun clearSession() {
        try { File(SESSION_FILE).delete() } catch (_: Exception) {}
        try {
            File(STITCH_IMAGES_DIR).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    fun updateSession(session: StitchSessionData) = saveSession(session)

    fun getImageDimensions(path: String): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) Pair(opts.outWidth, opts.outHeight) else null
    }
}
