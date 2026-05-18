package com.supernote_quicktoolbar.panels
import com.supernote_quicktoolbar.*
import com.supernote_quicktoolbar.overlays.*
import com.supernote_quicktoolbar.bubbles.*

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.*
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.ui_common.FolderCoverView
import com.supernote_quicktoolbar.ui_common.PanelBase
import com.supernote_quicktoolbar.ui_common.PanelChips
import com.supernote_quicktoolbar.ui_common.PanelHeader
import com.supernote_quicktoolbar.ui_common.PanelScrollHost
import com.supernote_quicktoolbar.ui_common.PanelTabBar
import com.supernote_quicktoolbar.ui_common.SelectionButton
import java.io.File
import java.io.FileOutputStream
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
                Log.e("ImagePanel", "saveToInsertCacheStatic: ${e.message}")
            }
        }
    }

    private var activeTab = "received"
    private var browseDir: String? = null
    private var currentBrowsePath: String = "/sdcard"
    private var selectedImagePath: String? = null
    private var allItems: List<GridItem> = emptyList()

    private var multiSelectMode = false
    private val multiSelectedPaths = linkedSetOf<String>()

    private val DEST_DIR_MAP = mapOf(
        "Document" to "/sdcard/Document", "Export" to "/sdcard/EXPORT",
        "MyStyle" to "/sdcard/MyStyle", "Note" to "/sdcard/Note",
        "SCREENSHOT" to "/sdcard/SCREENSHOT", "INBOX" to "/sdcard/INBOX"
    )
    private val DIR_KEYS = listOf("Document", "Export", "MyStyle", "Note", "SCREENSHOT", "INBOX")

    private var contentGrid: LinearLayout? = null
    private var scrollHost: PanelScrollHost? = null
    private var tabBar: PanelTabBar? = null
    private var chips: PanelChips? = null
    private var chipsView: View? = null
    private var cropBtn: SelectionButton? = null
    private var insertBtn: SelectionButton? = null

    private var multiSelectContainer: LinearLayout? = null
    private var checkboxBg: View? = null
    private var checkboxMark: TextView? = null
    private var checkboxLabel: TextView? = null

    fun show() {
        currentInstance = this
        selectedImagePath = null
        multiSelectMode = false
        multiSelectedPaths.clear()
        activeTab = "received"; browseDir = null; currentBrowsePath = "/sdcard"
        showPanel()
        handler.post { refreshContent() }
    }

    override fun hide() {
        handler.post {
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            rootView = null; windowManager = null
            onHide()
            toolbarModule.disablePenBlock()
            toolbarModule.emitEventPublic("onNativePanelClose",
                Arguments.createMap().apply { putString("panel", "image") })
        }
    }

    override fun onHide() {
        contentGrid = null
        scrollHost = null; tabBar = null; chips = null; chipsView = null
        cropBtn = null; insertBtn = null
        multiSelectContainer = null; checkboxBg = null; checkboxMark = null; checkboxLabel = null
        currentInstance = null
    }

    fun onFileReceived() {
        handler.post {
            if (activeTab == "received" && rootView != null) {
                refreshContent(clearSelection = false)
            }
        }
    }

    override fun buildContent(root: LinearLayout) {
        root.addView(PanelHeader.create(reactContext, NativeLocale.t("image_panel_title")))

        tabBar = PanelTabBar(reactContext, listOf(
            PanelTabBar.Tab.Icon("icons/ic_tab_received.xml", "received"),
            PanelTabBar.Tab.Icon("icons/ic_tab_browse.xml", "browse")
        )) { idx -> switchTab(if (idx == 0) "received" else "browse") }
        tabBar!!.setSelection(0)
        root.addView(tabBar!!.createView())

        chips = PanelChips(reactContext, DIR_KEYS) { selected ->
            if (activeTab == "browse" || selected != null) {
                activeTab = "browse"
                tabBar?.setSelection(1)
                browseDir = selected
                currentBrowsePath = if (selected != null) DEST_DIR_MAP[selected] ?: "/sdcard" else "/sdcard"
            }
            chips?.rebuildChips()
            refreshContent()
        }
        chipsView = chips!!.createView().apply {
            visibility = if (activeTab == "received") View.GONE else View.VISIBLE
        }
        root.addView(chipsView)

        scrollHost = PanelScrollHost(reactContext)
        contentGrid = scrollHost!!.content

        contentGrid!!.setPadding(
            contentGrid!!.paddingLeft,
            contentGrid!!.paddingTop,
            0,
            contentGrid!!.paddingBottom
        )
        root.addView(scrollHost!!.view)

        val cropTv = makeOutlinedBtn(NativeLocale.t("crop_and_insert")) { doCropAndInsert() }
        val insertTv = makeFilledBtn(NativeLocale.t("insert")) { doInsertOriginal() }
        cropBtn = SelectionButton(cropTv)
        insertBtn = SelectionButton(insertTv)

        val cbContainer = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            alpha = 0.4f
        }
        val checkSize = dp(24)
        val cbFrame = FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(checkSize, checkSize).apply { rightMargin = dp(8) }
        }
        checkboxBg = View(reactContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(2), Color.parseColor("#999999"))
                cornerRadius = dp(3).toFloat()
            }
        }
        cbFrame.addView(checkboxBg)
        checkboxMark = TextView(reactContext).apply {
            text = "✓"; textSize = 15f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        cbFrame.addView(checkboxMark)
        cbContainer.addView(cbFrame)
        checkboxLabel = TextView(reactContext).apply {
            text = NativeLocale.t("multi_select"); textSize = 15f
            setTextColor(Color.parseColor("#999999"))
        }
        cbContainer.addView(checkboxLabel)
        cbContainer.setOnClickListener { toggleMultiSelect() }
        multiSelectContainer = cbContainer

        root.addView(makeBottomBar(
            leftFlex = cbContainer,
            rightButtons = listOf(
                makeOutlinedBtn(NativeLocale.t("cancel")) { closeAndRestore() },
                cropTv,
                insertTv
            )
        ))
    }

    private fun switchTab(tab: String) {
        activeTab = tab
        if (tab == "received") browseDir = null
        chips?.setSelection(browseDir)
        chips?.rebuildChips()
        chipsView?.visibility = if (tab == "received") View.GONE else View.VISIBLE
        refreshContent()
    }

    private fun refreshContent() { refreshContent(clearSelection = true) }

    private fun refreshContent(clearSelection: Boolean) {
        if (clearSelection) {
            selectedImagePath = null
            cropBtn?.update(false)
            insertBtn?.update(false)
        }

        if (activeTab == "received") {
            val files = LocalSendModule.getReceivedImageFiles()
            Log.i(tag, "refreshContent: received tab, session files=${files.size}")
            allItems = if (files.isEmpty()) emptyList()
                       else files.map { GridItem(it.name, it.path, false, it.size) }
        } else {
            loadItems(currentBrowsePath)
        }
        showPage()
    }

    private fun showPage(resetScroll: Boolean = true) {
        val grid = contentGrid ?: return
        if (resetScroll) scrollHost?.prepareForContentChange()
        grid.removeAllViews()

        if (allItems.isEmpty()) {
            val msg = if (activeTab == "received") NativeLocale.t("no_received") else NativeLocale.t("no_images")
            grid.addView(makeEmptyView(msg))
            scrollHost?.refreshThumb()
            return
        }

        buildImageGrid(allItems)
        if (resetScroll) scrollHost?.scrollToTop() else scrollHost?.refreshThumb()
    }

    data class GridItem(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long = 0,
        val childCount: Int = 0,
        val previewPaths: List<String> = emptyList()
    )

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

    private fun buildImageGrid(items: List<GridItem>) {
        val grid = contentGrid ?: return

        val sideLeft = dp(22)
        val sideRight = 0
        val midGap = dp(18)
        val rowTopPad = dp(6)
        val innerW = scrollHost?.availableContentWidth(winW) ?: winW
        val colW = (innerW - sideLeft - sideRight - midGap) / 2

        var rowLayout: LinearLayout? = null
        for ((idx, item) in items.withIndex()) {
            if (idx % 2 == 0) {
                rowLayout = LinearLayout(reactContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(sideLeft, rowTopPad, sideRight, 0)
                }
                grid.addView(rowLayout)
            }
            val cell = createGridCell(item, colW)
            (cell.layoutParams as? LinearLayout.LayoutParams)?.apply {
                if (idx % 2 == 0) rightMargin = midGap
            }
            rowLayout?.addView(cell)
        }
        if (items.size % 2 != 0) {
            rowLayout?.addView(View(reactContext).apply {
                layoutParams = LinearLayout.LayoutParams(colW, 1)
            })
        }
    }

    private fun createGridCell(item: GridItem, width: Int): LinearLayout {
        val thumbH = (width * 1.1f).toInt()
        val isSelected = if (multiSelectMode) {
            !item.isDir && item.path in multiSelectedPaths
        } else {
            !item.isDir && selectedImagePath == item.path
        }

        val cell = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                if (item.isDir) {
                    currentBrowsePath = item.path
                    refreshContent()
                } else if (multiSelectMode) {
                    if (item.path in multiSelectedPaths) multiSelectedPaths.remove(item.path)
                    else multiSelectedPaths.add(item.path)
                    updateMultiSelectUI()
                    showPage(resetScroll = false)
                } else {
                    selectedImagePath = if (selectedImagePath == item.path) null else item.path
                    val hasSelection = selectedImagePath != null
                    cropBtn?.update(hasSelection)
                    insertBtn?.update(hasSelection)
                    updateCheckboxEnabled()
                    showPage(resetScroll = false)
                }
            }
        }

        val thumbFrame = FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(width, thumbH)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(if (isSelected) dp(2) else dp(1),
                    if (isSelected) Color.BLACK else Color.parseColor("#CCCCCC"))
                cornerRadius = dp(4).toFloat()
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(4).toFloat())
                }
            }
        }
        if (item.isDir) {

            thumbFrame.background = null
            thumbFrame.setBackgroundColor(Color.WHITE)
            val cover = FolderCoverView(reactContext).apply {
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
            val imageView = ImageView(reactContext).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            thumbFrame.addView(imageView)
            loadThumbnail(item.path, width, thumbH, imageView)
        }
        if (isSelected && multiSelectMode) {
            thumbFrame.addView(TextView(reactContext).apply {
                text = "✓"; textSize = 12f; setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.BLACK); cornerRadius = dp(10).toFloat()
                }
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, dp(4), dp(4), 0)
                }
            })
        }
        cell.addView(thumbFrame)

        val textContainer = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(6), dp(2), dp(4))
        }
        textContainer.addView(TextView(reactContext).apply {
            text = item.name; textSize = 12f; setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 2
        })
        if (item.isDir) {
            textContainer.addView(TextView(reactContext).apply {
                text = NativeLocale.itemCount(item.childCount)
                textSize = 10f; setTextColor(Color.parseColor("#666666"))
            })
        } else if (item.size > 0) {
            textContainer.addView(TextView(reactContext).apply {
                text = formatSize(item.size); textSize = 10f
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

    private fun calcSampleSize(outW: Int, outH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (outH > reqH || outW > reqW) {
            val halfH = outH / 2; val halfW = outW / 2
            while (halfH / sample >= reqH && halfW / sample >= reqW) sample *= 2
        }
        return sample
    }

    private fun doInsertOriginal() {
        if (multiSelectMode) {
            if (multiSelectedPaths.isEmpty()) return
            val paths = multiSelectedPaths.toList()
            multiSelectedPaths.clear()
            multiSelectMode = false
            synchronized(ImagePanel::class.java) {
                imageQueue.clear()
                imageQueue.addAll(paths.drop(1))
                Log.i(tag, "[QUEUE-DBG] doInsertOriginal: selected=${paths.size} queued=${imageQueue.size} queue=${imageQueue.toList()}")
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
        Log.i("ImagePanel", "[CROP] doCropAndInsert path=$path")
        hide()
        handler.postDelayed({
            Log.i("ImagePanel", "[CROP] opening CropPanel")
            CropPanel.getInstance(reactContext, toolbarModule).show(path) { crop ->
            kotlin.concurrent.thread(isDaemon = true) {
                try {
                    val src = BitmapFactory.decodeFile(path) ?: return@thread
                    val cropped = Bitmap.createBitmap(src, crop.offsetX, crop.offsetY, crop.width, crop.height)
                    src.recycle()
                    val outPath = "${reactContext.cacheDir.absolutePath}/crop_${System.currentTimeMillis()}.png"
                    java.io.FileOutputStream(outPath).use { fos ->
                        cropped.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    cropped.recycle()
                    handler.post { toolbarModule.requestInsertImage(outPath) }
                } catch (e: Exception) {
                    Log.e("ImagePanel", "crop failed: ${e.message}", e)
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
        val canToggle = multiSelectMode || selectedImagePath != null
        if (!canToggle) return
        if (multiSelectMode) {
            multiSelectMode = false
            multiSelectedPaths.clear()
            selectedImagePath = null
            cropBtn?.update(false)
            insertBtn?.update(false)
        } else {
            multiSelectMode = true
            selectedImagePath?.let { multiSelectedPaths.add(it) }
            selectedImagePath = null
            cropBtn?.update(false)
        }
        updateMultiSelectUI()
        showPage(resetScroll = false)
    }

    private fun updateMultiSelectUI() {
        if (multiSelectMode) {
            val count = multiSelectedPaths.size
            checkboxBg?.background = GradientDrawable().apply {
                setColor(Color.BLACK); cornerRadius = dp(3).toFloat()
            }
            checkboxMark?.visibility = View.VISIBLE
            checkboxLabel?.text = "${NativeLocale.t("multi_select")} ($count)"
            checkboxLabel?.setTextColor(Color.BLACK)
            multiSelectContainer?.alpha = 1f
            cropBtn?.update(false)
            insertBtn?.update(count > 0)
        } else {
            checkboxBg?.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(2), Color.parseColor("#999999"))
                cornerRadius = dp(3).toFloat()
            }
            checkboxMark?.visibility = View.GONE
            checkboxLabel?.text = NativeLocale.t("multi_select")
            checkboxLabel?.setTextColor(Color.parseColor("#999999"))
            updateCheckboxEnabled()
        }
    }

    private fun updateCheckboxEnabled() {
        val enabled = multiSelectMode || selectedImagePath != null
        multiSelectContainer?.alpha = if (enabled) 1f else 0.4f
    }
}
