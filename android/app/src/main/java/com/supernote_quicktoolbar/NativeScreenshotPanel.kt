package com.supernote_quicktoolbar

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.*
import com.facebook.react.bridge.ReactApplicationContext
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.*

class NativeScreenshotPanel(
    private val reactContext: ReactApplicationContext,
    private val toolbarModule: FloatingToolbarModule
) {
    companion object {
        private const val TAG = "NativeScreenshotPanel"
        @Volatile var currentInstance: NativeScreenshotPanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): NativeScreenshotPanel {
            val inst = currentInstance ?: NativeScreenshotPanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private const val QUEUE_DIR   = "/sdcard/SCREENSHOT/.plugin_staging/queue"
        private const val HISTORY_DIR = "/sdcard/SCREENSHOT/.plugin_history"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: View? = null

    private var activeTab = "history"
    private var selectedPath: String? = null

    private var contentGrid: LinearLayout? = null
    private var contentScroll: ScrollView? = null
    private var tabQueueBtn: TextView? = null
    private var tabHistoryBtn: TextView? = null
    private var tabQueueInd: View? = null
    private var tabHistoryInd: View? = null
    private var insertBtn: TextView? = null
    private var deleteBtn: TextView? = null

    private val density get() = reactContext.resources.displayMetrics.density
    private val screenW get() = reactContext.resources.displayMetrics.widthPixels
    private val screenH get() = reactContext.resources.displayMetrics.heightPixels
    private val winW get() = (screenW * 0.6).toInt()
    private val winH get() = (screenH * 0.65).toInt()
    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dp(v: Float) = (v * density).roundToInt()

    fun show() {
        handler.post {
            if (rootView != null) return@post
            currentInstance = this
            selectedPath = null
            activeTab = "history"
            toolbarModule.enablePenBlock()
            createPanel()
            refreshContent()
        }
    }

    fun hide() {
        handler.post {
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            rootView = null; windowManager = null
            contentGrid = null; contentScroll = null
            currentInstance = null
            toolbarModule.disablePenBlock()
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

        val contentWrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        contentScroll = ScrollView(ctx)
        contentGrid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        contentScroll!!.addView(contentGrid)
        contentWrapper.addView(contentScroll)
        root.addView(contentWrapper)

        root.addView(createBottomBar())

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
            text = NativeLocale.t("screenshot_panel_title")
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
        }

        val qTab = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab("queue") }
        }
        tabQueueBtn = TextView(ctx).apply {
            text = NativeLocale.t("tab_queue"); textSize = 14f
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        qTab.addView(tabQueueBtn)
        tabQueueInd = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
            setBackgroundColor(Color.TRANSPARENT)
        }
        qTab.addView(tabQueueInd)
        bar.addView(qTab)

        bar.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        })

        val hTab = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab("history") }
        }
        tabHistoryBtn = TextView(ctx).apply {
            text = NativeLocale.t("tab_history"); textSize = 14f
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }
        hTab.addView(tabHistoryBtn)
        tabHistoryInd = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
            setBackgroundColor(Color.BLACK)
        }
        hTab.addView(tabHistoryInd)
        bar.addView(hTab)

        wrapper.addView(bar)
        wrapper.addView(makeDivider())
        return wrapper
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

        deleteBtn = makeOutlinedBtn(NativeLocale.t("delete")) { doDelete() }
        deleteBtn!!.alpha = 0.4f; deleteBtn!!.isEnabled = false
        bar.addView(deleteBtn)

        bar.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        bar.addView(makeOutlinedBtn(NativeLocale.t("cancel")) { closeAndRestore() })

        insertBtn = makeFilledBtn(NativeLocale.t("insert")) { doInsert() }
        insertBtn!!.alpha = 0.4f; insertBtn!!.isEnabled = false
        bar.addView(insertBtn)

        wrapper.addView(bar)
        return wrapper
    }

    private fun switchTab(tab: String) {
        activeTab = tab
        updateTabStyles()
        selectedPath = null
        updateButtons()
        refreshContent()
    }

    private fun updateTabStyles() {
        val isQ = activeTab == "queue"
        tabQueueBtn?.setTextColor(if (isQ) Color.BLACK else Color.parseColor("#AAAAAA"))
        tabQueueInd?.setBackgroundColor(if (isQ) Color.BLACK else Color.TRANSPARENT)
        tabHistoryBtn?.setTextColor(if (!isQ) Color.BLACK else Color.parseColor("#AAAAAA"))
        tabHistoryInd?.setBackgroundColor(if (!isQ) Color.BLACK else Color.TRANSPARENT)
    }

    private fun updateButtons() {
        val hasSel = selectedPath != null
        insertBtn?.apply { alpha = if (hasSel) 1f else 0.4f; isEnabled = hasSel }
        deleteBtn?.apply { alpha = if (hasSel) 1f else 0.4f; isEnabled = hasSel }
    }

    private fun refreshContent() {
        refreshContent(clearSelection = true)
    }

    private fun refreshContent(clearSelection: Boolean) {
        val grid = contentGrid ?: return
        grid.removeAllViews()
        if (clearSelection) {
            selectedPath = null
            updateButtons()
        }

        val dir = if (activeTab == "queue") QUEUE_DIR else HISTORY_DIR
        val folder = File(dir)
        if (!folder.exists() || !folder.isDirectory) {
            grid.addView(makeEmptyView(
                if (activeTab == "queue") NativeLocale.t("no_queue") else NativeLocale.t("no_history")
            ))
            return
        }

        val files = (folder.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".png") }
            .sortedByDescending { it.name.removeSuffix(".png").toLongOrNull() ?: 0L }

        if (files.isEmpty()) {
            grid.addView(makeEmptyView(
                if (activeTab == "queue") NativeLocale.t("no_queue") else NativeLocale.t("no_history")
            ))
            return
        }

        buildGrid(files)
    }

    private fun buildGrid(files: List<File>) {
        val grid = contentGrid ?: return
        val ctx = reactContext
        val gap = dp(5)
        val colW = (winW - dp(10) - gap * 3) / 2

        var row: LinearLayout? = null
        for ((idx, file) in files.withIndex()) {
            if (idx % 2 == 0) {
                row = LinearLayout(ctx).apply {
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
            row?.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(colW, 1)
            })
        }
    }

    private fun createCell(file: File, width: Int): LinearLayout {
        val ctx = reactContext
        val thumbH = (width / 1.2f).toInt()
        val isSelected = selectedPath == file.absolutePath

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
                selectedPath = if (selectedPath == file.absolutePath) null else file.absolutePath
                updateButtons()
                refreshContent(clearSelection = false)
            }
        }

        val thumbContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(width, thumbH)
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
        val imageView = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        thumbContainer.addView(imageView)
        loadThumbnail(file.absolutePath, width, thumbH, imageView)
        cell.addView(thumbContainer)

        cell.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#D8D8D8"))
        })
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val ts = file.name.removeSuffix(".png").toLongOrNull() ?: 0L
        val timeStr = if (ts > 0) {
            val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            sdf.format(java.util.Date(ts))
        } else file.name
        info.addView(TextView(ctx).apply {
            text = timeStr; textSize = 11f; setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
        })

        info.addView(TextView(ctx).apply {
            text = formatSize(file.length()); textSize = 10f
            setTextColor(Color.parseColor("#999999")); gravity = Gravity.CENTER
        })
        cell.addView(info)

        return cell
    }

    private fun doInsert() {
        val path = selectedPath ?: return
        val isFromQueue = activeTab == "queue"
        hide()
        android.util.Log.i("NativeScreenshotPanel", "[INSERT-DBG/Kt] panel insert path=$path fromQueue=$isFromQueue")

        kotlin.concurrent.thread(isDaemon = true) {
            NativeImagePanel.saveToInsertCacheStatic(
                path,
                FloatingToolbarModule.lastNotePath,
                FloatingToolbarModule.lastPageNum
            )
        }

        handler.postDelayed({
            try {
                toolbarModule.requestInsertImage(path)
            } catch (_: Exception) {
                toolbarModule.restoreToolbar()
            }
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
            text = label; textSize = 14f; setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(dp(1), Color.BLACK)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(10) }
            setOnClickListener { onClick() }
        }
    }

    private fun makeFilledBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = label; textSize = 14f; setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(10), dp(22), dp(10))
            setBackgroundColor(Color.BLACK)
            setOnClickListener { onClick() }
        }
    }

    private fun makeDivider(): View {
        return View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
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
                gravity = Gravity.CENTER
            })
        }
    }
}
