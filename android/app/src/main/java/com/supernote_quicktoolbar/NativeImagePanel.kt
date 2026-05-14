package com.supernote_quicktoolbar

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.*
import com.facebook.react.bridge.ReactApplicationContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.*

class NativeImagePanel(
    private val reactContext: ReactApplicationContext,
    private val toolbarModule: FloatingToolbarModule
) {
    companion object {
        private const val TAG = "NativeImagePanel"
        @Volatile var currentInstance: NativeImagePanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): NativeImagePanel {
            val inst = currentInstance ?: NativeImagePanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private val ALLOWED_ROOT_FOLDERS = setOf(
            "Document", "EXPORT", "MyStyle", "Note", "SCREENSHOT", "INBOX", "Export"
        )
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "bmp", "gif", "webp")

        @JvmStatic
        fun saveToInsertCacheStatic(imagePath: String, notePath: String, pageNum: Int) {
            try {
                val src = java.io.File(imagePath)
                if (!src.exists()) return
                val cacheDir = java.io.File(src.parentFile, ".insert_cache")
                cacheDir.mkdirs()
                val meta = java.io.File(cacheDir, "${src.nameWithoutExtension}.meta")
                meta.writeText("$notePath\n$pageNum\n$imagePath")
            } catch (e: Exception) {
                Log.e(TAG, "saveToInsertCacheStatic: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: View? = null

    private var activeTab = "received"
    private var browseDir: String? = null
    private var currentBrowsePath: String = "/sdcard"
    private var selectedImagePath: String? = null
    private var isInCropper = false
    private var skipEnhance = false

    private var cropBitmap: Bitmap? = null
    private var cropRect = RectF(50f, 50f, 250f, 250f)

    private val DEST_DIR_MAP = mapOf(
        "Document" to "/sdcard/Document", "Export" to "/sdcard/EXPORT",
        "MyStyle" to "/sdcard/MyStyle", "Note" to "/sdcard/Note",
        "SCREENSHOT" to "/sdcard/SCREENSHOT", "INBOX" to "/sdcard/INBOX"
    )
    private val DIR_KEYS = listOf("Document", "Export", "MyStyle", "Note", "SCREENSHOT", "INBOX")

    private var mainContent: LinearLayout? = null
    private var contentScroll: ScrollView? = null
    private var contentGrid: LinearLayout? = null
    private var tabReceivedBtn: TextView? = null
    private var tabBrowseBtn: TextView? = null
    private var tabReceivedIndicator: View? = null
    private var tabBrowseIndicator: View? = null
    private var chipContainer: LinearLayout? = null
    private var bottomBar: LinearLayout? = null
    private var cropCheckText: TextView? = null
    private var insertBtn: TextView? = null

    private val density get() = reactContext.resources.displayMetrics.density
    private val screenW get() = reactContext.resources.displayMetrics.widthPixels
    private val screenH get() = reactContext.resources.displayMetrics.heightPixels
    private val winW get() = (screenW * 0.65).toInt()
    private val winH get() = (screenH * 0.72).toInt()
    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dp(v: Float) = (v * density).roundToInt()

    fun show() {
        handler.post {
            if (rootView != null) return@post
            currentInstance = this
            selectedImagePath = null
            isInCropper = false
            skipEnhance = false
            activeTab = "received"
            browseDir = null
            currentBrowsePath = "/sdcard"
            toolbarModule.enablePenBlock()
            createPanel()
            refreshContent()
        }
    }

    fun showForCrop(imagePath: String) {
        handler.post {
            if (rootView != null) return@post
            currentInstance = this
            selectedImagePath = imagePath
            isInCropper = false
            skipEnhance = true
            activeTab = "received"
            browseDir = null
            currentBrowsePath = "/sdcard"
            toolbarModule.enablePenBlock()
            createPanel()
            switchToCropper()
        }
    }

    fun hide() {
        handler.post {
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            rootView = null; windowManager = null
            mainContent = null; contentScroll = null; contentGrid = null
            cropBitmap?.recycle(); cropBitmap = null
            currentInstance = null

            FloatingToolbarModule.clearCropReplaceContext()
            toolbarModule.disablePenBlock()

            toolbarModule.emitEventPublic(
                "onNativePanelClose",
                com.facebook.react.bridge.Arguments.createMap().apply { putString("panel", "image") }
            )
        }
    }

    fun suspendVisibility() {
        handler.post { rootView?.visibility = View.GONE }
    }

    fun resumeVisibility() {
        handler.post { rootView?.visibility = View.VISIBLE }
    }

    private fun createPanel() {
        val ctx = reactContext
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) return

        windowManager = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager

        val root = TouchSinkLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.BLACK)
                cornerRadius = dp(8).toFloat()
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat())
                }
            }
        }

        root.addView(createTitleBar())

        root.addView(createTabBar())

        root.addView(createChipsRow())

        mainContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        contentScroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        contentGrid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        contentScroll!!.addView(contentGrid)
        mainContent!!.addView(contentScroll)
        root.addView(mainContent)

        bottomBar = createBottomBar()
        root.addView(bottomBar)

        val wmType = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            winW, winH, wmType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        rootView = root
        windowManager?.addView(root, lp)
        Log.i(TAG, "image panel shown")
    }

    private fun createTitleBar(): LinearLayout {
        val ctx = reactContext
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        bar.addView(TextView(ctx).apply {
            text = NativeLocale.t("image_panel_title")
            textSize = 18f; setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        wrapper.addView(bar)
        wrapper.addView(makeDivider())
        return wrapper
    }

    private fun createTabBar(): LinearLayout {
        val ctx = reactContext
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val recvTab = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab("received") }
        }
        tabReceivedBtn = TextView(ctx).apply {
            text = NativeLocale.t("tab_received"); textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }
        recvTab.addView(tabReceivedBtn)
        tabReceivedIndicator = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
            setBackgroundColor(Color.BLACK)
        }
        recvTab.addView(tabReceivedIndicator)
        bar.addView(recvTab)

        bar.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        })

        val browseTab = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab("browse") }
        }
        tabBrowseBtn = TextView(ctx).apply {
            text = NativeLocale.t("tab_browse"); textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        browseTab.addView(tabBrowseBtn)
        tabBrowseIndicator = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
            setBackgroundColor(Color.TRANSPARENT)
        }
        browseTab.addView(tabBrowseIndicator)
        bar.addView(browseTab)

        wrapper.addView(bar)
        wrapper.addView(makeDivider())
        return wrapper
    }

    private fun createChipsRow(): LinearLayout {
        val ctx = reactContext
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val row = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
        }
        chipContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        rebuildChips()
        row.addView(chipContainer)
        wrapper.addView(row)
        wrapper.addView(makeDivider())
        return wrapper
    }

    private fun rebuildChips() {
        val container = chipContainer ?: return
        container.removeAllViews()
        for (key in DIR_KEYS) {
            val isActive = activeTab == "browse" && browseDir == key
            val chip = TextView(reactContext).apply {
                text = key
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (isActive) Color.WHITE else Color.parseColor("#333333"))
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = GradientDrawable().apply {
                    setColor(if (isActive) Color.BLACK else Color.WHITE)
                    setStroke(dp(1), if (isActive) Color.BLACK else Color.parseColor("#999999"))
                    cornerRadius = dp(16).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(10) }
                setOnClickListener {
                    if (activeTab == "browse" && browseDir == key) {
                        browseDir = null
                    } else {
                        activeTab = "browse"
                        browseDir = key
                        currentBrowsePath = DEST_DIR_MAP[key] ?: "/sdcard"
                    }
                    updateTabStyles()
                    rebuildChips()
                    refreshContent()
                }
            }
            container.addView(chip)
        }
    }

    private fun createBottomBar(): LinearLayout {
        val ctx = reactContext
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(makeDivider())
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val cropTapArea = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                if (selectedImagePath != null) switchToCropper()
            }
        }
        cropTapArea.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) }
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1.5f), Color.parseColor("#999999"))
                cornerRadius = dp(2).toFloat()
            }
        })
        cropCheckText = TextView(ctx).apply {
            text = NativeLocale.t("cropper_title")
            textSize = 14f; setTextColor(Color.parseColor("#999999"))
        }
        cropTapArea.addView(cropCheckText)
        bar.addView(cropTapArea)

        bar.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        bar.addView(makeOutlinedBtn(NativeLocale.t("cancel")) {
            if (isInCropper) { switchToBrowser(); return@makeOutlinedBtn }
            closeAndRestore()
        })

        insertBtn = makeFilledBtn(NativeLocale.t("insert_original")) { doInsertOriginal() }
        insertBtn!!.alpha = 0.4f
        insertBtn!!.isEnabled = false
        bar.addView(insertBtn)

        wrapper.addView(bar)
        return wrapper
    }

    private fun switchTab(tab: String) {
        activeTab = tab
        if (tab == "received") browseDir = null
        updateTabStyles()
        rebuildChips()
        refreshContent()
    }

    private fun updateTabStyles() {
        val isRecv = activeTab == "received"
        tabReceivedBtn?.setTextColor(if (isRecv) Color.BLACK else Color.parseColor("#AAAAAA"))
        tabReceivedIndicator?.setBackgroundColor(if (isRecv) Color.BLACK else Color.TRANSPARENT)
        tabBrowseBtn?.setTextColor(if (!isRecv) Color.BLACK else Color.parseColor("#AAAAAA"))
        tabBrowseIndicator?.setBackgroundColor(if (!isRecv) Color.BLACK else Color.TRANSPARENT)
    }

    fun onFileReceived() {
        handler.post {
            if (activeTab == "received" && !isInCropper && rootView != null) {
                refreshContent(clearSelection = false)
            }
        }
    }

    private fun refreshContent() {
        refreshContent(clearSelection = true)
    }

    private fun refreshContent(clearSelection: Boolean) {
        val grid = contentGrid ?: return
        grid.removeAllViews()
        if (clearSelection) {
            selectedImagePath = null
            insertBtn?.alpha = 0.4f
            insertBtn?.isEnabled = false
        }

        if (activeTab == "received") {
            val files = LocalSendModule.getReceivedImageFiles()
            Log.i(TAG, "refreshContent: received tab, session files=${files.size}")
            if (files.isEmpty()) {
                grid.addView(makeEmptyView(NativeLocale.t("no_received")))
                return
            }
            buildImageGrid(files.map { GridItem(it.name, it.path, false, it.size) })
        } else {
            loadAndShowDirectory(currentBrowsePath)
        }
    }

    data class GridItem(val name: String, val path: String, val isDir: Boolean, val size: Long = 0)

    private fun loadAndShowDirectory(path: String) {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            contentGrid?.addView(makeEmptyView(NativeLocale.t("no_images")))
            return
        }

        val items = (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .filter { f ->
                if (f.isDirectory) {
                    if (path == "/sdcard") ALLOWED_ROOT_FOLDERS.contains(f.name) else true
                } else {
                    IMAGE_EXTS.contains(f.extension.lowercase())
                }
            }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
            .map { GridItem(it.name, it.absolutePath, it.isDirectory, it.length()) }

        if (items.isEmpty()) {
            contentGrid?.addView(makeEmptyView(NativeLocale.t("no_images")))
            return
        }
        buildImageGrid(items)
    }

    private fun buildImageGrid(items: List<GridItem>) {
        val grid = contentGrid ?: return
        val ctx = reactContext
        val gap = dp(5)
        val colW = (winW - dp(10) - gap * 3) / 2

        var rowLayout: LinearLayout? = null
        for ((idx, item) in items.withIndex()) {
            if (idx % 2 == 0) {
                rowLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(gap, gap, gap, 0)
                }
                grid.addView(rowLayout)
            }

            val cell = createGridCell(item, colW)
            (cell.layoutParams as? LinearLayout.LayoutParams)?.apply {
                if (idx % 2 == 0) rightMargin = gap
            }
            rowLayout?.addView(cell)
        }

        if (items.size % 2 != 0) {
            rowLayout?.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(colW, 1)
            })
        }
    }

    private fun createGridCell(item: GridItem, width: Int): LinearLayout {
        val ctx = reactContext
        val thumbH = (width / 1.2f).toInt()
        val isSelected = !item.isDir && selectedImagePath == item.path

        val cell = LinearLayout(ctx).apply {
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
                if (item.isDir) {
                    currentBrowsePath = item.path
                    refreshContent()
                } else {
                    selectedImagePath = if (selectedImagePath == item.path) null else item.path
                    insertBtn?.apply {
                        alpha = if (selectedImagePath != null) 1f else 0.4f
                        isEnabled = selectedImagePath != null
                    }
                    refreshContent(clearSelection = false)
                }
            }
        }

        val thumbContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(width, thumbH)
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
        if (item.isDir) {
            thumbContainer.addView(TextView(ctx).apply {
                text = "[DIR]"; textSize = 28f / density
                setTextColor(Color.parseColor("#333333"))
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        } else {
            val imageView = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            thumbContainer.addView(imageView)

            loadThumbnail(item.path, width, thumbH, imageView)
        }
        cell.addView(thumbContainer)

        val textContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = dp(36)
        }

        cell.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#D8D8D8"))
        })
        textContainer.addView(TextView(ctx).apply {
            text = item.name; textSize = 12f; setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER; maxLines = 2
        })
        if (!item.isDir && item.size > 0) {
            textContainer.addView(TextView(ctx).apply {
                text = formatSize(item.size); textSize = 10f
                setTextColor(Color.parseColor("#666666")); gravity = Gravity.CENTER
            })
        }
        cell.addView(textContainer)

        return cell
    }

    private fun loadThumbnail(path: String, reqW: Int, reqH: Int, imageView: ImageView) {
        thread(isDaemon = true) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                opts.inSampleSize = calcSampleSize(opts.outWidth, opts.outHeight, reqW, reqH)
                opts.inJustDecodeBounds = false
                val bmp = BitmapFactory.decodeFile(path, opts) ?: return@thread
                handler.post { imageView.setImageBitmap(bmp) }
            } catch (_: Exception) {}
        }
    }

    private fun calcSampleSize(outW: Int, outH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (outH > reqH || outW > reqW) {
            val halfH = outH / 2; val halfW = outW / 2
            while (halfH / sample >= reqH && halfW / sample >= reqW) sample *= 2
        }
        return sample
    }

    private fun switchToCropper() {
        val imgPath = selectedImagePath ?: return
        isInCropper = true
        val content = mainContent ?: return
        content.removeAllViews()
        bottomBar?.removeAllViews()

        cropBitmap?.recycle()
        val bmp = BitmapFactory.decodeFile(imgPath)
        if (bmp == null) { Log.w(TAG, "switchToCropper: cannot decode $imgPath"); switchToBrowser(); return }
        cropBitmap = bmp

        val cropView = CropImageView(reactContext, bmp) { rect -> cropRect = rect }
        content.addView(cropView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        bottomBar?.addView(makeDivider())
        val bar = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        bar.addView(TextView(reactContext).apply {
            text = NativeLocale.t("insert_original")
            textSize = 15f; setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { doInsertOriginal() }
        })

        bar.addView(View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#D8D8D8"))
        })

        bar.addView(TextView(reactContext).apply {
            text = NativeLocale.t("crop_and_insert")
            textSize = 15f; setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { doCropAndInsert() }
        })
        bottomBar?.addView(bar)
    }

    private fun switchToBrowser() {
        isInCropper = false
        cropBitmap?.recycle(); cropBitmap = null
        val content = mainContent ?: return
        content.removeAllViews()
        contentScroll = ScrollView(reactContext)
        contentGrid = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        contentScroll!!.addView(contentGrid)
        content.addView(contentScroll)

        bottomBar?.removeAllViews()
        val newBar = createBottomBar()

        while (newBar.childCount > 0) {
            val child = newBar.getChildAt(0)
            newBar.removeViewAt(0)
            bottomBar?.addView(child)
        }

        refreshContent()
    }

    private fun doInsertOriginal() {
        val path = selectedImagePath ?: return
        val shouldEnhance = !skipEnhance
        hide()
        thread(isDaemon = true) {
            var insertPath = path
            var tempFile: java.io.File? = null
            if (shouldEnhance) {
                try {
                    val enhanced = enhanceImageNative(path)
                    if (enhanced != path) {
                        tempFile = java.io.File(enhanced)
                        insertPath = enhanced
                    }
                } catch (_: Exception) {}
            }
            val fileToDelete = tempFile
            handler.post {
                toolbarModule.requestInsertImage(insertPath)
                handler.postDelayed({
                    try { fileToDelete?.delete() } catch (_: Exception) {}
                }, 2500)
            }
        }
    }

    private fun doCropAndInsert() {
        val bmp = cropBitmap ?: return
        val path = selectedImagePath ?: return
        val shouldEnhance = !skipEnhance

        val x = max(0, cropRect.left.toInt())
        val y = max(0, cropRect.top.toInt())
        val w = min(bmp.width - x, max(1, cropRect.width().toInt()))
        val h = min(bmp.height - y, max(1, cropRect.height().toInt()))

        thread(isDaemon = true) {
            try {
                val cropped = Bitmap.createBitmap(bmp, x, y, w, h)
                val croppedFile = File(path.replaceAfterLast('.', "cropped.png"))
                FileOutputStream(croppedFile).use { fos ->
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                cropped.recycle()

                var insertPath = croppedFile.absolutePath
                var enhancedFile: File? = null
                if (shouldEnhance) {
                    try {
                        val enhanced = enhanceImageNative(insertPath)
                        if (enhanced != insertPath) {
                            enhancedFile = File(enhanced)
                            insertPath = enhanced
                        }
                    } catch (_: Exception) {}
                }
                val filesToDelete = listOfNotNull(croppedFile, enhancedFile)
                handler.post {
                    hide()
                    toolbarModule.requestInsertImage(insertPath)
                    handler.postDelayed({
                        filesToDelete.forEach { f -> try { f.delete() } catch (_: Exception) {} }
                    }, 2500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Crop error: ${e.message}", e)
            }
        }
    }

    private fun enhanceImageNative(inputPath: String): String {
        val raw = BitmapFactory.decodeFile(inputPath) ?: return inputPath

        val maxW = 1600; val maxH = 2200
        val src = if (raw.width > maxW || raw.height > maxH) {
            val ratio = minOf(maxW.toFloat() / raw.width, maxH.toFloat() / raw.height)
            val nw = (raw.width * ratio).toInt().coerceAtLeast(1)
            val nh = (raw.height * ratio).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(raw, nw, nh, true)
            if (scaled !== raw) raw.recycle()
            scaled
        } else raw
        if (src === raw) return inputPath
        val outFile = File(inputPath.replaceAfterLast('.', "resized.png"))
        FileOutputStream(outFile).use { fos -> src.compress(Bitmap.CompressFormat.PNG, 100, fos) }
        src.recycle()
        return outFile.absolutePath
    }

    private fun closeAndRestore() {
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun formatSize(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${"%.1f".format(size / 1024.0)} KB"
        else -> "${"%.1f".format(size / (1024.0 * 1024.0))} MB"
    }

    private fun makeMinBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = label; textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(dp(1), Color.parseColor("#999999"))
                cornerRadius = dp(4).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeOutlinedBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = label; textSize = 15f; setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(10), dp(28), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(dp(1), Color.BLACK)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(12) }
            setOnClickListener { onClick() }
        }
    }

    private fun makeFilledBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = label; textSize = 15f; setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(10), dp(28), dp(10))
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    private fun makeDivider(): View {
        return View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
            setBackgroundColor(Color.parseColor("#D8D8D8"))
        }
    }

    private fun makeEmptyView(text: String): LinearLayout {
        return LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(60), dp(30), 0)
            addView(TextView(reactContext).apply {
                this.text = text; textSize = 14f
                setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER; setLineSpacing(dp(4).toFloat(), 1f)
            })
        }
    }
}

