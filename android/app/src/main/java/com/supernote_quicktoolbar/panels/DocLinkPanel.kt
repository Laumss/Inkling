package com.supernote_quicktoolbar.panels
import com.supernote_quicktoolbar.*

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.supernote_quicktoolbar.ui_common.VectorAssets
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.ui_common.BrowseDirs
import com.supernote_quicktoolbar.ui_common.ButtonHandle
import com.supernote_quicktoolbar.ui_common.CheckboxHandle
import com.supernote_quicktoolbar.ui_common.ChipsHandle
import com.supernote_quicktoolbar.ui_common.ListHandle
import com.supernote_quicktoolbar.ui_common.MultiSelectState
import com.supernote_quicktoolbar.ui_common.PanelBase
import com.supernote_quicktoolbar.ui_common.PanelHost
import com.supernote_quicktoolbar.ui_common.PanelWidgets
import java.io.File

class DocLinkPanel(
    ctx: ReactApplicationContext,
    private val toolbar: FloatingToolbarModule
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

        val DOC_EXTS = setOf("epub", "pdf", "cbz", "doc", "docx", "note", "mobi", "fb2")
        private val ALLOWED_ROOT_FOLDERS = setOf(
            "Document", "EXPORT", "INBOX", "LocalSend", "Export", "MyStyle", "Note", "SCREENSHOT", "Books", "Download"
        )
    }

    data class DocItem(val name: String, val path: String, val isDir: Boolean, val size: Long = 0)

    private var currentBrowsePath = "/sdcard/Document"
    private var selectedDocPath: String? = null
    private var currentNotePath: String? = null
    private val multi = MultiSelectState()
    private var fileReadPermissionRequestPending = false
    private var lastFilePermissionToastAt = 0L

    private lateinit var chipsH: ChipsHandle
    private lateinit var listH: ListHandle
    private lateinit var insertBtn: ButtonHandle
    private lateinit var multiCheckbox: CheckboxHandle

    fun show(currentFilePath: String?) {
        currentInstance = this
        selectedDocPath = null
        currentNotePath = currentFilePath?.let(::normalizePath)
        multi.clear()
        fileReadPermissionRequestPending = false
        currentBrowsePath = "/sdcard/Document"
        showPanel()
    }

    override fun buildContent(root: LinearLayout) {
        renderDsl(root) {
            header(NativeLocale.t("doc_panel_title"))
            chipsH = chips(BrowseDirs.KEYS, initial = "Document") { key ->
                currentBrowsePath = BrowseDirs.pathForKey(key)
                refreshList()
            }
            listH = list(
                itemsProvider = { loadItems() },
                emptyText = NativeLocale.t("doc_no_files")
            ) { h, item -> docRow(h, item) }
            bottomBar {
                multiCheckbox = checkbox(NativeLocale.t("multi_select")) { toggleMulti() }
                outlined(NativeLocale.t("cancel")) { closeAndRestore() }
                insertBtn = filled(NativeLocale.t("doc_insert_link")) { doInsertLink() }
            }
        }
        insertBtn.enabled = false
        updateCheckboxEnabled()
    }

    override fun onHide() {
        currentInstance = null
    }

    fun onFileReceived() {
        if (isShowing) listH.refresh()
    }

    private fun closeAndRestore() {
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun refreshList() {
        selectedDocPath = null
        insertBtn.enabled = false
        updateCheckboxEnabled()
        listH.refresh()
        listH.scrollTop()
    }

    private fun normalizePath(path: String): String = try {
        File(path).canonicalPath
    } catch (_: Exception) {
        path.replace("/sdcard/", "/storage/emulated/0/")
    }

    private fun loadItems(): List<DocItem> {
        val dir = File(currentBrowsePath)
        try {
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            val files = try {
                dir.listFiles() ?: emptyArray()
            } catch (e: SecurityException) {
                handleFileReadBlocked(currentBrowsePath, e)
                return emptyList()
            }
            return files
                .filter { !it.name.startsWith(".") }
                .filter { f ->
                    try {
                        if (f.isDirectory) {
                            if (currentBrowsePath == "/sdcard") ALLOWED_ROOT_FOLDERS.contains(f.name) else true
                        } else {
                            DOC_EXTS.contains(f.extension.lowercase()) &&
                                (currentNotePath == null || normalizePath(f.absolutePath) != currentNotePath)
                        }
                    } catch (e: SecurityException) {
                        handleFileReadBlocked(f.absolutePath, e, showToast = false)
                        false
                    }
                }
                .sortedWith(compareByDescending<File> { try { it.isDirectory } catch (_: SecurityException) { false } }.thenBy { it.name })
                .map {
                    DocItem(
                        it.name,
                        it.absolutePath,
                        try { it.isDirectory } catch (_: SecurityException) { false },
                        try { it.length() } catch (_: SecurityException) { 0L }
                    )
                }
        } catch (e: SecurityException) {
            handleFileReadBlocked(currentBrowsePath, e)
            return emptyList()
        }
    }

    private fun handleFileReadBlocked(path: String, e: SecurityException, showToast: Boolean = true) {
        if (BuildConfig.ENABLE_DEBUG) android.util.Log.w(tag, "file read blocked for $path: ${e.message}")
        requestFileReadPermissionOnce()
        if (showToast) {
            val now = System.currentTimeMillis()
            if (now - lastFilePermissionToastAt > 2500L) {
                lastFilePermissionToastAt = now
                Toast.makeText(reactContext, NativeLocale.t("file_read_permission_needed"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestFileReadPermissionOnce() {
        if (fileReadPermissionRequestPending) return
        fileReadPermissionRequestPending = true
        toolbar.requestPluginFileReadPermission { granted ->
            fileReadPermissionRequestPending = false
            if (granted && rootView != null) {
                refreshList()
            } else if (!granted) {
                toolbar.openPluginSettingsAfterFileReadDenied()
            }
        }
    }

    private var folderBitmap: Bitmap? = null

    private fun getFolderBitmap(host: PanelHost): Bitmap? {
        folderBitmap?.let { return it }
        val bmp = VectorAssets.loadBitmapTinted(
            host.ctx, "icons/ic_folder.xml", host.dp(36), Color.BLACK
        )
        folderBitmap = bmp
        return bmp
    }

    private fun docRow(host: PanelHost, item: DocItem): View {
        val isSelected = if (multi.isActive) {
            !item.isDir && multi.isSelected(item.path)
        } else {
            !item.isDir && selectedDocPath == item.path
        }

        val wrapper = LinearLayout(host.ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val row = LinearLayout(host.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (isSelected) Color.parseColor("#E8E8E8") else Color.WHITE)
            setOnClickListener {
                when {
                    item.isDir -> {
                        currentBrowsePath = item.path
                        chipsH.setSelection(null)
                        refreshList()
                    }
                    multi.isActive -> {
                        multi.toggle(item.path)
                        updateMultiUI()
                        listH.refresh()
                    }
                    else -> {
                        selectedDocPath = if (selectedDocPath == item.path) null else item.path
                        insertBtn.enabled = selectedDocPath != null
                        updateCheckboxEnabled()
                        listH.refresh()
                    }
                }
            }
        }

        val iconAreaSize = host.dp(54)
        if (item.isDir) {
            row.addView(android.widget.RelativeLayout(host.ctx).apply {
                layoutParams = LinearLayout.LayoutParams(iconAreaSize, iconAreaSize)
                addView(ImageView(host.ctx).apply {
                    val bmp = getFolderBitmap(host)
                    if (bmp != null) setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = android.widget.RelativeLayout.LayoutParams(
                        host.dp(36), host.dp(42)
                    ).apply { addRule(android.widget.RelativeLayout.CENTER_IN_PARENT) }
                })
            })
        } else {
            row.addView(View(host.ctx).apply {
                layoutParams = LinearLayout.LayoutParams(iconAreaSize, iconAreaSize)
            })
        }

        val infoCol = LinearLayout(host.ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoCol.addView(TextView(host.ctx).apply {
            text = item.name; textSize = host.sp(20f); setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val detail = if (item.isDir) {
            val count = try {
                File(item.path).listFiles()?.count { !it.name.startsWith(".") } ?: 0
            } catch (_: Exception) { 0 }
            NativeLocale.itemCount(count)
        } else {
            PanelWidgets.formatSize(item.size)
        }
        infoCol.addView(TextView(host.ctx).apply {
            text = detail; textSize = host.sp(12f)
            setTextColor(Color.parseColor("#9E9E9E"))
        })
        row.addView(infoCol)

        if (isSelected) {
            row.addView(TextView(host.ctx).apply {
                text = "✓"; textSize = host.sp(16f); setTextColor(Color.BLACK)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(host.dp(32), host.dp(32)).apply {
                    rightMargin = host.dp(10)
                }
            })
        } else {

            row.addView(ImageView(host.ctx).apply {
                layoutParams = LinearLayout.LayoutParams(host.dp(16), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = host.dp(10)
                }
            })
        }

        wrapper.addView(row)

        wrapper.addView(View(host.ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#D0D0D0"))
        })
        return wrapper
    }

    private fun toggleMulti() {
        val canToggle = multi.isActive || selectedDocPath != null
        if (!canToggle) return
        if (multi.isActive) {
            multi.deactivate()
            selectedDocPath = null
            insertBtn.enabled = false
        } else {
            multi.activate(seed = selectedDocPath)
            selectedDocPath = null
        }
        updateMultiUI()
        listH.refresh()
    }

    private fun updateMultiUI() {
        if (multi.isActive) {
            multiCheckbox.setChecked(true)
            multiCheckbox.setLabel("${NativeLocale.t("multi_select")} (${multi.count})")
            multiCheckbox.setActive(true)
            insertBtn.enabled = multi.count > 0
        } else {
            multiCheckbox.setChecked(false)
            multiCheckbox.setLabel(NativeLocale.t("multi_select"))
            updateCheckboxEnabled()
        }
    }

    private fun updateCheckboxEnabled() {
        multiCheckbox.setActive(multi.isActive || selectedDocPath != null)
    }

    private fun doInsertLink() {
        if (multi.isActive) {
            if (multi.count == 0) return
            val paths = multi.selectedPaths
            multi.clear()
            synchronized(DocLinkPanel::class.java) {
                docLinkQueue.clear()
                docLinkQueue.addAll(paths.drop(1))
            }
            val firstPath = paths.first()
            toolbarModule.emitEvent("nativeInsertDocLink", Arguments.createMap().apply {
                putString("path", firstPath)
                putString("linkName", File(firstPath).nameWithoutExtension)
            })
        } else {
            val docPath = selectedDocPath ?: return
            toolbarModule.emitEvent("nativeInsertDocLink", Arguments.createMap().apply {
                putString("path", docPath)
                putString("linkName", File(docPath).nameWithoutExtension)
            })
        }
        closeAndRestore()
    }

    private fun getDocIcon(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "pdf" -> "PDF"; "epub" -> "EPB"; "cbz" -> "CBZ"
        "doc", "docx" -> "DOC"; "note" -> "NOTE"
        "mobi" -> "MOB"; "fb2" -> "FB2"
        else -> "DOC"
    }
}
