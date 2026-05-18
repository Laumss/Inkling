package com.supernote_quicktoolbar.panels
import com.supernote_quicktoolbar.*
import com.supernote_quicktoolbar.overlays.*
import com.supernote_quicktoolbar.bubbles.*

import android.content.Intent
import android.graphics.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.*
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.supernote_quicktoolbar.ui_common.PanelBase
import com.supernote_quicktoolbar.ui_common.PanelHeader
import com.supernote_quicktoolbar.ui_common.PanelScrollHost
import com.supernote_quicktoolbar.ui_common.PanelTabBar
import com.supernote_quicktoolbar.ui_common.SelectionButton
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import org.json.JSONObject

class DocScreenshotPanel(
    ctx: ReactApplicationContext,
    toolbar: FloatingToolbarModule
) : PanelBase(ctx, toolbar) {

    override val tag = "DocScreenshotPanel"
    override val panelName = "screenshot"
    override val widthRatio = 0.6
    override val heightRatio = 0.65

    companion object {
        @Volatile var currentInstance: DocScreenshotPanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): DocScreenshotPanel {
            val inst = currentInstance ?: DocScreenshotPanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private const val QUEUE_DIR   = "/sdcard/SCREENSHOT/.plugin_staging/queue"
        private const val HISTORY_DIR = "/sdcard/SCREENSHOT/.plugin_history"
    }

    private var activeTab = "history"
    private var selectedPath: String? = null

    private var contentGrid: LinearLayout? = null
    private var scrollHost: PanelScrollHost? = null
    private var tabBar: PanelTabBar? = null
    private var insertBtn: SelectionButton? = null
    private var deleteBtn: SelectionButton? = null

    fun show() {
        currentInstance = this
        selectedPath = null
        activeTab = "history"
        showPanel()
        handler.post { refreshContent() }
    }

    override fun onHide() {
        contentGrid = null; scrollHost = null; tabBar = null
        insertBtn = null; deleteBtn = null
        currentInstance = null
    }

    override fun buildContent(root: LinearLayout) {
        root.addView(PanelHeader.create(reactContext, NativeLocale.t("screenshot_panel_title")))

        tabBar = PanelTabBar(reactContext, listOf(
            PanelTabBar.Tab.Icon("icons/ic_tab_queue.xml", "queue"),
            PanelTabBar.Tab.Icon("icons/ic_tab_history.xml", "history")
        )) { idx -> switchTab(if (idx == 0) "queue" else "history") }
        tabBar!!.setSelection(1)
        root.addView(tabBar!!.createView())

        scrollHost = PanelScrollHost(reactContext)
        contentGrid = scrollHost!!.content
        root.addView(scrollHost!!.view)

        val deleteTv = makeOutlinedBtn(NativeLocale.t("delete")) { doDelete() }
        val insertTv = makeFilledBtn(NativeLocale.t("insert")) { doInsert() }
        deleteBtn = SelectionButton(deleteTv)
        insertBtn = SelectionButton(insertTv)

        root.addView(makeBottomBar(
            leftButtons = listOf(deleteTv),
            rightButtons = listOf(
                makeOutlinedBtn(NativeLocale.t("cancel")) { closeAndRestore() },
                insertTv
            )
        ))
    }

    private fun switchTab(tab: String) {
        activeTab = tab
        selectedPath = null
        updateButtons()
        refreshContent()
    }

    private fun updateButtons() {
        val hasSel = selectedPath != null
        insertBtn?.update(hasSel)
        deleteBtn?.update(hasSel)
    }

    private fun refreshContent() { refreshContent(clearSelection = true) }

    private fun refreshContent(clearSelection: Boolean) {
        val grid = contentGrid ?: return
        if (clearSelection) scrollHost?.prepareForContentChange()
        grid.removeAllViews()
        if (clearSelection) { selectedPath = null; updateButtons() }

        val dir = if (activeTab == "queue") QUEUE_DIR else HISTORY_DIR
        val folder = File(dir)
        if (!folder.exists() || !folder.isDirectory) {
            grid.addView(makeEmptyView(
                if (activeTab == "queue") NativeLocale.t("no_queue") else NativeLocale.t("no_history")
            ))
            scrollHost?.refreshThumb()
            return
        }

        val files = (folder.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".png") }
            .sortedByDescending { it.name.removeSuffix(".png").toLongOrNull() ?: 0L }

        if (files.isEmpty()) {
            grid.addView(makeEmptyView(
                if (activeTab == "queue") NativeLocale.t("no_queue") else NativeLocale.t("no_history")
            ))
            scrollHost?.refreshThumb()
            return
        }
        buildGrid(files)
        if (clearSelection) scrollHost?.scrollToTop() else scrollHost?.refreshThumb()
    }

    private fun buildGrid(files: List<File>) {
        val grid = contentGrid ?: return
        val gap = dp(5)
        val innerW = scrollHost?.availableContentWidth(winW) ?: winW
        val colW = (innerW - gap * 3) / 2

        var row: LinearLayout? = null
        for ((idx, file) in files.withIndex()) {
            if (idx % 2 == 0) {
                row = LinearLayout(reactContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(gap, gap, gap, 0)
                }
                grid.addView(row)
            }
            val cell = createCell(file, colW)
            (cell.layoutParams as? LinearLayout.LayoutParams)?.apply {
                if (idx % 2 == 0) rightMargin = gap
            }
            row?.addView(cell)
        }
        if (files.size % 2 != 0) {
            row?.addView(View(reactContext).apply {
                layoutParams = LinearLayout.LayoutParams(colW, 1)
            })
        }
    }

    private fun createCell(file: File, width: Int): LinearLayout {
        val thumbH = (width / 1.2f).toInt()
        val isSelected = selectedPath == file.absolutePath

        val cell = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(if (isSelected) dp(3) else dp(1),
                    if (isSelected) Color.BLACK else Color.parseColor("#999999"))
                cornerRadius = dp(6).toFloat()
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat())
                }
            }
            setOnClickListener {
                selectedPath = if (selectedPath == file.absolutePath) null else file.absolutePath
                updateButtons()
                refreshContent(clearSelection = false)
            }
        }

        val thumbContainer = FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(width, thumbH)
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
        val imageView = ImageView(reactContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        thumbContainer.addView(imageView)
        loadThumbnail(file.absolutePath, width, thumbH, imageView)
        cell.addView(thumbContainer)

        cell.addView(View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#D8D8D8"))
        })
        val info = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val ts = file.name.removeSuffix(".png").toLongOrNull() ?: 0L
        val timeStr = if (ts > 0) {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
        } else file.name
        info.addView(TextView(reactContext).apply {
            text = timeStr; textSize = 11f; setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
        })
        info.addView(TextView(reactContext).apply {
            text = formatSize(file.length()); textSize = 10f
            setTextColor(Color.parseColor("#999999")); gravity = Gravity.CENTER
        })
        cell.addView(info)
        return cell
    }

    private fun doInsert() {
        val path = selectedPath ?: return
        hide()
        Log.i(tag, "[INSERT-DBG/Kt] panel insert path=$path fromQueue=${activeTab == "queue"}")
        thread(isDaemon = true) {
            ImagePanel.saveToInsertCacheStatic(
                path, FloatingToolbarModule.lastNotePath, FloatingToolbarModule.lastPageNum
            )
        }
        handler.postDelayed({
            try { toolbarModule.requestInsertImage(path) }
            catch (_: Exception) { toolbarModule.restoreToolbar() }
        }, 300)
    }

    private fun doDelete() {
        val path = selectedPath ?: return
        try { File(path).delete() } catch (_: Exception) {}
        selectedPath = null
        updateButtons()
        refreshContent()
    }

    private fun closeAndRestore() {
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun loadThumbnail(path: String, reqW: Int, reqH: Int, imageView: ImageView) {
        thread(isDaemon = true) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                var sample = 1
                val halfH = opts.outHeight / 2; val halfW = opts.outWidth / 2
                while (halfH / sample >= reqH && halfW / sample >= reqW) sample *= 2
                opts.inSampleSize = sample
                opts.inJustDecodeBounds = false
                val bmp = BitmapFactory.decodeFile(path, opts) ?: return@thread
                handler.post { imageView.setImageBitmap(bmp) }
            } catch (_: Exception) {}
        }
    }
}

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
