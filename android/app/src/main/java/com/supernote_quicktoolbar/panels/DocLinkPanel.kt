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
import com.supernote_quicktoolbar.ui_common.PanelBase
import com.supernote_quicktoolbar.ui_common.PanelChips
import com.supernote_quicktoolbar.ui_common.PanelHeader
import com.supernote_quicktoolbar.ui_common.PanelScrollHost
import com.supernote_quicktoolbar.ui_common.SelectionButton
import java.io.File
import kotlin.collections.LinkedHashSet

class DocLinkPanel(
    ctx: ReactApplicationContext,
    toolbar: FloatingToolbarModule
) : PanelBase(ctx, toolbar) {

    override val tag = "NativeDocPanel"
    override val panelName = "doc"

    companion object {
        @Volatile var currentInstance: DocLinkPanel? = null
        val docLinkQueue = mutableListOf<String>()

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): DocLinkPanel {
            val inst = currentInstance ?: DocLinkPanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private val DOC_EXTS = setOf("epub", "pdf", "cbz", "doc", "docx", "txt", "djvu", "mobi", "fb2")
        private val ALLOWED_ROOT_FOLDERS = setOf(
            "Document", "EXPORT", "INBOX", "LocalSend", "Export", "MyStyle", "Note", "SCREENSHOT", "Books", "Download"
        )
    }

    private var currentBrowsePath: String = "/sdcard/Document"
    private var selectedDocPath: String? = null

    private var multiSelectMode = false
    private val multiSelectedDocPaths = linkedSetOf<String>()

    private val DEST_DIR_MAP = mapOf(
        "Document" to "/sdcard/Document", "Export" to "/sdcard/EXPORT",
        "MyStyle" to "/sdcard/MyStyle", "Note" to "/sdcard/Note",
        "SCREENSHOT" to "/sdcard/SCREENSHOT", "INBOX" to "/sdcard/INBOX"
    )
    private val DIR_KEYS = listOf("Document", "Export", "MyStyle", "Note", "SCREENSHOT", "INBOX")

    private var contentGrid: LinearLayout? = null
    private var scrollHost: PanelScrollHost? = null
    private var insertBtn: SelectionButton? = null
    private var chips: PanelChips? = null

    private var multiSelectContainer: LinearLayout? = null
    private var checkboxBg: View? = null
    private var checkboxMark: TextView? = null
    private var checkboxLabel: TextView? = null

    fun show() {
        currentInstance = this
        selectedDocPath = null
        multiSelectMode = false
        multiSelectedDocPaths.clear()
        currentBrowsePath = "/sdcard/Document"
        showPanel()
        handler.post { refreshContent() }
    }

    override fun buildContent(root: LinearLayout) {
        root.addView(PanelHeader.create(reactContext, NativeLocale.t("doc_panel_title")) { closeAndRestore() })

        chips = PanelChips(reactContext, DIR_KEYS) { selected ->
            currentBrowsePath = if (selected != null) DEST_DIR_MAP[selected] ?: "/sdcard" else "/sdcard"
            refreshContent()
        }
        chips!!.setSelection("Document")
        root.addView(chips!!.createView())

        scrollHost = PanelScrollHost(reactContext, overlayScrollbar = true)
        contentGrid = scrollHost!!.content
        root.addView(scrollHost!!.view)

        val insertTv = makeFilledBtn(NativeLocale.t("doc_insert_link")) { doInsertLink() }
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
                insertTv
            )
        ))
    }

    override fun onHide() {
        contentGrid = null; scrollHost = null
        insertBtn = null; chips = null
        multiSelectContainer = null; checkboxBg = null; checkboxMark = null; checkboxLabel = null
        currentInstance = null
    }

    private fun refreshContent() {
        val grid = contentGrid ?: return
        grid.removeAllViews()
        selectedDocPath = null
        insertBtn?.update(false)
        updateCheckboxEnabled()
        loadAndShowDirectory(currentBrowsePath)
        scrollHost?.scrollToTop()
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
        for (item in items) {
            val isSelected = if (multiSelectMode) {
                !item.isDir && item.path in multiSelectedDocPaths
            } else {
                !item.isDir && selectedDocPath == item.path
            }
            val row = LinearLayout(reactContext).apply {
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
                    } else if (multiSelectMode) {
                        if (item.path in multiSelectedDocPaths) multiSelectedDocPaths.remove(item.path)
                        else multiSelectedDocPaths.add(item.path)
                        updateMultiSelectUI()
                        rebuildList()
                    } else {
                        selectedDocPath = if (selectedDocPath == item.path) null else item.path
                        insertBtn?.update(selectedDocPath != null)
                        updateCheckboxEnabled()
                        rebuildList()
                    }
                }
            }

            row.addView(TextView(reactContext).apply {
                text = if (item.isDir) "[DIR]" else getDocIcon(item.name)
                textSize = if (item.isDir) 12f else 14f
                setTextColor(if (item.isDir) Color.parseColor("#666666") else Color.BLACK)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(10) }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F5F5F5"))
                    cornerRadius = dp(4).toFloat()
                }
            })

            val infoCol = LinearLayout(reactContext).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(reactContext).apply {
                text = item.name; textSize = 13f; setTextColor(Color.BLACK)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (!item.isDir) {
                infoCol.addView(TextView(reactContext).apply {
                    text = formatSize(item.size); textSize = 10f
                    setTextColor(Color.parseColor("#999999"))
                })
            }
            row.addView(infoCol)

            if (isSelected) {
                row.addView(TextView(reactContext).apply {
                    text = "✓"; textSize = 16f; setTextColor(Color.BLACK)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                })
            }

            grid.addView(row)
        }
        scrollHost?.refreshThumb()
    }

    private fun rebuildList() {
        contentGrid?.removeAllViews()
        loadAndShowDirectory(currentBrowsePath)
    }

    private fun doInsertLink() {
        if (multiSelectMode) {
            if (multiSelectedDocPaths.isEmpty()) return
            val paths = multiSelectedDocPaths.toList()
            multiSelectedDocPaths.clear()
            multiSelectMode = false
            synchronized(DocLinkPanel::class.java) {
                docLinkQueue.clear()
                docLinkQueue.addAll(paths.drop(1))
            }
            val firstPath = paths.first()
            val linkName = File(firstPath).nameWithoutExtension
            toolbarModule.emitEventPublic("nativeInsertDocLink", Arguments.createMap().apply {
                putString("path", firstPath)
                putString("linkName", linkName)
            })
        } else {
            val docPath = selectedDocPath ?: return
            val linkName = File(docPath).nameWithoutExtension
            toolbarModule.emitEventPublic("nativeInsertDocLink", Arguments.createMap().apply {
                putString("path", docPath)
                putString("linkName", linkName)
            })
        }
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun closeAndRestore() {
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun toggleMultiSelect() {
        val canToggle = multiSelectMode || selectedDocPath != null
        if (!canToggle) return
        if (multiSelectMode) {
            multiSelectMode = false
            multiSelectedDocPaths.clear()
            selectedDocPath = null
            insertBtn?.update(false)
        } else {
            multiSelectMode = true
            selectedDocPath?.let { multiSelectedDocPaths.add(it) }
            selectedDocPath = null
        }
        updateMultiSelectUI()
        rebuildList()
    }

    private fun updateMultiSelectUI() {
        if (multiSelectMode) {
            val count = multiSelectedDocPaths.size
            checkboxBg?.background = GradientDrawable().apply {
                setColor(Color.BLACK); cornerRadius = dp(3).toFloat()
            }
            checkboxMark?.visibility = View.VISIBLE
            checkboxLabel?.text = "${NativeLocale.t("multi_select")} ($count)"
            checkboxLabel?.setTextColor(Color.BLACK)
            multiSelectContainer?.alpha = 1f
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
        val enabled = multiSelectMode || selectedDocPath != null
        multiSelectContainer?.alpha = if (enabled) 1f else 0.4f
    }

    private fun getDocIcon(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "pdf" -> "PDF"; "epub" -> "EPB"; "cbz" -> "CBZ"
        "doc", "docx" -> "DOC"; "txt" -> "TXT"
        "djvu" -> "DJV"; "mobi" -> "MOB"; "fb2" -> "FB2"
        else -> "DOC"
    }
}
