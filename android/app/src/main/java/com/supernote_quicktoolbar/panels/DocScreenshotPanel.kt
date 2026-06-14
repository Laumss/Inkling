package com.supernote_quicktoolbar.panels
import com.supernote_quicktoolbar.BuildConfig
import com.supernote_quicktoolbar.*
import com.supernote_quicktoolbar.overlays.*
import com.supernote_quicktoolbar.bubbles.*

import android.graphics.*
import android.graphics.Bitmap
import android.graphics.Typeface
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
import com.supernote_quicktoolbar.ui_common.ButtonHandle
import com.supernote_quicktoolbar.ui_common.PanelBase
import com.supernote_quicktoolbar.ui_common.PanelGrid
import com.supernote_quicktoolbar.ui_common.PanelHost
import com.supernote_quicktoolbar.ui_common.PanelScrollHost
import com.supernote_quicktoolbar.ui_common.PanelTabBar
import com.supernote_quicktoolbar.ui_common.PanelWidgets
import com.supernote_quicktoolbar.ui_common.TabBarHandle
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
    override val heightRatio = 0.81

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

    private lateinit var tabBarH: TabBarHandle
    private lateinit var insertBtn: ButtonHandle
    private lateinit var deleteBtn: ButtonHandle
    private var gridRebuild: (() -> Unit)? = null
    private var gridScrollTop: (() -> Unit)? = null

    fun show(initialTab: String = "history") {
        ScreenshotBubble.pendingReshow = false
        ScreenshotBubble.hide()
        currentInstance = this
        selectedPath = null
        activeTab = initialTab
        if (activeTab == "queue") {
            val queueDir = java.io.File(QUEUE_DIR)
            val hasFiles = queueDir.exists() && queueDir.listFiles()?.any { it.name.endsWith(".png") } == true
            if (!hasFiles) activeTab = "history"
        }
        showPanel()
    }

    override fun onHide() {
        gridRebuild = null
        gridScrollTop = null
        currentInstance = null
    }

    override fun buildContent(root: LinearLayout) {
        renderDsl(root) {
            header(NativeLocale.t("screenshot_panel_title"))
            tabBarH = tabBar(
                listOf(
                    PanelTabBar.Tab.Icon("icons/ic_tab_queue.xml", "queue"),
                    PanelTabBar.Tab.Icon("icons/ic_tab_history.xml", "history")
                ),
                initial = if (activeTab == "queue") 0 else 1
            ) { idx -> switchTab(if (idx == 0) "queue" else "history") }

            custom { host ->
                val scroll = PanelScrollHost(host.ctx, overlayScrollbar = true)
                gridRebuild = {
                    scroll.content.removeAllViews()
                    val files = loadFiles()
                    if (files.isEmpty()) {
                        scroll.content.addView(PanelWidgets.emptyView(host,
                            if (activeTab == "queue") NativeLocale.t("no_queue")
                            else NativeLocale.t("no_history")))
                    } else {
                        PanelGrid.build(host.ctx, scroll, host.screenW, host.panelW, files) { file, colW ->
                            screenshotCell(host, file, colW)
                        }
                    }
                    scroll.refreshThumb()
                }
                gridScrollTop = { scroll.scrollToTop() }
                gridRebuild?.invoke()
                scroll.view
            }

            custom { host ->
                val deleteTv = PanelWidgets.outlinedButton(host, NativeLocale.t("delete")) { doDelete() }
                deleteBtn = ButtonHandle().also { it.view = deleteTv }
                val insertTv = PanelWidgets.filledButton(host, NativeLocale.t("insert")) { doInsert() }
                insertBtn = ButtonHandle().also { it.view = insertTv }

                val wrapper = LinearLayout(host.ctx).apply { orientation = LinearLayout.VERTICAL }
                wrapper.addView(PanelWidgets.divider(host))
                val bar = LinearLayout(host.ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(host.dp(28), host.dp(28), host.dp(28), host.dp(28))
                }
                bar.addView(deleteTv)
                bar.addView(View(host.ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
                bar.addView(PanelWidgets.outlinedButton(host, NativeLocale.t("cancel")) { closeAndRestore() })
                bar.addView(PanelWidgets.outlinedButton(host, NativeLocale.t("screenshot_bubble")) { showBubbleAndClose() })
                bar.addView(insertTv)
                wrapper.addView(bar)
                wrapper
            }
        }
        insertBtn.enabled = false
        deleteBtn.enabled = false
    }

    private fun switchTab(tab: String) {
        activeTab = tab
        selectedPath = null
        updateButtons()
        gridRebuild?.invoke()
        gridScrollTop?.invoke()
    }

    private fun updateButtons() {
        val hasSel = selectedPath != null
        insertBtn.enabled = hasSel
        deleteBtn.enabled = hasSel
    }

    private fun loadFiles(): List<File> {
        val dir = if (activeTab == "queue") QUEUE_DIR else HISTORY_DIR
        val folder = File(dir)
        if (!folder.exists() || !folder.isDirectory) return emptyList()
        return (folder.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".png") }
            .sortedByDescending { it.name.removeSuffix(".png").toLongOrNull() ?: 0L }
    }

    private fun screenshotCell(host: PanelHost, file: File, colW: Int): View {
        val thumbH = (colW * 1.1f).toInt()
        val isSelected = selectedPath == file.absolutePath

        val cell = LinearLayout(host.ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(colW, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                selectedPath = if (selectedPath == file.absolutePath) null else file.absolutePath
                updateButtons()
                gridRebuild?.invoke()
            }
        }

        val thumbFrame = FrameLayout(host.ctx).apply {
            layoutParams = LinearLayout.LayoutParams(colW, thumbH)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(if (isSelected) host.dp(2) else host.dp(1),
                    if (isSelected) Color.BLACK else Color.parseColor("#CCCCCC"))
                cornerRadius = host.dp(4).toFloat()
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, host.dp(4).toFloat())
                }
            }
        }
        val imageView = ImageView(host.ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(host.dp(2), host.dp(2), host.dp(2), host.dp(2))
        }
        thumbFrame.addView(imageView)
        loadThumbnail(file.absolutePath, colW, thumbH, imageView)
        cell.addView(thumbFrame)

        val textContainer = LinearLayout(host.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(2), host.dp(6), host.dp(2), host.dp(4))
        }
        val ts = file.name.removeSuffix(".png").toLongOrNull() ?: 0L
        val timeStr = if (ts > 0) {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
        } else file.name
        textContainer.addView(TextView(host.ctx).apply {
            text = timeStr; textSize = host.sp(12f); setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 2
        })
        textContainer.addView(TextView(host.ctx).apply {
            text = PanelWidgets.formatSize(file.length()); textSize = host.sp(10f)
            setTextColor(Color.parseColor("#666666"))
        })
        cell.addView(textContainer)
        return cell
    }

    private fun doInsert() {
        val path = selectedPath ?: return
        hide()
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "[INSERT-DBG/Kt] panel insert path=$path fromQueue=${activeTab == "queue"}")
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
        val fileName = File(path).name
        try { File(path).delete() } catch (_: Exception) {}
        kotlin.concurrent.thread(isDaemon = true) { DocScreenshotService.unmarkInsertNext(fileName) }
        selectedPath = null
        updateButtons()
        gridRebuild?.invoke()
    }

    private fun showBubbleAndClose() {
        dismissWithoutPenRelease()
        handler.postDelayed({
            ScreenshotBubble.show(reactContext, toolbarModule)
            toolbarModule.restoreToolbar()
        }, 200)
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
        if (BuildConfig.ENABLE_DEBUG) android.util.Log.i("ScreenshotModule", "[LASSO-DBG/Kt] takeScreenshot invoked")
        Thread {
            try {
                val ts = System.currentTimeMillis()
                val outPath = "$cacheDir/screenshot_crop_$ts.png"
                if (BuildConfig.ENABLE_DEBUG) android.util.Log.i("ScreenshotModule", "[LASSO-DBG/Kt] takeScreenshot running screencap -> $outPath")
                val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outPath))
                val exitCode = process.waitFor()
                val file = File(outPath)
                if (BuildConfig.ENABLE_DEBUG) android.util.Log.i("ScreenshotModule", "[LASSO-DBG/Kt] screencap exit=$exitCode size=${file.length()}")
                if (exitCode == 0 && file.exists() && file.length() > 500) {
                    promise.resolve(outPath)
                } else {
                    promise.reject("SCREENCAP_FAILED", "exit=$exitCode size=${file.length()}")
                }
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) android.util.Log.e("ScreenshotModule", "[LASSO-DBG/Kt] takeScreenshot EX: ${e.message}", e)
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

                val restartIntent = android.content.Intent(activity.intent).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
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
                if (BuildConfig.ENABLE_DEBUG) android.util.Log.e("ScreenshotModule", "captureAndReopen error: ${e.message}", e)
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
        if (BuildConfig.ENABLE_DEBUG) android.util.Log.i("ScreenshotModule", "[LASSO-DBG/Kt] setPendingLassoPath: $path (prev=$pendingLassoPath)")
        pendingLassoPath = path
    }

    @ReactMethod
    fun getPendingLassoPath(promise: Promise) {
        val path = pendingLassoPath
        if (BuildConfig.ENABLE_DEBUG) android.util.Log.i("ScreenshotModule", "[LASSO-DBG/Kt] getPendingLassoPath returning: $path")
        pendingLassoPath = null
        promise.resolve(path)
    }

    @ReactMethod
    fun peekPendingLassoPath(promise: Promise) {
        if (BuildConfig.ENABLE_DEBUG) android.util.Log.i("ScreenshotModule", "[LASSO-DBG/Kt] peekPendingLassoPath: $pendingLassoPath")
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
