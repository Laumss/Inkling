package com.supernote_quicktoolbar

import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import java.io.File
import kotlin.math.max
import kotlin.math.min

class TextboxMetricsModule(
    reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "TextboxMetrics"

    @ReactMethod
    fun measureTextLayout(options: ReadableMap, promise: Promise) {
        try {
            val ctx = buildMeasurement(options)
            val layout = ctx.layout

            var maxLineWidth = 0.0
            for (i in 0 until layout.lineCount) {
                maxLineWidth = maxOf(maxLineWidth, layout.getLineWidth(i).toDouble())
            }

            val result = Arguments.createMap().apply {
                putString("text", ctx.text)
                putInt("requestedWidth", ctx.width)
                putDouble("requestedFontSize", ctx.fontSize.toDouble())
                putBoolean("includePad", ctx.includePad)
                putInt("layoutHeight", layout.height)
                putInt("lineCount", layout.lineCount)
                putDouble("maxLineWidth", maxLineWidth)
            }
            promise.resolve(result)
        } catch (e: Throwable) {
            promise.reject("E_MEASURE_TEXT", e)
        }
    }

    @ReactMethod
    fun measureTextLayoutDetailed(options: ReadableMap, promise: Promise) {
        try {
            val ctx = buildMeasurement(options)
            val layout = ctx.layout

            var maxLineWidth = 0.0
            val lines = Arguments.createArray()
            for (i in 0 until layout.lineCount) {
                val lineWidth = layout.getLineWidth(i).toDouble()
                maxLineWidth = max(maxLineWidth, lineWidth)
                val line = Arguments.createMap().apply {
                    putInt("index", i)
                    putInt("start", layout.getLineStart(i))
                    putInt("end", layout.getLineEnd(i))
                    putDouble("left", layout.getLineLeft(i).toDouble())
                    putDouble("right", layout.getLineRight(i).toDouble())
                    putDouble("top", layout.getLineTop(i).toDouble())
                    putDouble("bottom", layout.getLineBottom(i).toDouble())
                    putDouble("baseline", layout.getLineBaseline(i).toDouble())
                    putDouble("width", lineWidth)
                }
                lines.pushMap(line)
            }

            val words = Arguments.createArray()
            val wordRegex = Regex("\\S+")
            for (match in wordRegex.findAll(ctx.text)) {
                val wordStart = match.range.first
                val wordEndExclusive = match.range.last + 1
                var segmentStart = wordStart

                while (segmentStart < wordEndExclusive) {
                    val lineIndex = layout.getLineForOffset(segmentStart)
                    val segmentEndExclusive = min(wordEndExclusive, layout.getLineEnd(lineIndex))
                    if (segmentEndExclusive <= segmentStart) break

                    val segmentLeft = layout.getPrimaryHorizontal(segmentStart)
                    val segmentRight = layout.getPrimaryHorizontal(segmentEndExclusive)
                    val left = min(segmentLeft, segmentRight).toDouble()
                    val right = max(segmentLeft, segmentRight).toDouble()
                    val top = layout.getLineTop(lineIndex).toDouble()
                    val bottom = layout.getLineBottom(lineIndex).toDouble()
                    val text = ctx.text.substring(segmentStart, segmentEndExclusive)

                    val word = Arguments.createMap().apply {
                        putInt("start", segmentStart)
                        putInt("end", segmentEndExclusive)
                        putInt("tokenStart", wordStart)
                        putInt("tokenEnd", wordEndExclusive)
                        putInt("lineIndex", lineIndex)
                        putString("text", text)
                        putDouble("left", left)
                        putDouble("right", right)
                        putDouble("top", top)
                        putDouble("bottom", bottom)
                        putDouble("width", right - left)
                        putDouble("height", bottom - top)
                        putDouble("centerX", (left + right) / 2.0)
                        putDouble("centerY", (top + bottom) / 2.0)
                    }
                    words.pushMap(word)
                    segmentStart = segmentEndExclusive
                }
            }

            val result = Arguments.createMap().apply {
                putString("text", ctx.text)
                putInt("requestedWidth", ctx.width)
                putDouble("requestedFontSize", ctx.fontSize.toDouble())
                putBoolean("includePad", ctx.includePad)
                putInt("layoutHeight", layout.height)
                putInt("lineCount", layout.lineCount)
                putDouble("maxLineWidth", maxLineWidth)
                putArray("lines", lines)
                putArray("words", words)
            }
            promise.resolve(result)
        } catch (e: Throwable) {
            promise.reject("E_MEASURE_TEXT_DETAILED", e)
        }
    }

    @ReactMethod
    fun splitTextForHeight(options: ReadableMap, promise: Promise) {
        try {
            val ctx = buildMeasurement(options)
            val layout = ctx.layout
            val availableHeight = options.getInt("availableHeight")

            if (layout.height <= availableHeight) {
                val result = Arguments.createMap().apply {
                    putString("fittingText", ctx.text)
                    putString("overflowText", "")
                    putInt("fittingHeight", layout.height)
                    putInt("fittingLineCount", layout.lineCount)
                    putBoolean("didSplit", false)
                }
                promise.resolve(result)
                return
            }

            var lastFittingLine = -1
            for (i in 0 until layout.lineCount) {
                val lineBottom = layout.getLineBottom(i)
                if (lineBottom <= availableHeight) {
                    lastFittingLine = i
                } else {
                    break
                }
            }

            if (lastFittingLine < 0) {
                val result = Arguments.createMap().apply {
                    putString("fittingText", "")
                    putString("overflowText", ctx.text)
                    putInt("fittingHeight", 0)
                    putInt("fittingLineCount", 0)
                    putBoolean("didSplit", true)
                }
                promise.resolve(result)
                return
            }

            val splitCharIndex = layout.getLineEnd(lastFittingLine)
            var fittingText = ctx.text.substring(0, splitCharIndex).trimEnd()
            var overflowText = ctx.text.substring(splitCharIndex).trimStart()

            var actualLastLine = lastFittingLine
            val betterSplit = findNaturalBreak(ctx.text, splitCharIndex)
            if (betterSplit > 0 && betterSplit < splitCharIndex) {
                fittingText = ctx.text.substring(0, betterSplit).trimEnd()
                overflowText = ctx.text.substring(betterSplit).trimStart()

                val breakLine = layout.getLineForOffset(max(0, betterSplit - 1))
                if (breakLine < lastFittingLine) actualLastLine = breakLine
            }

            val fittingHeight = layout.getLineBottom(actualLastLine)
            val result = Arguments.createMap().apply {
                putString("fittingText", fittingText)
                putString("overflowText", overflowText)
                putInt("fittingHeight", fittingHeight)
                putInt("fittingLineCount", actualLastLine + 1)
                putBoolean("didSplit", true)
            }
            promise.resolve(result)
        } catch (e: Throwable) {
            promise.reject("E_SPLIT_TEXT", e)
        }
    }

    private data class MeasurementContext(
        val text: String,
        val width: Int,
        val fontSize: Float,
        val includePad: Boolean,
        val layout: StaticLayout
    )

    private fun buildMeasurement(options: ReadableMap): MeasurementContext {
        val text = options.getString("text") ?: ""
        val width = options.getInt("width")
        val fontSize = options.getDouble("fontSize").toFloat()
        val includePad = if (options.hasKey("includePad")) options.getBoolean("includePad") else true
        val fontPath = if (options.hasKey("fontPath") && !options.isNull("fontPath"))
            options.getString("fontPath") else null

        if (width < 0) throw IllegalArgumentException("width must be >= 0")

        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = fontSize
            typeface = loadTypeface(fontPath)
        }

        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(includePad)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, includePad)
        }

        return MeasurementContext(text, width, fontSize, includePad, layout)
    }

    private fun loadTypeface(fontPath: String?): Typeface? {
        if (fontPath.isNullOrBlank()) return null
        val file = File(fontPath)
        if (!file.exists()) return null
        return try { Typeface.createFromFile(file) } catch (_: Throwable) { null }
    }

    private fun findNaturalBreak(text: String, splitCharIndex: Int): Int {

        val searchStart = max(0, splitCharIndex - (splitCharIndex * 0.3).toInt())

        val lastParaBreak = text.lastIndexOf("\n\n", splitCharIndex - 1)
        if (lastParaBreak >= searchStart && lastParaBreak > 0) {
            return lastParaBreak + 2
        }

        val lastNewline = text.lastIndexOf('\n', splitCharIndex - 1)
        if (lastNewline >= searchStart && lastNewline > 0) {
            return lastNewline + 1
        }

        val sentenceEnders = charArrayOf('。', '！', '？', '.', '!', '?')
        for (i in (splitCharIndex - 1) downTo searchStart) {
            if (text[i] in sentenceEnders) {
                val next = i + 1
                if (next <= splitCharIndex) return next
            }
        }

        return 0
    }
}
