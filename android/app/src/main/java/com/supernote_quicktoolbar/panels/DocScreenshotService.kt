package com.supernote_quicktoolbar.panels
import com.supernote_quicktoolbar.BuildConfig

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
    private const val QUEUE_META_FILE   = "$QUEUE_DIR/queue_meta.json"
    private const val HISTORY_DIR       = "/sdcard/SCREENSHOT/.plugin_history"
    private const val SESSION_FILE      = "$STAGING_DIR/stitch_session.json"
    private const val STITCH_IMAGES_DIR = "$STAGING_DIR/stitch_images"
    private const val MAX_HISTORY       = 20

    fun cropAndSave(srcPath: String, crop: CropPanel.CropResult, destPath: String): Boolean {
        var source: Bitmap? = null
        var cropped: Bitmap? = null
        return try {
            val decoded = BitmapFactory.decodeFile(srcPath) ?: return false
            source = decoded
            val output = Bitmap.createBitmap(decoded, crop.offsetX, crop.offsetY, crop.width, crop.height)
            cropped = output
            FileOutputStream(destPath).use { fos ->
                if (!output.compress(Bitmap.CompressFormat.PNG, 100, fos)) return false
            }
            true
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "cropAndSave failed: ${e.message}", e)
            false
        } catch (e: OutOfMemoryError) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "cropAndSave out of memory", e)
            false
        } finally {
            if (cropped !== source) cropped?.recycle()
            source?.recycle()
        }
    }

    private fun ensureDirs(): Boolean {
        return try {
            for (path in listOf(STAGING_DIR, QUEUE_DIR, HISTORY_DIR, STITCH_IMAGES_DIR)) {
                val dir = File(path)
                if (!dir.exists() && !dir.mkdirs()) return false
            }
            true
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "ensureDirs failed: ${e.message}", e)
            false
        }
    }

    fun stageToQueue(srcPath: String, crop: CropPanel.CropResult, insertNext: Boolean = false): String? {
        return try {
            if (!ensureDirs()) return null
            val ts = System.currentTimeMillis()
            val dest = "$QUEUE_DIR/$ts.png"
            if (!cropAndSave(srcPath, crop, dest)) return null
            if (insertNext && !markInsertNext("$ts.png")) {
                try { File(dest).delete() } catch (_: Exception) {}
                return null
            }
            dest
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "stageToQueue failed: ${e.message}", e)
            null
        }
    }

    fun saveToHistory(srcPath: String, crop: CropPanel.CropResult): String? {
        return try {
            if (!ensureDirs()) return null
            val ts = System.currentTimeMillis()
            val dest = "$HISTORY_DIR/$ts.png"
            val ok = cropAndSave(srcPath, crop, dest)
            if (ok) pruneHistory()
            if (ok) dest else null
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "saveToHistory failed: ${e.message}", e)
            null
        }
    }

    private fun loadQueueMeta(): MutableSet<String> {
        return try {
            val f = File(QUEUE_META_FILE)
            if (!f.exists()) return mutableSetOf()
            val arr = JSONObject(f.readText()).optJSONArray("insertNext") ?: return mutableSetOf()
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            set
        } catch (_: Exception) { mutableSetOf() }
    }

    private fun saveQueueMeta(set: Set<String>): Boolean {
        return try {
            if (!ensureDirs()) return false
            val json = JSONObject().apply {
                put("insertNext", JSONArray().apply { for (name in set) put(name) })
            }
            File(QUEUE_META_FILE).writeText(json.toString())
            true
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "saveQueueMeta failed: ${e.message}", e)
            false
        }
    }

    private fun markInsertNext(fileName: String): Boolean {
        val set = loadQueueMeta()
        set.add(fileName)
        return saveQueueMeta(set)
    }

    fun unmarkInsertNext(fileName: String) {
        try {
            val set = loadQueueMeta()
            if (set.remove(fileName)) saveQueueMeta(set)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "unmarkInsertNext failed: ${e.message}", e)
        }
    }

    fun isInsertNext(fileName: String): Boolean = loadQueueMeta().contains(fileName)

    fun firstInsertNextFile(): File? {
        return try {
            val set = loadQueueMeta()
            if (set.isEmpty()) return null
            val dir = File(QUEUE_DIR)
            if (!dir.exists()) return null
            (dir.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(".png") && set.contains(it.name) }
                .minByOrNull { it.name.removeSuffix(".png").toLongOrNull() ?: Long.MAX_VALUE }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "firstInsertNextFile failed: ${e.message}", e)
            null
        }
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
        var cols: Int = 0,
        var gridOrder: String = "row"
    )

    data class StitchSessionData(
        val images: MutableList<StitchImage>,
        val params: StitchParams,
        val createdAt: Long
    )

    @Synchronized
    fun hasActiveSession(): Boolean {
        return try {
            File(SESSION_FILE).exists()
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "hasActiveSession failed: ${e.message}", e)
            false
        }
    }

    @Synchronized
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
                gridOrder = p.optString("gridOrder", "row"),
            )
            StitchSessionData(imageList, params, json.optLong("createdAt", 0))
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "loadSession failed: ${e.message}")
            null
        }
    }

    private fun saveSession(session: StitchSessionData): Boolean {
        return try {
            if (!ensureDirs()) return false
            val json = JSONObject().apply {
                put("createdAt", session.createdAt)
                put("params", JSONObject().apply {
                    put("direction", session.params.direction)
                    put("overlap", session.params.overlap)
                    put("topLayerIndex", session.params.topLayerIndex)
                    put("cols", session.params.cols)
                    put("gridOrder", session.params.gridOrder)
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
            true
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "saveSession failed: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun startSession(imagePath: String, width: Int, height: Int): StitchSessionData? {
        return try {
            if (!ensureDirs()) return null
            val ts = System.currentTimeMillis()
            val dest = "$STITCH_IMAGES_DIR/${ts}_0.png"
            File(imagePath).copyTo(File(dest), overwrite = true)
            val session = StitchSessionData(
                images = mutableListOf(StitchImage(dest, width, height)),
                params = StitchParams(),
                createdAt = ts
            )
            if (saveSession(session)) session else {
                try { File(dest).delete() } catch (_: Exception) {}
                null
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "startSession failed: ${e.message}", e)
            null
        }
    }

    @Synchronized
    fun addImage(imagePath: String, width: Int, height: Int): StitchSessionData? {
        return try {
            val session = loadSession() ?: return null
            if (!ensureDirs()) return null
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
            if (saveSession(session)) session else {
                try { File(dest).delete() } catch (_: Exception) {}
                null
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "addImage failed: ${e.message}", e)
            null
        }
    }

    @Synchronized
    fun keepFirstOnly(): Boolean {
        return try {
            val session = loadSession() ?: return false
            if (session.images.size < 2) return true
            val removedPath = session.images[1].path
            session.images.removeAt(1)
            session.params.direction = "vertical"
            session.params.overlap = 100
            session.params.topLayerIndex = 1
            if (!saveSession(session)) return false
            try { File(removedPath).delete() } catch (_: Exception) {}
            true
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "keepFirstOnly failed: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun clearSession(): Boolean {
        return try {
            var ok = true
            val sessionFile = File(SESSION_FILE)
            if (sessionFile.exists() && !sessionFile.delete()) ok = false
            File(STITCH_IMAGES_DIR).listFiles()?.forEach { if (!it.delete()) ok = false }
            ok
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "clearSession failed: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun updateSession(session: StitchSessionData): Boolean = saveSession(session)

    fun getImageDimensions(path: String): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) Pair(opts.outWidth, opts.outHeight) else null
    }
}
