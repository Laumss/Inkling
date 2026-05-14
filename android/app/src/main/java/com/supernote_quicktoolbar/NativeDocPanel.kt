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
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import java.io.File
import kotlin.math.roundToInt

class NativeDocPanel(
    private val reactContext: ReactApplicationContext,
    private val toolbarModule: FloatingToolbarModule
) {
    companion object {
        private const val TAG = "NativeDocPanel"
        @Volatile var currentInstance: NativeDocPanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): NativeDocPanel {
            val inst = currentInstance ?: NativeDocPanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private val DOC_EXTS = setOf("epub", "pdf", "cbz", "doc", "docx", "txt", "djvu", "mobi", "fb2")

        private val ALLOWED_ROOT_FOLDERS = setOf(
            "Document", "EXPORT", "INBOX", "LocalSend", "Export", "MyStyle", "Note", "SCREENSHOT", "Books", "Download"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: View? = null

    private var currentBrowsePath: String = "/sdcard/Document"
    private var selectedDocPath: String? = null
    private var browseDir: String? = "Document"

    private val DEST_DIR_MAP = mapOf(
        "Document" to "/sdcard/Document",
        "Export" to "/sdcard/EXPORT",
        "MyStyle" to "/sdcard/MyStyle",
        "Note" to "/sdcard/Note",
        "SCREENSHOT" to "/sdcard/SCREENSHOT",
        "INBOX" to "/sdcard/INBOX"
    )
    private val DIR_KEYS = listOf("Document", "Export", "MyStyle", "Note", "SCREENSHOT", "INBOX")

    private var contentScroll: ScrollView? = null
    private var contentGrid: LinearLayout? = null
    private var chipContainer: LinearLayout? = null
    private var insertBtn: TextView? = null
    private var selectedLabel: TextView? = null

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
            selectedDocPath = null
            browseDir = "Document"
            currentBrowsePath = "/sdcard/Document"
            toolbarModule.enablePenBlock()
            createPanel()
            refreshContent()
        }
    }

    fun hide() {
        handler.post {
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            rootView = null; windowManager = null
            contentScroll = null; contentGrid = null
            currentInstance = null
            toolbarModule.disablePenBlock()
            toolbarModule.emitEventPublic(
                "onNativePanelClose",
                Arguments.createMap().apply { putString("panel", "doc") }
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
        root.addView(createChipsRow())

        val mainContent = LinearLayout(ctx).apply {
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
        mainContent.addView(contentScroll)
        root.addView(mainContent)

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
        Log.i(TAG, "doc panel shown")
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
            text = NativeLocale.t("doc_panel_title")
            textSize = 18f; setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        wrapper.addView(bar)
        wrapper.addView(makeDivider())
        return wrapper
    }

    private fun createChipsRow(): LinearLayout {
        val ctx = reactContext
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val row = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
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
            val isActive = browseDir == key
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
                    browseDir = if (browseDir == key) null else key
                    currentBrowsePath = if (browseDir != null) DEST_DIR_MAP[browseDir] ?: "/sdcard" else "/sdcard"
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

        selectedLabel = TextView(ctx).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#666666"))
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
        }
        bar.addView(selectedLabel)

        bar.addView(makeOutlinedBtn(NativeLocale.t("cancel")) { closeAndRestore() })

        insertBtn = makeFilledBtn(NativeLocale.t("doc_insert_link")) { doInsertLink() }
        insertBtn!!.alpha = 0.4f
        insertBtn!!.isEnabled = false
        bar.addView(insertBtn)

        wrapper.addView(bar)
        return wrapper
    }

    private fun refreshContent() {
        val grid = contentGrid ?: return
        grid.removeAllViews()
        selectedDocPath = null
        insertBtn?.alpha = 0.4f
        insertBtn?.isEnabled = false
        selectedLabel?.text = ""

        loadAndShowDirectory(currentBrowsePath)
    }

    data class DocItem(val name: String, val path: String, val isDir: Boolean, val size: Long = 0)

    private fun loadAndShowDirectory(path: String) {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            contentGrid?.addView(makeEmptyView(NativeLocale.t("doc_no_files")))
            return
        }

        val items = (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .filter { f ->
                if (f.isDirectory) {
                    if (path == "/sdcard") ALLOWED_ROOT_FOLDERS.contains(f.name) else true
                } else {
                    DOC_EXTS.contains(f.extension.lowercase())
                }
            }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
            .map { DocItem(it.name, it.absolutePath, it.isDirectory, it.length()) }

        if (items.isEmpty()) {
            contentGrid?.addView(makeEmptyView(NativeLocale.t("doc_no_files")))
            return
        }
        buildDocList(items)
    }

    private fun buildDocList(items: List<DocItem>) {
        val grid = contentGrid ?: return
        val ctx = reactContext

        for (item in items) {
            val isSelected = !item.isDir && selectedDocPath == item.path
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(if (isSelected) Color.parseColor("#F0F0F0") else Color.WHITE)
                    setStroke(
                        if (isSelected) dp(2) else dp(1),
                        if (isSelected) Color.BLACK else Color.parseColor("#E0E0E0")
                    )
                    cornerRadius = dp(6).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }

                setOnClickListener {
                    if (item.isDir) {
                        currentBrowsePath = item.path
                        refreshContent()
                    } else {
                        selectedDocPath = if (selectedDocPath == item.path) null else item.path
                        insertBtn?.apply {
                            alpha = if (selectedDocPath != null) 1f else 0.4f
                            isEnabled = selectedDocPath != null
                        }
                        selectedLabel?.text = if (selectedDocPath != null) {
                            File(selectedDocPath!!).nameWithoutExtension
                        } else ""
                        rebuildList()
                    }
                }
            }

            val iconText = if (item.isDir) "[DIR]" else getDocIcon(item.name)
            row.addView(TextView(ctx).apply {
                text = iconText
                textSize = if (item.isDir) 12f else 14f
                setTextColor(if (item.isDir) Color.parseColor("#666666") else Color.BLACK)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    rightMargin = dp(10)
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F5F5F5"))
                    cornerRadius = dp(4).toFloat()
                }
            })

            val infoCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(ctx).apply {
                text = item.name
                textSize = 13f
                setTextColor(Color.BLACK)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (!item.isDir) {
                infoCol.addView(TextView(ctx).apply {
                    text = formatSize(item.size)
                    textSize = 10f
                    setTextColor(Color.parseColor("#999999"))
                })
            }
            row.addView(infoCol)

            if (isSelected) {
                row.addView(TextView(ctx).apply {
                    text = "✓"
                    textSize = 16f
                    setTextColor(Color.BLACK)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                })
            }

            grid.addView(row)
        }
    }

    private fun rebuildList() {
        val grid = contentGrid ?: return
        grid.removeAllViews()
        loadAndShowDirectory(currentBrowsePath)
    }

    private fun doInsertLink() {
        val docPath = selectedDocPath ?: return
        val linkName = File(docPath).nameWithoutExtension

        val map = Arguments.createMap().apply {
            putString("path", docPath)
            putString("linkName", linkName)
        }
        toolbarModule.emitEventPublic("nativeInsertDocLink", map)

        hide()
        toolbarModule.restoreToolbar()
    }

    private fun closeAndRestore() {
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun getDocIcon(name: String): String {
        return when (name.substringAfterLast('.').lowercase()) {
            "pdf" -> "PDF"
            "epub" -> "EPB"
            "cbz" -> "CBZ"
            "doc", "docx" -> "DOC"
            "txt" -> "TXT"
            "djvu" -> "DJV"
            "mobi" -> "MOB"
            "fb2" -> "FB2"
            else -> "DOC"
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1fKB", kb)
        val mb = kb / 1024.0
        return String.format("%.1fMB", mb)
    }

    private fun makeDivider(): View {
        return View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#D8D8D8"))
        }
    }

    private fun makeEmptyView(text: String): TextView {
        return TextView(reactContext).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(60), dp(20), dp(60))
        }
    }

    private fun makeMinBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = label; textSize = 18f
            setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CCCCCC"))
                cornerRadius = dp(4).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeOutlinedBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = label; textSize = 14f
            setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1.5f), Color.BLACK)
                cornerRadius = dp(4).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeFilledBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = label; textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(4).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }
}
