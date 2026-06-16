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
        private const val WIDTH_ADJUSTMENT = 30

        private const val HEIGHT_SAFETY_FACTOR = 1.15
        private const val BOTTOM_PAD = 50
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
            val left = if (overrideLeft >= 0) max(0, min(overrideLeft, right - (FONT_SIZE * 3))) else defaultLeft
            val maxH = floor(pageHeight * cfg.threshold).toInt()
            val boxWidth = right - left

            val layout = buildStaticLayout(text, boxWidth)
            val nativeHeight = layout.height
            val lineCount = layout.lineCount

            val segments = text.split("\n").filter { it.trim().isNotEmpty() }
            val gapCount = if (cfg.newlineGapLines > 0 && segments.size > 1) segments.size - 1 else 0
            val extraGap = ceil(gapCount * cfg.newlineGapLines * FONT_SIZE * cfg.lineHeightRatio).toInt()

            val boxH = ceil((nativeHeight + extraGap) * HEIGHT_SAFETY_FACTOR).toInt() + BOTTOM_PAD

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
                putInt("charsPerLine", if (lineCount > 0) max(1, text.length / lineCount) else 1)
                putInt("lines", lineCount)
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
    fun calculateBatch(params: ReadableMap, promise: Promise) {
        try {
            val texts = params.getArray("texts") ?: Arguments.createArray()
            val mode = params.getString("mode") ?: "nospacing"
            val pageWidth = params.getInt("pageWidth")
            val pageHeight = params.getInt("pageHeight")
            var nextTop = params.getInt("nextTop")
            val occupiedArray = params.getArray("occupiedRanges")

            val cfg = modeConfigs[mode] ?: modeConfigs["nospacing"]!!
            val topMargin = (TOP_MARGIN_BASE * (pageHeight.toDouble() / BASE_PAGE_HEIGHT)).roundToInt()
            val left = floor(pageWidth * PAGE_MARGIN_LEFT).toInt()
            val right = pageWidth - floor(pageWidth * PAGE_MARGIN_RIGHT).toInt()
            val maxH = floor(pageHeight * cfg.threshold).toInt()
            val boxWidth = right - left

            val occupied = parseOccupiedRanges(occupiedArray).toMutableList()
            val results = Arguments.createArray()

            for (i in 0 until texts.size()) {
                val text = texts.getString(i) ?: ""

                val layout = buildStaticLayout(text, boxWidth)
                val nativeHeight = layout.height

                val segments = text.split("\n").filter { it.trim().isNotEmpty() }
                val gapCount = if (cfg.newlineGapLines > 0 && segments.size > 1) segments.size - 1 else 0
                val extraGap = ceil(gapCount * cfg.newlineGapLines * FONT_SIZE * cfg.lineHeightRatio).toInt()
                val boxH = ceil((nativeHeight + extraGap) * HEIGHT_SAFETY_FACTOR).toInt() + BOTTOM_PAD

                val top = skipOccupiedArea(nextTop, boxH, max(cfg.boxGap, 10), occupied)
                val bottom = min(top + boxH, pageHeight - topMargin)
                val batchUsableH = pageHeight - 2 * topMargin
                val newPage = top >= maxH
                    || (top > topMargin + 80 && top + boxH > pageHeight - topMargin)
                    || (top <= topMargin + 80 && boxH > batchUsableH)

                val item = Arguments.createMap().apply {
                    putInt("top", top)
                    putInt("boxHeight", boxH)
                    putInt("bottom", bottom)
                    putBoolean("newPage", newPage)
                }
                results.pushMap(item)

                if (newPage) break

                occupied.add(Pair(top, bottom))
                occupied.sortBy { it.first }
                nextTop = bottom + cfg.boxGap
            }

            promise.resolve(results)
        } catch (e: Exception) {
            promise.reject("BATCH_LAYOUT_ERROR", e.message, e)
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
