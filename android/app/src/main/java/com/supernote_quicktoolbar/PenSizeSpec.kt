package com.supernote_quicktoolbar

import android.os.Build

object PenSizeSpec {

    const val NEEDLE = 10
    const val BALL = 16
    const val CALLIGRAPHY = 14
    const val MARKER = 11

    private val ALL_LABELS = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.5f, 2.0f)
    private const val BALL_START = 2
    private const val CALLI_START = 4

    private class DeviceTable(val needle: IntArray, val marker: Int)

    private val A5X2 = DeviceTable(
        needle = intArrayOf(200, 300, 400, 500, 600, 700, 900, 1000, 1100, 1200, 1800, 2400),
        marker = 3800,
    )

    private val A5X = DeviceTable(
        needle = intArrayOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1500, 2000),
        marker = 3000,
    )

    private val A6X_NOMAD = DeviceTable(
        needle = intArrayOf(200, 300, 400, 500, 700, 800, 1000, 1100, 1200, 1300, 1800, 2400),
        marker = 3800,
    )

    private val table: DeviceTable = when {
        Build.BOARD == "A5X2" -> A5X2
        Build.MODEL == "Supernote A5 X" -> A5X
        Build.MODEL == "Supernote A6 X" || Build.MODEL == "Supernote Nomad" -> A6X_NOMAD
        else -> A5X2
    }

    private fun startIndex(penType: Int?): Int = when (penType) {
        BALL -> BALL_START
        CALLIGRAPHY -> CALLI_START
        else -> 0
    }

    
    fun stepsFor(penType: Int?): IntArray =
        if (penType == MARKER) intArrayOf(table.marker)
        else table.needle.copyOfRange(startIndex(penType), table.needle.size)

    
    fun labelsFor(penType: Int?): FloatArray =
        if (penType == MARKER) FloatArray(0)
        else ALL_LABELS.copyOfRange(startIndex(penType), ALL_LABELS.size)

    fun stepIndex(penType: Int?, raw: Int): Int {
        val steps = stepsFor(penType)
        var best = 0
        for (i in steps.indices) {
            if (Math.abs(steps[i] - raw) < Math.abs(steps[best] - raw)) best = i
        }
        return best
    }

    
    fun snap(penType: Int?, raw: Int): Int = stepsFor(penType)[stepIndex(penType, raw)]

    
    fun label(penType: Int?, raw: Int): String {
        val labels = labelsFor(penType)
        if (labels.isEmpty()) return ""
        return formatLabel(labels[stepIndex(penType, raw)])
    }

    fun formatLabel(value: Float): String = "%.1f".format(value)

    
    fun rawForLabel(penType: Int?, labelValue: Float): Int {
        val labels = labelsFor(penType)
        if (labels.isEmpty()) return table.marker
        var best = 0
        for (i in labels.indices) {
            if (Math.abs(labels[i] - labelValue) < Math.abs(labels[best] - labelValue)) best = i
        }
        return stepsFor(penType)[best]
    }

    
    fun convert(fromPen: Int?, toPen: Int?, raw: Int): Int {
        if (fromPen == toPen) return snap(toPen, raw)
        val fromLabels = labelsFor(fromPen)
        if (toPen == MARKER) return table.marker
        if (fromLabels.isEmpty()) return snap(toPen, raw)
        return rawForLabel(toPen, fromLabels[stepIndex(fromPen, raw)])
    }

    
    fun penIconAsset(penType: Int): String = when (penType) {
        BALL -> "icons/pen/ic_ink_pen_black_2_svg.xml"
        CALLIGRAPHY -> "icons/pen/ic_calligraphy_pen_black_right_svg.xml"
        MARKER -> "icons/pen/ic_mark_pen_black_svg.xml"
        else -> "icons/pen/ic_needle_pen_black_3_svg.xml"
    }

    private val NEEDLE_PREVIEWS = arrayOf(
        "ic_needle_pen_color_one_and_a_half_svg",
        "ic_needle_pen_color_black_2_svg",
        "ic_needle_pen_color_black_3_svg",
        "ic_needle_pen_color_black_4_svg",
        "ic_needle_pen_color_black_5_svg",
        "ic_needle_pen_color_black_6_svg",
        "ic_needle_pen_color_black_7_svg",
        "ic_needle_pen_color_black_8_svg",
        "ic_needle_pen_color_black_9_svg",
        "ic_needle_pen_color_black_10_svg",
        "ic_needle_pen_color_black_11_svg",
        "ic_needle_pen_color_black_12_svg",
    )

    private val BALL_PREVIEWS = arrayOf(
        "ic_ink_pen_color_black_3_svg",
        "ic_ink_pen_color_black_4_svg",
        "ic_ink_pen_color_black_5_svg",
        "ic_ink_pen_color_black_6_svg",
        "ic_ink_pen_color_black_7_svg",
        "ic_ink_pen_color_black_8_svg",
        "ic_ink_pen_color_black_9_svg",
        "ic_ink_pen_color_black_10_svg",
        "ic_ink_pen_color_black_15_svg",
        "ic_ink_pen_color_black_20_svg",
    )

    private val CALLI_PREVIEWS = arrayOf(
        "ic_calligraphy_pen_color_black_3_svg",
        "ic_calligraphy_pen_color_black_4_svg",
        "ic_calligraphy_pen_color_black_5_svg",
        "ic_calligraphy_pen_color_black_6_svg",
        "ic_calligraphy_pen_color_black_7_svg",
        "ic_calligraphy_pen_color_black_8_svg",
        "ic_calligraphy_pen_color_black_9_svg",
        "ic_calligraphy_pen_color_black_10_svg",
    )

    
    fun previewAsset(penType: Int?, raw: Int): String {
        val name = when (penType) {
            BALL -> BALL_PREVIEWS[stepIndex(penType, raw)]
            CALLIGRAPHY -> CALLI_PREVIEWS[stepIndex(penType, raw)]
            MARKER -> "ic_mark_pen_size_black_svg"
            else -> NEEDLE_PREVIEWS[stepIndex(penType, raw)]
        }
        return "icons/pen/$name.xml"
    }
}