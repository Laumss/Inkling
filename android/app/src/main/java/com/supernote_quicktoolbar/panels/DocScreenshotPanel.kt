package com.supernote_quicktoolbar.panels
import com.supernote_quicktoolbar.BuildConfig
import com.supernote_quicktoolbar.*
import com.supernote_quicktoolbar.overlays.*
import com.supernote_quicktoolbar.bubbles.*

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.*
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.ui_common.ButtonHandle
import com.supernote_quicktoolbar.ui_common.PanelBase
import com.supernote_quicktoolbar.ui_common.PanelGrid
import com.supernote_quicktoolbar.ui_common.PanelHost
import com.supernote_quicktoolbar.ui_common.PanelScrollHost
import com.supernote_quicktoolbar.ui_common.PanelTabBar
import com.supernote_quicktoolbar.ui_common.PanelWidgets
import com.supernote_quicktoolbar.ui_common.TabBarHandle
import java.io.File
import kotlin.concurrent.thread

class DocScreenshotPanel(
    ctx: ReactApplicationContext,
    private val toolbar: FloatingToolbarModule
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
    private lateinit var pasteBtn: ButtonHandle
    private var gridRebuild: (() -> Unit)? = null
    private var gridScrollTop: (() -> Unit)? = null

    fun show(initialTab: String = "history", onResult: ((Boolean) -> Unit)? = null) {
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
        showPanel(onResult)
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
                        PanelGrid.build(host.ctx, scroll, host.screenW, host.screenH, host.panelW, files) { file, colW ->
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
                val pasteTv = PanelWidgets.outlinedButton(host, NativeLocale.t("paste_image")) { doPaste() }
                pasteBtn = ButtonHandle().also { it.view = pasteTv }
                bar.addView(pasteTv)
                bar.addView(insertTv)
                wrapper.addView(bar)
                wrapper
            }
        }
        insertBtn.enabled = false
        deleteBtn.enabled = false
        pasteBtn.enabled = false
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
        pasteBtn.enabled = hasSel
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
            text = timeStr; textSize = host.gridSp(12f); setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 2
        })
        textContainer.addView(TextView(host.ctx).apply {
            text = PanelWidgets.formatSize(file.length()); textSize = host.gridSp(10f)
            setTextColor(Color.parseColor("#666666"))
        })
        cell.addView(textContainer)
        return cell
    }

    private fun doInsert() {
        val path = selectedPath ?: return
        FloatingToolbarModule.beginInsertImageGuard()
        hide()
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "[INSERT-DBG/Kt] panel insert path=$path fromQueue=${activeTab == "queue"}")
        thread(isDaemon = true) {
            ImagePanel.saveToInsertCacheStatic(
                path, FloatingToolbarModule.lastNotePath, FloatingToolbarModule.lastPageNum
            )
        }
        handler.postDelayed({
            try { toolbar.requestInsertImage(path) }
            catch (_: Exception) { toolbarModule.restoreToolbar() }
        }, 300)
    }

    private fun doDelete() {
        val path = selectedPath ?: return
        val fileName = File(path).name
        toolbarModule.emitEvent("nativeDeleteFile",
            com.facebook.react.bridge.Arguments.createMap().apply {
                putString("path", path)
            })
        kotlin.concurrent.thread(isDaemon = true) { DocScreenshotService.unmarkInsertNext(fileName) }
        selectedPath = null
        handler.post {
            updateButtons()
            gridRebuild?.invoke()
        }
    }

    private fun doPaste() {
        val path = selectedPath ?: return
        if (StickyNotes.isFull) {
            com.supernote_quicktoolbar.ui_common.Dialog.tip(reactContext, NativeLocale.t("sticky_limit_reached"))
            return
        }
        dismissWithoutPenRelease()
        handler.postDelayed({
            StickyNotes.add(reactContext, toolbar, path)
            toolbarModule.restoreToolbar()
            releasePenGuardOwner()
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
