package com.supernote_quicktoolbar.panels
import com.supernote_quicktoolbar.BuildConfig
import com.supernote_quicktoolbar.*

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.ui_common.*
import org.json.JSONArray
import org.json.JSONObject

class ConfigPanel(
    ctx: ReactApplicationContext,
    toolbar: FloatingToolbarModule
) : PanelBase(ctx, toolbar) {

    override val tag = "ConfigPanel"
    override val panelName = "config"

    companion object {
        @Volatile var currentInstance: ConfigPanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): ConfigPanel {
            val inst = currentInstance ?: ConfigPanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private const val CONFIG_STORE_KEY = "preset_1"
        private const val PREFS_NAME = "quicktoolbar_presets"
    }

    data class ToolDef(
        val id: String,
        val iconAsset: String,
        val action: String,
        val nameKey: String,
        val category: String
    )

    private val TOOL_DEFS = listOf(
        ToolDef("insert_image",          "icons/ic_tool_image.xml",    "insert_image",          "config_tool_image",     "insert"),
        ToolDef("insert_doc_screenshot", "icons/ic_tool_doc.xml",      "insert_doc_screenshot", "config_tool_doc",       "insert"),
        ToolDef("insert_text",           "icons/ic_tool_text.xml",     "insert_text",           "config_tool_text",      "insert"),
        ToolDef("send_ai",              "icons/ic_tool_lasso_ai.xml", "lasso_smart_send",      "config_tool_lasso_ai",  "lasso"),
        ToolDef("insert_link",           "icons/ic_tool_link.xml",     "insert_link",           "config_tool_link",      "insert"),
        ToolDef("voice_transcribe",      "icons/ic_tool_voice.xml",    "voice_transcribe",      "config_tool_voice",     "insert"),
        ToolDef("invert_ink",            "icons/ic_tool_invert.xml",   "invert_ink",            "config_tool_palette",   "lasso"),
    )

    private val CATEGORIES = listOf("all", "insert", "text", "lasso")

    private var tools = mutableListOf<ToolDef>()
    private var isAddScreen = false
    private var catFilter = "all"

    private var scrollHost: PanelScrollHost? = null
    private var contentArea: LinearLayout? = null
    private var toolCountLabel: TextView? = null

    fun show() {
        currentInstance = this
        isAddScreen = false
        catFilter = "all"
        loadTools()
        showPanel()
    }

    override fun onHide() {
        scrollHost = null
        contentArea = null
        toolCountLabel = null
        currentInstance = null
    }

    override fun buildContent(root: LinearLayout) {
        if (isAddScreen) buildAddToolContent(root) else buildMainContent(root)
    }

    private fun buildMainContent(root: LinearLayout) {
        renderDsl(root) {
            header("Inkling")

            custom { h ->
                LinearLayout(h.ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(h.dp(14), h.dp(10), h.dp(14), h.dp(10))
                    setBackgroundColor(Color.parseColor("#FAFAFA"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = h.dp(1); rightMargin = h.dp(1) }

                    addView(TextView(h.ctx).apply {
                        text = NativeLocale.t("config_tools")
                        textSize = h.sp(13f)
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(Color.parseColor("#222222"))
                    })
                    toolCountLabel = TextView(h.ctx).apply {
                        text = " · ${tools.size}"
                        textSize = h.sp(10f)
                        setTextColor(Color.parseColor("#AAAAAA"))
                    }
                    addView(toolCountLabel)
                    addView(View(h.ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })
                    addView(TextView(h.ctx).apply {
                        text = NativeLocale.t("config_add")
                        textSize = h.sp(12f)
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(Color.BLACK)
                        gravity = Gravity.CENTER
                        setPadding(h.dp(12), h.dp(5), h.dp(12), h.dp(5))
                        background = GradientDrawable().apply {
                            setColor(Color.WHITE)
                            setStroke(h.dp(1), Color.BLACK)
                            cornerRadius = h.dp(3).toFloat()
                        }
                        setOnClickListener { switchToAddScreen() }
                    })
                }
            }

            custom { h ->
                View(h.ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(Color.parseColor("#D8D8D8"))
                }
            }

            custom { h ->
                val sh = PanelScrollHost(h.ctx)
                scrollHost = sh
                contentArea = sh.content
                rebuildToolList()
                sh.view
            }

            custom { _ ->
                makeBottomBar(
                    leftButtons = listOf(
                        makeOutlinedBtn(NativeLocale.t("config_dock")) {
                            hide()
                            toolbarModule.dockToEdge()
                        }
                    ),
                    rightButtons = listOf(
                        makeOutlinedBtn(NativeLocale.t("config_collapse")) {
                            saveTools()
                            hide()
                            showToolbarAndClose()
                        },
                        makeFilledBtn(NativeLocale.t("config_save")) {
                            saveTools()
                            hide()
                            toolbarModule.destroyAll()
                        }
                    )
                )
            }
        }
    }

    private fun rebuildToolList() {
        val grid = contentArea ?: return
        scrollHost?.prepareForContentChange()
        grid.removeAllViews()

        toolCountLabel?.text = " · ${tools.size}"

        if (tools.isEmpty()) {
            grid.addView(makeEmptyView(
                NativeLocale.t("config_empty"),
                NativeLocale.t("config_empty_hint")
            ))
            scrollHost?.refreshThumb()
            return
        }

        val iconSz = dp(32)

        for ((idx, tool) in tools.withIndex()) {
            val row = LinearLayout(reactContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(TextView(reactContext).apply {
                text = "${idx + 1}"
                textSize = sp(9f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                val sz = dp(18)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { rightMargin = dp(8) }
                background = GradientDrawable().apply {
                    setColor(Color.BLACK)
                    cornerRadius = sz / 2f
                }
            })

            val iconFrame = FrameLayout(reactContext).apply {
                val sz = dp(36)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { rightMargin = dp(8) }
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    setStroke(dp(1), Color.parseColor("#888888"))
                    cornerRadius = dp(4).toFloat()
                }
            }
            val iconDrawable = UiUtils.loadAssetIcon(reactContext, tool.iconAsset, iconSz, Color.parseColor("#333333"))
            if (iconDrawable != null) {
                iconFrame.addView(ImageView(reactContext).apply {
                    setImageDrawable(iconDrawable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                })
            }
            row.addView(iconFrame)

            val infoCol = LinearLayout(reactContext).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(reactContext).apply {
                text = NativeLocale.t(tool.nameKey)
                textSize = sp(13f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.parseColor("#111111"))
                setSingleLine(true)
            })
            infoCol.addView(TextView(reactContext).apply {
                text = tool.action
                textSize = sp(9f)
                setTextColor(Color.parseColor("#AAAAAA"))
            })
            row.addView(infoCol)

            val moveRow = LinearLayout(reactContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val capturedIdx = idx
            moveRow.addView(makeMoveBtn("▲") { moveToolUp(capturedIdx) })
            moveRow.addView(makeMoveBtn("▼") { moveToolDown(capturedIdx) })
            row.addView(moveRow)

            val wrapper = LinearLayout(reactContext).apply {
                orientation = LinearLayout.VERTICAL
                addView(row)
                addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(Color.parseColor("#F0F0F0"))
                })
            }
            grid.addView(wrapper)
        }
        scrollHost?.refreshThumb()
    }

    private fun makeMoveBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = label
            textSize = sp(8f)
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            val w = dp(24); val h = dp(18)
            layoutParams = LinearLayout.LayoutParams(w, h).apply { rightMargin = dp(2) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FAFAFA"))
                setStroke(1, Color.parseColor("#DDDDDD"))
                cornerRadius = dp(2).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeRemoveBtn(onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = "✕"
            textSize = sp(10f)
            setTextColor(Color.parseColor("#CC6666"))
            gravity = Gravity.CENTER
            val sz = dp(24)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { leftMargin = dp(4) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF8F8"))
                setStroke(1, Color.parseColor("#E0C0C0"))
                cornerRadius = dp(3).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun moveToolUp(idx: Int) {
        if (idx <= 0 || idx >= tools.size) return
        val tmp = tools[idx - 1]
        tools[idx - 1] = tools[idx]
        tools[idx] = tmp
        rebuildToolList()
    }

    private fun moveToolDown(idx: Int) {
        if (idx < 0 || idx >= tools.size - 1) return
        val tmp = tools[idx]
        tools[idx] = tools[idx + 1]
        tools[idx + 1] = tmp
        rebuildToolList()
    }

    private fun removeTool(idx: Int) {
        if (idx < 0 || idx >= tools.size) return
        tools.removeAt(idx)
        rebuildToolList()
    }

    private fun switchToAddScreen() {
        isAddScreen = true
        catFilter = "all"
        val root = rootView as? LinearLayout ?: return
        root.removeAllViews()
        buildAddToolContent(root)
    }

    private fun switchToMainScreen() {
        isAddScreen = false
        val root = rootView as? LinearLayout ?: return
        root.removeAllViews()
        buildMainContent(root)
    }

    private fun buildAddToolContent(root: LinearLayout) {
        renderDsl(root) {
            header(NativeLocale.t("config_add_title"))

            custom { h ->
                val container = LinearLayout(h.ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(h.dp(14), h.dp(8), h.dp(14), h.dp(8))
                }
                rebuildCatChips(container)
                container
            }

            custom { h ->
                View(h.ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(Color.parseColor("#D8D8D8"))
                }
            }

            custom { h ->
                val sh = PanelScrollHost(h.ctx)
                scrollHost = sh
                contentArea = sh.content
                rebuildAvailableList()
                sh.view
            }

            custom { _ ->
                val countTv = TextView(reactContext).apply {
                    text = NativeLocale.t("config_selected_count", tools.size)
                    textSize = sp(10f)
                    setTextColor(Color.parseColor("#888888"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                toolCountLabel = countTv
                makeBottomBar(
                    leftButtons = listOf(countTv),
                    rightButtons = listOf(
                        makeFilledBtn(NativeLocale.t("config_done")) {
                            switchToMainScreen()
                        }
                    )
                )
            }
        }
    }

    private var catChipsContainer: LinearLayout? = null

    private fun rebuildCatChips(container: LinearLayout) {
        catChipsContainer = container
        container.removeAllViews()
        val catKeys = listOf(
            "all" to "config_cat_all",
            "insert" to "config_cat_insert",
            "text" to "config_cat_text",
            "lasso" to "config_cat_lasso",
        )
        for ((idx, pair) in catKeys.withIndex()) {
            val (key, nameKey) = pair
            val active = catFilter == key
            container.addView(TextView(reactContext).apply {
                text = NativeLocale.t(nameKey)
                textSize = sp(10f)
                setTextColor(if (active) Color.WHITE else Color.parseColor("#888888"))
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply {
                    setColor(if (active) Color.BLACK else Color.WHITE)
                    setStroke(1, if (active) Color.BLACK else Color.parseColor("#DDDDDD"))
                    cornerRadius = dp(12).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (idx > 0) leftMargin = dp(4) }
                setOnClickListener {
                    catFilter = key
                    rebuildCatChips(container)
                    rebuildAvailableList()
                }
            })
        }
    }

    private fun rebuildAvailableList() {
        val grid = contentArea ?: return
        scrollHost?.prepareForContentChange()
        grid.removeAllViews()

        val addedIds = tools.map { it.id }.toSet()
        val iconSz = dp(32)

        val filtered = if (catFilter == "all") TOOL_DEFS
            else TOOL_DEFS.filter { it.category == catFilter }

        for (def in filtered) {
            val isAdded = def.id in addedIds

            val row = LinearLayout(reactContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                alpha = if (isAdded) 0.5f else 1f
            }

            val iconFrame = FrameLayout(reactContext).apply {
                val sz = dp(36)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { rightMargin = dp(8) }
                background = GradientDrawable().apply {
                    setColor(if (isAdded) Color.parseColor("#F8F8F8") else Color.WHITE)
                    setStroke(dp(1), if (isAdded) Color.parseColor("#DDDDDD") else Color.parseColor("#888888"))
                    cornerRadius = dp(4).toFloat()
                }
            }
            val tintColor = if (isAdded) Color.parseColor("#BBBBBB") else Color.parseColor("#333333")
            val iconDrawable = UiUtils.loadAssetIcon(reactContext, def.iconAsset, iconSz, tintColor)
            if (iconDrawable != null) {
                iconFrame.addView(ImageView(reactContext).apply {
                    setImageDrawable(iconDrawable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                })
            }
            row.addView(iconFrame)

            val infoCol = LinearLayout(reactContext).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(reactContext).apply {
                text = NativeLocale.t(def.nameKey)
                textSize = sp(13f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (isAdded) Color.parseColor("#AAAAAA") else Color.parseColor("#111111"))
                setSingleLine(true)
            })
            infoCol.addView(TextView(reactContext).apply {
                text = def.action
                textSize = sp(9f)
                setTextColor(Color.parseColor("#AAAAAA"))
            })
            row.addView(infoCol)

            if (isAdded) {
                row.addView(makeRemoveBtn {
                    tools.removeAll { it.id == def.id }
                    toolCountLabel?.text = NativeLocale.t("config_selected_count", tools.size)
                    rebuildAvailableList()
                })
            } else {
                row.addView(TextView(reactContext).apply {
                    text = "+"
                    textSize = sp(14f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.BLACK)
                    gravity = Gravity.CENTER
                    val sz = dp(26)
                    layoutParams = LinearLayout.LayoutParams(sz, sz)
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        setStroke(dp(1), Color.BLACK)
                        cornerRadius = dp(3).toFloat()
                    }
                    setOnClickListener {
                        if (tools.none { it.id == def.id }) {
                            tools.add(def)
                            toolCountLabel?.text = NativeLocale.t("config_selected_count", tools.size)
                            rebuildAvailableList()
                        }
                    }
                })
            }

            val wrapper = LinearLayout(reactContext).apply {
                orientation = LinearLayout.VERTICAL
                addView(row)
                addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(Color.parseColor("#F0F0F0"))
                })
            }
            if (!isAdded) {
                wrapper.setOnClickListener {
                    if (tools.none { it.id == def.id }) {
                        tools.add(def)
                        toolCountLabel?.text = NativeLocale.t("config_selected_count", tools.size)
                        rebuildAvailableList()
                    }
                }
            }
            grid.addView(wrapper)
        }
        scrollHost?.refreshThumb()
    }

    private fun loadTools() {
        tools.clear()
        try {
            val prefs = reactContext.getSharedPreferences(PREFS_NAME, 0)
            val json = prefs.getString(CONFIG_STORE_KEY, null)
            if (json != null) {
                val data = JSONObject(json)
                val arr = data.getJSONArray("tools")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.getString("id")
                    val def = TOOL_DEFS.find { it.id == id }
                    if (def != null) tools.add(def)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(tag, "loadTools: ${e.message}")
        }
        if (tools.isEmpty()) {
            tools.addAll(TOOL_DEFS.filter { it.id != "invert_ink" })
        }
    }

    private fun saveTools() {
        try {
            val arr = JSONArray()
            for (tool in tools) {
                arr.put(JSONObject().apply {
                    put("id", tool.id)
                    put("name", NativeLocale.t(tool.nameKey))
                    put("icon", tool.id)
                    put("action", tool.action)
                    put("latches", tool.action in setOf("insert_text", "text_recv_nospacing", "text_recv_paragraph", "voice_transcribe"))
                })
            }
            val data = JSONObject().apply { put("tools", arr) }
            val prefs = reactContext.getSharedPreferences(PREFS_NAME, 0)
            prefs.edit().putString(CONFIG_STORE_KEY, data.toString()).apply()
            if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "saveTools: saved ${tools.size} tools")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(tag, "saveTools: ${e.message}")
        }
    }

    private fun showToolbarAndClose() {
        val arr = JSONArray()
        for (tool in tools) {
            arr.put(JSONObject().apply {
                put("id", tool.id)
                put("name", NativeLocale.t(tool.nameKey))
                put("icon", tool.id)
                put("action", tool.action)
                put("latches", tool.action in setOf("insert_text", "text_recv_nospacing", "text_recv_paragraph", "voice_transcribe"))
            })
        }
        toolbarModule.show(arr.toString())
        toolbarModule.requestClosePluginView()
    }
}