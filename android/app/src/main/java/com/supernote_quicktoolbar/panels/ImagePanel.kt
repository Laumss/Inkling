package com.supernote_quicktoolbar.panels
import com.supernote_quicktoolbar.BuildConfig
import com.supernote_quicktoolbar.*

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.*
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.ui_common.BrowseDirs
import com.supernote_quicktoolbar.ui_common.ButtonHandle
import com.supernote_quicktoolbar.ui_common.CheckboxHandle
import com.supernote_quicktoolbar.ui_common.ChipsHandle
import com.supernote_quicktoolbar.ui_common.FolderCoverView
import com.supernote_quicktoolbar.ui_common.GridHandle
import com.supernote_quicktoolbar.ui_common.MultiSelectState
import com.supernote_quicktoolbar.ui_common.PanelBase
import com.supernote_quicktoolbar.ui_common.PanelChips
import com.supernote_quicktoolbar.ui_common.PanelHost
import com.supernote_quicktoolbar.ui_common.PanelTabBar
import com.supernote_quicktoolbar.ui_common.PanelWidgets
import com.supernote_quicktoolbar.ui_common.TabBarHandle
import java.io.File
import kotlin.concurrent.thread

class ImagePanel(
    ctx: ReactApplicationContext,
    toolbar: FloatingToolbarModule
) : PanelBase(ctx, toolbar) {

    override val tag = "ImagePanel"
    override val panelName = "image"

    companion object {
        @Volatile var currentInstance: ImagePanel? = null
        val imageQueue = mutableListOf<String>()

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): ImagePanel {
            val inst = currentInstance ?: ImagePanel(ctx, module)
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
                val src = File(imagePath)
                if (!src.exists()) return
                val cacheDir = File(src.parentFile, ".insert_cache")
                cacheDir.mkdirs()
                val meta = File(cacheDir, "${src.nameWithoutExtension}.meta")
                meta.writeText("$notePath\n$pageNum\n$imagePath")
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e("ImagePanel", "saveToInsertCacheStatic: ${e.message}")
            }
        }
    }

    data class GridItem(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long = 0,
        val childCount: Int = 0,
        val previewPaths: List<String> = emptyList()
    )

    private var activeTab = "received"
    private var browseDir: String? = null
    private var currentBrowsePath: String = "/sdcard"
    private var selectedImagePath: String? = null
    private var allItems: List<GridItem> = emptyList()
    private val multi = MultiSelectState()
    private val cellFrameMap = mutableMapOf<String, FrameLayout>()

    private lateinit var tabBarH: TabBarHandle
    private lateinit var chipsH: ChipsHandle
    private lateinit var gridH: GridHandle
    private lateinit var cropBtn: ButtonHandle
    private lateinit var insertBtn: ButtonHandle
    private lateinit var multiCheckbox: CheckboxHandle
    private var chipsView: View? = null

    fun show() {
        currentInstance = this
        selectedImagePath = null
        multi.clear()
        activeTab = "received"; browseDir = null; currentBrowsePath = "/sdcard"
        showPanel()
        handler.post { refreshContent() }
    }

    override fun buildContent(root: LinearLayout) {
        renderDsl(root) {
            header(NativeLocale.t("image_panel_title"))

            tabBarH = tabBar(
                listOf(
                    PanelTabBar.Tab.Icon("icons/ic_tab_received.xml", "received"),
                    PanelTabBar.Tab.Icon("icons/ic_tab_browse.xml", "browse")
                ),
                initial = 0
            ) { idx -> switchTab(if (idx == 0) "received" else "browse") }

            custom { host ->
                val c = PanelChips(host.ctx, BrowseDirs.KEYS) { selected ->
                    if (activeTab == "browse" || selected != null) {
                        activeTab = "browse"
                        tabBarH.setSelection(1)
                        browseDir = selected
                        currentBrowsePath = BrowseDirs.pathForKey(selected)
                    }
                    chipsH.setSelection(browseDir)
                    refreshContent()
                }
                chipsH = ChipsHandle().also { it.chips = c }
                c.createView().also {
                    chipsView = it
                    it.visibility = if (activeTab == "received") View.GONE else View.VISIBLE
                }
            }

            gridH = grid(
                itemsProvider = { allItems },
                emptyText = if (activeTab == "received") NativeLocale.t("no_received") else NativeLocale.t("no_images")
            ) { host, item, colW -> gridCell(host, item, colW) }

            bottomBar {
                multiCheckbox = checkbox(NativeLocale.t("multi_select")) { toggleMultiSelect() }
                outlined(NativeLocale.t("cancel")) { closeAndRestore() }
                cropBtn = outlined(NativeLocale.t("crop_and_insert")) { doCropAndInsert() }
                insertBtn = filled(NativeLocale.t("insert")) { doInsertOriginal() }
            }
        }
        cropBtn.enabled = false
        insertBtn.enabled = false
        updateCheckboxEnabled()
    }

    override fun onHide() {
        cellFrameMap.clear()
        currentInstance = null
    }

    fun onFileReceived() {
        handler.post {
            if (activeTab == "received" && rootView != null) {
                refreshContent(clearSelection = false)
            }
        }
    }

    private fun switchTab(tab: String) {
        activeTab = tab
        if (tab == "received") browseDir = null
        chipsH.setSelection(browseDir)
        chipsView?.visibility = if (tab == "received") View.GONE else View.VISIBLE
        refreshContent()
    }

    private fun refreshContent() { refreshContent(clearSelection = true) }

    private fun refreshContent(clearSelection: Boolean) {
        if (clearSelection) {
            selectedImagePath = null
            cropBtn.enabled = false
            insertBtn.enabled = false
        }

        if (activeTab == "received") {
            val files = LocalSendModule.getReceivedImageFiles()
            if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "refreshContent: received tab, session files=${files.size}")
            allItems = if (files.isEmpty()) emptyList()
                       else files.map { GridItem(it.name, it.path, false, it.size) }
        } else {
            loadItems(currentBrowsePath)
        }
        cellFrameMap.clear()
        gridH.refresh()
        gridH.scrollTop()
    }

    private fun loadItems(path: String) {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            allItems = emptyList()
            return
        }
        allItems = (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .filter { f ->
                if (f.isDirectory) {
                    if (path == "/sdcard") ALLOWED_ROOT_FOLDERS.contains(f.name) else true
                } else {
                    IMAGE_EXTS.contains(f.extension.lowercase())
                }
            }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
            .map { f ->
                if (f.isDirectory) {
                    val children = f.listFiles()
                    val previews = children
                        ?.asSequence()
                        ?.filter { c -> !c.name.startsWith(".") && !c.isDirectory }
                        ?.filter { c -> IMAGE_EXTS.contains(c.extension.lowercase()) }
                        ?.sortedBy { it.name }
                        ?.take(4)
                        ?.map { it.absolutePath }
                        ?.toList()
                        ?: emptyList()
                    GridItem(f.name, f.absolutePath, true, 0L, children?.size ?: 0, previews)
                } else {
                    GridItem(f.name, f.absolutePath, false, f.length(), 0, emptyList())
                }
            }
    }

    private fun gridCell(host: PanelHost, item: GridItem, colW: Int): View {
        val thumbH = (colW * 1.22f).toInt()
        val isSelected = if (multi.isActive) {
            !item.isDir && multi.isSelected(item.path)
        } else {
            !item.isDir && selectedImagePath == item.path
        }

        val cell = LinearLayout(host.ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(colW, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                if (item.isDir) {
                    currentBrowsePath = item.path
                    val matchedKey = BrowseDirs.KEYS.firstOrNull { BrowseDirs.pathForKey(it) == item.path }
                    browseDir = matchedKey
                    chipsH.setSelection(matchedKey)
                    refreshContent()
                } else if (multi.isActive) {
                    multi.toggle(item.path)
                    updateMultiSelectUI()
                    gridH.refresh()
                } else {
                    selectedImagePath = if (selectedImagePath == item.path) null else item.path
                    val hasSelection = selectedImagePath != null
                    cropBtn.enabled = hasSelection
                    insertBtn.enabled = hasSelection
                    updateCheckboxEnabled()
                    gridH.refresh()
                }
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
        if (item.isDir) {
            thumbFrame.clipToOutline = false
            thumbFrame.background = null
            thumbFrame.setBackgroundColor(Color.WHITE)
            val cover = FolderCoverView(host.ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            thumbFrame.addView(cover)
            cover.post {
                cover.setupChildSlots(cover.width, cover.height)
                val slotW = (cover.width * 0.34f).toInt()
                val slotH = (slotW * 1.31f).toInt()
                item.previewPaths.forEachIndexed { i, p ->
                    val slotView = when (i) { 0 -> cover.child1; 1 -> cover.child2; 2 -> cover.child3; else -> cover.child4 }
                    loadThumbnail(p, slotW, slotH, slotView)
                    slotView.visibility = View.VISIBLE
                }
            }
        } else {
            val imageView = ImageView(host.ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(host.dp(2), host.dp(2), host.dp(2), host.dp(2))
            }
            thumbFrame.addView(imageView)
            loadThumbnail(item.path, colW, thumbH, imageView)
        }
        if (isSelected && multi.isActive) {
            thumbFrame.addView(makeCheckmark(host))
        }
        if (!item.isDir) cellFrameMap[item.path] = thumbFrame
        cell.addView(thumbFrame)

        val textContainer = LinearLayout(host.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(2), host.dp(6), host.dp(2), host.dp(4))
        }
        textContainer.addView(TextView(host.ctx).apply {
            text = item.name; textSize = host.sp(12f); setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 2
        })
        if (item.isDir) {
            textContainer.addView(TextView(host.ctx).apply {
                text = NativeLocale.itemCount(item.childCount)
                textSize = host.sp(10f); setTextColor(Color.parseColor("#666666"))
            })
        } else if (item.size > 0) {
            textContainer.addView(TextView(host.ctx).apply {
                text = PanelWidgets.formatSize(item.size); textSize = host.sp(10f)
                setTextColor(Color.parseColor("#666666"))
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

    private fun makeCheckmark(host: PanelHost): TextView = TextView(host.ctx).apply {
        tag = "checkmark"
        text = "✓"; textSize = host.sp(12f); setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Color.BLACK); cornerRadius = host.dp(10).toFloat()
        }
        layoutParams = FrameLayout.LayoutParams(host.dp(20), host.dp(20)).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, host.dp(4), host.dp(4), 0)
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

    private fun doInsertOriginal() {
        if (multi.isActive) {
            if (multi.count == 0) return
            val paths = multi.selectedPaths
            multi.clear()
            synchronized(ImagePanel::class.java) {
                imageQueue.clear()
                imageQueue.addAll(paths.drop(1))
                if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "[QUEUE-DBG] doInsertOriginal: selected=${paths.size} queued=${imageQueue.size} queue=${imageQueue.toList()}")
            }
            hide()
            toolbarModule.requestInsertImage(paths.first())
        } else {
            val path = selectedImagePath ?: return
            hide()
            toolbarModule.requestInsertImage(path)
        }
    }

    private fun doCropAndInsert() {
        val path = selectedImagePath ?: return
        if (BuildConfig.ENABLE_DEBUG) Log.i("ImagePanel", "[CROP] doCropAndInsert path=$path")
        hide()
        handler.postDelayed({
            if (BuildConfig.ENABLE_DEBUG) Log.i("ImagePanel", "[CROP] opening CropPanel")
            CropPanel.getInstance(reactContext, toolbarModule).show(path) { crop ->
            kotlin.concurrent.thread(isDaemon = true) {
                try {
                    val src = BitmapFactory.decodeFile(path) ?: return@thread
                    val cropped = Bitmap.createBitmap(src, crop.offsetX, crop.offsetY, crop.width, crop.height)
                    val output = if (crop.opacity < 100) {
                        val alphaBmp = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(alphaBmp)
                        val paint = android.graphics.Paint().apply { alpha = (255 * crop.opacity / 100) }
                        canvas.drawBitmap(cropped, 0f, 0f, paint)
                        cropped.recycle()
                        alphaBmp
                    } else cropped
                    val outPath = "${reactContext.cacheDir.absolutePath}/crop_${System.currentTimeMillis()}.png"
                    java.io.FileOutputStream(outPath).use { fos ->
                        output.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    if (output != src) output.recycle()
                    src.recycle()
                    handler.post { toolbarModule.requestInsertImage(outPath) }
                } catch (e: Exception) {
                    if (BuildConfig.ENABLE_DEBUG) Log.e("ImagePanel", "crop failed: ${e.message}", e)
                    handler.post { toolbarModule.restoreToolbar() }
                }
            }
        }
        }, 150)
    }

    private fun closeAndRestore() {
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun toggleMultiSelect() {
        val canToggle = multi.isActive || selectedImagePath != null
        if (!canToggle) return
        if (multi.isActive) {
            multi.deactivate()
            selectedImagePath = null
            cropBtn.enabled = false
            insertBtn.enabled = false
        } else {
            multi.activate(seed = selectedImagePath)
            selectedImagePath = null
            cropBtn.enabled = false
        }
        updateMultiSelectUI()
        cellFrameMap.clear()
        gridH.refresh()
    }

    private fun updateMultiSelectUI() {
        if (multi.isActive) {
            multiCheckbox.setChecked(true)
            multiCheckbox.setLabel("${NativeLocale.t("multi_select")} (${multi.count})")
            multiCheckbox.setActive(true)
            cropBtn.enabled = false
            insertBtn.enabled = multi.count > 0
        } else {
            multiCheckbox.setChecked(false)
            multiCheckbox.setLabel(NativeLocale.t("multi_select"))
            updateCheckboxEnabled()
        }
    }

    private fun updateCheckboxEnabled() {
        multiCheckbox.setActive(multi.isActive || selectedImagePath != null)
    }
}