class CropImageView(
    context: android.content.Context,
    private val bitmap: Bitmap,
    private val onCropChanged: (RectF) -> Unit
) : View(context) {

    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(90, 0, 0, 0) }
    private val borderPaint = Paint().apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val handlePaint = Paint().apply { color = Color.BLACK }

    private var imgRect = RectF()

    private var cropBox = RectF()

    private val HANDLE_SIZE = 14f
    private val EDGE_ZONE = 40f
    private val MIN_CROP = 50f

    private var dragMode: String? = null
    private var dragStartX = 0f; private var dragStartY = 0f
    private var dragStartBox = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val vw = w.toFloat(); val vh = h.toFloat()
        val imgAspect = bitmap.width.toFloat() / bitmap.height
        val viewAspect = vw / vh
        val dw: Float; val dh: Float; val dx: Float; val dy: Float
        if (imgAspect > viewAspect) {
            dw = vw; dh = vw / imgAspect; dx = 0f; dy = (vh - dh) / 2f
        } else {
            dh = vh; dw = vh * imgAspect; dx = (vw - dw) / 2f; dy = 0f
        }
        imgRect.set(dx, dy, dx + dw, dy + dh)

        cropBox.set(imgRect)
        notifyCrop()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawBitmap(bitmap, null, imgRect, imgPaint)

        canvas.drawRect(imgRect.left, imgRect.top, imgRect.right, cropBox.top, dimPaint)

        canvas.drawRect(imgRect.left, cropBox.bottom, imgRect.right, imgRect.bottom, dimPaint)

        canvas.drawRect(imgRect.left, cropBox.top, cropBox.left, cropBox.bottom, dimPaint)

        canvas.drawRect(cropBox.right, cropBox.top, imgRect.right, cropBox.bottom, dimPaint)

        canvas.drawRect(cropBox, borderPaint)

        val hs = HANDLE_SIZE
        canvas.drawRect(cropBox.left - hs/2, cropBox.top - hs/2, cropBox.left + hs/2, cropBox.top + hs/2, handlePaint)
        canvas.drawRect(cropBox.right - hs/2, cropBox.top - hs/2, cropBox.right + hs/2, cropBox.top + hs/2, handlePaint)
        canvas.drawRect(cropBox.left - hs/2, cropBox.bottom - hs/2, cropBox.left + hs/2, cropBox.bottom + hs/2, handlePaint)
        canvas.drawRect(cropBox.right - hs/2, cropBox.bottom - hs/2, cropBox.right + hs/2, cropBox.bottom + hs/2, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = detectMode(x, y)
                if (dragMode == null) return false
                dragStartX = x; dragStartY = y; dragStartBox = RectF(cropBox)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - dragStartX; val dy = y - dragStartY
                val ob = dragStartBox
                when (dragMode) {
                    "move" -> { cropBox.set(ob.left + dx, ob.top + dy, ob.right + dx, ob.bottom + dy) }
                    "tl" -> { cropBox.left = ob.left + dx; cropBox.top = ob.top + dy }
                    "tr" -> { cropBox.right = ob.right + dx; cropBox.top = ob.top + dy }
                    "bl" -> { cropBox.left = ob.left + dx; cropBox.bottom = ob.bottom + dy }
                    "br" -> { cropBox.right = ob.right + dx; cropBox.bottom = ob.bottom + dy }
                    "t" -> cropBox.top = ob.top + dy
                    "b" -> cropBox.bottom = ob.bottom + dy
                    "l" -> cropBox.left = ob.left + dx
                    "r" -> cropBox.right = ob.right + dx
                }
                clampCropBox()
                notifyCrop()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = null; return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun detectMode(x: Float, y: Float): String? {
        val ez = EDGE_ZONE
        val nearL = abs(x - cropBox.left) < ez; val nearR = abs(x - cropBox.right) < ez
        val nearT = abs(y - cropBox.top) < ez; val nearB = abs(y - cropBox.bottom) < ez
        if (nearT && nearL) return "tl"; if (nearT && nearR) return "tr"
        if (nearB && nearL) return "bl"; if (nearB && nearR) return "br"
        if (nearT) return "t"; if (nearB) return "b"
        if (nearL) return "l"; if (nearR) return "r"
        if (x in cropBox.left..cropBox.right && y in cropBox.top..cropBox.bottom) return "move"
        return null
    }

    private fun clampCropBox() {
        if (cropBox.width() < MIN_CROP) cropBox.right = cropBox.left + MIN_CROP
        if (cropBox.height() < MIN_CROP) cropBox.bottom = cropBox.top + MIN_CROP
        if (cropBox.left < imgRect.left) { cropBox.right += imgRect.left - cropBox.left; cropBox.left = imgRect.left }
        if (cropBox.top < imgRect.top) { cropBox.bottom += imgRect.top - cropBox.top; cropBox.top = imgRect.top }
        if (cropBox.right > imgRect.right) { cropBox.left -= cropBox.right - imgRect.right; cropBox.right = imgRect.right }
        if (cropBox.bottom > imgRect.bottom) { cropBox.top -= cropBox.bottom - imgRect.bottom; cropBox.bottom = imgRect.bottom }
        cropBox.left = cropBox.left.coerceIn(imgRect.left, imgRect.right - MIN_CROP)
        cropBox.top = cropBox.top.coerceIn(imgRect.top, imgRect.bottom - MIN_CROP)
        cropBox.right = cropBox.right.coerceIn(cropBox.left + MIN_CROP, imgRect.right)
        cropBox.bottom = cropBox.bottom.coerceIn(cropBox.top + MIN_CROP, imgRect.bottom)
    }

    private fun notifyCrop() {
        if (imgRect.width() <= 0 || imgRect.height() <= 0) return
        val sx = bitmap.width / imgRect.width()
        val sy = bitmap.height / imgRect.height()
        onCropChanged(RectF(
            (cropBox.left - imgRect.left) * sx,
            (cropBox.top - imgRect.top) * sy,
            (cropBox.right - imgRect.left) * sx,
            (cropBox.bottom - imgRect.top) * sy
        ))
    }
}
