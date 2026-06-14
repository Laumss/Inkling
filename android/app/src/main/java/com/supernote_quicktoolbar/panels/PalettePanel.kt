package com.supernote_quicktoolbar.panels
import com.supernote_quicktoolbar.BuildConfig
import com.supernote_quicktoolbar.*
import com.supernote_quicktoolbar.bubbles.PaletteBubbleModule

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.*
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.ui_common.*
import org.json.JSONArray
import org.json.JSONObject

class PalettePanel(
    ctx: ReactApplicationContext,
    toolbar: FloatingToolbarModule
) : PanelBase(ctx, toolbar) {

    override val tag = "PalettePanel"
    override val panelName = "palette"
    override val heightRatio = 0.80

    companion object {
        @Volatile var currentInstance: PalettePanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): PalettePanel {
            val inst = currentInstance ?: PalettePanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private const val PREFS_NAME = "quicktoolbar_presets"
        private const val PRESETS_KEY = "palette_presets"
        private const val ACTIVE_COLS_KEY = "palette_active_cols"
        private const val TIER_PREFS_KEY = "palette_tier_prefs"

        const val COLS = 6
        const val ROWS = 2
        const val MAX_SLOTS = COLS * ROWS
        const val MIN_ACTIVE_COLS = 4
        const val MAX_ACTIVE_COLS = COLS

        private val INK = Color.BLACK
        private val INK2 = Color.parseColor("#6B6B6B")
        private val LINE = Color.parseColor("#C6C6C6")
        private val LINE2 = Color.parseColor("#9D9D9D")
        private val FAINT = Color.parseColor("#9D9D9D")

        fun normalizePenType(raw: Int): Int? = when (raw) {
            10, 16, 14, 11 -> raw
            0, 2 -> 10
            1 -> 16
            15, 4 -> 14
            else -> null
        }
    }

    data class PenDef(
        val id: String,
        val penType: Int,
        val label: String,
        val kind: String,
        val fixedColor: String? = null,
    )

    private val PENS = listOf(
        PenDef("needle",  10, NativeLocale.t("pen_needle"),      "normal"),
        PenDef("ball",    16, NativeLocale.t("pen_ball"),         "normal"),
        PenDef("brush",   14, NativeLocale.t("pen_calligraphy"), "normal"),
        PenDef("mk-bk",  11, NativeLocale.t("palette_marker_black"), "marker", fixedColor = "black"),
        PenDef("mk-gy",  11, NativeLocale.t("palette_marker_gray"),  "marker", fixedColor = "darkGray"),
        PenDef("mk-wt",  11, NativeLocale.t("palette_marker_white"), "marker", fixedColor = "ghost"),
    )

    private fun penDefById(id: String) = PENS.find { it.id == id } ?: PENS[0]

    data class PenColorDef(val key: String, val value: Int, val label: String, val swatch: Int, val hex: String = "", val isExtended: Boolean = false)

    private val PRODUCT_COLORS = listOf(
        PenColorDef("black",     0x00, NativeLocale.t("color_black"),      Color.BLACK, "#000000"),
        PenColorDef("darkGray",  0x9D, NativeLocale.t("color_dark_gray"),  Color.parseColor("#9D9D9D"), "#9D9D9D"),
        PenColorDef("lightGray", 0xC9, NativeLocale.t("color_light_gray"), Color.parseColor("#C9C9C9"), "#C9C9C9"),
        PenColorDef("ghost",     0xFE, NativeLocale.t("color_ghost"),      Color.WHITE, "#FFFFFF"),
    )

    private val EXTENDED_COLORS = listOf(
        PenColorDef("blue",   0x00, NativeLocale.t("color_blue"),   Color.parseColor("#3F6FA8"), "#3F6FA8", isExtended = true),
        PenColorDef("red",    0x00, NativeLocale.t("color_red"),    Color.parseColor("#B23F3F"), "#B23F3F", isExtended = true),
        PenColorDef("pink",   0x00, NativeLocale.t("color_pink"),   Color.parseColor("#C266A0"), "#C266A0", isExtended = true),
        PenColorDef("orange", 0x00, NativeLocale.t("color_orange"), Color.parseColor("#C67C33"), "#C67C33", isExtended = true),
        PenColorDef("green",  0x00, NativeLocale.t("color_green"),  Color.parseColor("#4F8C4F"), "#4F8C4F", isExtended = true),
        PenColorDef("cyan",   0x00, NativeLocale.t("color_cyan"),   Color.parseColor("#3C9A9A"), "#3C9A9A", isExtended = true),
        PenColorDef("lime",   0x00, NativeLocale.t("color_lime"),   Color.parseColor("#94A93C"), "#94A93C", isExtended = true),
        PenColorDef("purple", 0x00, NativeLocale.t("color_purple"), Color.parseColor("#7B5BA6"), "#7B5BA6", isExtended = true),
    )

    private val EXTENDED_BRIGHT = listOf(
        PenColorDef("blue_b",   0x00, NativeLocale.t("color_blue"),   Color.parseColor("#7FB2E2"), "#7FB2E2", isExtended = true),
        PenColorDef("red_b",    0x00, NativeLocale.t("color_red"),    Color.parseColor("#E58585"), "#E58585", isExtended = true),
        PenColorDef("pink_b",   0x00, NativeLocale.t("color_pink"),   Color.parseColor("#ECA6CF"), "#ECA6CF", isExtended = true),
        PenColorDef("orange_b", 0x00, NativeLocale.t("color_orange"), Color.parseColor("#EEB66E"), "#EEB66E", isExtended = true),
        PenColorDef("green_b",  0x00, NativeLocale.t("color_green"),  Color.parseColor("#90CA90"), "#90CA90", isExtended = true),
        PenColorDef("cyan_b",   0x00, NativeLocale.t("color_cyan"),   Color.parseColor("#7BCDCD"), "#7BCDCD", isExtended = true),
        PenColorDef("lime_b",   0x00, NativeLocale.t("color_lime"),   Color.parseColor("#CCDB79"), "#CCDB79", isExtended = true),
        PenColorDef("purple_b", 0x00, NativeLocale.t("color_purple"), Color.parseColor("#B398DB"), "#B398DB", isExtended = true),
    )

    private val ALL_COLORS = PRODUCT_COLORS + EXTENDED_COLORS + EXTENDED_BRIGHT

    private fun colorDefByKey(key: String) = ALL_COLORS.find { it.key == key }

    data class TierPreset(val key: String, val label: String, var labelValue: Float)

    private val tierPresets = mutableListOf(
        TierPreset("thin",   NativeLocale.t("palette_tier_thin"),   0.3f),
        TierPreset("thick",  NativeLocale.t("palette_tier_thick"),  1.0f),
    )

    data class Preset(val penId: String, val color: String, val thickness: Int) {
        val penType: Int get() = when {
            penId.startsWith("mk-") -> 11
            penId == "ball" -> 16
            penId == "brush" -> 14
            else -> 10
        }
    }

    private var strokeCount = 0
    private var geometryCount = 0
    private var avgThickness = 100
    private var hasMarkerStroke = false
    private var elementNums = listOf<Int>()
    private var dominantPenType: Int? = null
    private var dominantPenColor: Int? = null

    private var activeCols = MIN_ACTIVE_COLS
    private var presets = arrayOfNulls<Preset>(MAX_SLOTS)
    private var selectedSlot: Int? = null

    private var selectedColor: String? = null
    private var selectedPenId: String? = null
    private var thickness = 100
    private var thicknessChanged = false
    private var initialColor: String? = null
    private var initialPenType: Int? = null
    private var activeTierKey: String? = null

    private var thicknessLabel: TextView? = null
    private var thicknessPreview: ImageView? = null
    private var thicknessContainer: LinearLayout? = null
    private var gridContainer: LinearLayout? = null
    private var addColBtn: View? = null
    private var removeColBtn: View? = null
    private var slotInfoLabel: TextView? = null
    private var colorContainer: LinearLayout? = null
    private var markerLockNote: LinearLayout? = null
    private var penTypeLabel: TextView? = null
    private var thicknessHeaderLabel: TextView? = null
    private var applyBtn: TextView? = null
    private val penBtns = mutableMapOf<String, View>()
    private val colorBtns = mutableMapOf<String, View>()
    private val tierBtns = mutableMapOf<String, View>()

    fun show(infoJson: String) {
        currentInstance = this
        PaletteBubbleModule.hideStatic()
        parseInfo(infoJson)
        loadPresets()
        loadTierPrefs()
        selectedPenType(dominantPenType)
        selectedColor = dominantPenColor?.let { c -> PRODUCT_COLORS.find { it.value == c }?.key } ?: "black"
        initialPenType = dominantPenType?.let { normalizePenType(it) }
        initialColor = selectedColor
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "show: dominantPenType=$dominantPenType -> $selectedPenId, " +
            "dominantPenColor=$dominantPenColor -> $selectedColor, avgThickness=$avgThickness")
        thicknessChanged = false
        activeTierKey = null
        thickness = PenSizeSpec.snap(initialPenType, avgThickness)
        showPanel()
    }

    private fun selectedPenType(type: Int?) {
        val normalized = type?.let { normalizePenType(it) }
        selectedPenId = when (normalized) {
            10 -> "needle"
            16 -> "ball"
            14 -> "brush"
            11 -> "mk-bk"
            else -> "needle"
        }
    }

    override fun onHide() {
        thicknessLabel = null
        thicknessPreview = null
        thicknessContainer = null
        gridContainer = null
        addColBtn = null
        removeColBtn = null
        slotInfoLabel = null
        colorContainer = null
        colorHeaderLabel = null
        markerLockNote = null
        penTypeLabel = null
        thicknessHeaderLabel = null
        applyBtn = null
        penBtns.clear()
        colorBtns.clear()
        tierBtns.clear()
        currentInstance = null
    }

    private fun parseInfo(json: String) {
        try {
            val o = JSONObject(json)
            strokeCount = o.optInt("strokeCount", 0)
            geometryCount = o.optInt("geometryCount", 0)
            avgThickness = o.optInt("avgThickness", 100)
            hasMarkerStroke = o.optBoolean("hasMarkerStroke", false)
            dominantPenType = if (o.has("dominantPenType") && !o.isNull("dominantPenType"))
                o.getInt("dominantPenType") else null
            dominantPenColor = if (o.has("dominantPenColor") && !o.isNull("dominantPenColor"))
                o.getInt("dominantPenColor") else null
            val arr = o.optJSONArray("elementNums")
            elementNums = if (arr != null) (0 until arr.length()).map { arr.getInt(it) } else emptyList()
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(tag, "parseInfo: ${e.message}")
        }
    }

    private fun curPreset(): Preset? = selectedSlot?.let { presets[it] }
    private fun colOf(idx: Int) = idx % COLS
    private fun isSlotActive(idx: Int) = colOf(idx) < activeCols

    private fun currentPenDef(): PenDef? = selectedPenId?.let { penDefById(it) }
    private fun isMarkerSelected(): Boolean = currentPenDef()?.kind == "marker"

    private fun effectivePenType(): Int? = currentPenDef()?.penType

    override fun buildContent(root: LinearLayout) {
        renderDsl(root) {
            header(NativeLocale.t("palette_header_title"))

            custom { host ->
                val scrollHost = PanelScrollHost(host.ctx, overlayScrollbar = true)
                val content = scrollHost.content
                content.setPadding(0, 0, 0, 0)

                content.addView(buildSlotsSection())
                content.addView(makeFullDivider())

                content.addView(buildTripleSection())

                thicknessContainer = LinearLayout(host.ctx).apply {
                    orientation = LinearLayout.VERTICAL
                }
                thicknessPreview = ImageView(host.ctx).apply {
                    adjustViewBounds = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        leftMargin = host.dp(18); rightMargin = host.dp(18)
                        topMargin = host.dp(4); bottomMargin = host.dp(4)
                    }
                }
                thicknessContainer!!.addView(thicknessPreview)
                content.addView(thicknessContainer)

                updatePenSelection()
                updateColorSelection()
                syncThicknessDisplay()

                scrollHost.view
            }

            custom { _ ->
                applyBtn = makeFilledBtn(NativeLocale.t("palette_apply")) { doApply() }
                updateApplyState()
                makeBottomBar(
                    leftButtons = listOf(
                        makeOutlinedBtn(NativeLocale.t("palette_reset")) { doResetAll() }
                    ),
                    rightButtons = listOf(
                        makeOutlinedBtn(NativeLocale.t("cancel")) { closeAndRestore() },
                        applyBtn!!
                    )
                )
            }
        }
    }

    private fun makeFullDivider(): View {
        return View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1.5f)
            )
            setBackgroundColor(LINE)
        }
    }

    private fun makeSectionHeader(zhLabel: String, enLabel: String, trailingText: String? = null): LinearLayout {
        return LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }

            addView(TextView(reactContext).apply {
                text = zhLabel
                textSize = sp(14.5f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(INK)
            })

            addView(TextView(reactContext).apply {
                text = enLabel.uppercase()
                textSize = sp(11.5f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(INK2)
                letterSpacing = 0.15f
                setPadding(dp(9), 0, 0, 0)
            })

            if (trailingText != null) {
                addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                })
                addView(TextView(reactContext).apply {
                    text = trailingText
                    textSize = sp(12.5f)
                    setTextColor(FAINT)
                })
            }
        }
    }

    private fun buildSlotsSection(): LinearLayout {
        val section = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(15), 0, dp(15))
        }

        slotInfoLabel = TextView(reactContext).apply {
            textSize = sp(14.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            setPadding(dp(18), 0, dp(18), dp(12))
        }
        updateSlotHeaderText()
        section.addView(slotInfoLabel)

        gridContainer = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        rebuildGrid()
        section.addView(gridContainer)

        val colBtnRow = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(10), dp(18), 0)
        }
        removeColBtn = makeColCtrlBtn(NativeLocale.t("palette_remove_col")) { removeColumn() }
        addColBtn = makeColCtrlBtn(NativeLocale.t("palette_add_col")) { addColumn() }
        colBtnRow.addView(removeColBtn)
        colBtnRow.addView(View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9), 0)
        })
        colBtnRow.addView(addColBtn)
        updateColBtnState()
        section.addView(colBtnRow)

        return section
    }

    private fun currentPenDescription(): String {
        val pen = currentPenDef() ?: return ""
        val colorDef = selectedColor?.let { colorDefByKey(it) }
        val colorName = colorDef?.label ?: ""
        val thkLabel = PenSizeSpec.label(effectivePenType(), thickness)
        return "$colorName${pen.label} $thkLabel"
    }

    private fun updateSlotHeaderText() {
        slotInfoLabel?.text = currentPenDescription().ifEmpty {
            NativeLocale.t("palette_header_new")
        }
    }

    private fun rebuildGrid() {
        val container = gridContainer ?: return
        container.removeAllViews()
        val gap = dp(7)
        for (r in 0 until ROWS) {
            val row = LinearLayout(reactContext).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (r > 0) topMargin = gap }
            }
            for (c in 0 until COLS) {
                val idx = r * COLS + c
                if (c > 0) row.addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(gap, 0)
                })
                row.addView(makeSlotCell(idx))
            }
            container.addView(row)
        }
    }

    private fun makeSlotCell(index: Int): View {
        val disabled = !isSlotActive(index)
        val preset = presets[index]
        val isActive = index == selectedSlot
        val cellH = dp(62)

        if (disabled) return makeDisabledSlot(index, cellH)

        val cell = FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, cellH, 1f)
            background = if (isActive) GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2.5f), INK)
                cornerRadius = 0f
            } else null
            setOnClickListener {
                if (preset != null) {
                    if (selectedSlot == index) {
                        selectedSlot = null
                        restoreFromLasso()
                    } else {
                        selectedSlot = index
                        loadSlotIntoEditor()
                    }
                    refreshAll()
                } else {
                    val color = selectedColor ?: "black"
                    val penId = selectedPenId ?: "needle"
                    presets[index] = Preset(penId, color, thickness)
                    savePresets()
                    refreshAll()
                }
            }
        }

        if (preset == null) {
            cell.addView(TextView(reactContext).apply {
                text = "+"
                textSize = sp(21f)
                setTextColor(FAINT)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        } else {
            val pen = penDefById(preset.penId)
            val inner = LinearLayout(reactContext).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setPadding(dp(5), dp(5), dp(6), 0)
            }

            val iconSz = dp(17)
            inner.addView(ImageView(reactContext).apply {
                setImageBitmap(VectorAssets.loadBitmapTinted(
                    reactContext, PenSizeSpec.penIconAsset(pen.penType), iconSz, INK))
                layoutParams = LinearLayout.LayoutParams(iconSz, iconSz)
            })

            inner.addView(FrameLayout(reactContext).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                val strkW = dp(28)
                val strkH = dp(22)
                addView(ImageView(reactContext).apply {
                    layoutParams = FrameLayout.LayoutParams(strkW, strkH, Gravity.CENTER)
                    setImageBitmap(makeStrokeLine(
                        strkW, strkH,
                        colorDefByKey(preset.color)?.swatch ?: Color.BLACK,
                        tierThickness(preset.thickness),
                        preset.color == "ghost"
                    ))
                })
            })

            cell.addView(inner)
        }

        cell.addView(TextView(reactContext).apply {
            text = "${index + 1}"
            textSize = sp(10.5f)
            setTextColor(FAINT)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { rightMargin = dp(5); bottomMargin = dp(3) }
        })

        return cell
    }

    private fun tierThickness(raw: Int): Float {
        val label = PenSizeSpec.label(null, raw)
        val value = label.replace("mm", "").trim().toFloatOrNull() ?: 0.5f
        return when {
            value <= 0.4f -> 2.6f
            value <= 0.8f -> 5.2f
            else -> 8.4f
        }
    }

    private fun makeStrokeLine(w: Int, h: Int, color: Int, strokeW: Float, isWhite: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val scale = w / 28f
        if (isWhite) {
            val underPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = LINE2; style = Paint.Style.STROKE
                this.strokeWidth = (strokeW + 3f) * scale; strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(5f * scale, 17f * scale, 23f * scale, 5f * scale, underPaint)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE
            this.strokeWidth = strokeW * scale; strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(5f * scale, 17f * scale, 23f * scale, 5f * scale, paint)
        return bmp
    }

    private fun makeDisabledSlot(index: Int, cellH: Int): View {
        return FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, cellH, 1f)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1.5f), LINE2, dp(4).toFloat(), dp(3).toFloat())
                cornerRadius = 0f
            }
            alpha = 0.6f
            addView(TextView(reactContext).apply {
                text = "+"
                textSize = sp(21f)
                setTextColor(FAINT)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            setOnClickListener {
                val targetCol = colOf(index) + 1
                if (targetCol > activeCols) {
                    activeCols = targetCol
                    saveActiveCols()
                    refreshAll()
                }
            }
        }
    }

    private fun makeColCtrlBtn(text: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            this.text = text
            textSize = sp(12.5f)
            setTextColor(INK2)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1.5f), LINE2, dp(4).toFloat(), dp(3).toFloat())
                cornerRadius = 0f
            }
            setOnClickListener { onClick() }
        }
    }

    private fun buildTripleSection(): LinearLayout {
        val triple = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(15), dp(18), dp(15))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val penCol = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.38f)
            setPadding(0, 0, dp(10), 0)
        }
        penTypeLabel = TextView(reactContext).apply {
            textSize = sp(14.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            setPadding(0, 0, 0, dp(10))
        }
        updatePenTypeLabel()
        penCol.addView(penTypeLabel)
        buildPenTypeGrid(penCol)
        triple.addView(penCol)

        val thkCol = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.34f)
            setPadding(dp(6), 0, dp(6), 0)
        }
        thicknessHeaderLabel = TextView(reactContext).apply {
            textSize = sp(14.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            setPadding(0, 0, 0, dp(10))
        }
        thkCol.addView(thicknessHeaderLabel)
        buildThicknessInline(thkCol)
        triple.addView(thkCol)

        val colorCol = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.28f)
            setPadding(dp(10), 0, 0, 0)
        }
        colorContainer = colorCol
        buildColorSection(colorCol)
        triple.addView(colorCol)

        return triple
    }

    private fun buildPenTypeGrid(parent: LinearLayout) {
        penBtns.clear()
        val normalPens = PENS.filter { it.kind == "normal" }
        val markerPens = PENS.filter { it.kind == "marker" }
        parent.addView(makePenRow(normalPens))
        parent.addView(makePenRow(markerPens))
    }

    private fun makePenRow(pens: List<PenDef>): LinearLayout {
        return LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((i, pen) in pens.withIndex()) {
                if (i > 0) addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(7), 0)
                })
                val tile = makePenTile(pen)
                penBtns[pen.id] = tile
                addView(tile)
            }
        }
    }

    private fun makePenTile(pen: PenDef): FrameLayout {
        val isActive = selectedPenId == pen.id
        return FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(60), 1f)
            background = if (isActive) GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2.5f), INK)
                cornerRadius = 0f
            } else null
            setOnClickListener {
                if (selectedPenId == pen.id) return@setOnClickListener
                val prevPen = effectivePenType()
                selectedPenId = pen.id
                activeTierKey = null
                if (pen.kind == "marker" && pen.fixedColor != null) {
                    selectedColor = pen.fixedColor
                }
                val newPen = effectivePenType()
                val converted = PenSizeSpec.convert(prevPen, newPen, thickness)
                if (converted != thickness) {
                    thickness = converted
                    thicknessChanged = true
                }
                syncThicknessDisplay()
                updatePenSelection()
                updateColorSelection()
                updateApplyState()
                autoSaveToActivePreset()
            }

            val inner = LinearLayout(reactContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            val iconSz = dp(25)
            inner.addView(ImageView(reactContext).apply {
                setImageBitmap(VectorAssets.loadBitmap(reactContext, PenSizeSpec.penIconAsset(pen.penType), iconSz))
                layoutParams = LinearLayout.LayoutParams(iconSz, iconSz)
            })

            inner.addView(TextView(reactContext).apply {
                text = pen.label
                textSize = sp(10.5f)
                setTextColor(if (isActive) INK else INK2)
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(3), 0, 0)
            })

            addView(inner)

            if (pen.kind == "marker" && pen.fixedColor != null) {
                val dotSz = dp(8)
                val colorDef = colorDefByKey(pen.fixedColor)
                addView(View(reactContext).apply {
                    layoutParams = FrameLayout.LayoutParams(dotSz, dotSz, Gravity.BOTTOM or Gravity.END).apply {
                        rightMargin = dp(4); bottomMargin = dp(4)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(colorDef?.swatch ?: Color.BLACK)
                        if (pen.fixedColor == "ghost") setStroke(1, LINE2)
                    }
                })
            }

        }
    }

    private fun updatePenTypeLabel() {
        penTypeLabel?.text = currentPenDef()?.label ?: NativeLocale.t("palette_pen_type")
    }

    private fun updatePenSelection() {
        for ((id, btn) in penBtns) {
            val selected = selectedPenId == id
            val frame = btn as? FrameLayout ?: continue

            frame.background = if (selected) GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2.5f), INK)
                cornerRadius = 0f
            } else null

            val inner = frame.getChildAt(0) as? LinearLayout ?: continue
            val label = inner.getChildAt(1) as? TextView ?: continue
            label.setTextColor(if (selected) INK else INK2)
        }
        updatePenTypeLabel()
        thicknessContainer?.visibility = if (isMarkerSelected()) View.GONE else View.VISIBLE
    }

    private var colorHeaderLabel: TextView? = null

    private fun buildColorSection(parent: LinearLayout) {
        colorBtns.clear()

        colorHeaderLabel = TextView(reactContext).apply {
            textSize = sp(14.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            setPadding(0, 0, 0, dp(10))
        }
        updateColorHeaderLabel()
        parent.addView(colorHeaderLabel)

        buildColorGrid(parent, PRODUCT_COLORS, 2)

        if (BuildConfig.ENABLE_DEBUG) {
            parent.addView(View(reactContext).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
            })

            buildColorGrid(parent, EXTENDED_COLORS, 4)
            parent.addView(View(reactContext).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
            })
            buildColorGrid(parent, EXTENDED_BRIGHT, 4)
        }

        markerLockNote = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, 0)
            visibility = View.GONE
            addView(ImageView(reactContext).apply {
                val sz = dp(14)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { rightMargin = dp(8) }
                setImageBitmap(makeLockIcon(sz))
            })
            addView(TextView(reactContext).apply {
                text = NativeLocale.t("palette_marker_note2")
                textSize = sp(11.5f)
                setTextColor(INK2)
            })
        }
        parent.addView(markerLockNote)
    }

    private fun buildColorGrid(parent: LinearLayout, colors: List<PenColorDef>, cols: Int) {
        val gap = dp(5)
        for (i in colors.indices step cols) {
            val row = LinearLayout(reactContext).apply {
                orientation = LinearLayout.HORIZONTAL
                if (i > 0) layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = gap }
            }
            for (c in 0 until cols) {
                if (c > 0) row.addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(gap, 0)
                })
                if (i + c < colors.size) {
                    row.addView(makeColorDot(colors[i + c]))
                } else {
                    row.addView(View(reactContext).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                    })
                }
            }
            parent.addView(row)
        }
    }

    private fun updateColorHeaderLabel() {
        val colorDef = selectedColor?.let { colorDefByKey(it) }
        colorHeaderLabel?.text = colorDef?.label ?: NativeLocale.t("palette_color")
    }

    private fun makeColorDot(def: PenColorDef): FrameLayout {
        val dotSz = if (def.isExtended) dp(22) else dp(30)
        val cellH = if (def.isExtended) dp(28) else dp(38)
        val cell = FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, cellH, 1f)
            setOnClickListener {
                if (isMarkerSelected()) return@setOnClickListener
                selectedColor = def.key
                activeTierKey = null
                updateColorSelection()
                updateApplyState()
                autoSaveToActivePreset()
            }
            addView(View(reactContext).apply {
                layoutParams = FrameLayout.LayoutParams(dotSz, dotSz, Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(def.swatch)
                    setStroke(if (def.isExtended) dp(1) else dp(1.5f), LINE2)
                }
            })
        }
        colorBtns[def.key] = cell
        return cell
    }

    private fun makeLockIcon(sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val s = sizePx / 16f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK2; style = Paint.Style.STROKE
            strokeWidth = 1.5f * s; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        canvas.drawRect(3f * s, 7f * s, 13f * s, 14f * s, paint)

        val path = Path().apply {
            moveTo(5.2f * s, 7f * s)
            lineTo(5.2f * s, 5f * s)
            addArc(5.2f * s, 2.2f * s, 10.8f * s, 7.8f * s, 180f, -180f)
            lineTo(10.8f * s, 7f * s)
        }
        canvas.drawPath(path, paint)
        return bmp
    }

    private fun updateColorSelection() {
        val isMarker = isMarkerSelected()
        for ((_, btn) in colorBtns) {
            btn.alpha = if (isMarker) 0.3f else 1f
        }
        updateColorHeaderLabel()
        updateSlotHeaderText()
        markerLockNote?.visibility = if (isMarker) View.VISIBLE else View.GONE
    }

    private fun buildThicknessInline(parent: LinearLayout) {

        val tierRow = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for ((i, tier) in tierPresets.withIndex()) {
            if (i > 0) tierRow.addView(View(reactContext).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6), 0)
            })
            val btn = makeTierBtn(tier)
            tierBtns[tier.key] = btn
            tierRow.addView(btn)
        }
        parent.addView(tierRow)

        val stepper = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        stepper.addView(makeStepperBtn("−") { stepThickness(-1) })
        thicknessLabel = TextView(reactContext).apply {
            textSize = sp(16f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
        }
        stepper.addView(thicknessLabel)
        stepper.addView(makeStepperBtn("+") { stepThickness(1) })
        parent.addView(stepper)

        syncThicknessDisplay()
    }

    private fun makeTierBtn(tier: TierPreset): FrameLayout {
        val isActive = activeTierKey == tier.key
        return FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
            background = if (isActive) GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2.5f), INK)
                cornerRadius = 0f
            } else null
            setOnClickListener {
                if (effectivePenType() == PenSizeSpec.MARKER) return@setOnClickListener
                if (activeTierKey == tier.key) {
                    activeTierKey = null
                } else {
                    activeTierKey = tier.key
                    thickness = PenSizeSpec.rawForLabel(effectivePenType(), tier.labelValue)
                    thicknessChanged = true
                }
                syncThicknessDisplay()
                updateTierSelection()
                updateApplyState()
                autoSaveToActivePreset()
            }

            val inner = LinearLayout(reactContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            inner.addView(TextView(reactContext).apply {
                text = tier.label
                textSize = sp(12.5f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (isActive) INK else INK2)
                gravity = Gravity.CENTER
                letterSpacing = 0.2f
            })

            inner.addView(ImageView(reactContext).apply {
                val w = dp(60); val h = dp(12)
                layoutParams = LinearLayout.LayoutParams(w, h).apply { topMargin = dp(5) }
                setImageBitmap(makeTierPreviewLine(w, h, tier))
            })
            addView(inner)
        }
    }

    private fun makeTierPreviewLine(w: Int, h: Int, tier: TierPreset): Bitmap {
        val strokeW = when (tier.key) {
            "thin" -> 2.0f
            "medium" -> 4.0f
            else -> 7.0f
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK2; style = Paint.Style.STROKE
            this.strokeWidth = strokeW * density * ScreenScale.factor(reactContext) / 2f
            strokeCap = Paint.Cap.ROUND
        }
        val path = Path().apply {
            moveTo(w * 0.05f, h * 0.75f)
            cubicTo(w * 0.33f, h * 0.5f, w * 0.63f, h * 0.42f, w * 0.95f, h * 0.33f)
        }
        canvas.drawPath(path, paint)
        return bmp
    }

    private fun makeStepperBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = label
            textSize = sp(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK2)
            gravity = Gravity.CENTER
            val sz = dp(28)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener { onClick() }
        }
    }

    private fun updateTierSelection() {
        for ((key, btn) in tierBtns) {
            val isActive = activeTierKey == key
            val frame = btn as? FrameLayout ?: continue

            frame.background = if (isActive) GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2.5f), INK)
                cornerRadius = 0f
            } else null

            val inner = frame.getChildAt(0) as? LinearLayout ?: continue
            val label = inner.getChildAt(0) as? TextView ?: continue
            label.setTextColor(if (isActive) INK else INK2)
        }
    }

    private fun syncThicknessDisplay() {
        val isMarker = effectivePenType() == PenSizeSpec.MARKER
        val labelText = if (isMarker) "—" else PenSizeSpec.label(effectivePenType(), thickness)
        thicknessLabel?.text = labelText
        thicknessHeaderLabel?.text = "${NativeLocale.t("palette_thickness")} $labelText"
        thicknessPreview?.setImageBitmap(
            VectorAssets.loadBitmapTinted(
                reactContext,
                PenSizeSpec.previewAsset(effectivePenType(), thickness),
                dp(280),
                Color.BLACK
            )
        )
        updateSlotHeaderText()
    }

    private fun stepThickness(direction: Int) {
        val penType = effectivePenType()
        if (penType == PenSizeSpec.MARKER) return

        if (activeTierKey != null) {
            val tier = tierPresets.find { it.key == activeTierKey } ?: return
            val labels = PenSizeSpec.labelsFor(penType)
            if (labels.isEmpty()) return
            val curIdx = labels.indices.minByOrNull { Math.abs(labels[it] - tier.labelValue) } ?: return
            val newIdx = (curIdx + direction).coerceIn(0, labels.lastIndex)
            tier.labelValue = labels[newIdx]
            thickness = PenSizeSpec.rawForLabel(penType, tier.labelValue)
            thicknessChanged = true
            saveTierPrefs()
            updateTierSelection()
        } else {
            val steps = PenSizeSpec.stepsFor(penType)
            val curIdx = steps.indexOfFirst { it == thickness }.takeIf { it >= 0 }
                ?: steps.indices.minByOrNull { Math.abs(steps[it] - thickness) } ?: 0
            val newIdx = (curIdx + direction).coerceIn(0, steps.lastIndex)
            thickness = steps[newIdx]
            thicknessChanged = true
        }
        syncThicknessDisplay()
        updateApplyState()
        autoSaveToActivePreset()
    }

    private fun loadSlotIntoEditor() {
        val preset = curPreset() ?: return
        selectedPenId = preset.penId
        selectedColor = preset.color
        thickness = preset.thickness
        thicknessChanged = true
        activeTierKey = null
    }

    private fun restoreFromLasso() {
        selectedPenType(dominantPenType)
        selectedColor = initialColor
        thickness = PenSizeSpec.snap(initialPenType?.let { normalizePenType(it) }, avgThickness)
        thicknessChanged = false
        activeTierKey = null
    }

    private fun addColumn() {
        if (activeCols >= MAX_ACTIVE_COLS) return
        activeCols++
        saveActiveCols()
        refreshAll()
    }

    private fun removeColumn() {
        if (activeCols <= MIN_ACTIVE_COLS) return
        val removingCol = activeCols - 1
        val removingIndices = (0 until ROWS).map { r -> r * COLS + removingCol }
        val hasData = removingIndices.any { presets[it] != null }
        val doRemove = {
            removingIndices.forEach { presets[it] = null }
            if (selectedSlot != null && colOf(selectedSlot!!) >= activeCols - 1) selectedSlot = null
            activeCols--
            saveActiveCols()
            savePresets()
            refreshAll()
        }
        if (hasData) {
            showDeleteConfirm(removingIndices.count { presets[it] != null }, doRemove)
        } else {
            doRemove()
        }
    }

    private fun updateColBtnState() {
        addColBtn?.let {
            it.alpha = if (activeCols >= MAX_ACTIVE_COLS) 0.4f else 1f
            it.isEnabled = activeCols < MAX_ACTIVE_COLS
        }
        removeColBtn?.let {
            it.alpha = if (activeCols <= MIN_ACTIVE_COLS) 0.4f else 1f
            it.isEnabled = activeCols > MIN_ACTIVE_COLS
        }
    }

    private fun updateSlotInfo() {
        updateSlotHeaderText()
    }

    private fun doResetAll() {
        val count = presets.count { it != null }
        if (count == 0) return
        showDeleteConfirm(count) {
            for (i in presets.indices) presets[i] = null
            selectedSlot = null
            activeCols = MIN_ACTIVE_COLS
            saveActiveCols()
            savePresets()
            refreshAll()
        }
    }

    private fun showDeleteConfirm(count: Int, onConfirm: () -> Unit) {
        val msg = NativeLocale.t("palette_delete_confirm", count)
        com.ratta.supernote.pluginlib.api.HostUIAPI.getInstance().showRattaDialog(
            reactContext.currentActivity, msg,
            NativeLocale.t("cancel"), NativeLocale.t("confirm"), false,
            object : com.ratta.supernote.pluginlib.callback.RattaDialogListener {
                override fun onConfirm() { onConfirm() }
                override fun onCancel() {}
            }
        )
    }

    private fun autoSaveToActivePreset() {
        val idx = selectedSlot ?: return
        val preset = curPreset() ?: return
        val color = selectedColor ?: return
        val penId = selectedPenId ?: return
        presets[idx] = Preset(penId, color, thickness)
        savePresets()
        rebuildGrid()
    }

    private fun colorChanged(): Boolean {
        val cur = selectedColor ?: return false
        return cur != initialColor
    }

    private fun penTypeChanged(): Boolean {
        val curType = effectivePenType() ?: return false
        return curType != initialPenType
    }

    private fun updateApplyState() {
        val canApply = colorChanged() || thicknessChanged || penTypeChanged()
        applyBtn?.alpha = if (canApply) 1f else 0.4f
        applyBtn?.isEnabled = canApply
    }

    private fun refreshAll() {
        rebuildGrid()
        updateColBtnState()
        updateSlotInfo()
        updatePenSelection()
        updateColorSelection()
        updateTierSelection()
        syncThicknessDisplay()
        updateApplyState()
    }

    private fun doApply() {
        val colorDef = selectedColor?.let { colorDefByKey(it) }
        val colorValue = if (colorChanged() && colorDef != null) {
            if (colorDef.isExtended) 0x00 else colorDef.value
        } else null

        val map = Arguments.createMap().apply {
            if (colorValue != null) putInt("penColor", colorValue)
            if (thicknessChanged) putInt("thickness", thickness)
            if (penTypeChanged()) putInt("penType", effectivePenType()!!)
            putString("elementNums", JSONArray(elementNums).toString())
        }
        toolbarModule.emitEventPublic("paletteApply", map)
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun closeAndRestore() {
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun loadPresets() {
        try {
            val prefs = reactContext.getSharedPreferences(PREFS_NAME, 0)
            activeCols = prefs.getInt(ACTIVE_COLS_KEY, MIN_ACTIVE_COLS)
                .coerceIn(MIN_ACTIVE_COLS, MAX_ACTIVE_COLS)
            val json = prefs.getString(PRESETS_KEY, null) ?: return
            val arr = JSONArray(json)
            for (i in 0 until minOf(arr.length(), MAX_SLOTS)) {
                if (arr.isNull(i)) { presets[i] = null; continue }
                val o = arr.getJSONObject(i)
                val penId = o.optString("penId", "")
                val color = o.optString("color", "black")
                val rawThickness = o.optInt("thickness", 200)
                if (penId.isEmpty()) {
                    val rawPt = o.optInt("penType", 10)
                    val pt = when (rawPt) { 0 -> 10; 1 -> 16; 2 -> 10; else -> rawPt }
                    val migPenId = when {
                        pt == 11 -> when (color) {
                            "darkGray" -> "mk-gy"
                            "ghost" -> "mk-wt"
                            else -> "mk-bk"
                        }
                        pt == 16 -> "ball"
                        pt == 14 -> "brush"
                        else -> "needle"
                    }
                    presets[i] = Preset(migPenId, color, PenSizeSpec.snap(pt, rawThickness))
                } else {
                    val pen = penDefById(penId)
                    presets[i] = Preset(penId, color, PenSizeSpec.snap(pen.penType, rawThickness))
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(tag, "loadPresets: ${e.message}")
        }
    }

    private fun savePresets() {
        try {
            val arr = JSONArray()
            for (i in 0 until MAX_SLOTS) {
                val p = presets[i]
                if (p == null) { arr.put(JSONObject.NULL); continue }
                arr.put(JSONObject().apply {
                    put("penId", p.penId)
                    put("color", p.color)
                    put("thickness", p.thickness)
                    put("penType", p.penType)
                })
            }
            val prefs = reactContext.getSharedPreferences(PREFS_NAME, 0)
            prefs.edit().putString(PRESETS_KEY, arr.toString()).apply()
            PaletteBubbleModule.reloadPresets()
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(tag, "savePresets: ${e.message}")
        }
    }

    private fun saveActiveCols() {
        val prefs = reactContext.getSharedPreferences(PREFS_NAME, 0)
        prefs.edit().putInt(ACTIVE_COLS_KEY, activeCols).apply()
    }

    private fun loadTierPrefs() {
        try {
            val prefs = reactContext.getSharedPreferences(PREFS_NAME, 0)
            val json = prefs.getString(TIER_PREFS_KEY, null) ?: return
            val o = JSONObject(json)
            for (tier in tierPresets) {
                if (o.has(tier.key)) {
                    tier.labelValue = o.getDouble(tier.key).toFloat()
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveTierPrefs() {
        try {
            val o = JSONObject()
            for (tier in tierPresets) {
                o.put(tier.key, tier.labelValue.toDouble())
            }
            val prefs = reactContext.getSharedPreferences(PREFS_NAME, 0)
            prefs.edit().putString(TIER_PREFS_KEY, o.toString()).apply()
        } catch (_: Exception) {}
    }
}