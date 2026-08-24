package com.supernote_quicktoolbar

import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.facebook.react.bridge.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TextLayoutEngine(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "TextLayoutEngine"

    companion object {
        private const val FONT_SIZE = 36
        private const val PAGE_MARGIN_LEFT = 0.07
        private const val PAGE_MARGIN_RIGHT = 0.04
        private const val TOP_MARGIN_BASE = 150
        private const val BASE_PAGE_HEIGHT = 1872
        // Device-calibrated baseline: NOTE wraps text slightly narrower than the frame rect.
        private const val WIDTH_ADJUSTMENT = 30
        // With fontPath="" NOTE uses its own system font, whose glyph widths do not exactly
        // match PluginHost's StaticLayout font. Long boxes can therefore wrap ~15% more
        // lines on the note side even at the adjusted width. Reserve those lines once a
        // box is long enough for the mismatch to accumulate; short boxes keep their
        // compact sizing and are already covered by NOTE_RENDER_PAD.
        private const val SYSTEM_FONT_WRAP_SAFETY_RATIO = 0.15
        private const val SYSTEM_FONT_WRAP_SAFETY_MIN_LINES = 8
        private const val MAX_INSERT_LEFT_RATIO = 0.60
        private const val MIN_TEXTBOX_WIDTH_RATIO = 0.20
        private const val MIN_TEXTBOX_FONT_COLUMNS = 8

        // The NOTE app renders each line at exactly 42px (textLineHeight in
        // trail data), regardless of what StaticLayout computes. Over many
        // lines the difference compounds → bottom content clipped. We now
        // use max(StaticLayout.height, lineCount * DEVICE_LINE_HEIGHT) so
        // the rect is always tall enough for what the device actually renders.
        private const val DEVICE_LINE_HEIGHT = 42
        const val NOTE_RENDER_PAD = 28

        private const val MIN_TEXTBOX_HEIGHT = 48

        // The NOTE app renders emoji noticeably wider than StaticLayout
        // measures them (device-side clipping consistently follows lines
        // with 🦈💤😳 etc.): the device wraps earlier, producing more lines
        // than budgeted → tail lines fall outside the rect. Reserve one
        // extra device line per EMOJI_PER_EXTRA_LINE emoji as insurance —
        // a slightly-too-tall box is harmless, a too-short one clips.
        private const val EMOJI_PER_EXTRA_LINE = 4
        private val EMOJI_REGEX = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{2190}-\\x{21FF}\\x{2B00}-\\x{2BFF}]")

        private fun emojiExtraHeight(text: String): Int {
            val count = EMOJI_REGEX.findAll(text).count()
            if (count == 0) return 0
            return ((count + EMOJI_PER_EXTRA_LINE - 1) / EMOJI_PER_EXTRA_LINE) * DEVICE_LINE_HEIGHT
        }

        private fun noteRenderLineCount(measuredLineCount: Int): Int {
            if (measuredLineCount < SYSTEM_FONT_WRAP_SAFETY_MIN_LINES) return measuredLineCount
            val extraLines = ceil(measuredLineCount * SYSTEM_FONT_WRAP_SAFETY_RATIO).toInt()
            return measuredLineCount + extraLines
        }
    }

    private data class ModeConfig(
        val boxGap: Int,
        val threshold: Double,
        val lineHeightRatio: Double,
        val newlineGapLines: Double,
    )

    private val modeConfigs = mapOf(
        "nospacing" to ModeConfig(boxGap = 0, threshold = 0.87, lineHeightRatio = 1.4, newlineGapLines = 0.0),
        "paragraph" to ModeConfig(boxGap = 40, threshold = 0.80, lineHeightRatio = 1.6, newlineGapLines = 0.3),
    )

    private val textPaint = TextPaint().apply {
        isAntiAlias = true
        textSize = FONT_SIZE.toFloat()
    }

    private fun buildStaticLayout(text: String, width: Int): StaticLayout {
        val measuredWidth = max(24, width - WIDTH_ADJUSTMENT)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, textPaint, measuredWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(true)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, textPaint, measuredWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, true)
        }
    }

    private fun normalizeOverrideLeft(pageWidth: Int, right: Int, requestedLeft: Int): Int {
        val minTextboxWidth = min(
            right,
            max(FONT_SIZE * MIN_TEXTBOX_FONT_COLUMNS, floor(pageWidth * MIN_TEXTBOX_WIDTH_RATIO).toInt())
        )
        val safeMaxLeft = max(
            0,
            min(floor(pageWidth * MAX_INSERT_LEFT_RATIO).toInt(), right - minTextboxWidth)
        )
        return requestedLeft.coerceIn(0, safeMaxLeft)
    }

    @ReactMethod
    fun calculateLayout(params: ReadableMap, promise: Promise) {
        try {
            val text = params.getString("text") ?: ""
            val mode = params.getString("mode") ?: "nospacing"
            val pageWidth = params.getInt("pageWidth")
            val pageHeight = params.getInt("pageHeight")
            val nextTop = params.getInt("nextTop")
            val occupiedArray = params.getArray("occupiedRanges")

            val cfg = modeConfigs[mode] ?: modeConfigs["nospacing"]!!

            val topMargin = (TOP_MARGIN_BASE * (pageHeight.toDouble() / BASE_PAGE_HEIGHT)).roundToInt()
            val defaultLeft = floor(pageWidth * PAGE_MARGIN_LEFT).toInt()
            val right = pageWidth - floor(pageWidth * PAGE_MARGIN_RIGHT).toInt()
            val overrideLeft = if (params.hasKey("overrideLeft") && !params.isNull("overrideLeft")) params.getInt("overrideLeft") else -1
            val left = if (overrideLeft >= 0) normalizeOverrideLeft(pageWidth, right, overrideLeft) else defaultLeft
            val maxH = floor(pageHeight * cfg.threshold).toInt()
            val boxWidth = right - left

            val layout = buildStaticLayout(text, boxWidth)
            val nativeHeight = layout.height
            val measuredLineCount = layout.lineCount
            val renderLineCount = noteRenderLineCount(measuredLineCount)
            // Use the larger of StaticLayout height and the conservative NOTE-side
            // render height so system-font wrap differences cannot clip tail lines.
            val deviceHeight = renderLineCount * DEVICE_LINE_HEIGHT

            val segments = text.split("\n").filter { it.trim().isNotEmpty() }
            val gapCount = if (cfg.newlineGapLines > 0 && segments.size > 1) segments.size - 1 else 0
            val extraGap = ceil(gapCount * cfg.newlineGapLines * FONT_SIZE * cfg.lineHeightRatio).toInt()

            val boxH = max(MIN_TEXTBOX_HEIGHT,
                max(nativeHeight, deviceHeight) + extraGap + emojiExtraHeight(text) + NOTE_RENDER_PAD)

            val occupied = parseOccupiedRanges(occupiedArray)
            val top = skipOccupiedArea(nextTop, boxH, max(cfg.boxGap, 10), occupied)

            val bottom = min(top + boxH, pageHeight - topMargin)

            val usableHeight = pageHeight - 2 * topMargin
            val newPage = top >= maxH
                || (top > topMargin + 80 && top + boxH > pageHeight - topMargin)
                || (top <= topMargin + 80 && boxH > usableHeight)

            val remainingHeight = max(0, (pageHeight - topMargin) - top)

            val result = Arguments.createMap().apply {
                putInt("top", top)
                putInt("boxHeight", boxH)
                putInt("bottom", bottom)
                putBoolean("newPage", newPage)
                putInt("maxH", maxH)
                putInt("left", left)
                putInt("right", right)
                putInt("charsPerLine", if (measuredLineCount > 0) max(1, text.length / measuredLineCount) else 1)
                putInt("lines", renderLineCount)
                putInt("measuredLines", measuredLineCount)
                putInt("topMargin", topMargin)
                putInt("boxGap", cfg.boxGap)
                putInt("nativeHeight", nativeHeight)
                putInt("remainingHeight", remainingHeight)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("LAYOUT_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun measureLineStarts(params: ReadableMap, promise: Promise) {
        try {
            val text = params.getString("text") ?: ""
            val boxWidth = params.getInt("boxWidth")
            val layout = buildStaticLayout(text, boxWidth)
            val starts = Arguments.createArray()
            for (i in 0 until layout.lineCount) starts.pushInt(layout.getLineStart(i))
            val result = Arguments.createMap().apply {
                putInt("lineCount", layout.lineCount)
                putInt("nativeHeight", layout.height)
                putArray("lineStarts", starts)
                putDouble("spaceWidth", textPaint.measureText(" ").toDouble())
            }
            if (params.hasKey("offsets") && !params.isNull("offsets")) {
                val offs = params.getArray("offsets")!!
                val infos = Arguments.createArray()
                for (i in 0 until offs.size()) {
                    val o = offs.getInt(i).coerceIn(0, text.length)
                    infos.pushMap(Arguments.createMap().apply {
                        putInt("line", layout.getLineForOffset(o))
                        putDouble("x", layout.getPrimaryHorizontal(o).toDouble())
                    })
                }
                result.putArray("offsetInfo", infos)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("MEASURE_ERROR", e.message, e)
        }
    }

    private fun parseOccupiedRanges(array: ReadableArray?): List<Pair<Int, Int>> {
        if (array == null) return emptyList()
        val ranges = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until array.size()) {
            val map = array.getMap(i) ?: continue
            val top = map.getInt("top")
            val bottom = map.getInt("bottom")
            ranges.add(Pair(top, bottom))
        }
        ranges.sortBy { it.first }
        return ranges
    }

    private fun skipOccupiedArea(candidateTop: Int, boxH: Int, gap: Int, ranges: List<Pair<Int, Int>>): Int {
        var top = candidateTop
        var iterations = 0
        while (iterations < 50) {
            iterations++
            var collision = false
            for ((rTop, rBottom) in ranges) {
                if (top < rBottom && (top + boxH) > rTop) {
                    top = rBottom + gap
                    collision = true
                    break
                }
            }
            if (!collision) break
        }
        return top
    }
}
