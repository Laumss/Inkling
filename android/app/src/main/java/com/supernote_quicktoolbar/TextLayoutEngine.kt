package com.supernote_quicktoolbar

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
        private const val CHAR_WIDTH_FACTOR_CJK = 1.0
        private const val CHAR_WIDTH_FACTOR_LATIN = 0.62
        private const val PAGE_MARGIN_LEFT = 0.07
        private const val PAGE_MARGIN_RIGHT = 0.04
        private const val TOP_MARGIN_BASE = 150
        private const val BASE_PAGE_HEIGHT = 1872
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
            val left = floor(pageWidth * PAGE_MARGIN_LEFT).toInt()
            val right = pageWidth - floor(pageWidth * PAGE_MARGIN_RIGHT).toInt()
            val maxH = floor(pageHeight * cfg.threshold).toInt()
            val boxWidth = right - left

            val cjkCount = text.count { isCJK(it) }
            val cjkRatio = if (text.isNotEmpty()) cjkCount.toDouble() / text.length else 0.0
            val charWidthFactor = CHAR_WIDTH_FACTOR_CJK * cjkRatio + CHAR_WIDTH_FACTOR_LATIN * (1 - cjkRatio)
            val charsPerLine = max(1, floor(boxWidth / (FONT_SIZE * charWidthFactor)).toInt())

            val segments = text.split("\n").filter { it.trim().isNotEmpty() }
            var lines = 0.0
            for ((idx, seg) in segments.withIndex()) {
                val segLines = max(1.0, ceil(seg.length.toDouble() / charsPerLine))
                val newlineGap = if (cfg.newlineGapLines > 0 &&
                    idx < segments.size - 1 && segments.getOrNull(idx + 1)?.trim()?.isNotEmpty() == true
                ) cfg.newlineGapLines else 0.0
                lines += segLines + newlineGap
            }
            val boxH = ceil(lines * FONT_SIZE * cfg.lineHeightRatio).toInt()

            val occupied = parseOccupiedRanges(occupiedArray)
            var top = skipOccupiedArea(nextTop, boxH, max(cfg.boxGap, 10), occupied)

            val bottom = min(top + boxH, pageHeight - topMargin)

            val newPage = top >= maxH || (top > topMargin + 80 && top + boxH > pageHeight - topMargin)

            val result = Arguments.createMap().apply {
                putInt("top", top)
                putInt("boxHeight", boxH)
                putInt("bottom", bottom)
                putBoolean("newPage", newPage)
                putInt("maxH", maxH)
                putInt("left", left)
                putInt("right", right)
                putInt("charsPerLine", charsPerLine)
                putInt("lines", lines.toInt())
                putInt("topMargin", topMargin)
                putInt("boxGap", cfg.boxGap)
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

                val cjkCount = text.count { isCJK(it) }
                val cjkRatio = if (text.isNotEmpty()) cjkCount.toDouble() / text.length else 0.0
                val charWidthFactor = CHAR_WIDTH_FACTOR_CJK * cjkRatio + CHAR_WIDTH_FACTOR_LATIN * (1 - cjkRatio)
                val charsPerLine = max(1, floor(boxWidth / (FONT_SIZE * charWidthFactor)).toInt())

                val segments = text.split("\n").filter { it.trim().isNotEmpty() }
                var lines = 0.0
                for ((idx, seg) in segments.withIndex()) {
                    val segLines = max(1.0, ceil(seg.length.toDouble() / charsPerLine))
                    val newlineGap = if (cfg.newlineGapLines > 0 &&
                        idx < segments.size - 1 && segments.getOrNull(idx + 1)?.trim()?.isNotEmpty() == true
                    ) cfg.newlineGapLines else 0.0
                    lines += segLines + newlineGap
                }
                val boxH = ceil(lines * FONT_SIZE * cfg.lineHeightRatio).toInt()

                val top = skipOccupiedArea(nextTop, boxH, max(cfg.boxGap, 10), occupied)
                val bottom = min(top + boxH, pageHeight - topMargin)
                val newPage = top >= maxH || (top > topMargin + 80 && top + boxH > pageHeight - topMargin)

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

    @ReactMethod
    fun preprocessText(text: String, promise: Promise) {
        try {
            var result = text.replace(Regex("^(\\d+)\\."), "$1​.")
            result = result.replace("*", "")
            result = result.split("\n").filter { it.trim().isNotEmpty() }.joinToString("\n")
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("PREPROCESS_ERROR", e.message, e)
        }
    }

    private fun isCJK(c: Char): Boolean {
        return c in '一'..'鿿' || c in '　'..'〿' || c in '＀'..'￯'
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
